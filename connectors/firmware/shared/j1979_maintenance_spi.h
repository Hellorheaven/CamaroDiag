#pragma once
#include "j1979_maintenance_interprocessor.h"
#include "j1979_spi_transport.h"

namespace cd { namespace maintenance_link {
// Dedicated CI/4 SPI framing. Reuses only physical port contracts, not the
// CI/2 or CI/3 packet parser. Every CS transaction remains exactly 32 bytes.
class SpiTransport final:public Transport {
 public:
  explicit SpiTransport(readlink::SpiMasterPort& port):port_(port){}
  bool active()override{return !closed_&&port_.admitted();}
  uint32_t monotonicMs()override{return port_.monotonicMs();}
  void fault()override{fail();}
  bool exchange(const uint8_t* request,uint8_t* response,uint32_t timeout)override{
    Packet p;
    if(!active()||!request||!response||!timeout||timeout>1000||
       !decode(request,Size,p)||uint8_t(p.op)>=0x80||port_.responseReady())return fail();
    const auto start=monotonicMs();
    uint8_t ignored[Size]{},dummy[Size]{},reply[Size]{};
    if(!transfer(request,ignored,start,timeout)||!wait(true,start,timeout)||
       !transfer(dummy,reply,start,timeout)||!wait(false,start,timeout))return fail();
    Packet result;
    if(!decode(reply,Size,result)||result.sequence!=p.sequence||result.op!=expected(p.op))return fail();
    std::memcpy(response,reply,Size);return true;
  }
 private:
  static Op expected(Op op){
    switch(op){
      case Op::ScopeA:case Op::ScopeB:case Op::Start:return Op::Accepted;
      case Op::Poll:return Op::Progress;
      case Op::Fetch:return Op::Chunk;
      default:return Op::Closed;
    }
  }
  bool fail(){if(!closed_){closed_=true;port_.fault();}return false;}
  bool within(uint32_t start,uint32_t budget){return active()&&uint32_t(monotonicMs()-start)<budget;}
  bool transfer(const uint8_t* tx,uint8_t* rx,uint32_t start,uint32_t budget){
    if(!within(start,budget))return false;
    const auto remaining=budget-uint32_t(monotonicMs()-start);
    return remaining&&port_.transfer(tx,rx,remaining)&&within(start,budget);
  }
  bool wait(bool level,uint32_t start,uint32_t budget){
    for(unsigned i=0;i<65536;++i){
      if(!within(start,budget))return false;
      if(port_.responseReady()==level)return within(start,budget);
      port_.yield();
    }
    return false;
  }
  readlink::SpiMasterPort& port_;bool closed_=false;
};

// Task-context slave. During execution the board must service poll()
// independently of incoming packets. An IRQ may only signal slot completion.
class SpiSlave {
 public:
  enum class Phase { Idle,Request,Response,Finished,Fault };
  SpiSlave(readlink::SpiSlavePort& port,Endpoint& endpoint):port_(port),endpoint_(endpoint){}
  SpiSlave(const SpiSlave&)=delete;SpiSlave& operator=(const SpiSlave&)=delete;
  Phase phase()const{return phase_;}
  bool begin(){
    if(phase_!=Phase::Idle||!port_.admitted())return fail();
    sessionStart_=port_.monotonicMs();
    return armRequest();
  }
  bool poll(){
    if(phase_==Phase::Finished)return true;
    if(phase_==Phase::Fault||phase_==Phase::Idle)return false;
    const auto now=port_.monotonicMs();
    if(!port_.admitted()||uint32_t(now-sessionStart_)>=85000u||
       uint32_t(now-armedAt_)>=5000u)return fail();
    // The endpoint can already be closed while the final acknowledgement
    // is waiting in the response slot. Let that slot complete normally.
    if(!endpoint_.closed()&&!endpoint_.poll())return fail();
    return true;
  }
  bool completed(size_t bytes,bool clean){
    if((phase_!=Phase::Request&&phase_!=Phase::Response)||!poll()||!clean||bytes!=Size)return fail();
    if(phase_==Phase::Request){
      if(!endpoint_.transact(rx_,Size,tx_,Size)||!port_.admitted())return fail();
      std::memset(rx_,0,Size);
      if(!port_.arm(tx_,rx_,Size)||!port_.admitted())return fail();
      phase_=Phase::Response;armedAt_=port_.monotonicMs();port_.ready(true);
      return port_.admitted()||fail();
    }
    for(uint8_t byte:rx_)if(byte)return fail();
    if(endpoint_.closed()){
      phase_=Phase::Finished;port_.ready(false);return port_.admitted()||fail();
    }
    return armRequest();
  }
  void abort(){fail();}
 private:
  bool armRequest(){
    std::memset(tx_,0,Size);std::memset(rx_,0,Size);
    if(!port_.admitted()||!port_.arm(tx_,rx_,Size)||!port_.admitted())return fail();
    phase_=Phase::Request;armedAt_=port_.monotonicMs();port_.ready(false);
    return port_.admitted()||fail();
  }
  bool fail(){
    if(phase_!=Phase::Fault){
      phase_=Phase::Fault;endpoint_.fault();port_.fault();
    }
    return false;
  }
  readlink::SpiSlavePort& port_;Endpoint& endpoint_;
  uint8_t tx_[Size]{},rx_[Size]{};
  uint32_t sessionStart_=0,armedAt_=0;
  Phase phase_=Phase::Idle;
};
} }
