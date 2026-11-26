#pragma once
#include "j1979_can_read_exchange.h"

namespace cd {
// Bounded standalone physical CAN campaign. Evidence is streamed to a
// caller-owned sink (no heap, no retained vehicle log in firmware).
// No mutator, automatic transport negotiation or hardware activation.
class J1979CanCampaign {
 public:
  using Exchange=J1979CanReadExchange;
  using State=Exchange::State;
  enum class End { Complete, InitialUnverified, Budget, LinkFault, SinkStopped };
  struct Summary {
    End end=End::LinkFault;
    uint32_t steps=0, positive=0, raw=0, negative=0, timeout=0;
    bool transportObserved=false;
    bool pidInventoryComplete=false,freezeTriggerObserved=false,freezeFramePresent=false;
    bool freezeInventoryComplete=false,monitorInventoryComplete=false;
    bool controlInventoryComplete=false,identityInventoryComplete=false;
  };
  class Sink {
   public:
    virtual ~Sink()=default;
    virtual bool observation(const uint8_t* request,size_t requestSize,
                             const Exchange::Result& reply)=0;
  };
  // Application-level boundary: the local CAN link or a remote processor
  // must deliver a correlated response and enforce the read-only policy.
  class ApplicationLink {
   public:
    virtual ~ApplicationLink()=default;
    virtual bool active()=0;
    virtual bool matches(uint32_t tx,uint32_t rx,bool extended)const=0;
    virtual uint32_t monotonicMs()=0;
    virtual void exchange(const uint8_t* request,size_t size,uint32_t timeout,
                          Exchange::Result& result)=0;
  };
  static Summary run(J1979CanReadLink& link,uint32_t tx,uint32_t rx,bool extended,
                     uint32_t budgetMs,Sink& sink,Exchange::Result& scratch){
    class Local final:public ApplicationLink {
     public:
      Local(J1979CanReadLink& l,uint32_t tx,uint32_t rx,bool ext):l_(l),tx_(tx),rx_(rx),ext_(ext){}
      bool active()override{return l_.active();}
      bool matches(uint32_t tx,uint32_t rx,bool ext)const override{
        return tx==tx_&&rx==rx_&&ext==ext_;
      }
      uint32_t monotonicMs()override{return l_.monotonicMs();}
      void exchange(const uint8_t* req,size_t n,uint32_t timeout,Exchange::Result& result)override{
        Exchange::run(l_,tx_,rx_,ext_,req,n,timeout,result);
      }
     private:
      J1979CanReadLink& l_;uint32_t tx_,rx_;bool ext_;
    } local(link,tx,rx,extended);
    return runApplication(local,tx,rx,extended,budgetMs,sink,scratch);
  }
  static Summary runApplication(ApplicationLink& link,uint32_t tx,uint32_t rx,bool extended,
                     uint32_t budgetMs,Sink& sink,Exchange::Result& scratch){
    Summary s;
    if(budgetMs<1||budgetMs>600000u||
       !J1979CanReadSession::physicalPair(tx,rx,extended)||
       !link.matches(tx,rx,extended)||!link.active())return s;
    const uint32_t started=link.monotonicMs();
    s.end=End::Complete;
    auto read=[&](const uint8_t* request,size_t size)->bool {
      if(s.end!=End::Complete)return false;
      const uint32_t elapsed=uint32_t(link.monotonicMs()-started);
      if(elapsed>=budgetMs){s.end=End::Budget;return false;}
      const uint32_t remaining=budgetMs-elapsed;
      scratch.state=State::Fault;scratch.responseSize=0;scratch.elapsedMs=0;
      if(link.active())link.exchange(request,size,remaining<1800u?remaining:1800u,scratch);
      // Independently check the application result before using its bytes
      // as bitmap or identity evidence, even for an alternate transport.
      using R=J1979ReadEvidence::Result;
      if(scratch.state==State::Positive||scratch.state==State::Raw||scratch.state==State::Negative){
        const auto shape=scratch.responseSize<=sizeof(scratch.response)?
          J1979ReadEvidence::classify(J1979ReadEvidence::Variant::ClassicCan,
            request,size,scratch.response,scratch.responseSize):R::Invalid;
        if((scratch.state==State::Positive&&shape!=R::Positive)||
           (scratch.state==State::Raw&&shape!=R::Raw)||
           (scratch.state==State::Negative&&shape!=R::Negative)){
          scratch.state=State::Fault;scratch.responseSize=0;
        }
      }else scratch.responseSize=0;
      ++s.steps;
      switch(scratch.state){
        case State::Positive:++s.positive;break;
        case State::Raw:++s.raw;break;
        case State::Negative:++s.negative;break;
        case State::Timeout:++s.timeout;break;
        default:s.end=End::LinkFault;break;
      }
      if(!sink.observation(request,size,scratch)){s.end=End::SinkStopped;return false;}
      return s.end==End::Complete&&scratch.state==State::Positive;
    };
    // Copy a page before another exchange reuses the scratch buffer.
    uint8_t initial[4]{};
    const uint8_t first[]={1,0};
    if(!read(first,2)){
      if(s.end==End::Complete)s.end=End::InitialUnverified;
      return s;
    }
    s.transportObserved=true;
    std::memcpy(initial,scratch.response+2,4);
    const uint8_t vin[]={9,2};
    read(vin,2);
    const uint8_t dtcModes[]={3,7,10};
    for(uint8_t mode:dtcModes)read(&mode,1);

    auto inventory=[&](uint8_t mode,bool firstCached,bool readMembers)->bool {
      const size_t requestSize=mode==2?3:2;
      const size_t offset=mode==2?3:2;
      for(unsigned base=0;base<=0xe0&&s.end==End::Complete;base+=32){
        uint8_t bitmap[4]{};
        uint8_t req[]={mode,static_cast<uint8_t>(base),0};
        if(firstCached&&base==0)std::memcpy(bitmap,initial,4);
        else{
          if(!read(req,requestSize))return false;
          std::memcpy(bitmap,scratch.response+offset,4);
        }
        if(readMembers)for(unsigned bit=0;bit<31&&s.end==End::Complete;++bit){
          if(!(bitmap[bit/8]&(0x80u>>(bit%8))))continue;
          req[1]=static_cast<uint8_t>(base+bit+1);
          // VIN was read first; freeze trigger already read separately.
          if((mode==9&&req[1]==2)||(mode==2&&req[1]==2))continue;
          read(req,requestSize);
        }
        if(!(bitmap[3]&1u))return s.end==End::Complete;
      }
      return false;
    };
    s.pidInventoryComplete=inventory(1,true,true);
    const uint8_t trigger[]={2,2,0};
    s.freezeTriggerObserved=read(trigger,3);
    if(s.freezeTriggerObserved){
      s.freezeFramePresent=scratch.response[3]||scratch.response[4];
      if(s.freezeFramePresent)s.freezeInventoryComplete=inventory(2,false,true);
    }
    s.monitorInventoryComplete=inventory(6,false,true);
    s.controlInventoryComplete=inventory(8,false,false); // no control actions
    s.identityInventoryComplete=inventory(9,false,true);
    return s;
  }
};
}
