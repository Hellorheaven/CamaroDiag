#pragma once
#include "j1979_can_read_exchange.h"

namespace cd {
// Receiver-side sequence for a single approved raw live recording. The
// expected request is derived exclusively from the selected ECU's previous
// responses, the immutable plan and monotonic time. No retry of failed VIN
// admission, inventory, malformed replies or expired sessions.
class J1979LiveSequence {
 public:
  using Exchange=J1979CanReadExchange;
  using State=Exchange::State;
  static constexpr uint32_t MaximumRawBytes=16u*1024u*1024u;
  enum class Phase:uint8_t { InitialVin=1, Inventory=2, PeriodicVin=3,
    Sample=4, FinalVin=5, Waiting=6, Done=7 };
  enum class Stop:uint8_t { None=0, InvalidScope=1, Budget=2, Context=3,
    Transport=4, VinUnverified=5, VinMismatch=6, PidUnverified=7,
    NoRawResponse=8, SinkStopped=9, InvalidSequence=10, EvidenceLimit=11 };
  struct Summary {
    Stop stop=Stop::InvalidScope;
    uint32_t steps=0,samples=0,raw=0,negative=0,timeout=0,durationMs=0,rawBytes=0;
    uint16_t completedCycles=0;
    bool initialIdentity=false,finalIdentity=false,inventoryComplete=false;
    bool completed=false;
    uint8_t advertised[32]{};
    uint16_t rawPerPid[J1979LiveScope::MaximumPids]{};
  };

  explicit J1979LiveSequence(const J1979LiveScope& scope):scope_(scope){
    if(scope_.valid()){stage_=Phase::InitialVin;summary_.stop=Stop::None;}
  }
  bool begin(uint32_t now){
    if(started_||finished())return false;
    started_=true;start_=now;return true;
  }
  bool finished()const{return stage_==Phase::Done;}
  bool inFlight()const{return inFlight_;}
  const Summary& summary()const{return summary_;}
  const J1979LiveScope& scope()const{return scope_;}
  uint32_t elapsed(uint32_t now)const{return started_?uint32_t(now-start_):0;}
  uint32_t remaining(uint32_t now)const{
    const auto spent=elapsed(now);return spent<scope_.budgetMs?scope_.budgetMs-spent:0;
  }
  bool advertised(uint8_t pid)const{
    return J1979LiveScope::measurementPid(pid)&&
      (summary_.advertised[pid/8]&(1u<<(pid%8)))!=0;
  }
  uint16_t cycle()const{return uint16_t(summary_.completedCycles+1);}
  uint8_t selectedIndex()const{return index_;}
  void abort(Stop reason,uint32_t now){
    if(!finished()){summary_.stop=reason;stage_=Phase::Done;inFlight_=false;}
    summary_.durationMs=elapsed(now);
  }
  Phase phase(uint32_t now){
    if(!started_||finished())return Phase::Done;
    if(!remaining(now)){abort(Stop::Budget,now);return Phase::Done;}
    if(inFlight_)return pendingPhase_;
    if(stage_==Phase::Sample&&index_==0){
      if(haveCycleStart_&&uint32_t(now-cycleStart_)<scope_.intervalMs)return Phase::Waiting;
      if(uint32_t(now-lastIdentity_)>=J1979LiveScope::IdentityInterval)return Phase::PeriodicVin;
    }
    return stage_;
  }
  // Host scheduling margin: separate MCU clocks and SPI mailbox latency
  // can place the receiver's first sample later than the host's. Never
  // relax the receiver's actual minimum cadence or identity deadline.
  uint32_t hostWaitRemaining(uint32_t now)const{
    if(stage_!=Phase::Sample||index_!=0||!haveCycleStart_)return 0;
    const uint32_t desired=uint32_t(scope_.intervalMs)+25u;
    const uint32_t spent=uint32_t(now-cycleStart_);
    return spent<desired?desired-spent:0;
  }
  Phase recommendedPhase(uint32_t now){
    const auto current=phase(now);
    if(current==Phase::Sample&&index_==0&&!earlyIdentity_&&
       uint32_t(now-lastIdentity_)>=J1979LiveScope::IdentityInterval-1000u)
      return Phase::PeriodicVin;
    return current;
  }
  // At a cycle boundary an earlier VIN recheck is also allowed. This
  // accommodates bounded independent host/receiver clock differences without
  // ever permitting a sample once the receiver's identity deadline expired.
  bool allows(const uint8_t* req,size_t n,uint32_t now){
    if(!started_||inFlight_||!scope_.allows(req,n))return false;
    const auto current=phase(now);
    if(current==Phase::Done||current==Phase::Waiting)return false;
    if(current==Phase::Sample&&index_==0&&req[0]==9&&req[1]==2)
      return !earlyIdentity_;
    uint8_t expected[2]{};
    return next(now,expected)&&std::memcmp(req,expected,2)==0;
  }
  bool next(uint32_t now,uint8_t (&req)[2]){
    if(inFlight_)return false;
    switch(phase(now)){
      case Phase::InitialVin:case Phase::PeriodicVin:case Phase::FinalVin:
        req[0]=9;req[1]=2;return true;
      case Phase::Inventory:req[0]=1;req[1]=base_;return true;
      case Phase::Sample:req[0]=1;req[1]=scope_.pids[index_];return true;
      default:return false;
    }
  }
  // Reserve the worst-case correlated application response before sending.
  // This makes a capacity stop verifiable without recording a discarded
  // or truncated response. VIN/inventory/negative responses are bounded.
  static uint32_t maximumReply(Phase phase){
    switch(phase){
      case Phase::InitialVin:case Phase::PeriodicVin:case Phase::FinalVin:return 20;
      case Phase::Inventory:return 6;
      case Phase::Sample:return 4095;
      default:return 0;
    }
  }
  bool canRetain(Phase phase)const{
    const uint32_t size=maximumReply(phase);
    return size&&summary_.rawBytes<=MaximumRawBytes&&
      size<=MaximumRawBytes-summary_.rawBytes;
  }
  bool admit(const uint8_t* req,size_t n,uint32_t now,uint32_t timeout){
    if(!allows(req,n,now)||!timeout||timeout>4000||timeout>remaining(now)){
      abort(Stop::InvalidSequence,now);return false;
    }
    pendingPhase_=phase(now);
    if(pendingPhase_==Phase::Sample&&req[0]==9)pendingPhase_=Phase::PeriodicVin;
    if(!canRetain(pendingPhase_)){abort(Stop::EvidenceLimit,now);return false;}
    pending_[0]=req[0];pending_[1]=req[1];pendingStart_=now;pendingTimeout_=timeout;
    if(pendingPhase_==Phase::Sample&&index_==0){cycleStart_=now;haveCycleStart_=true;}
    inFlight_=true;return true;
  }
  bool observe(const uint8_t* req,size_t n,const Exchange::Result& reply,uint32_t now){
    if(!inFlight_||!req||n!=2||std::memcmp(req,pending_,2)!=0){
      abort(Stop::InvalidSequence,now);return false;
    }
    const auto observedPhase=pendingPhase_;
    inFlight_=false;++summary_.steps;summary_.durationMs=elapsed(now);
    const uint32_t duration=uint32_t(now-pendingStart_);
    if(!remaining(now)){abort(Stop::Budget,now);return false;}
    if(duration>pendingTimeout_||reply.elapsedMs>pendingTimeout_){
      abort(Stop::Transport,now);return false;
    }
    using R=J1979ReadEvidence::Result;
    const auto shape=reply.responseSize<=sizeof(reply.response)?
      J1979ReadEvidence::classifyLive(scope_,req,n,reply.response,reply.responseSize):R::Invalid;
    const bool positive=reply.state==State::Positive&&shape==R::Positive;
    const bool raw=reply.state==State::Raw&&shape==R::Raw;
    const bool negative=reply.state==State::Negative&&shape==R::Negative;
    const bool timeout=reply.state==State::Timeout&&reply.responseSize==0;
    if(!positive&&!raw&&!negative&&!timeout){abort(Stop::Transport,now);return false;}
    if(reply.responseSize>MaximumRawBytes-summary_.rawBytes){
      abort(Stop::EvidenceLimit,now);return false;
    }
    summary_.rawBytes+=uint32_t(reply.responseSize);
    if(observedPhase==Phase::InitialVin||observedPhase==Phase::PeriodicVin||observedPhase==Phase::FinalVin){
      if(!positive){abort(Stop::VinUnverified,now);return true;}
      if(!scope_.vinMatches(reply.response,reply.responseSize)){abort(Stop::VinMismatch,now);return true;}
      lastIdentity_=now;
      if(observedPhase==Phase::InitialVin){summary_.initialIdentity=true;stage_=Phase::Inventory;}
      else if(observedPhase==Phase::PeriodicVin){earlyIdentity_=true;stage_=Phase::Sample;}
      else {
        summary_.finalIdentity=true;stage_=Phase::Done;
        for(size_t i=0;i<scope_.count;++i)
          if(!summary_.rawPerPid[i]){summary_.stop=Stop::NoRawResponse;return true;}
        summary_.completed=true;
      }
      return true;
    }
    if(observedPhase==Phase::Inventory){
      if(!positive||reply.responseSize!=6){abort(Stop::PidUnverified,now);return true;}
      for(unsigned bit=0;bit<31;++bit){
        if(!(reply.response[2+bit/8]&(0x80u>>(bit%8))))continue;
        const unsigned pid=unsigned(base_)+bit+1;
        summary_.advertised[pid/8]|=uint8_t(1u<<(pid%8));
      }
      const bool more=(reply.response[5]&1u)!=0;
      summary_.inventoryComplete=!more;
      bool all=true;
      for(size_t i=0;i<scope_.count;++i)if(!advertised(scope_.pids[i]))all=false;
      if(all){stage_=Phase::Sample;return true;}
      if(!more||base_==224){abort(Stop::PidUnverified,now);return true;}
      base_=uint8_t(base_+32);return true;
    }
    if(observedPhase!=Phase::Sample||(!raw&&!negative&&!timeout)){
      abort(Stop::Transport,now);return false;
    }
    ++summary_.samples;
    if(raw){++summary_.raw;++summary_.rawPerPid[index_];}
    else if(negative)++summary_.negative;
    else ++summary_.timeout;
    ++index_;
    if(index_==scope_.count){
      index_=0;earlyIdentity_=false;++summary_.completedCycles;
      if(summary_.completedCycles==scope_.cycles)stage_=Phase::FinalVin;
    }
    return true;
  }
 private:
  const J1979LiveScope scope_;
  Summary summary_{};
  Phase stage_=Phase::Done,pendingPhase_=Phase::Done;
  bool started_=false,inFlight_=false,haveCycleStart_=false,earlyIdentity_=false;
  uint32_t start_=0,lastIdentity_=0,cycleStart_=0,pendingStart_=0,pendingTimeout_=0;
  uint8_t base_=0,index_=0,pending_[2]{};
};
}
