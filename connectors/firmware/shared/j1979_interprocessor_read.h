#pragma once
#include "interprocessor_link.h"
#include "j1979_can_read_exchange.h"

namespace cd { namespace readlink {
// CI/2 application mailbox. Separate from CI/1 discovery; no implicit upgrade.
// A board owner must establish an exclusive, qualified physical link before
// constructing Endpoint, and independently stop it on any mailbox failure.
// CRC/sequence detect corruption and stale replies, not authentication.
static constexpr size_t Size=32, BodySize=20;
enum class Op:uint8_t { Read=1, Fetch=2, Result=0x81, Chunk=0x82 };
struct Packet {
  Op op=Op::Read;
  uint16_t seq=0;
  uint8_t length=0;
  uint8_t body[BodySize]{};
};
inline uint16_t get16(const uint8_t* p){return uint16_t(p[0])|(uint16_t(p[1])<<8);}
inline void put16(uint8_t* p,uint16_t n){p[0]=uint8_t(n);p[1]=uint8_t(n>>8);}
inline uint32_t get32(const uint8_t* p){
  uint32_t n=0;for(unsigned i=0;i<4;++i)n|=uint32_t(p[i])<<(8*i);return n;
}
inline void put32(uint8_t* p,uint32_t n){for(unsigned i=0;i<4;++i)p[i]=uint8_t(n>>(8*i));}
inline bool known(Op op){
  return op==Op::Read||op==Op::Fetch||op==Op::Result||op==Op::Chunk;
}
inline bool encode(const Packet& p,uint8_t* out,size_t n){
  if(!out||n!=Size||!known(p.op)||p.length>BodySize)return false;
  std::memset(out,0,n);out[0]='C';out[1]='I';out[2]=2;out[3]=uint8_t(p.op);
  put16(out+4,p.seq);out[6]=p.length;std::memcpy(out+8,p.body,p.length);
  put32(out+28,link::crc(out,28));return true;
}
inline bool decode(const uint8_t* in,size_t n,Packet& out){
  out=Packet{};
  if(!in||n!=Size||in[0]!='C'||in[1]!='I'||in[2]!=2||in[7]||
     in[6]>BodySize||get32(in+28)!=link::crc(in,28))return false;
  for(size_t i=8+in[6];i<28;++i)if(in[i])return false;
  const auto op=static_cast<Op>(in[3]);if(!known(op))return false;
  out.op=op;out.seq=get16(in+4);out.length=in[6];
  std::memcpy(out.body,in+8,out.length);return true;
}
using Exchange=J1979CanReadExchange;
using State=Exchange::State;
inline uint8_t wireState(State s){
  switch(s){
    case State::Positive:return 1;case State::Raw:return 2;
    case State::Negative:return 3;case State::Timeout:return 4;
    case State::Fault:return 5;default:return 0;
  }
}
inline State stateFromWire(uint8_t s){
  switch(s){
    case 1:return State::Positive;case 2:return State::Raw;
    case 3:return State::Negative;case 4:return State::Timeout;
    default:return State::Fault;
  }
}
inline bool validResult(const uint8_t* req,size_t n,const Exchange::Result& r){
  const auto s=wireState(r.state);
  if(!s||r.responseSize>4095||(s<=3)!=(r.responseSize>0))return false;
  if(s>3)return true;
  using R=J1979ReadEvidence::Result;
  const auto evidence=J1979ReadEvidence::classify(J1979ReadEvidence::Variant::ClassicCan,
      req,n,r.response,r.responseSize);
  return (s==1&&evidence==R::Positive)||(s==2&&evidence==R::Raw)||
    (s==3&&evidence==R::Negative);
}
class Endpoint {
 public:
  using Admission=bool(*)();
  Endpoint(J1979CanReadLink& bus,Exchange::Result& scratch,uint32_t tx,uint32_t rx,
      bool ext,Admission admitted):bus_(bus),scratch_(scratch),tx_(tx),rx_(rx),
      ext_(ext),admitted_(admitted){}
  Endpoint(const Endpoint&)=delete;
  Endpoint& operator=(const Endpoint&)=delete;
  bool closed()const{return closed_;}
  bool transact(const uint8_t* in,size_t n,uint8_t* out,size_t m){
    Packet p,r;
    if(closed_||!admitted_||!admitted_()||!bus_.active()||
       !J1979CanReadSession::physicalPair(tx_,rx_,ext_)||!out||m!=Size||
       !decode(in,n,p)||p.seq!=next_)return fail();
    r.seq=p.seq;
    if(p.op==Op::Read){
      if(offset_!=scratchSize_||p.length<3||p.length>9)return fail();
      const uint16_t timeout=get16(p.body);
      const size_t reqSize=p.length-2;
      if(timeout<1||timeout>4000||
         !J1979ReadEvidence::readRequest(J1979ReadEvidence::Variant::ClassicCan,p.body+2,reqSize))
        return fail();
      Exchange::run(bus_,tx_,rx_,ext_,p.body+2,reqSize,timeout,scratch_);
      if(!validResult(p.body+2,reqSize,scratch_))return fail();
      scratchSize_=scratch_.responseSize;offset_=0;
      r.op=Op::Result;r.length=7;r.body[0]=wireState(scratch_.state);
      put16(r.body+1,uint16_t(scratchSize_));put32(r.body+3,scratch_.elapsedMs);
    }else if(p.op==Op::Fetch){
      if(p.length!=2||get16(p.body)!=offset_||offset_>=scratchSize_)return fail();
      const size_t remaining=scratchSize_-offset_;
      const size_t count=remaining<BodySize-2?remaining:BodySize-2;
      r.op=Op::Chunk;r.length=uint8_t(count+2);
      put16(r.body,uint16_t(offset_));
      std::memcpy(r.body+2,scratch_.response+offset_,count);offset_+=count;
    }else return fail();
    if(!admitted_()||!encode(r,out,m))return fail();
    if(next_==0xffffu||scratch_.state==State::Fault)closed_=true;
    else ++next_;
    return true;
  }
 private:
  bool fail(){closed_=true;return false;}
  J1979CanReadLink& bus_;Exchange::Result& scratch_;
  uint32_t tx_,rx_;bool ext_;Admission admitted_;
  size_t scratchSize_=0,offset_=0;
  uint16_t next_=0;bool closed_=false;
};
// Actual SPI master must enforce a bounded request/response transaction,
// including chip select, response readiness and local session admission.
// Failure is terminal; no automatic resend of any read or fetch packet.
class Transport {
 public:
  virtual ~Transport()=default;
  virtual bool exchange(const uint8_t* request,uint8_t* response,uint32_t timeoutMs)=0;
};
class Client {
 public:
  explicit Client(Transport& transport):transport_(transport){}
  Client(const Client&)=delete;
  Client& operator=(const Client&)=delete;
  bool closed()const{return closed_;}
  bool read(const uint8_t* req,size_t n,uint16_t timeout,Exchange::Result& out){
    out.state=State::Fault;out.responseSize=0;out.elapsedMs=0;
    if(closed_||!req||n>7||timeout<1||timeout>4000||
       !J1979ReadEvidence::readRequest(J1979ReadEvidence::Variant::ClassicCan,req,n))return fail();
    Packet p,r;p.op=Op::Read;p.length=uint8_t(n+2);
    put16(p.body,timeout);std::memcpy(p.body+2,req,n);
    if(!request(p,Op::Result,r,uint32_t(timeout)+100u)||r.length!=7||
       r.body[0]<1||r.body[0]>5)return fail();
    const uint8_t state=r.body[0];const size_t total=get16(r.body+1);
    if(total>4095||(state<=3)!=(total>0))return fail();
    const uint32_t elapsed=get32(r.body+3);
    size_t offset=0;
    while(offset<total){
      Packet fetch,chunk;fetch.op=Op::Fetch;fetch.length=2;put16(fetch.body,uint16_t(offset));
      if(!request(fetch,Op::Chunk,chunk,100u)||chunk.length<=2||
         get16(chunk.body)!=offset)return fail();
      const size_t count=chunk.length-2;
      const size_t remaining=total-offset;
      if(count!=(remaining<BodySize-2?remaining:BodySize-2))return fail();
      std::memcpy(out.response+offset,chunk.body+2,count);offset+=count;
    }
    out.state=stateFromWire(state);out.elapsedMs=elapsed;out.responseSize=total;
    if(!validResult(req,n,out)){
      out.state=State::Fault;out.responseSize=0;return fail();
    }
    if(out.state==State::Fault)closed_=true;
    return true;
  }
 private:
  bool fail(){closed_=true;return false;}
  bool request(Packet& p,Op expected,Packet& r,uint32_t timeout){
    if(closed_)return false;
    p.seq=next_;uint8_t tx[Size]{},rx[Size]{};
    if(!encode(p,tx,Size)||!transport_.exchange(tx,rx,timeout)||
       !decode(rx,Size,r)||r.op!=expected||r.seq!=next_)return fail();
    if(next_==0xffffu)closed_=true;else ++next_;
    return true;
  }
  Transport& transport_;uint16_t next_=0;bool closed_=false;
};
} }
