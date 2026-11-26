#pragma once
#include "j1979_interprocessor_read.h"

namespace cd { namespace readlink {
// Two chip-select transactions per mailbox request. READY low means the
// request slot is armed, READY high means the response slot is armed.
// The board must independently establish admission and initial synchronization;
// a disconnected/pulled-down READY pin is NOT proof of a qualified session.
class SpiMasterPort {
 public:
  virtual ~SpiMasterPort()=default;
  virtual bool admitted()=0;
  virtual bool responseReady()=0;
  virtual uint32_t monotonicMs()=0;
  virtual void yield()=0;
  // Exactly Size bytes under one CS assertion; release CS on every return.
  // Must not queue or retry transfers, even after an uncertain completion.
  virtual bool transfer(const uint8_t* tx,uint8_t* rx,uint32_t remainingMs)=0;
  virtual void fault()=0;
};
class SpiTransport final:public Transport {
 public:
  explicit SpiTransport(SpiMasterPort& port):port_(port){}
  bool active()override{return !closed_&&port_.admitted();}
  uint32_t monotonicMs()override{return port_.monotonicMs();}
  bool exchange(const uint8_t* req,uint8_t* res,uint32_t timeout)override{
    if(closed_)return false;
    Packet packet;
    if(!req||!res||timeout<1||timeout>4000||!active()||
       !decode(req,Size,packet)||(packet.op!=Op::Read&&packet.op!=Op::Fetch)||
       port_.responseReady())return fail();
    const uint32_t start=monotonicMs();
    uint8_t ignored[Size]{},dummy[Size]{},reply[Size]{};
    if(!transfer(req,ignored,start,timeout)||!waitFor(true,start,timeout)||
       !transfer(dummy,reply,start,timeout)||!waitFor(false,start,timeout))
      return fail();
    Packet result;
    const auto expected=packet.op==Op::Read?Op::Result:Op::Chunk;
    if(!decode(reply,Size,result)||result.op!=expected||result.seq!=packet.seq)
      return fail();
    std::memcpy(res,reply,Size);return true;
  }
 private:
  bool fail(){if(!closed_){closed_=true;port_.fault();}return false;}
  bool within(uint32_t start,uint32_t budget){return active()&&uint32_t(monotonicMs()-start)<budget;}
  bool transfer(const uint8_t* tx,uint8_t* rx,uint32_t start,uint32_t budget){
    if(!within(start,budget))return false;
    const uint32_t remaining=budget-uint32_t(monotonicMs()-start);
    return remaining&&port_.transfer(tx,rx,remaining)&&within(start,budget);
  }
  bool waitFor(bool level,uint32_t start,uint32_t budget){
    // Bound even a faulty/non-advancing monotonic source.
    for(unsigned poll=0;poll<65536;++poll){
      if(!within(start,budget))return false;
      if(port_.responseReady()==level)return within(start,budget);
      port_.yield();
    }
    return false;
  }
  SpiMasterPort& port_;bool closed_=false;
};

// Task-context state machine for a qualified SPI slave. The hardware
// adapter arms exactly one full-duplex DMA/IRQ slot before publishing READY.
// Never call Endpoint::transact from an interrupt. On fault, the board must
// independently disable vehicle TX and latch recovery if stop is uncertain.
class SpiSlavePort {
 public:
  virtual ~SpiSlavePort()=default;
  virtual bool admitted()=0;
  virtual uint32_t monotonicMs()=0;
  virtual bool arm(const uint8_t* tx,uint8_t* rx,size_t length)=0;
  virtual void ready(bool response)=0;
  virtual void fault()=0;
};
class SpiSlave {
 public:
  enum class Phase { Idle, Request, Response, Closed };
  SpiSlave(SpiSlavePort& port,Endpoint& endpoint):port_(port),endpoint_(endpoint){}
  SpiSlave(const SpiSlave&)=delete;
  SpiSlave& operator=(const SpiSlave&)=delete;
  Phase phase()const{return phase_;}
  bool begin(uint32_t idleBudget){
    if(phase_!=Phase::Idle||idleBudget<1||idleBudget>600000u||!port_.admitted())return fail();
    idleBudget_=idleBudget;
    return armRequest();
  }
  bool poll(){
    if(phase_==Phase::Closed)return false;
    if(phase_==Phase::Idle)return true;
    if(!port_.admitted()||uint32_t(port_.monotonicMs()-armedAt_)>=idleBudget_)return fail();
    return true;
  }
  // Called once by the owner task after NSS deassertion and a completed slot.
  // A partial, extra-clock or failed transfer never reaches the endpoint.
  bool completed(size_t bytes,bool clean){
    if((phase_!=Phase::Request&&phase_!=Phase::Response)||!poll()||!clean||bytes!=Size)
      return fail();
    if(phase_==Phase::Request){
      if(!endpoint_.transact(rx_,Size,tx_,Size)||!port_.admitted())return fail();
      std::memset(rx_,0,Size);
      if(!port_.arm(tx_,rx_,Size)||!port_.admitted())return fail();
      phase_=Phase::Response;armedAt_=port_.monotonicMs();port_.ready(true);
      return true;
    }
    for(uint8_t byte:rx_)if(byte)return fail();
    if(endpoint_.closed())return fail();
    return armRequest();
  }
  void abort(){fail();}
 private:
  bool armRequest(){
    std::memset(tx_,0,Size);std::memset(rx_,0,Size);
    if(!port_.admitted()||!port_.arm(tx_,rx_,Size)||!port_.admitted())return fail();
    phase_=Phase::Request;armedAt_=port_.monotonicMs();port_.ready(false);
    return true;
  }
  bool fail(){if(phase_!=Phase::Closed){phase_=Phase::Closed;port_.fault();}return false;}
  SpiSlavePort& port_;Endpoint& endpoint_;
  uint8_t tx_[Size]{},rx_[Size]{};
  uint32_t idleBudget_=0,armedAt_=0;
  Phase phase_=Phase::Idle;
};
} }
