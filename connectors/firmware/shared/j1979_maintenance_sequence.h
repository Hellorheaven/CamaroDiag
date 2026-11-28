#pragma once
#include "j1979_maintenance_scope.h"

namespace cd {
// Pure one-shot state machine used at the vehicle-side boundary. No I/O.
// The caller must independently persist the baseline and intent and check
// local authorization before every exchange; a USB assertion is insufficient.
class J1979MaintenanceSequence {
 public:
  using Scope=J1979MaintenanceScope;
  enum class Stage:uint8_t { Initial=1, Baseline=2, Persist=3, Recheck=4,
    Intent=5, Command=6, Readback=7, Finished=8 };
  enum class Status:uint8_t { Positive=1, Negative=2, Timeout=3, Fault=4 };
  enum class Outcome:uint8_t { Running=0, NotTested=1, Refused=2, Uncertain=3,
    AcknowledgedPartial=4, AcknowledgedReadback=5, AcknowledgedEvap=6 };
  enum class Reason:uint8_t { None=0, InvalidScope=1, Context=2, Deadline=3,
    Sequence=4, Identity=5, Stationary=6, Unsupported=7, Baseline=8,
    Journal=9, Transport=10, Negative=11, Readback=12, DtcRemaining=13 };
  struct Request {
    uint8_t bytes[2]{};
    uint8_t size=0;
    Stage stage=Stage::Finished;
    bool same(const uint8_t* data,size_t n)const{
      return data&&size==n&&std::memcmp(bytes,data,n)==0;
    }
  };
  struct Summary {
    Outcome outcome=Outcome::NotTested;
    Reason reason=Reason::InvalidScope;
    uint8_t observations=0,nrc=0;
    bool baselinePersisted=false,intentPersisted=false,attempted=false,acknowledged=false;
    bool initialVin=false,recheckedVin=false,finalVin=false;
    bool beforeConfirmed=false,afterConfirmed=false,afterPending=false;
    bool afterConfirmedEmpty=false,afterPendingEmpty=false;
    uint32_t durationMs=0;
  };
  static constexpr uint8_t ClearRequests=16,EvapRequests=9;
  static constexpr uint32_t FreshnessMs=5000,MaxExchangeMs=1800;
  explicit J1979MaintenanceSequence(const Scope& scope):scope_(scope){
    if(scope.valid()){summary_.outcome=Outcome::Running;summary_.reason=Reason::None;}
  }
  bool begin(uint32_t now){
    if(started_||finished())return false;
    started_=true;start_=now;return true;
  }
  const Scope& scope()const{return scope_;}
  const Summary& summary()const{return summary_;}
  bool finished()const{return summary_.outcome!=Outcome::Running;}
  uint32_t remaining(uint32_t now)const{
    if(!started_)return 0;
    const auto elapsed=uint32_t(now-start_);
    return elapsed<scope_.budgetMs?scope_.budgetMs-elapsed:0;
  }
  // Remaining freshness at the actual command boundary. The transport
  // must include persistent intent readback and all pre-send work in this
  // same timeout, not restart its deadline after storage operations.
  uint32_t commandWindow(uint32_t now)const{
    if(!fresh(now))return 0;
    uint32_t age=uint32_t(now-identityAt_);
    const uint32_t speedAge=uint32_t(now-speedAt_),rpmAge=uint32_t(now-rpmAt_);
    if(speedAge>age)age=speedAge;
    if(rpmAge>age)age=rpmAge;
    return age<FreshnessMs?FreshnessMs-age:0;
  }
  Stage stage()const{
    if(finished())return Stage::Finished;
    if(index_==baselineBoundary()&&!summary_.baselinePersisted)return Stage::Persist;
    if(index_==commandIndex()&&!summary_.intentPersisted)return Stage::Intent;
    return requestAt(index_).stage;
  }
  Request next()const{
    const auto s=stage();
    return s==Stage::Persist||s==Stage::Intent||s==Stage::Finished?Request{}:requestAt(index_);
  }
  void abort(Reason reason,uint32_t now){
    if(finished())return;
    summary_.durationMs=started_?uint32_t(now-start_):0;
    summary_.reason=reason;
    summary_.outcome=summary_.acknowledged?Outcome::AcknowledgedPartial:
      summary_.attempted||summary_.intentPersisted?Outcome::Uncertain:Outcome::NotTested;
    pending_=false;
  }
  bool persistBaseline(bool verified,uint32_t now){
    if(!started_||pending_||stage()!=Stage::Persist||!remaining(now)){
      abort(Reason::Sequence,now);return false;
    }
    if(!verified){abort(Reason::Journal,now);return false;}
    summary_.baselinePersisted=true;return true;
  }
  bool persistIntent(bool verified,uint32_t now){
    if(!started_||pending_||stage()!=Stage::Intent||!remaining(now)||!fresh(now)){
      abort(Reason::Sequence,now);return false;
    }
    if(!verified){abort(Reason::Journal,now);return false;}
    summary_.intentPersisted=true;return true;
  }
  bool admit(const uint8_t* request,size_t n,uint32_t now,uint32_t timeout){
    if(!started_||finished()||pending_||!remaining(now)||!timeout||
       timeout>MaxExchangeMs||timeout>remaining(now)){
      abort(Reason::Deadline,now);return false;
    }
    const Request expected=next();
    if(!expected.size||!expected.same(request,n)){
      abort(Reason::Sequence,now);return false;
    }
    if(expected.stage==Stage::Command){
      if(summary_.attempted||!summary_.baselinePersisted||!summary_.intentPersisted||timeout>commandWindow(now)){
        abort(Reason::Stationary,now);return false;
      }
      // This marks the boundary before transport invocation, not proof
      // that a frame actually reached the ECU. Never attempt a retry.
      summary_.attempted=true;
    }
    pending_=true;pendingStart_=now;pendingTimeout_=timeout;return true;
  }
  bool observe(const uint8_t* req,size_t n,Status status,const uint8_t* response,
      size_t size,uint32_t now){
    if(!pending_||finished()||!requestAt(index_).same(req,n)){
      abort(Reason::Sequence,now);return false;
    }
    pending_=false;++summary_.observations;
    summary_.durationMs=uint32_t(now-start_);
    if(!remaining(now)||uint32_t(now-pendingStart_)>pendingTimeout_){
      abort(Reason::Deadline,now);return false;
    }
    const auto expected=requestAt(index_);
    if(status!=Status::Positive&&status!=Status::Negative&&status!=Status::Timeout&&status!=Status::Fault){
      abort(Reason::Transport,now);return false;
    }
    if(status==Status::Fault||(status==Status::Timeout&&size)||
       (status==Status::Positive&&scope_.classify(req,n,response,size)!=Scope::Shape::Positive)||
       (status==Status::Negative&&scope_.classify(req,n,response,size)!=Scope::Shape::Negative)){
      abort(Reason::Transport,now);return false;
    }
    const bool positive=status==Status::Positive;
    if(expected.stage==Stage::Command){
      if(status==Status::Negative){
        summary_.nrc=response[2];summary_.outcome=Outcome::Refused;summary_.reason=Reason::Negative;return true;
      }
      if(!positive){abort(Reason::Transport,now);return true;}
      summary_.acknowledged=true;
      if(scope_.operation==Scope::Operation::Evap){
        summary_.outcome=Outcome::AcknowledgedEvap;return true;
      }
    }else if(req[0]==9){
      if(!positive||!scope_.vinMatches(response,size)){abort(Reason::Identity,now);return true;}
      if(expected.stage==Stage::Initial)summary_.initialVin=true;
      else if(expected.stage==Stage::Recheck){
        summary_.recheckedVin=true;identityAt_=now;
      }else summary_.finalVin=true;
    }else if(n==2&&req[0]==1&&(req[1]==0x0d||req[1]==0x0c)){
      const size_t length=req[1]==0x0d?3:4;
      if(!positive||size!=length||response[2]||(length==4&&response[3])){
        abort(Reason::Stationary,now);return true;
      }
      if(expected.stage==Stage::Recheck){
        if(req[1]==0x0d){speedAt_=now;speedSeen_=true;}
        else{rpmAt_=now;rpmSeen_=true;}
      }
    }else if(req[0]==8){
      if(!positive||size!=6||!(response[2]&0x80u)){abort(Reason::Unsupported,now);return true;}
    }else if(req[0]==3&&expected.stage==Stage::Baseline){
      if(!positive){abort(Reason::Baseline,now);return true;}
      summary_.beforeConfirmed=true;
    }else if(expected.stage==Stage::Readback&&req[0]==3){
      summary_.afterConfirmed=positive;summary_.afterConfirmedEmpty=positive&&response[1]==0;
    }else if(expected.stage==Stage::Readback&&req[0]==7){
      summary_.afterPending=positive;summary_.afterPendingEmpty=positive&&response[1]==0;
    }
    ++index_;
    if(index_==ClearRequests&&scope_.operation==Scope::Operation::Clear){
      if(summary_.afterConfirmed&&summary_.afterPending&&summary_.finalVin){
        summary_.outcome=Outcome::AcknowledgedReadback;
        summary_.reason=summary_.afterConfirmedEmpty&&summary_.afterPendingEmpty?Reason::None:Reason::DtcRemaining;
      }else abort(Reason::Readback,now);
    }
    return true;
  }
 private:
  bool clear()const{return scope_.operation==Scope::Operation::Clear;}
  uint8_t baselineBoundary()const{return clear()?7:4;}
  uint8_t commandIndex()const{return clear()?10:8;}
  bool fresh(uint32_t now)const{
    return summary_.recheckedVin&&speedSeen_&&rpmSeen_&&
      uint32_t(now-identityAt_)<=FreshnessMs&&uint32_t(now-speedAt_)<=FreshnessMs&&
      uint32_t(now-rpmAt_)<=FreshnessMs;
  }
  static Request one(Stage stage,uint8_t mode){Request r;r.stage=stage;r.size=1;r.bytes[0]=mode;return r;}
  static Request two(Stage stage,uint8_t mode,uint8_t pid){Request r=one(stage,mode);r.size=2;r.bytes[1]=pid;return r;}
  Request requestAt(uint8_t i)const{
    if(clear()){
      switch(i){
        case 0:return two(Stage::Initial,9,2);
        case 1:return one(Stage::Baseline,3);
        case 2:return one(Stage::Baseline,7);
        case 3:return one(Stage::Baseline,10);
        case 4:return two(Stage::Baseline,1,1);
        case 5:return two(Stage::Baseline,1,0x0d);
        case 6:return two(Stage::Baseline,1,0x0c);
        case 7:return two(Stage::Recheck,9,2);
        case 8:return two(Stage::Recheck,1,0x0d);
        case 9:return two(Stage::Recheck,1,0x0c);
        case 10:return one(Stage::Command,4);
        case 11:return one(Stage::Readback,3);
        case 12:return one(Stage::Readback,7);
        case 13:return one(Stage::Readback,10);
        case 14:return two(Stage::Readback,1,1);
        case 15:return two(Stage::Readback,9,2);
        default:return Request{};
      }
    }
    switch(i){
      case 0:return two(Stage::Initial,9,2);
      case 1:return two(Stage::Baseline,8,0);
      case 2:return two(Stage::Baseline,1,0x0d);
      case 3:return two(Stage::Baseline,1,0x0c);
      case 4:return two(Stage::Recheck,9,2);
      case 5:return two(Stage::Recheck,8,0);
      case 6:return two(Stage::Recheck,1,0x0d);
      case 7:return two(Stage::Recheck,1,0x0c);
      case 8:return two(Stage::Command,8,1);
      default:return Request{};
    }
  }
  const Scope scope_;
  Summary summary_{};
  uint8_t index_=0;
  uint32_t start_=0,pendingStart_=0,pendingTimeout_=0,identityAt_=0,speedAt_=0,rpmAt_=0;
  bool started_=false,pending_=false,speedSeen_=false,rpmSeen_=false;
};
}
