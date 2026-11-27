#pragma once
#include "j1979_maintenance_sequence.h"
#include "j1979_can_read_exchange.h"

namespace cd {
// Fixed-size evidence and one-shot campaign. The physical owner and durable
// store are independent interfaces. This must never be used by a discovery,
// generic CAN, read-only USB or automatic scan route.
class J1979MaintenanceCampaign {
 public:
  using Scope=J1979MaintenanceScope;
  using Sequence=J1979MaintenanceSequence;
  using Status=Sequence::Status;
  using Stage=Sequence::Stage;
  using Outcome=Sequence::Outcome;
  using Reason=Sequence::Reason;
  static constexpr size_t MaxRows=Sequence::ClearRequests;
  // DTC response is bounded by the one-byte count (2+2*255).
  static constexpr size_t MaxReply=512;
  struct Observation {
    Stage stage=Stage::Finished;
    Status status=Status::Fault;
    uint8_t request[2]{},requestSize=0;
    uint16_t responseSize=0;
    uint32_t startMs=0,endMs=0,timeoutMs=0;
    uint8_t response[MaxReply]{};
  };
  struct Evidence {
    Scope scope{};
    Sequence::Summary summary{};
    Observation rows[MaxRows]{};
    size_t count=0;
    bool physicalStopVerified=false;
    bool terminalPersisted=false;
    bool recoveryRequired=false;
    bool record(const Sequence::Request& request,Status status,
        const uint8_t* reply,size_t n,uint32_t start,uint32_t end,uint32_t timeout){
      if(count>=MaxRows||!request.size||request.size>2||n>MaxReply||(!reply&&n)||end<start)return false;
      auto& row=rows[count++];
      row.stage=request.stage;row.status=status;row.requestSize=request.size;
      std::memcpy(row.request,request.bytes,request.size);
      row.responseSize=uint16_t(n);row.startMs=start;row.endMs=end;row.timeoutMs=timeout;
      if(n)std::memcpy(row.response,reply,n);
      return true;
    }
  };
  class Owner {
   public:
    virtual ~Owner()=default;
    // The local owner independently checks the exact operation and scope,
    // credential, physical qualification, both confirmations, interlocks,
    // exclusive session and unresolved recovery. Not derived from the host.
    virtual bool approved(const Scope& scope)=0;
    virtual bool start(const Scope& scope)=0;
    virtual bool stopAndVerify()=0;
    virtual void latchRecovery()=0;
  };
  class Store {
   public:
    virtual ~Store()=default;
    // Called before physical start. A previously unresolved intent, torn
    // record or uncertain shutdown must fail closed, never be overwritten.
    virtual bool begin(const Scope& scope)=0;
    // Persist, flush, reopen and verify exact evidence before returning true.
    virtual bool baseline(const Evidence& evidence)=0;
    virtual bool intent(const Evidence& evidence)=0;
    // Called only after physical shutdown. Uncertain commands may be
    // recorded but must not be silently marked resolved.
    virtual bool finish(const Evidence& evidence)=0;
    virtual void uncertain()=0;
  };
  class Link {
   public:
    virtual ~Link()=default;
    virtual bool active()=0;
    virtual uint32_t monotonicMs()=0;
    virtual bool matches(const Scope& scope)const=0;
    virtual void read(const uint8_t* req,size_t n,uint32_t timeout,
        J1979CanReadExchange::Result& result)=0;
    // There is no request byte parameter and no generic write entry point.
    // Board independently enforces the exact one-shot operation and intent.
    virtual void command(const Scope& scope,uint32_t timeout,
        J1979CanReadExchange::Result& result)=0;
  };
  static void run(Owner& owner,Store& store,Link& link,const Scope& requested,
      Evidence& evidence,J1979CanReadExchange::Result& scratch){
    // Caller owns large scratch buffers; do not allocate an ECU response
    // buffer or a whole evidence structure on the microcontroller stack.
    evidence.scope=requested;evidence.summary=Sequence::Summary{};evidence.count=0;
    evidence.physicalStopVerified=false;evidence.terminalPersisted=false;evidence.recoveryRequired=false;
    const Scope scope=requested;
    Sequence sequence(scope);
    if(!scope.valid()||!link.matches(scope)||!owner.approved(scope))return;
    if(!store.begin(scope)){
      evidence.recoveryRequired=true;store.uncertain();owner.latchRecovery();return;
    }
    const uint32_t start=link.monotonicMs();
    sequence.begin(start);
    // A false start may have partially enabled the physical channel.
    if(!owner.approved(scope)||!owner.start(scope)||!owner.approved(scope)||!link.active()){
      sequence.abort(Reason::Context,link.monotonicMs());
    }
    while(!sequence.finished()){
      const uint32_t now=link.monotonicMs();
      if(!owner.approved(scope)||!link.active()||!link.matches(scope)){
        sequence.abort(Reason::Context,now);break;
      }
      if(!sequence.remaining(now)){sequence.abort(Reason::Deadline,now);break;}
      if(sequence.stage()==Stage::Persist||sequence.stage()==Stage::Intent){
        const auto stage=sequence.stage();
        evidence.summary=sequence.summary();
        const bool saved=stage==Stage::Persist?store.baseline(evidence):store.intent(evidence);
        const uint32_t after=link.monotonicMs();
        // An intent may already have reached durable storage even if the
        // context changed during I/O. Never permit a subsequent command.
        if(stage==Stage::Persist)sequence.persistBaseline(saved,after);
        else {
          if(!sequence.persistIntent(saved,after)&&saved)evidence.recoveryRequired=true;
        }
        if(!owner.approved(scope)||!link.active()||!link.matches(scope))
          sequence.abort(Reason::Context,after);
        continue;
      }
      const auto request=sequence.next();
      const uint32_t remaining=sequence.remaining(now);
      const uint32_t timeout=remaining<Sequence::MaxExchangeMs?remaining:Sequence::MaxExchangeMs;
      if(!sequence.admit(request.bytes,request.size,now,timeout))break;
      scratch.state=J1979CanReadExchange::State::Fault;scratch.responseSize=0;scratch.elapsedMs=0;
      if(owner.approved(scope)&&link.active()&&link.matches(scope)){
        if(request.stage==Stage::Command)link.command(scope,timeout,scratch);
        else link.read(request.bytes,request.size,timeout,scratch);
      }
      const uint32_t end=link.monotonicMs();
      Status status=toStatus(scratch.state);
      size_t n=scratch.responseSize;
      if(n>MaxReply||scratch.elapsedMs>timeout||!owner.approved(scope)||!link.active()||
         !link.matches(scope)||status==Status::Fault||(status==Status::Timeout&&n)){
        n=0;status=Status::Fault;
      }
      if(!evidence.record(request,status,scratch.response,n,uint32_t(now-start),uint32_t(end-start),timeout)){
        sequence.abort(Reason::Transport,end);break;
      }
      sequence.observe(request.bytes,request.size,status,scratch.response,n,end);
    }
    evidence.summary=sequence.summary();
    evidence.physicalStopVerified=owner.stopAndVerify();
    if(!evidence.physicalStopVerified||evidence.summary.outcome==Outcome::Uncertain)
      evidence.recoveryRequired=true;
    // Store receives the actual outcome and cannot erase an uncertain intent
    // merely because the physical line is now quiescent.
    if(evidence.physicalStopVerified)evidence.terminalPersisted=store.finish(evidence);
    if(!evidence.terminalPersisted)evidence.recoveryRequired=true;
    if(evidence.recoveryRequired){store.uncertain();owner.latchRecovery();}
  }
 private:
  static Status toStatus(J1979CanReadExchange::State state){
    using S=J1979CanReadExchange::State;
    switch(state){
      case S::Positive:return Status::Positive;
      case S::Negative:return Status::Negative;
      case S::Timeout:return Status::Timeout;
      default:return Status::Fault;
    }
  }
};
}
