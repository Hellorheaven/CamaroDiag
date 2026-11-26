#pragma once
#include <Arduino.h>
#include <stm32g4xx_hal_spi.h>
#include "../../shared/j1979_spi_transport.h"

namespace cd {
// Board owns NSS/clock counting and interrupt routing. A HAL completion
// alone does not prove that NSS framed exactly 256 clock edges.
class StmJ1979SpiPort final:public readlink::SpiSlavePort {
 public:
  using Admission=bool(*)();
  using Ready=void(*)(bool);
  using Fault=void(*)();
  StmJ1979SpiPort(SPI_HandleTypeDef& spi,Admission admission,Ready ready,Fault fault)
    :spi_(spi),admission_(admission),ready_(ready),fault_(fault){}
  bool admitted()override;
  uint32_t monotonicMs()override{return HAL_GetTick();}
  bool arm(const uint8_t* tx,uint8_t* rx,size_t length)override;
  void ready(bool value)override;
  void fault()override;
  // Called from owner task after NSS rises; board's clock monitor supplies
  // the observed bit count and clean electrical framing, not host data.
  bool completed(readlink::SpiSlave& slave,uint32_t bits,bool clean);
 private:
  SPI_HandleTypeDef& spi_;Admission admission_;Ready ready_;Fault fault_;
  bool failed_=false,armed_=false;
};
}
