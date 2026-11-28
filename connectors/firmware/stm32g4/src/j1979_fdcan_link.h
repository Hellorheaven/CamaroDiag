#pragma once
#include <Arduino.h>
#include <stm32g4xx_hal_fdcan.h>
#include "../../shared/j1979_can_read_exchange.h"

namespace cd {
// Exclusive, already started FDCAN1 classic-CAN session. Board owner
// configures pins, clock, bitrate, filters, standby and electrical interlock.
// No automatic retransmit, FD, bus recovery or target power control here.
class StmJ1979FdcanLink final:public J1979CanReadLink {
 public:
  using Admission=bool(*)();
  using Fault=void(*)();
  StmJ1979FdcanLink(FDCAN_HandleTypeDef& can,Admission admission,Fault onFault)
    :can_(can),admission_(admission),fault_(onFault){}
  bool active()override;
  uint32_t monotonicMs()override{return HAL_GetTick();}
  bool discardReceive()override;
  bool send(const Frame& frame,uint32_t timeoutMs)override;
  Receive receive(Frame& frame,uint32_t timeoutMs)override;
 private:
  bool healthy();
  bool fail(){if(!failed_){failed_=true;if(fault_)fault_();}return false;}
  FDCAN_HandleTypeDef& can_;Admission admission_;Fault fault_;
  bool failed_=false;
  uint8_t marker_=0;
};
}
