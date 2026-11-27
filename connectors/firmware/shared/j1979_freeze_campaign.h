#pragma once
#include "j1979_can_campaign.h"

namespace cd {
// Bounded explicit Mode 02 recipe, no heap and no retained VIN/vehicle log.
// The application link must be independently bound to the same immutable
// local scope. Observation evidence is streamed; no ECU write is supported.
class J1979FreezeCampaign {
 public:
  using Exchange=J1979CanReadExchange;
  using State=Exchange::State;
  using Link=J1979CanCampaign::ApplicationLink;
  static constexpr uint32_t MaximumSteps=8u*(8u+2u+247u)+2u;
  enum class Outcome:uint8_t {
    Received=1,Timeout=2,TransportError=3,Budget=4,ContextChanged=5,LateResponse=6
  };
  enum class Stop:uint8_t {
    None=0,Budget=1,TransportError=2,ContextChanged=3,
    VinUnverified=4,VinMismatch=5,FinalVinUnverified=6,FinalVinMismatch=7,
    SinkStopped=8
  };
  enum class FrameState:uint8_t { Unavailable=0,Absent=1,Complete=2,Partial=3,Changed=4 };
  struct Summary {
    Stop stop=Stop::ContextChanged;
    uint32_t steps=0,durationMs=0;
    uint8_t visited=0;
    bool identityRechecked=false,allFramesStable=false,completed=false;
    FrameState frames[J1979FreezeScope::MaximumFrames]{};
  };
  class Sink {
   public:
    virtual ~Sink()=default;
    virtual bool observation(const uint8_t* request,size_t size,Outcome outcome,
        bool exchangeAttempted,uint32_t duration,const Exchange::Result& reply)=0;
  };

  static Summary run(J1979CanReadLink& hardware,uint32_t tx,uint32_t rx,bool ext,
      const J1979FreezeScope& scope,uint32_t budget,Sink& sink,Exchange::Result& scratch){
    class Local final:public Link {
     public:
      Local(J1979CanReadLink& h,uint32_t tx,uint32_t rx,bool ext,const J1979FreezeScope& s)
        :h_(h),tx_(tx),rx_(rx),ext_(ext),scope_(s){}
      bool active()override{return h_.active();}
      bool matches(uint32_t tx,uint32_t rx,bool ext)const override{
        return tx==tx_&&rx==rx_&&ext==ext_;
      }
      bool matchesFreeze(const J1979FreezeScope& s)const override{return scope_.same(s);}
      uint32_t monotonicMs()override{return h_.monotonicMs();}
      void exchange(const uint8_t* req,size_t n,uint32_t timeout,Exchange::Result& result)override{
        Exchange::run(h_,tx_,rx_,ext_,req,n,timeout,result,&scope_);
      }
     private:
      J1979CanReadLink& h_;uint32_t tx_,rx_;bool ext_;const J1979FreezeScope scope_;
    } local(hardware,tx,rx,ext,scope);
    return runApplication(local,tx,rx,ext,scope,budget,sink,scratch);
  }

  static Summary runApplication(Link& link,uint32_t tx,uint32_t rx,bool ext,
      const J1979FreezeScope& requested,uint32_t budget,Sink& sink,Exchange::Result& scratch){
    const J1979FreezeScope scope=requested;
    Summary summary;
    if(!scope.valid()||budget<1000u||budget>600000u||
       !J1979CanReadSession::physicalPair(tx,rx,ext)||!link.matches(tx,rx,ext)||
       !link.matchesFreeze(scope)||!link.active())return summary;
    const uint32_t started=link.monotonicMs();
    summary.stop=Stop::None;
    auto elapsed=[&](){return uint32_t(link.monotonicMs()-started);};

    // Returns true if an observation was emitted. The scratch reply belongs
    // to this request only. Negative or raw are distinct from positive.
    auto read=[&](const uint8_t* request,size_t size)->bool{
      if(summary.stop!=Stop::None)return false;
      if(summary.steps>=MaximumSteps||!scope.allows(request,size)){
        summary.stop=Stop::ContextChanged;return false;
      }
      const uint32_t begin=elapsed();
      bool attempted=false;
      Outcome outcome=Outcome::ContextChanged;
      scratch.state=State::Fault;scratch.responseSize=0;scratch.elapsedMs=0;
      if(!link.active()){
        summary.stop=Stop::ContextChanged;
      }else if(begin>=budget||budget-begin<2u){
        outcome=Outcome::Budget;summary.stop=Stop::Budget;
      }else{
        attempted=true;
        const uint32_t left=budget-begin;
        link.exchange(request,size,left<2500u?left:2500u,scratch);
        const bool response=scratch.state==State::Positive||scratch.state==State::Raw||
          scratch.state==State::Negative;
        bool valid=response&&scratch.responseSize>0&&scratch.responseSize<=sizeof(scratch.response);
        if(valid){
          const auto shape=J1979ReadEvidence::classifyFreeze(scope,request,size,
              scratch.response,scratch.responseSize);
          using R=J1979ReadEvidence::Result;
          valid=(scratch.state==State::Positive&&shape==R::Positive)||
            (scratch.state==State::Raw&&shape==R::Raw)||
            (scratch.state==State::Negative&&shape==R::Negative);
        }
        if(!link.active()){
          outcome=Outcome::TransportError;summary.stop=Stop::TransportError;
        }else if(valid){
          if(elapsed()>=budget){outcome=Outcome::LateResponse;summary.stop=Stop::Budget;}
          else outcome=Outcome::Received;
        }else if(scratch.state==State::Timeout&&scratch.responseSize==0){
          outcome=Outcome::Timeout;
        }else{
          outcome=Outcome::TransportError;summary.stop=Stop::TransportError;
        }
      }
      if(outcome!=Outcome::Received&&outcome!=Outcome::LateResponse){
        scratch.responseSize=0;scratch.state=outcome==Outcome::Timeout?State::Timeout:State::Fault;
      }
      const uint32_t duration=elapsed()-begin;
      ++summary.steps;
      if(!sink.observation(request,size,outcome,attempted,duration,scratch)){
        summary.stop=Stop::SinkStopped;return false;
      }
      return true;
    };
    auto positive=[&](const uint8_t* request,size_t size)->bool{
      return read(request,size)&&summary.stop==Stop::None&&scratch.state==State::Positive;
    };
    const uint8_t vinRequest[]={9,2};
    const bool initialVin=positive(vinRequest,sizeof(vinRequest));
    if(summary.stop==Stop::None&&!initialVin)summary.stop=Stop::VinUnverified;
    else if(summary.stop==Stop::None&&!scope.vinMatches(scratch.response,scratch.responseSize))
      summary.stop=Stop::VinMismatch;

    auto acquire=[&](uint8_t frame)->FrameState{
      bool ids[256]{};
      bool more=false;
      auto page=[&](uint8_t base)->bool{
        const uint8_t request[]={2,base,frame};
        if(!positive(request,sizeof(request)))return false;
        if(scratch.responseSize!=7)return false;
        for(unsigned bit=0;bit<31;++bit)
          if(scratch.response[3+bit/8]&(0x80u>>(bit%8)))ids[unsigned(base)+bit+1]=true;
        more=(scratch.response[6]&1u)!=0;
        return true;
      };
      if(!page(0)||!ids[2])return FrameState::Unavailable;
      const uint8_t triggerRequest[]={2,2,frame};
      if(!positive(triggerRequest,sizeof(triggerRequest))||scratch.responseSize!=5)
        return FrameState::Unavailable;
      const uint8_t firstA=scratch.response[3],firstB=scratch.response[4];
      if(!firstA&&!firstB)return FrameState::Absent;
      bool complete=!more;
      for(unsigned base=32;base<256&&more&&summary.stop==Stop::None;base+=32){
        if(!page(uint8_t(base)))break;
        complete=!more;
      }
      bool failures=false;
      for(unsigned pid=1;pid<256;++pid){
        if(!ids[pid]||!scope.includesPid(uint8_t(pid)))continue;
        const uint8_t request[]={2,uint8_t(pid),frame};
        if(!read(request,sizeof(request))){failures=true;break;}
        if(scratch.state!=State::Positive&&scratch.state!=State::Raw)failures=true;
      }
      if(summary.stop!=Stop::None)return FrameState::Partial;
      if(!positive(triggerRequest,sizeof(triggerRequest))||scratch.responseSize!=5)
        return FrameState::Partial;
      if(scratch.response[3]!=firstA||scratch.response[4]!=firstB)return FrameState::Changed;
      return !complete||failures?FrameState::Partial:FrameState::Complete;
    };

    for(size_t index=0;index<scope.frameCount&&summary.stop==Stop::None;++index){
      summary.frames[index]=acquire(scope.frames[index]);++summary.visited;
    }
    if(summary.stop==Stop::None){
      const bool finalVin=positive(vinRequest,sizeof(vinRequest));
      if(summary.stop==Stop::None&&!finalVin)summary.stop=Stop::FinalVinUnverified;
      else if(summary.stop==Stop::None&&!scope.vinMatches(scratch.response,scratch.responseSize))
        summary.stop=Stop::FinalVinMismatch;
    }
    summary.completed=summary.stop==Stop::None&&summary.visited==scope.frameCount;
    summary.identityRechecked=summary.completed;
    summary.allFramesStable=summary.completed;
    for(size_t i=0;i<summary.visited;++i)
      if(summary.frames[i]!=FrameState::Complete&&summary.frames[i]!=FrameState::Absent)
        summary.allFramesStable=false;
    summary.durationMs=elapsed();
    return summary;
  }
};
}
