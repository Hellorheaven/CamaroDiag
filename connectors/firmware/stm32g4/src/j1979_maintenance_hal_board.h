#pragma once
#include <Arduino.h>
#include <stm32g4xx_hal_fdcan.h>
#include "j1979_maintenance_task.h"

namespace cd {
// Board-side CAN lifecycle for the maintenance worker. Separate from the
// SPI owner: stopping FDCAN must not abort a pending journal response.
// Physical TX cutoff and lease/consent checks are externally qualified.
class StmJ1979MaintenanceHalBoard final:public StmJ1979MaintenanceTask::Board {
 public:
  using Scope=J1979MaintenanceScope;
  using Snapshot=J1979MaintenanceLocal::Snapshot;
  struct Local {
    Snapshot scope{};
    bool gateDisabled=false,gateEnabled=false,controllerOwned=false;
  };
  class Interlock {
   public:
    virtual ~Interlock()=default;
    virtual Local observe(const Scope& scope)=0;
    virtual bool disableTx(uint64_t lease)=0;
    virtual bool enableTx(uint64_t lease)=0;
    virtual void latchRecovery()=0;
    virtual bool scheduleMaintenanceWorker()=0;
    virtual bool releaseMaintenanceLease(uint64_t lease)=0;
    virtual bool connectionPresent(uint64_t connection,uint64_t lease)=0;
  };
  StmJ1979MaintenanceHalBoard(FDCAN_HandleTypeDef& can,Interlock& interlock,const Scope& scope):
    can_(can),interlock_(interlock),scope_(scope),initial_(interlock.observe(scope)){}
  StmJ1979MaintenanceHalBoard(const StmJ1979MaintenanceHalBoard&)=delete;
  StmJ1979MaintenanceHalBoard& operator=(const StmJ1979MaintenanceHalBoard&)=delete;
  Snapshot snapshot(const Scope& scope)override;
  bool start(const Scope& scope,uint64_t lease)override;
  bool stopAndVerify(uint64_t lease)override;
  void latchRecovery()override;
  bool connectionPresent(uint64_t connection,uint64_t lease)override;
  uint32_t monotonicMs()override{return HAL_GetTick();}
  bool scheduleWorker()override;
  void emergencyStopAndLatch(uint64_t lease)override;
  bool releaseMaintenance(uint64_t lease)override;
  // Physical link callbacks run only in worker context.
  bool canAdmitted();
  void inhibitCanOnFault();
 private:
  bool pinned(const Local& local)const;
  bool eligible(const Local& local)const;
  bool halProfile()const;
  bool canStopped()const;
  bool stopController();
  Local observe(){return interlock_.observe(scope_);}
  FDCAN_HandleTypeDef& can_;Interlock& interlock_;const Scope scope_;const Local initial_;
  std::atomic<bool> emergency_{false},recovery_{false},released_{false};
  bool attempted_=false,active_=false,stopped_=false,verified_=false;
};
}
