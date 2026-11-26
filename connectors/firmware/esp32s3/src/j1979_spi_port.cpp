#include "j1979_spi_port.h"

namespace cd {
bool EspJ1979SpiPort::transfer(const uint8_t* tx,uint8_t* rx,uint32_t remainingMs){
  if(!tx||!rx||!admitted()||!remainingMs)return false;
  const uint32_t start=monotonicMs();
  // SPIClass is synchronous. Exclusive ownership is essential: its
  // internal mutex is not a deadline-aware admission mechanism.
  spi_.beginTransaction(SPISettings(hz_,MSBFIRST,SPI_MODE0));
  if(!admitted()||uint32_t(monotonicMs()-start)>=remainingMs){
    spi_.endTransaction();return false;
  }
  digitalWrite(cs_,LOW);
  spi_.transferBytes(tx,rx,readlink::Size);
  digitalWrite(cs_,HIGH);
  spi_.endTransaction();
  return admitted()&&uint32_t(monotonicMs()-start)<remainingMs;
}
void EspJ1979SpiPort::fault(){
  if(failed_)return;
  failed_=true;
  // No second SPI transfer, reset or implicit recovery. The board owner
  // must quiesce both processors through its independent interlock.
  if(fault_)fault_();
}
}
