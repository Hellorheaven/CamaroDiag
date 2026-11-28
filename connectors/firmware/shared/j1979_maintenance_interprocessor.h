#pragma once
#include "j1979_maintenance_journal.h"
#include "interprocessor_link.h"

namespace cd { namespace maintenance_link {
// CI/4 is intentionally independent of the read-only CI/2 and CI/3
// mailboxes. The remote host cannot supply a diagnostic PDU. It may only
// match a locally approved immutable scope, request the entire one-shot
// recipe, inspect progress and retrieve the original durable journal.
static constexpr size_t Size=32,BodySize=20,ChunkBytes=16;
enum class Op:uint8_t {
  ScopeA=1,ScopeB=2,Start=3,Poll=4,Fetch=5,Close=6,Abort=7,
  Accepted=0x81,Progress=0x82,Chunk=0x83,Closed=0x84
};
struct Packet {
  Op op=Op::ScopeA;
  uint16_t sequence=0;
  uint8_t length=0,body[BodySize]{};
};
inline bool known(Op op){
  switch(op){
    case Op::ScopeA:case Op::ScopeB:case Op::Start:case Op::Poll:case Op::Fetch:
    case Op::Close:case Op::Abort:case Op::Accepted:case Op::Progress:case Op::Chunk:case Op::Closed:return true;
    default:return false;
  }
}
inline uint16_t u16(const uint8_t* p){return uint16_t(p[0])|(uint16_t(p[1])<<8);}
inline uint32_t u32(const uint8_t* p){
  uint32_t n=0;for(unsigned i=0;i<4;++i)n|=uint32_t(p[i])<<(8*i);return n;
}
inline void p16(uint8_t* p,uint16_t n){p[0]=uint8_t(n);p[1]=uint8_t(n>>8);}
inline void p32(uint8_t* p,uint32_t n){for(unsigned i=0;i<4;++i)p[i]=uint8_t(n>>(8*i));}
inline bool encode(const Packet& p,uint8_t* out,size_t n){
  if(!out||n!=Size||!known(p.op)||p.length>BodySize)return false;
  std::memset(out,0,n);out[0]='C';out[1]='I';out[2]=4;out[3]=uint8_t(p.op);
  p16(out+4,p.sequence);out[6]=p.length;
  std::memcpy(out+8,p.body,p.length);p32(out+28,link::crc(out,28));return true;
}
inline bool decode(const uint8_t* bytes,size_t n,Packet& out){
  out=Packet{};
  if(!bytes||n!=Size||bytes[0]!='C'||bytes[1]!='I'||bytes[2]!=4||bytes[7]||
     bytes[6]>BodySize||u32(bytes+28)!=link::crc(bytes,28))return false;
  for(size_t i=8+bytes[6];i<28;++i)if(bytes[i])return false;
  const auto op=static_cast<Op>(bytes[3]);if(!known(op))return false;
  out.op=op;out.sequence=u16(bytes+4);out.length=bytes[6];
  std::memcpy(out.body,bytes+8,out.length);return true;
}
using Scope=J1979MaintenanceScope;
using Journal=J1979MaintenanceJournal;
enum class RunState:uint8_t { Running=1,Stopped=2,Unavailable=3 };
struct Progress {
  RunState state=RunState::Unavailable;
  bool recoveryRequired=false,physicalStop=false;
  uint32_t journalSize=0;
  bool valid()const{
    if(state==RunState::Running)return !physicalStop&&!journalSize&&!recoveryRequired;
    if(state==RunState::Stopped)return physicalStop&&journalSize&&journalSize<=Journal::MaximumJournal;
    return state==RunState::Unavailable&&!journalSize&&recoveryRequired;
  }
  bool same(const Progress& other)const{
    return state==other.state&&recoveryRequired==other.recoveryRequired&&
      physicalStop==other.physicalStop&&journalSize==other.journalSize;
  }
  bool encode(Packet& p)const{
    if(!valid())return false;
    p.op=Op::Progress;p.length=8;p.body[0]=uint8_t(state);
    p.body[1]=(recoveryRequired?1u:0u)|(physicalStop?2u:0u);
    p32(p.body+4,journalSize);return true;
  }
  static bool decode(const Packet& p,Progress& out){
    out=Progress{};
    if(p.op!=Op::Progress||p.length!=8||(p.body[1]&~3u)||p.body[2]||p.body[3])return false;
    Progress v;v.state=static_cast<RunState>(p.body[0]);
    v.recoveryRequired=(p.body[1]&1)!=0;v.physicalStop=(p.body[1]&2)!=0;v.journalSize=u32(p.body+4);
    if(!v.valid())return false;
    out=v;return true;
  }
};
class Runner {
 public:
  virtual ~Runner()=default;
  // Checks independently provisioned scope and local lease. The
  // implementation must not derive these checks from CI/4 requests.
  virtual bool approved(const Scope& scope)=0;
  virtual bool connectionPresent()=0;
  virtual uint32_t monotonicMs()=0;
  // Nonblocking schedule, not execution inside an SPI transaction/IRQ.
  // False may still leave a pending intent: cancel/verify is mandatory.
  virtual bool schedule(const Scope& scope)=0;
  virtual Progress progress()=0;
  virtual bool readJournal(size_t offset,uint8_t* output,size_t n)=0;
  virtual void cancelAndLatchRecovery()=0;
  virtual bool releaseVerified()=0;
};
class Endpoint {
 public:
  enum class Phase { ScopeA,ScopeB,Prepared,Running,Journal,Unavailable,Closed };
  explicit Endpoint(Runner& runner,const Scope& approved):runner_(runner),scope_(approved){}
  Endpoint(const Endpoint&)=delete;
  Endpoint& operator=(const Endpoint&)=delete;
  Phase phase()const{return phase_;}
  bool closed()const{return phase_==Phase::Closed;}
  // Board task calls poll independently of host activity; a silent USB
  // host or missing SPI clock must not keep an active recipe indefinitely.
  bool poll(){
    if(closed())return false;
    if(!runner_.connectionPresent()||!scope_.valid())return fail();
    if(!begun_)return true;
    const uint32_t now=runner_.monotonicMs();
    const uint32_t elapsed=uint32_t(now-start_);
    if(phase_==Phase::ScopeB||phase_==Phase::Prepared){
      if(elapsed>=5000u||!runner_.approved(scope_))return fail();
    }else if(phase_==Phase::Running){
      if(elapsed>=scope_.budgetMs+5000u)return fail();
      auto progress=runner_.progress();
      if(!progress.valid())return fail();
      if(progress.state==RunState::Running){
        if(!runner_.approved(scope_))return fail();
      }else{
        terminal_=progress;finishedAt_=now;
        phase_=progress.state==RunState::Stopped?Phase::Journal:Phase::Unavailable;
      }
    }else if((phase_==Phase::Journal||phase_==Phase::Unavailable)&&
       uint32_t(now-finishedAt_)>=15000u)return fail();
    return true;
  }
  bool transact(const uint8_t* input,size_t n,uint8_t* output,size_t m){
    Packet p,q;
    if(!output||m!=Size||!poll()||!decode(input,n,p)||p.sequence!=next_)return fail();
    q.sequence=p.sequence;
    if(p.op==Op::Abort){
      if(p.length)return fail();
      // A host abort never clears the local journal or recovery latch.
      cancel();q.op=Op::Closed;q.length=0;
      phase_=Phase::Closed;return encode(q,output,m);
    }
    switch(phase_){
      case Phase::ScopeA:{
        if(p.op!=Op::ScopeA||p.length!=BodySize||!runner_.approved(scope_))return fail();
        uint8_t wire[Scope::WireSize]{};
        if(!scope_.encode(wire,sizeof(wire))||std::memcmp(p.body,wire,BodySize))return fail();
        begun_=true;start_=runner_.monotonicMs();phase_=Phase::ScopeB;
        q.op=Op::Accepted;q.length=0;break;
      }
      case Phase::ScopeB:{
        if(p.op!=Op::ScopeB||p.length!=Scope::WireSize-BodySize)return fail();
        uint8_t wire[Scope::WireSize]{};
        if(!scope_.encode(wire,sizeof(wire))||std::memcmp(p.body,wire+BodySize,p.length))return fail();
        phase_=Phase::Prepared;q.op=Op::Accepted;q.length=0;break;
      }
      case Phase::Prepared:
        if(p.op!=Op::Start||p.length||!runner_.approved(scope_)||scheduled_)return fail();
        scheduled_=true;start_=runner_.monotonicMs();
        if(!runner_.schedule(scope_))return fail();
        phase_=Phase::Running;q.op=Op::Accepted;q.length=0;break;
      case Phase::Running:{
        if(p.op!=Op::Poll||p.length)return fail();
        Progress running;running.state=RunState::Running;
        if(!running.encode(q))return fail();
        break;
      }
      case Phase::Journal:
        if(!runner_.progress().same(terminal_))return fail();
        if(p.op==Op::Poll&&p.length==0){
          if(!terminal_.encode(q))return fail();
        }else if(p.op==Op::Fetch&&p.length==4&&u32(p.body)==offset_&&offset_<terminal_.journalSize){
          const size_t count=terminal_.journalSize-offset_<ChunkBytes?terminal_.journalSize-offset_:ChunkBytes;
          q.op=Op::Chunk;q.length=uint8_t(4+count);p32(q.body,offset_);
          if(!runner_.readJournal(offset_,q.body+4,count)||!runner_.connectionPresent()||
             !runner_.progress().same(terminal_))return fail();
          offset_+=uint32_t(count);
        }else if(p.op==Op::Close&&p.length==0&&offset_==terminal_.journalSize){
          if(!runner_.releaseVerified())return fail();
          phase_=Phase::Closed;q.op=Op::Closed;q.length=0;
        }else return fail();
        break;
      case Phase::Unavailable:
        if(p.op==Op::Poll&&p.length==0){
          if(!terminal_.encode(q))return fail();
        }else if(p.op==Op::Close&&p.length==0){
          if(!runner_.releaseVerified())return fail();
          phase_=Phase::Closed;q.op=Op::Closed;q.length=0;
        }else return fail();
        break;
      case Phase::Closed:return false;
    }
    if(!runner_.connectionPresent()||!encode(q,output,m))return fail();
    if(next_==0xffffu&&phase_!=Phase::Closed)return fail();
    ++next_;return true;
  }
  void fault(){fail();}
 private:
  void cancel(){
    if(!cancelled_){cancelled_=true;if(scheduled_)runner_.cancelAndLatchRecovery();}
  }
  bool fail(){cancel();phase_=Phase::Closed;return false;}
  Runner& runner_;const Scope scope_;
  Progress terminal_{};
  Phase phase_=Phase::ScopeA;
  uint16_t next_=0;
  uint32_t start_=0,finishedAt_=0,offset_=0;
  bool begun_=false,scheduled_=false,cancelled_=false;
};

class Transport {
 public:
  virtual ~Transport()=default;
  virtual bool active()=0;
  virtual uint32_t monotonicMs()=0;
  virtual bool exchange(const uint8_t* request,uint8_t* response,uint32_t timeout)=0;
  // Disconnect physical mailbox/lease; no resend of uncertain Start.
  virtual void fault()=0;
};
class Client {
 public:
  class Sink {
   public:
    virtual ~Sink()=default;
    virtual bool append(const uint8_t* bytes,size_t n)=0;
  };
  explicit Client(Transport& transport,const Scope& scope):transport_(transport),scope_(scope){}
  Client(const Client&)=delete;Client& operator=(const Client&)=delete;
  bool prepare(){
    if(phase_||!scope_.valid()||!transport_.active())return fail();
    uint8_t wire[Scope::WireSize]{};
    if(!scope_.encode(wire,sizeof(wire)))return fail();
    Packet p,q;p.op=Op::ScopeA;p.length=BodySize;std::memcpy(p.body,wire,BodySize);
    if(!request(p,Op::Accepted,q)||q.length)return fail();
    p=Packet{};p.op=Op::ScopeB;p.length=Scope::WireSize-BodySize;
    std::memcpy(p.body,wire+BodySize,p.length);
    if(!request(p,Op::Accepted,q)||q.length)return fail();
    phase_=1;return true;
  }
  bool start(){
    if(phase_!=1||attempted_)return fail();
    attempted_=true;started_=transport_.monotonicMs();
    Packet p,q;p.op=Op::Start;
    if(!request(p,Op::Accepted,q)||q.length)return fail();
    phase_=2;return true;
  }
  bool poll(Progress& result){
    if(phase_!=2||uint32_t(transport_.monotonicMs()-started_)>=scope_.budgetMs+5000u)return fail();
    Packet p,q;p.op=Op::Poll;
    if(!request(p,Op::Progress,q)||!Progress::decode(q,result))return fail();
    if(result.state!=RunState::Running){terminal_=result;phase_=3;finishedAt_=transport_.monotonicMs();}
    return true;
  }
  bool fetch(Sink& sink){
    if(phase_!=3||terminal_.state!=RunState::Stopped||fetched_)return fail();
    while(offset_<terminal_.journalSize){
      if(uint32_t(transport_.monotonicMs()-finishedAt_)>=15000u)return fail();
      Packet p,q;p.op=Op::Fetch;p.length=4;p32(p.body,offset_);
      if(!request(p,Op::Chunk,q)||q.length<=4||u32(q.body)!=offset_)return fail();
      const size_t remaining=terminal_.journalSize-offset_;
      const size_t count=remaining<ChunkBytes?remaining:ChunkBytes;
      if(q.length!=4+count||!sink.append(q.body+4,count))return fail();
      offset_+=uint32_t(count);
    }
    fetched_=true;return true;
  }
  bool close(){
    if(phase_!=3||(terminal_.state==RunState::Stopped&&!fetched_)||
       uint32_t(transport_.monotonicMs()-finishedAt_)>=15000u)return fail();
    Packet p,q;p.op=Op::Close;
    if(!request(p,Op::Closed,q)||q.length)return fail();
    phase_=4;return true;
  }
  void abort(){
    if(phase_!=4&&phase_!=5&&transport_.active()){
      Packet p,q;p.op=Op::Abort;
      if(request(p,Op::Closed,q)&&q.length==0){phase_=4;return;}
    }
    fail();
  }
  bool attempted()const{return attempted_;}
  bool failed()const{return phase_==5;}
  const Progress& terminal()const{return terminal_;}
 private:
  bool fail(){if(phase_!=5&&phase_!=4){phase_=5;transport_.fault();}return false;}
  bool request(Packet& p,Op expected,Packet& q){
    if(phase_==4||phase_==5||!transport_.active()||next_==0xffffu)return fail();
    p.sequence=next_;
    uint8_t tx[Size]{},rx[Size]{};
    const uint32_t start=transport_.monotonicMs();
    if(!encode(p,tx,Size)||!transport_.exchange(tx,rx,1000)||!transport_.active()||
       uint32_t(transport_.monotonicMs()-start)>=1000u||
       !decode(rx,Size,q)||q.sequence!=next_||q.op!=expected)return fail();
    ++next_;return true;
  }
  Transport& transport_;const Scope scope_;
  Progress terminal_{};
  uint8_t phase_=0;
  uint16_t next_=0;
  uint32_t started_=0,finishedAt_=0,offset_=0;
  bool attempted_=false,fetched_=false;
};
} }
