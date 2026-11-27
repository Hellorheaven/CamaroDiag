#pragma once
#include "j1979_maintenance_campaign.h"

namespace cd {
// Dedicated physical CAN implementation. The interlock is local to the
// qualified board and must not derive its decision from the USB peer.
// Read-only CI/2, CI/3 and CU/1..3 are deliberately unaffected.
class J1979MaintenanceCanLink final:public J1979MaintenanceCampaign::Link {
 public:
  using Scope=J1979MaintenanceScope;
  using Exchange=J1979CanReadExchange;
  using State=Exchange::State;
  class Interlock {
   public:
    virtual ~Interlock()=default;
    virtual bool active(const Scope& exact)=0;
    // Atomically consumes the local one-shot command authorization.
    // The persistent command intent must already be verified.
    virtual bool consumeIntent(const Scope& exact)=0;
  };
  J1979MaintenanceCanLink(J1979CanReadLink& bus,Interlock& lock,const Scope& approved):
    bus_(bus),lock_(lock),scope_(approved){}
  J1979MaintenanceCanLink(const J1979MaintenanceCanLink&)=delete;
  J1979MaintenanceCanLink& operator=(const J1979MaintenanceCanLink&)=delete;
  bool active()override{return !fault_&&scope_.valid()&&lock_.active(scope_)&&bus_.active();}
  uint32_t monotonicMs()override{return bus_.monotonicMs();}
  bool matches(const Scope& scope)const override{return scope_.same(scope);}
  void read(const uint8_t* req,size_t n,uint32_t timeout,Exchange::Result& result)override{
    reset(result);
    if(!active()||!scope_.read(req,n)||!timeout||timeout>J1979MaintenanceSequence::MaxExchangeMs){
      fault_=true;return;
    }
    Guarded guarded(*this);
    Exchange::run(guarded,scope_.tx,scope_.rx,scope_.extended,req,n,timeout,result);
    if(!active()||result.state==State::Fault){fault_=true;result.state=State::Fault;result.responseSize=0;}
  }
  void command(const Scope& scope,uint32_t timeout,Exchange::Result& result)override{
    reset(result);
    if(attempted_||!active()||!matches(scope)||!timeout||timeout>J1979MaintenanceSequence::MaxExchangeMs){
      fault_=true;return;
    }
    attempted_=true;
    const uint32_t started=monotonicMs();
    if(!lock_.consumeIntent(scope_)||!active()){fault_=true;return;}
    // No caller-controlled bytes, no arbitrary diagnostic service, no
    // segmented requests. Only the exact two supported maintenance PDUs.
    const uint8_t clear[]={4},evap[]={8,1};
    const bool clearing=scope_.operation==Scope::Operation::Clear;
    const uint8_t* req=clearing?clear:evap;
    const size_t size=clearing?sizeof(clear):sizeof(evap);
    auto within=[&](){return active()&&uint32_t(monotonicMs()-started)<timeout;};
    auto finishFault=[&](){fault_=true;result.state=State::Fault;result.responseSize=0;
      result.elapsedMs=uint32_t(monotonicMs()-started);};
    if(!within()||!bus_.discardReceive()||!within()){finishFault();return;}
    J1979CanReadLink::Frame frame{};
    frame.id=scope_.tx;frame.extended=scope_.extended;frame.length=8;
    frame.data[0]=uint8_t(size);std::memcpy(frame.data+1,req,size);
    const uint32_t beforeSend=uint32_t(monotonicMs()-started);
    if(beforeSend>=timeout){finishFault();return;}
    uint32_t remaining=timeout-beforeSend;
    if(!bus_.send(frame,remaining)||!active()){finishFault();return;}
    for(unsigned i=0;i<8192;++i){
      const auto spent=uint32_t(monotonicMs()-started);
      if(!active()){finishFault();return;}
      if(spent>=timeout){result.state=State::Timeout;result.elapsedMs=spent;fault_=true;return;}
      remaining=timeout-spent;
      J1979CanReadLink::Frame reply{};
      const auto received=bus_.receive(reply,remaining<20?remaining:20);
      if(!active()||received==J1979CanReadLink::Receive::Fault){finishFault();return;}
      if(received==J1979CanReadLink::Receive::NoFrame)continue;
      if(reply.id!=scope_.rx||reply.extended!=scope_.extended)continue;
      if(!within()){finishFault();return;}
      // Both acknowledgments and NRCs must be single frames. Never issue
      // flow control for an unexpected segmented mutation response.
      if(reply.length<2||reply.length>8||reply.data[0]>>4){finishFault();return;}
      const size_t n=reply.data[0]&15u;
      if(!n||n>size_t(reply.length-1)){finishFault();return;}
      const auto shape=scope_.classify(req,size,reply.data+1,n);
      if(shape==Scope::Shape::Pending)continue; // no restart of deadline
      if(shape!=Scope::Shape::Positive&&shape!=Scope::Shape::Negative){finishFault();return;}
      result.state=shape==Scope::Shape::Positive?State::Positive:State::Negative;
      result.responseSize=n;std::memcpy(result.response,reply.data+1,n);
      result.elapsedMs=uint32_t(monotonicMs()-started);
      if(!within()){finishFault();return;}
      return;
    }
    finishFault();
  }
 private:
  class Guarded final:public J1979CanReadLink {
   public:
    explicit Guarded(J1979MaintenanceCanLink& owner):owner_(owner){}
    bool active()override{return owner_.active();}
    uint32_t monotonicMs()override{return owner_.monotonicMs();}
    bool discardReceive()override{return active()&&owner_.bus_.discardReceive()&&active();}
    bool send(const Frame& frame,uint32_t timeout)override{
      return active()&&owner_.bus_.send(frame,timeout)&&active();
    }
    Receive receive(Frame& frame,uint32_t timeout)override{
      if(!active())return Receive::Fault;
      const auto result=owner_.bus_.receive(frame,timeout);
      return active()?result:Receive::Fault;
    }
   private:J1979MaintenanceCanLink& owner_;
  };
  static void reset(Exchange::Result& r){r.state=State::Fault;r.responseSize=0;r.elapsedMs=0;}
  J1979CanReadLink& bus_;Interlock& lock_;const Scope scope_;
  bool attempted_=false,fault_=false;
};
}
