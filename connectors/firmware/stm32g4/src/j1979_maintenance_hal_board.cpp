#include "j1979_maintenance_hal_board.h"

namespace cd {
bool StmJ1979MaintenanceHalBoard::pinned(const Local& local)const{
  const auto& a=initial_.scope;const auto& b=local.scope;
  return scope_.valid()&&a.connection&&a.credential&&a.approval&&a.lease&&
    a.connection==b.connection&&a.credential==b.credential&&a.approval==b.approval&&
    a.lease==b.lease&&local.controllerOwned;
}
bool StmJ1979MaintenanceHalBoard::eligible(const Local& local)const{
  const auto& s=local.scope;
  return pinned(local)&&s.qualified&&s.authenticated&&s.warningAccepted&&s.operationConfirmed&&
    s.stationaryInterlock&&s.stableSupply&&s.exclusive&&!s.recovery&&
    !emergency_.load(std::memory_order_acquire)&&!recovery_.load(std::memory_order_acquire)&&
    !released_.load(std::memory_order_acquire);
}
J1979MaintenanceLocal::Snapshot StmJ1979MaintenanceHalBoard::snapshot(const Scope& scope){
  const auto local=observe();
  auto result=local.scope;
  if(!scope_.same(scope)||!eligible(local)){
    result.qualified=false;result.exclusive=false;
    result.recovery=result.recovery||recovery_.load(std::memory_order_acquire);
  }
  return result;
}
bool StmJ1979MaintenanceHalBoard::connectionPresent(uint64_t connection,uint64_t lease){
  // The interlock keeps the SPI/USB connection pinned through evidence
  // transfer, including after a safe FDCAN stop or maintenance revocation.
  return connection&&lease&&connection==initial_.scope.connection&&lease==initial_.scope.lease&&
    interlock_.connectionPresent(connection,lease);
}
bool StmJ1979MaintenanceHalBoard::halProfile()const{
  return can_.Instance==FDCAN1&&can_.Init.FrameFormat==FDCAN_FRAME_CLASSIC&&
    can_.Init.Mode==FDCAN_MODE_NORMAL&&can_.Init.AutoRetransmission==DISABLE&&
    can_.Init.StdFiltersNbr==(scope_.extended?0u:1u)&&
    can_.Init.ExtFiltersNbr==(scope_.extended?1u:0u);
}
bool StmJ1979MaintenanceHalBoard::canStopped()const{
  return can_.Instance==FDCAN1&&can_.State==HAL_FDCAN_STATE_READY&&
    (can_.Instance->CCCR&FDCAN_CCCR_INIT)&&!can_.Instance->TXBRP;
}
bool StmJ1979MaintenanceHalBoard::scheduleWorker(){
  return eligible(observe())&&interlock_.scheduleMaintenanceWorker();
}
bool StmJ1979MaintenanceHalBoard::start(const Scope& scope,uint64_t lease){
  if(attempted_||stopped_||!scope_.same(scope)||lease!=initial_.scope.lease||
     !eligible(observe())||!halProfile()||!canStopped())return false;
  attempted_=true;
  // First disable TX and check independent electrical feedback, even
  // though the controller is already in INIT.
  if(!interlock_.disableTx(lease))return false;
  auto local=observe();
  if(!eligible(local)||!local.gateDisabled||local.gateEnabled)return false;
  FDCAN_FilterTypeDef filter{};
  filter.IdType=scope_.extended?FDCAN_EXTENDED_ID:FDCAN_STANDARD_ID;
  filter.FilterIndex=0;filter.FilterType=FDCAN_FILTER_MASK;
  filter.FilterConfig=FDCAN_FILTER_TO_RXFIFO0;filter.FilterID1=scope_.rx;
  filter.FilterID2=scope_.extended?0x1fffffffu:0x7ffu;
  if(HAL_FDCAN_ConfigFilter(&can_,&filter)!=HAL_OK||
     HAL_FDCAN_ConfigGlobalFilter(&can_,FDCAN_REJECT,FDCAN_REJECT,
       FDCAN_REJECT_REMOTE,FDCAN_REJECT_REMOTE)!=HAL_OK||
     HAL_FDCAN_ConfigRxFifoOverwrite(&can_,FDCAN_RX_FIFO0,FDCAN_RX_FIFO_BLOCKING)!=HAL_OK||
     !eligible(observe())||HAL_FDCAN_Start(&can_)!=HAL_OK)return false;
  local=observe();
  if(!eligible(local)||!local.gateDisabled||local.gateEnabled||can_.Instance->TXBRP||
     HAL_FDCAN_GetRxFifoFillLevel(&can_,FDCAN_RX_FIFO0)||
     (can_.Instance->TXEFS&FDCAN_TXEFS_EFFL))return false;
  if(!interlock_.enableTx(lease))return false;
  active_=true;
  if(!canAdmitted()){inhibitCanOnFault();return false;}
  return true;
}
bool StmJ1979MaintenanceHalBoard::canAdmitted(){
  if(!active_||stopped_||!halProfile()||can_.State!=HAL_FDCAN_STATE_BUSY||
     (can_.Instance->CCCR&FDCAN_CCCR_INIT))return false;
  auto local=observe();
  return eligible(local)&&local.gateEnabled&&!local.gateDisabled;
}
void StmJ1979MaintenanceHalBoard::latchRecovery(){
  if(!recovery_.exchange(true,std::memory_order_acq_rel))interlock_.latchRecovery();
}
void StmJ1979MaintenanceHalBoard::inhibitCanOnFault(){
  // In worker context. The physical cutoff does not depend on HAL.
  active_=false;
  if(!interlock_.disableTx(initial_.scope.lease))latchRecovery();
}
void StmJ1979MaintenanceHalBoard::emergencyStopAndLatch(uint64_t lease){
  // May be invoked concurrently from the SPI owner. Never touch HAL
  // controller state or worker-owned booleans in this callback.
  if(lease!=initial_.scope.lease){latchRecovery();return;}
  emergency_.store(true,std::memory_order_release);
  (void)interlock_.disableTx(lease);
  latchRecovery();
}
bool StmJ1979MaintenanceHalBoard::stopController(){
  if(can_.Instance!=FDCAN1)return false;
  bool cancelled=true;
  if(can_.State==HAL_FDCAN_STATE_BUSY&&can_.Instance->TXBRP){
    const uint32_t pending=can_.Instance->TXBRP;
    cancelled=HAL_FDCAN_AbortTxRequest(&can_,pending)==HAL_OK;
    const uint32_t start=HAL_GetTick();
    for(unsigned i=0;i<100&&can_.Instance->TXBRP;++i){
      if(uint32_t(HAL_GetTick()-start)>=100u)break;
      delay(1);
    }
  }
  bool stopped=can_.State==HAL_FDCAN_STATE_READY;
  if(can_.State==HAL_FDCAN_STATE_BUSY)stopped=HAL_FDCAN_Stop(&can_)==HAL_OK;
  return cancelled&&stopped&&canStopped();
}
bool StmJ1979MaintenanceHalBoard::stopAndVerify(uint64_t lease){
  if(lease!=initial_.scope.lease||!lease){latchRecovery();return false;}
  if(stopped_)return verified_;
  active_=false;stopped_=true;
  // Independent cutoff is permitted even after consent/credential loss.
  // Do not touch a controller that has been reassigned to another owner.
  const bool disabled=interlock_.disableTx(lease);
  auto local=observe();
  const bool owns=local.controllerOwned&&local.scope.lease==lease;
  const bool halted=owns&&(attempted_?stopController():canStopped());
  local=observe();
  verified_=disabled&&halted&&local.controllerOwned&&local.scope.lease==lease&&
    local.gateDisabled&&!local.gateEnabled;
  if(!verified_)latchRecovery();
  return verified_;
}
bool StmJ1979MaintenanceHalBoard::releaseMaintenance(uint64_t lease){
  // Called after worker completion (published by the task). Preserve
  // recovery latches even if the stopped lease can safely be released.
  if(lease!=initial_.scope.lease||!verified_||released_.exchange(true,std::memory_order_acq_rel))return false;
  if(!interlock_.releaseMaintenanceLease(lease)){latchRecovery();return false;}
  return true;
}
}
