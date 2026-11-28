#include "j1979_fdcan_link.h"

namespace cd {
namespace {
constexpr uint32_t faultFlags=FDCAN_IR_RF0L|FDCAN_IR_RF1L|FDCAN_IR_TEFL|
  FDCAN_IR_MRAF|FDCAN_IR_ELO|FDCAN_IR_EP|FDCAN_IR_BO|
  FDCAN_IR_WDI|FDCAN_IR_PEA|FDCAN_IR_PED|FDCAN_IR_ARA;
}
bool StmJ1979FdcanLink::healthy(){
  // A qualified owner exclusively owns status registers, RX and TX event
  // FIFOs. Error IRQs must not clear these sticky flags behind this adapter.
  if(failed_||!admission_||!fault_||!admission_()||can_.Instance!=FDCAN1||
     can_.State!=HAL_FDCAN_STATE_BUSY||can_.Init.Mode!=FDCAN_MODE_NORMAL||
     can_.Init.FrameFormat!=FDCAN_FRAME_CLASSIC||can_.Init.AutoRetransmission!=DISABLE||
     (can_.Instance->CCCR&FDCAN_CCCR_INIT)!=0||
     (can_.Instance->IR&faultFlags)!=0)return false;
  FDCAN_ProtocolStatusTypeDef status{};
  return HAL_FDCAN_GetProtocolStatus(&can_,&status)==HAL_OK&&
    !status.BusOff&&!status.ErrorPassive&&!status.Warning&&
    (status.LastErrorCode==FDCAN_PROTOCOL_ERROR_NONE||
     status.LastErrorCode==FDCAN_PROTOCOL_ERROR_NO_CHANGE);
}
bool StmJ1979FdcanLink::active(){return healthy()||fail();}
bool StmJ1979FdcanLink::discardReceive(){
  if(!active()||can_.Instance->TXBRP||
     (can_.Instance->TXEFS&FDCAN_TXEFS_EFFL))return fail();
  FDCAN_RxHeaderTypeDef header{};uint8_t bytes[64]{};
  // STM32G4 FIFO depth is finite; flooding must fail instead of spinning.
  for(unsigned i=0;i<32;++i){
    if(!active())return false;
    if(!HAL_FDCAN_GetRxFifoFillLevel(&can_,FDCAN_RX_FIFO0))return active();
    if(HAL_FDCAN_GetRxMessage(&can_,FDCAN_RX_FIFO0,&header,bytes)!=HAL_OK)return fail();
  }
  return fail();
}
bool StmJ1979FdcanLink::send(const Frame& frame,uint32_t timeoutMs){
  if(!timeoutMs||timeoutMs>4000u||frame.length!=8||
     frame.id>(frame.extended?0x1fffffffu:0x7ffu)||!active()||
     can_.Instance->TXBRP||(can_.Instance->TXEFS&FDCAN_TXEFS_EFFL))return fail();
  FDCAN_TxHeaderTypeDef header{};
  header.Identifier=frame.id;
  header.IdType=frame.extended?FDCAN_EXTENDED_ID:FDCAN_STANDARD_ID;
  header.TxFrameType=FDCAN_DATA_FRAME;header.DataLength=FDCAN_DLC_BYTES_8;
  header.ErrorStateIndicator=FDCAN_ESI_ACTIVE;header.BitRateSwitch=FDCAN_BRS_OFF;
  header.FDFormat=FDCAN_CLASSIC_CAN;header.TxEventFifoControl=FDCAN_STORE_TX_EVENTS;
  header.MessageMarker=++marker_;
  uint8_t data[8];std::memcpy(data,frame.data,8);
  const uint32_t start=monotonicMs();
  if(!active()||HAL_FDCAN_AddMessageToTxFifoQ(&can_,&header,data)!=HAL_OK)return fail();
  // Queued != transmitted. A fresh matching event is mandatory. On failure
  // the independent interlock must stop pending transmission, never retry.
  for(unsigned i=0;i<8192;++i){
    if(!active()||uint32_t(monotonicMs()-start)>=timeoutMs)return fail();
    if(can_.Instance->TXEFS&FDCAN_TXEFS_EFFL){
      FDCAN_TxEventFifoTypeDef event{};
      if(HAL_FDCAN_GetTxEvent(&can_,&event)!=HAL_OK||
         event.EventType!=FDCAN_TX_EVENT||event.MessageMarker!=header.MessageMarker||
         event.Identifier!=header.Identifier||event.IdType!=header.IdType||
         event.TxFrameType!=FDCAN_DATA_FRAME||event.DataLength!=FDCAN_DLC_BYTES_8||
         event.FDFormat!=FDCAN_CLASSIC_CAN||can_.Instance->TXBRP||
         (can_.Instance->TXEFS&FDCAN_TXEFS_EFFL))return fail();
      return active();
    }
    delay(1);
  }
  return fail();
}
J1979CanReadLink::Receive StmJ1979FdcanLink::receive(Frame& frame,uint32_t timeoutMs){
  if(!timeoutMs||timeoutMs>20u||!active()){fail();return Receive::Fault;}
  const uint32_t start=monotonicMs();
  for(unsigned i=0;i<128;++i){
    if(!active())return Receive::Fault;
    if(HAL_FDCAN_GetRxFifoFillLevel(&can_,FDCAN_RX_FIFO0)){
      FDCAN_RxHeaderTypeDef header{};uint8_t data[64]{};
      if(HAL_FDCAN_GetRxMessage(&can_,FDCAN_RX_FIFO0,&header,data)!=HAL_OK||!active()){
        fail();return Receive::Fault;
      }
      if(header.RxFrameType!=FDCAN_DATA_FRAME||header.FDFormat!=FDCAN_CLASSIC_CAN)
        return Receive::NoFrame;
      if(header.IdType!=FDCAN_STANDARD_ID&&header.IdType!=FDCAN_EXTENDED_ID){
        fail();return Receive::Fault;
      }
      unsigned length=0;
      switch(header.DataLength){
        case FDCAN_DLC_BYTES_1:length=1;break;
        case FDCAN_DLC_BYTES_2:length=2;break;
        case FDCAN_DLC_BYTES_3:length=3;break;
        case FDCAN_DLC_BYTES_4:length=4;break;
        case FDCAN_DLC_BYTES_5:length=5;break;
        case FDCAN_DLC_BYTES_6:length=6;break;
        case FDCAN_DLC_BYTES_7:length=7;break;
        case FDCAN_DLC_BYTES_8:length=8;break;
        default:fail();return Receive::Fault;
      }
      frame=Frame{};frame.id=header.Identifier;frame.extended=header.IdType==FDCAN_EXTENDED_ID;
      if(frame.id>(frame.extended?0x1fffffffu:0x7ffu)){fail();return Receive::Fault;}
      frame.length=uint8_t(length);std::memcpy(frame.data,data,length);
      return Receive::Data;
    }
    if(uint32_t(monotonicMs()-start)>=timeoutMs)return Receive::NoFrame;
    delay(1);
  }
  fail();return Receive::Fault;
}
}
