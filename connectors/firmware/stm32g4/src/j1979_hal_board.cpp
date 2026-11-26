#include "j1979_hal_board.h"

namespace cd {
bool StmJ1979HalBoard::halProfile()const{
  // Exactly one filter in the selected identifier space. No other active
  // filter, no FD, no retransmission and no silent bitrate negotiation.
  return can_.Instance==FDCAN1&&spi_.Instance==SPI1&&
    can_.Init.FrameFormat==FDCAN_FRAME_CLASSIC&&can_.Init.Mode==FDCAN_MODE_NORMAL&&
    can_.Init.AutoRetransmission==DISABLE&&
    can_.Init.StdFiltersNbr==(ext_?0u:1u)&&can_.Init.ExtFiltersNbr==(ext_?1u:0u)&&
    spi_.Init.Mode==SPI_MODE_SLAVE&&spi_.Init.Direction==SPI_DIRECTION_2LINES&&
    spi_.Init.DataSize==SPI_DATASIZE_8BIT;
}
bool StmJ1979HalBoard::canStopped()const{
  return can_.Instance==FDCAN1&&can_.State==HAL_FDCAN_STATE_READY&&
    (can_.Instance->CCCR&FDCAN_CCCR_INIT)&&can_.Instance->TXBRP==0;
}
bool StmJ1979HalBoard::fresh(const Local& s)const{
  return generation_&&s.generation==generation_&&s.profileQualified&&
    s.powerStable&&s.hostPresent&&!s.recovery&&!recovery_;
}
bool StmJ1979HalBoard::qualified(uint32_t tx,uint32_t rx,bool ext){
  if(attempted_||failed_||recovery_||tx!=tx_||rx!=rx_||ext!=ext_||
     !J1979CanReadSession::physicalPair(tx_,rx_,ext_)||!halProfile()||
     !canStopped()||HAL_SPI_GetState(&spi_)!=HAL_SPI_STATE_READY)return false;
  const auto s=observe();generation_=s.generation;
  return fresh(s)&&s.gateDisabled&&!s.gateEnabled&&s.spiIdle;
}
bool StmJ1979HalBoard::startReadChannel(){
  if(attempted_||!qualified(tx_,rx_,ext_))return false;
  attempted_=true;
  // Start controller while the independent transceiver gate stays closed.
  if(!interlock_.disableTx())return false;
  auto s=observe();
  if(!fresh(s)||!s.gateDisabled||s.gateEnabled)return false;
  FDCAN_FilterTypeDef filter{};
  filter.IdType=ext_?FDCAN_EXTENDED_ID:FDCAN_STANDARD_ID;
  filter.FilterIndex=0;filter.FilterType=FDCAN_FILTER_MASK;
  filter.FilterConfig=FDCAN_FILTER_TO_RXFIFO0;
  filter.FilterID1=rx_;filter.FilterID2=ext_?0x1fffffffu:0x7ffu;
  if(HAL_FDCAN_ConfigFilter(&can_,&filter)!=HAL_OK||
     HAL_FDCAN_ConfigGlobalFilter(&can_,FDCAN_REJECT,FDCAN_REJECT,
       FDCAN_REJECT_REMOTE,FDCAN_REJECT_REMOTE)!=HAL_OK||
     HAL_FDCAN_ConfigRxFifoOverwrite(&can_,FDCAN_RX_FIFO0,FDCAN_RX_FIFO_BLOCKING)!=HAL_OK||
     !fresh(observe())||HAL_FDCAN_Start(&can_)!=HAL_OK)return false;
  s=observe();
  if(!fresh(s)||!s.gateDisabled||s.gateEnabled||can_.Instance->TXBRP||
     HAL_FDCAN_GetRxFifoFillLevel(&can_,FDCAN_RX_FIFO0)||
     (can_.Instance->TXEFS&FDCAN_TXEFS_EFFL))return false;
  if(!interlock_.enableTx())return false;
  active_=true;return admitted();
}
bool StmJ1979HalBoard::admitted(){
  if(!active_||failed_||!halProfile()||can_.State!=HAL_FDCAN_STATE_BUSY||
     (can_.Instance->CCCR&FDCAN_CCCR_INIT))return false;
  const auto s=observe();
  return fresh(s)&&s.gateEnabled&&!s.gateDisabled;
}
void StmJ1979HalBoard::inhibitOnFault(){
  active_=false;failed_=true;
  if(!interlock_.disableTx())latchRecovery();
}
bool StmJ1979HalBoard::stopAndVerifyQuiescent(){
  active_=false;
  if(!attempted_)return false;
  // Independent physical cutoff first. Never rely on HAL abort/stop to
  // prove the transceiver actually ceased driving the vehicle bus.
  const bool disabled=interlock_.disableTx();
  bool spiOk=HAL_SPI_GetState(&spi_)==HAL_SPI_STATE_READY;
  if(!spiOk)spiOk=HAL_SPI_Abort(&spi_)==HAL_OK;
  if(can_.Instance!=FDCAN1){latchRecovery();return false;}
  bool cancelOk=true;
  if(can_.State==HAL_FDCAN_STATE_BUSY&&can_.Instance->TXBRP){
    const uint32_t pending=can_.Instance->TXBRP;
    cancelOk=HAL_FDCAN_AbortTxRequest(&can_,pending)==HAL_OK;
    const uint32_t start=HAL_GetTick();
    for(unsigned i=0;i<100&&can_.Instance->TXBRP;++i){
      if(uint32_t(HAL_GetTick()-start)>=100u)break;
      delay(1);
    }
  }
  bool stopOk=can_.State==HAL_FDCAN_STATE_READY;
  if(can_.State==HAL_FDCAN_STATE_BUSY)stopOk=HAL_FDCAN_Stop(&can_)==HAL_OK;
  const auto s=observe();
  const bool verified=disabled&&spiOk&&cancelOk&&stopOk&&canStopped()&&
    HAL_SPI_GetState(&spi_)==HAL_SPI_STATE_READY&&s.gateDisabled&&!s.gateEnabled&&s.spiIdle&&
    s.generation==generation_&&!s.recovery&&!recovery_;
  if(!verified)latchRecovery();
  return verified;
}
void StmJ1979HalBoard::latchRecovery(){
  if(recovery_)return;
  recovery_=true;active_=false;interlock_.latchRecovery();
}
}
