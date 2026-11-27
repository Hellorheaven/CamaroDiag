#pragma once
#include "j1979_maintenance_can_link.h"
#include "j1979_maintenance_journal.h"

namespace cd {
// Local-only composition for an independently qualified board. There is
// deliberately no CU/CI dispatch or discovery capability advertisement.
// The platform owns the exclusive lease and durable recovery latch shared
// with other diagnostic sessions.
class J1979MaintenanceLocal final:public J1979MaintenanceCampaign::Owner,
    public J1979MaintenanceCanLink::Interlock {
 public:
  using Scope=J1979MaintenanceScope;
  struct Snapshot {
    uint64_t connection=0,approval=0,credential=0,lease=0;
    bool qualified=false,authenticated=false,warningAccepted=false,operationConfirmed=false;
    bool stationaryInterlock=false,stableSupply=false,exclusive=false,recovery=false;
  };
  class Board {
   public:
    virtual ~Board()=default;
    virtual Snapshot snapshot(const Scope& scope)=0;
    virtual bool start(const Scope& scope,uint64_t exclusiveLease)=0;
    virtual bool stopAndVerify(uint64_t exclusiveLease)=0;
    virtual void latchRecovery()=0;
  };
  J1979MaintenanceLocal(Board& board,J1979MaintenanceJournal& journal,const Scope& scope):
    board_(board),journal_(journal),scope_(scope),initial_(board.snapshot(scope)){}
  J1979MaintenanceLocal(const J1979MaintenanceLocal&)=delete;
  J1979MaintenanceLocal& operator=(const J1979MaintenanceLocal&)=delete;
  bool approved(const Scope& scope)override{
    if(recovery_||stopped_||!scope_.same(scope)||journal_.failed())return false;
    const auto s=board_.snapshot(scope_);
    return initial_.connection&&initial_.approval&&initial_.credential&&initial_.lease&&
      s.connection==initial_.connection&&s.approval==initial_.approval&&
      s.credential==initial_.credential&&s.lease==initial_.lease&&
      s.qualified&&s.authenticated&&s.warningAccepted&&s.operationConfirmed&&
      s.stationaryInterlock&&s.stableSupply&&s.exclusive&&!s.recovery;
  }
  bool start(const Scope& scope)override{
    if(attemptedStart_||!approved(scope))return false;
    attemptedStart_=true;
    started_=board_.start(scope_,initial_.lease);
    return started_&&approved(scope_);
  }
  bool stopAndVerify()override{
    if(stopped_)return !recovery_;
    // Never stop another session. The board must verify ownership of the
    // pinned lease even on partial start or revocation.
    stopped_=true;
    if(attemptedStart_&&!board_.stopAndVerify(initial_.lease)){
      latchRecovery();return false;
    }
    return true;
  }
  void latchRecovery()override{
    if(!recovery_){recovery_=true;board_.latchRecovery();}
  }
  bool active(const Scope& scope)override{return started_&&approved(scope);}
  bool consumeIntent(const Scope& scope)override{
    if(consumed_||!active(scope))return false;
    consumed_=true;
    if(!journal_.consumeIntent(scope)||!active(scope)){latchRecovery();return false;}
    return true;
  }
 private:
  Board& board_;J1979MaintenanceJournal& journal_;
  const Scope scope_;const Snapshot initial_;
  bool attemptedStart_=false,started_=false,stopped_=false,recovery_=false,consumed_=false;
};

inline void runJ1979LocalMaintenance(J1979MaintenanceLocal::Board& owner,
    J1979MaintenanceJournal::Storage& storage,J1979MaintenanceJournal::Scratch& journalScratch,
    J1979CanReadLink& bus,const J1979MaintenanceScope& scope,
    J1979MaintenanceCampaign::Evidence& evidence,J1979CanReadExchange::Result& exchangeScratch){
  J1979MaintenanceJournal journal(storage,journalScratch);
  J1979MaintenanceLocal local(owner,journal,scope);
  J1979MaintenanceCanLink link(bus,local,scope);
  J1979MaintenanceCampaign::run(local,journal,link,scope,evidence,exchangeScratch);
}
}
