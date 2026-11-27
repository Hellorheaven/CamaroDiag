#pragma once
#include "../../shared/j1979_maintenance_spi.h"
#include "../../shared/j1979_maintenance_local.h"
#include <atomic>

namespace cd {
// STM32 task composition. SPI mailbox task and maintenance worker are
// distinct. Board-specific task scheduling, interlocks and durable storage
// must be qualified independently; discovery firmware does not start this.
class StmJ1979MaintenanceTask final:public maintenance_link::Runner,
    private J1979MaintenanceLocal::Board {
 public:
  using Scope=J1979MaintenanceScope;
  using Progress=maintenance_link::Progress;
  using RunState=maintenance_link::RunState;
  using Snapshot=J1979MaintenanceLocal::Snapshot;
  class Board:public J1979MaintenanceLocal::Board {
   public:
    // These callbacks must be safe from both tasks. The emergency stop is
    // independent of the worker, and must latch recovery if stop is uncertain.
    virtual bool connectionPresent(uint64_t connection,uint64_t lease)=0;
    virtual uint32_t monotonicMs()=0;
    virtual bool scheduleWorker()=0;
    virtual void emergencyStopAndLatch(uint64_t lease)=0;
    // Release only the maintenance lease, not the SPI response slot.
    virtual bool releaseMaintenance(uint64_t lease)=0;
  };
  StmJ1979MaintenanceTask(Board& board,J1979MaintenanceJournal::Storage& storage,
      J1979MaintenanceJournal::Scratch& journalScratch,J1979CanReadLink& bus,
      J1979MaintenanceCampaign::Evidence& evidence,J1979CanReadExchange::Result& exchangeScratch,
      const Scope& scope):board_(board),storage_(storage),journalScratch_(journalScratch),bus_(bus),
      evidence_(evidence),exchangeScratch_(exchangeScratch),scope_(scope),initial_(board.snapshot(scope)){}
  StmJ1979MaintenanceTask(const StmJ1979MaintenanceTask&)=delete;
  StmJ1979MaintenanceTask& operator=(const StmJ1979MaintenanceTask&)=delete;
  bool approved(const Scope& scope)override{
    if(!scope_.same(scope)||cancelled_.load(std::memory_order_acquire))return false;
    const auto s=snapshot(scope);
    return pinned(s)&&s.qualified&&s.authenticated&&s.warningAccepted&&s.operationConfirmed&&
      s.stationaryInterlock&&s.stableSupply&&s.exclusive&&!s.recovery;
  }
  bool connectionPresent()override{
    return initial_.connection&&initial_.lease&&board_.connectionPresent(initial_.connection,initial_.lease);
  }
  uint32_t monotonicMs()override{return board_.monotonicMs();}
  bool schedule(const Scope& scope)override{
    if(!approved(scope)||!connectionPresent())return false;
    State expected=State::Idle;
    if(!state_.compare_exchange_strong(expected,State::Scheduled,std::memory_order_acq_rel))return false;
    if(!board_.scheduleWorker()){cancelAndLatchRecovery();return false;}
    return true;
  }
  // Only the separately scheduled worker may call this. No ECU or storage
  // transaction runs from the SPI task or an interrupt.
  bool work(){
    State expected=State::Scheduled;
    if(!state_.compare_exchange_strong(expected,State::Working,std::memory_order_acq_rel))return false;
    if(!approved(scope_)||!connectionPresent()){
      cancelAndLatchRecovery();state_.store(State::Unavailable,std::memory_order_release);return false;
    }
    runJ1979LocalMaintenance(*this,storage_,journalScratch_,bus_,scope_,evidence_,exchangeScratch_);
    size_t bytes=0;
    bool safe=evidence_.physicalStopVerified;
    if(!safe)cancelAndLatchRecovery();
    bool available=safe&&storage_.size(bytes)&&bytes&&bytes<=J1979MaintenanceJournal::MaximumJournal;
    terminal_.state=available?RunState::Stopped:RunState::Unavailable;
    terminal_.physicalStop=safe;
    terminal_.journalSize=available?uint32_t(bytes):0;
    terminal_.recoveryRequired=!available||evidence_.recoveryRequired||cancelled_.load(std::memory_order_acquire);
    state_.store(available?State::Stopped:State::Unavailable,std::memory_order_release);
    return available;
  }
  Progress progress()override{
    const State state=state_.load(std::memory_order_acquire);
    if(state==State::Scheduled||state==State::Working){
      Progress p;p.state=RunState::Running;return p;
    }
    if(state==State::Stopped||state==State::Unavailable){
      Progress p=terminal_;
      if(!p.valid()){p=Progress{};p.recoveryRequired=true;}
      return p;
    }
    Progress p;p.recoveryRequired=true;return p;
  }
  bool readJournal(size_t offset,uint8_t* out,size_t n)override{
    if(state_.load(std::memory_order_acquire)!=State::Stopped||!connectionPresent()||!out||!n)return false;
    size_t total=0;
    return storage_.size(total)&&total==terminal_.journalSize&&offset<=total&&n<=total-offset&&
      storage_.read(offset,out,n)&&connectionPresent();
  }
  void cancelAndLatchRecovery()override{
    if(!cancelled_.exchange(true,std::memory_order_acq_rel)){
      board_.emergencyStopAndLatch(initial_.lease);
      board_.latchRecovery();
    }
  }
  bool releaseVerified()override{
    const State state=state_.load(std::memory_order_acquire);
    if((state!=State::Stopped&&state!=State::Unavailable)||!terminal_.physicalStop)return false;
    if(released_)return false;
    released_=true;
    return board_.releaseMaintenance(initial_.lease);
  }
 private:
  enum class State:uint8_t { Idle,Scheduled,Working,Stopped,Unavailable };
  bool pinned(const Snapshot& s)const{
    return initial_.connection&&initial_.approval&&initial_.credential&&initial_.lease&&
      s.connection==initial_.connection&&s.approval==initial_.approval&&s.credential==initial_.credential&&
      s.lease==initial_.lease;
  }
  Snapshot snapshot(const Scope& scope)override{
    auto s=board_.snapshot(scope);
    if(!scope_.same(scope)||!pinned(s)||cancelled_.load(std::memory_order_acquire)||!connectionPresent()){
      s.qualified=false;s.exclusive=false;s.recovery=true;
    }
    return s;
  }
  bool start(const Scope& scope,uint64_t lease)override{
    return lease==initial_.lease&&approved(scope)&&board_.start(scope,lease)&&approved(scope);
  }
  bool stopAndVerify(uint64_t lease)override{
    return lease==initial_.lease&&board_.stopAndVerify(lease);
  }
  void latchRecovery()override{board_.latchRecovery();}
  Board& board_;J1979MaintenanceJournal::Storage& storage_;
  J1979MaintenanceJournal::Scratch& journalScratch_;J1979CanReadLink& bus_;
  J1979MaintenanceCampaign::Evidence& evidence_;J1979CanReadExchange::Result& exchangeScratch_;
  const Scope scope_;const Snapshot initial_;
  std::atomic<State> state_{State::Idle};std::atomic<bool> cancelled_{false};
  Progress terminal_{};bool released_=false;
};
}
