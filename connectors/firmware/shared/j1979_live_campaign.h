#pragma once
#include "j1979_can_campaign.h"
#include "j1979_live_sequence.h"

namespace cd {
// Runs one selected physical CAN raw-only recording. The application link
// must independently hold the same approved scope. No automatic retry,
// driver activation, write, new connection or protocol negotiation.
class J1979LiveCampaign {
 public:
  using Sequence=J1979LiveSequence;
  using Exchange=Sequence::Exchange;
  using State=Sequence::State;
  using Phase=Sequence::Phase;
  using Stop=Sequence::Stop;
  using Summary=Sequence::Summary;
  using Link=J1979CanCampaign::ApplicationLink;
  static constexpr uint32_t MaximumSteps=J1979LiveScope::MaximumSamples+8u+1002u;
  class Waiter {
   public:
    virtual ~Waiter()=default;
    // Interruptible bounded wait. False means no more ECU requests.
    virtual bool waitMs(uint32_t duration)=0;
  };
  struct Observation {
    Phase phase=Phase::Done;
    uint16_t cycle=0;
    uint8_t pid=0;
    uint32_t startedElapsed=0,endedElapsed=0,timeoutMs=0;
    bool accepted=false;
  };
  class Sink {
   public:
    virtual ~Sink()=default;
    virtual bool observation(const Observation& event,const uint8_t* request,
      size_t n,const Exchange::Result& result)=0;
  };
  static Summary run(J1979CanReadLink& hardware,uint32_t tx,uint32_t rx,bool ext,
      const J1979LiveScope& scope,Waiter& waiter,Sink& sink,Exchange::Result& scratch){
    class Local final:public Link {
     public:
      Local(J1979CanReadLink& hw,uint32_t tx,uint32_t rx,bool ext,const J1979LiveScope& scope):
        hw_(hw),tx_(tx),rx_(rx),ext_(ext),scope_(scope){}
      bool active()override{return hw_.active();}
      bool matches(uint32_t tx,uint32_t rx,bool ext)const override{
        return tx==tx_&&rx==rx_&&ext==ext_;
      }
      bool matchesLive(const J1979LiveScope& scope)const override{return scope_.same(scope);}
      uint32_t monotonicMs()override{return hw_.monotonicMs();}
      void exchange(const uint8_t* req,size_t n,uint32_t timeout,Exchange::Result& output)override{
        Exchange::run(hw_,tx_,rx_,ext_,req,n,timeout,output,nullptr,&scope_);
      }
     private:
      J1979CanReadLink& hw_;uint32_t tx_,rx_;bool ext_;const J1979LiveScope scope_;
    } local(hardware,tx,rx,ext,scope);
    return runApplication(local,tx,rx,ext,scope,waiter,sink,scratch);
  }
  static Summary runApplication(Link& link,uint32_t tx,uint32_t rx,bool ext,
      const J1979LiveScope& requested,Waiter& waiter,Sink& sink,Exchange::Result& scratch){
    const J1979LiveScope scope=requested;
    Sequence sequence(scope);
    if(!scope.valid()||!J1979CanReadSession::physicalPair(tx,rx,ext)||
        !link.matches(tx,rx,ext)||!link.matchesLive(scope)||!link.active()){
      auto denied=sequence.summary();denied.stop=Stop::InvalidScope;return denied;
    }
    const uint32_t start=link.monotonicMs();
    if(!sequence.begin(start))return sequence.summary();
    uint32_t waitIterations=0;
    while(!sequence.finished()){
      const auto now=link.monotonicMs();
      if(!link.active()||!link.matches(tx,rx,ext)||!link.matchesLive(scope)){
        sequence.abort(Stop::Context,now);break;
      }
      const auto phase=sequence.recommendedPhase(now);
      if(phase==Phase::Done)break;
      const uint32_t hostWait=sequence.hostWaitRemaining(now);
      if(phase==Phase::Waiting||hostWait){
        const uint32_t remaining=sequence.remaining(now);
        if(!remaining){sequence.abort(Stop::Budget,now);break;}
        // At most 30,000 nominal 20ms waits over the maximum 10min
        // budget. A stalled/non-monotonic clock cannot loop forever.
        uint32_t pause=remaining<20u?remaining:20u;
        if(hostWait&&hostWait<pause)pause=hostWait;
        if(++waitIterations>60000u||!waiter.waitMs(pause)){
          sequence.abort(Stop::Context,link.monotonicMs());break;
        }
        if(link.monotonicMs()==now){
          sequence.abort(Stop::Context,now);break;
        }
        continue;
      }
      uint8_t req[2]{};
      const uint32_t timeout=sequence.remaining(now)<2500u?sequence.remaining(now):2500u;
      if(timeout<2u){sequence.abort(Stop::Budget,now);break;}
      bool next=false;
      if(phase==Phase::PeriodicVin){req[0]=9;req[1]=2;next=true;}
      else next=sequence.next(now,req);
      if(sequence.summary().steps>=MaximumSteps||!next||
          !sequence.admit(req,sizeof(req),now,timeout)){
        sequence.abort(Stop::InvalidSequence,now);break;
      }
      Observation event{};
      event.phase=phase;
      event.cycle=phase==Phase::Sample?sequence.cycle():0;
      event.pid=phase==Phase::Sample?req[1]:0;
      event.startedElapsed=sequence.elapsed(now);
      event.timeoutMs=timeout;
      scratch.state=State::Fault;scratch.responseSize=0;scratch.elapsedMs=0;
      if(link.active())link.exchange(req,sizeof(req),timeout,scratch);
      const auto end=link.monotonicMs();
      event.endedElapsed=sequence.elapsed(end);
      // No accepted sample after the lease, transport or target changed.
      if(!link.active()||!link.matches(tx,rx,ext)||!link.matchesLive(scope)){
        scratch.state=State::Fault;scratch.responseSize=0;
      }
      event.accepted=sequence.observe(req,sizeof(req),scratch,end);
      if(!event.accepted){
        // Rejected/late evidence is never exposed as a successful sample.
        // The raw bytes from an uncorrelated response are not exported.
        scratch.state=State::Fault;scratch.responseSize=0;
      }
      if(!sink.observation(event,req,sizeof(req),scratch)){
        sequence.abort(Stop::SinkStopped,end);
        auto summary=sequence.summary();summary.stop=Stop::SinkStopped;summary.completed=false;
        return summary;
      }
      if(!event.accepted)break;
    }
    return sequence.summary();
  }
};
}
