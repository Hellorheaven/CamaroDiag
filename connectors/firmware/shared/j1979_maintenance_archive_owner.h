#pragma once
#include "j1979_maintenance_archive.h"

namespace cd {
// Local read-only owner for CU/5 or CI/5. The storage lease is independent
// of the SPI/USB response lease and of any previous maintenance generation.
// An unresolved recovery latch is never cleared or treated as an admission
// for vehicle communication. The board must independently inhibit CAN TX.
class J1979MaintenanceArchiveOwner final:public J1979MaintenanceArchive::Owner {
 public:
  using Scope=J1979MaintenanceScope;
  struct Snapshot {
    uint64_t connection=0,approval=0,credential=0,lease=0;
    bool approved=false,txInhibited=false,controllerQuiescent=false;
    bool storageExclusive=false,writerAbsent=false,recoveryRequired=true;
  };
  class Board {
   public:
    virtual ~Board()=default;
    // Approval and generation come from a local operator/qualified owner,
    // not the incoming USB proof or CI/5 scope packet.
    virtual Snapshot snapshot(const Scope& scope)=0;
    virtual bool acquireReadOnly(const Scope& scope,uint64_t lease)=0;
    // Must verify the same lease was released without affecting a different
    // connection or enabling CAN. Idempotence belongs to the caller.
    virtual bool releaseReadOnly(uint64_t lease)=0;
    // An uncertain lease state must keep independent physical TX inhibited.
    virtual void inhibitAndLatchRecovery(uint64_t lease)=0;
  };
  J1979MaintenanceArchiveOwner(Board& board,const Scope& scope):
    board_(board),scope_(scope),initial_(board.snapshot(scope)){}
  J1979MaintenanceArchiveOwner(const J1979MaintenanceArchiveOwner&)=delete;
  J1979MaintenanceArchiveOwner& operator=(const J1979MaintenanceArchiveOwner&)=delete;
  ~J1979MaintenanceArchiveOwner(){if(attempted_&&!released_)(void)release();}
  bool acquire(const Scope& scope)override{
    if(attempted_||released_||!scope_.same(scope)||!eligible(board_.snapshot(scope_)))return false;
    attempted_=true;
    acquired_=board_.acquireReadOnly(scope_,initial_.lease);
    if(!acquired_||!admitted(scope_)){(void)release();return false;}
    return true;
  }
  bool admitted(const Scope& scope)override{
    if(!acquired_||released_||!scope_.same(scope))return false;
    const auto s=board_.snapshot(scope_);
    return eligible(s)&&s.storageExclusive;
  }
  bool release()override{
    if(released_)return verified_;
    released_=true;acquired_=false;
    if(!attempted_)return false;
    // Never require consent to release. Revocation after the last byte
    // still invalidates host completion, but must not strand the lease.
    const bool ok=board_.releaseReadOnly(initial_.lease);
    const auto s=board_.snapshot(scope_);
    verified_=ok&&s.txInhibited&&s.controllerQuiescent&&s.writerAbsent;
    if(!verified_)board_.inhibitAndLatchRecovery(initial_.lease);
    return verified_;
  }
  bool releaseVerified()const{return released_&&verified_;}
 private:
  bool eligible(const Snapshot& s)const{
    return scope_.valid()&&initial_.connection&&initial_.approval&&initial_.credential&&initial_.lease&&
      s.connection==initial_.connection&&s.approval==initial_.approval&&
      s.credential==initial_.credential&&s.lease==initial_.lease&&
      s.approved&&s.txInhibited&&s.controllerQuiescent&&s.writerAbsent;
  }
  Board& board_;const Scope scope_;const Snapshot initial_;
  bool attempted_=false,acquired_=false,released_=false,verified_=false;
};
}
