#pragma once
#include "j1979_maintenance_archive.h"
#include "j1979_maintenance_interprocessor.h"

namespace cd { namespace archive_link {
// CI/5 has the same physical slot size as CI/4, but a separate version
// and a strictly read-only operation set. Start is never accepted.
using Packet=maintenance_link::Packet;
using Op=maintenance_link::Op;
using Scope=J1979MaintenanceScope;
using Archive=J1979MaintenanceArchive;
using Info=Archive::Info;
static constexpr size_t Size=maintenance_link::Size,BodySize=maintenance_link::BodySize,
  ChunkBytes=maintenance_link::ChunkBytes;
inline bool allowed(Op op){
  return op==Op::ScopeA||op==Op::ScopeB||op==Op::Poll||op==Op::Fetch||op==Op::Close||op==Op::Abort||
    op==Op::Accepted||op==Op::Progress||op==Op::Chunk||op==Op::Closed;
}
inline bool encode(const Packet& p,uint8_t* out,size_t n){
  if(!allowed(p.op)||!maintenance_link::encode(p,out,n))return false;
  out[2]=5;maintenance_link::p32(out+28,link::crc(out,28));return true;
}
inline bool decode(const uint8_t* in,size_t n,Packet& out){
  out=Packet{};
  if(!in||n!=Size||in[2]!=5||maintenance_link::u32(in+28)!=link::crc(in,28))return false;
  uint8_t b[Size];std::memcpy(b,in,Size);b[2]=4;maintenance_link::p32(b+28,link::crc(b,28));
  return maintenance_link::decode(b,Size,out)&&allowed(out.op);
}
inline bool encodeInfo(const Info& info,Packet& p){
  if(!info.valid())return false;
  p.op=Op::Progress;p.length=8;
  p.body[0]=(info.terminal?1:0)|(info.damagedTail?2:0)|(info.recoveryRequired?4:0);
  p.body[1]=info.records;
  maintenance_link::p32(p.body+4,info.bytes);return true;
}
inline bool decodeInfo(const Packet& p,Info& info){
  info=Info{};
  if(p.op!=Op::Progress||p.length!=8||(p.body[0]&~7u)||p.body[2]||p.body[3])return false;
  Info v;v.terminal=(p.body[0]&1)!=0;v.damagedTail=(p.body[0]&2)!=0;
  v.recoveryRequired=(p.body[0]&4)!=0;v.records=p.body[1];
  v.bytes=maintenance_link::u32(p.body+4);
  if(!v.valid())return false;
  info=v;return true;
}
class Endpoint {
 public:
  class Connection {
   public:
    virtual ~Connection()=default;
    virtual bool present(const Scope& scope)=0;
    virtual uint32_t monotonicMs()=0;
  };
  Endpoint(Archive::Source& source,Connection& connection,const Scope& approved):
    source_(source),connection_(connection),scope_(approved){}
  Endpoint(const Endpoint&)=delete;
  Endpoint& operator=(const Endpoint&)=delete;
  ~Endpoint(){close();}
  bool closed()const{return closed_;}
  bool poll(){
    if(closed_||!scope_.valid()||!connection_.present(scope_))return fail();
    if(phase_&&uint32_t(connection_.monotonicMs()-started_)>=20000u)return fail();
    if(phase_>=2&&!source_.current())return fail();
    return true;
  }
  bool transact(const uint8_t* request,size_t n,uint8_t* response,size_t m){
    Packet p,q;
    if(!response||m!=Size||!poll()||!archive_link::decode(request,n,p)||p.sequence!=next_)return fail();
    q.sequence=p.sequence;
    if(p.op==Op::Abort&&p.length==0){
      q.op=Op::Closed;close();return archive_link::encode(q,response,m);
    }
    uint8_t wire[Scope::WireSize]{};
    if(!scope_.encode(wire,sizeof(wire)))return fail();
    if(phase_==0){
      if(p.op!=Op::ScopeA||p.length!=BodySize||std::memcmp(p.body,wire,BodySize))return fail();
      phase_=1;started_=connection_.monotonicMs();q.op=Op::Accepted;
    }else if(phase_==1){
      if(p.op!=Op::ScopeB||p.length!=Scope::WireSize-BodySize||
         std::memcmp(p.body,wire+BodySize,p.length)||!source_.open(scope_,info_)||!info_.valid())return fail();
      phase_=2;q.op=Op::Accepted;
    }else if(phase_==2){
      if(p.op==Op::Poll&&p.length==0){
        if(!encodeInfo(info_,q))return fail();
      }else if(p.op==Op::Fetch&&p.length==4&&maintenance_link::u32(p.body)==offset_&&offset_<info_.bytes){
        const size_t count=info_.bytes-offset_<ChunkBytes?info_.bytes-offset_:ChunkBytes;
        q.op=Op::Chunk;q.length=uint8_t(4+count);maintenance_link::p32(q.body,offset_);
        if(!source_.read(offset_,q.body+4,count)||!source_.current())return fail();
        offset_+=uint32_t(count);
      }else if(p.op==Op::Close&&p.length==0&&offset_==info_.bytes){
        if(!source_.finish())return fail();
        q.op=Op::Closed;close();
      }else return fail();
    }else return fail();
    if(!connection_.present(scope_)||!archive_link::encode(q,response,m)||next_==0xffffu)return fail();
    ++next_;return true;
  }
  void fault(){close();}
 private:
  void close(){if(!closed_){closed_=true;source_.close();}}
  bool fail(){close();return false;}
  Archive::Source& source_;Connection& connection_;const Scope scope_;
  Info info_{};uint32_t offset_=0,started_=0;uint16_t next_=0;
  uint8_t phase_=0;bool closed_=false;
};
class Client {
 public:
  using Transport=maintenance_link::Transport;
  Client(Transport& transport,const Scope& scope):transport_(transport),scope_(scope){}
  Client(const Client&)=delete;
  Client& operator=(const Client&)=delete;
  bool open(Info& info){
    info=Info{};
    if(phase_||!scope_.valid()||!transport_.active())return fail();
    uint8_t wire[Scope::WireSize]{};
    if(!scope_.encode(wire,sizeof(wire)))return fail();
    started_=transport_.monotonicMs();phase_=1;
    Packet p,q;p.op=Op::ScopeA;p.length=BodySize;std::memcpy(p.body,wire,BodySize);
    if(!request(p,Op::Accepted,q)||q.length)return fail();
    p=Packet{};p.op=Op::ScopeB;p.length=Scope::WireSize-BodySize;
    std::memcpy(p.body,wire+BodySize,p.length);
    if(!request(p,Op::Accepted,q)||q.length)return fail();
    p=Packet{};p.op=Op::Poll;
    if(!request(p,Op::Progress,q)||!decodeInfo(q,info_))return fail();
    phase_=2;info=info_;return true;
  }
  bool active(){return phase_==2&&transport_.active()&&uint32_t(transport_.monotonicMs()-started_)<20000u;}
  bool read(size_t offset,uint8_t* out,size_t n){
    if(!active()||!out||!n||offset!=offset_||offset>info_.bytes||n>info_.bytes-offset)return fail();
    // Source callers may request chunks larger than the SPI payload.
    // An internal remainder is retained, never re-requested.
    while(n){
      if(cursor_==available_){
        Packet p,q;p.op=Op::Fetch;p.length=4;maintenance_link::p32(p.body,wireOffset_);
        if(!request(p,Op::Chunk,q)||q.length<=4||maintenance_link::u32(q.body)!=wireOffset_)return fail();
        const size_t remaining=info_.bytes-wireOffset_;
        const size_t expected=remaining<ChunkBytes?remaining:ChunkBytes;
        if(q.length!=4+expected||!expected)return fail();
        available_=expected;cursor_=0;
        std::memcpy(buffer_,q.body+4,expected);wireOffset_+=uint32_t(expected);
      }
      const size_t take=n<available_-cursor_?n:available_-cursor_;
      std::memcpy(out,buffer_+cursor_,take);out+=take;n-=take;cursor_+=take;offset_+=uint32_t(take);
    }
    return active();
  }
  bool finish(){
    if(!active()||offset_!=info_.bytes||wireOffset_!=info_.bytes||cursor_!=available_)return fail();
    Packet p,q;p.op=Op::Close;
    if(!request(p,Op::Closed,q)||q.length)return fail();
    phase_=3;return true;
  }
  void abort(){
    if((phase_==1||phase_==2)&&transport_.active()){
      Packet p,q;p.op=Op::Abort;
      if(request(p,Op::Closed,q)&&q.length==0){phase_=3;return;}
    }
    (void)fail();
  }
 private:
  bool fail(){if(phase_!=3&&phase_!=4){phase_=4;transport_.fault();}return false;}
  bool request(Packet& p,Op expected,Packet& q){
    if((phase_!=1&&phase_!=2)||!transport_.active()||next_==0xffffu||
       uint32_t(transport_.monotonicMs()-started_)>=20000u)return fail();
    p.sequence=next_;uint8_t tx[Size]{},rx[Size]{};
    const uint32_t start=transport_.monotonicMs();
    if(!archive_link::encode(p,tx,Size)||!transport_.exchange(tx,rx,1000)||!transport_.active()||
       uint32_t(transport_.monotonicMs()-start)>=1000u||
       !archive_link::decode(rx,Size,q)||q.op!=expected||q.sequence!=next_)return fail();
    ++next_;return true;
  }
  Transport& transport_;const Scope scope_;Info info_{};
  uint32_t offset_=0,wireOffset_=0,started_=0;
  uint16_t next_=0;uint8_t phase_=0,buffer_[ChunkBytes]{};
  size_t cursor_=0,available_=0;
};
} }
