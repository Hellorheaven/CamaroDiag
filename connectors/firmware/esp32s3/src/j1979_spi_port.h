#pragma once
#include <Arduino.h>
#include <SPI.h>
#include "../../shared/j1979_spi_transport.h"

namespace cd {
// SPI instance, chip-select and READY GPIO must already be configured by
// a qualified exclusive board owner. No pinMode, SPI.begin or OE activation
// here. In particular, no READY GPIO is assigned for the unqualified Rev A0.
// The owner must use an uncontended SPI instance and independent TX interlock.
class EspJ1979SpiPort final:public readlink::SpiMasterPort {
 public:
  using Admission=bool(*)();
  using Fault=void(*)();
  EspJ1979SpiPort(SPIClass& spi,uint8_t cs,uint8_t ready,uint32_t hz,
      Admission admitted,Fault onFault)
    :spi_(spi),cs_(cs),ready_(ready),hz_(hz),admission_(admitted),fault_(onFault){}
  bool admitted()override{
    return !failed_&&admission_&&fault_&&cs_!=ready_&&hz_>=100000u&&hz_<=1000000u&&admission_();
  }
  bool responseReady()override{return digitalRead(ready_)==HIGH;}
  uint32_t monotonicMs()override{return millis();}
  void yield()override{delay(1);}
  bool transfer(const uint8_t* tx,uint8_t* rx,uint32_t remainingMs)override;
  void fault()override;
 private:
  SPIClass& spi_;uint8_t cs_,ready_;uint32_t hz_;
  Admission admission_;Fault fault_;bool failed_=false;
};
}
