#include "j1979_spi_port.h"
#include "../../shared/j1979_maintenance_spi.h"

namespace cd {
bool StmJ1979SpiPort::admitted(){
  return !failed_&&admission_&&ready_&&fault_&&admission_()&&
    spi_.Instance==SPI1&&spi_.Init.Mode==SPI_MODE_SLAVE&&
    spi_.Init.Direction==SPI_DIRECTION_2LINES&&spi_.Init.DataSize==SPI_DATASIZE_8BIT&&
    spi_.Init.CLKPolarity==SPI_POLARITY_LOW&&spi_.Init.CLKPhase==SPI_PHASE_1EDGE&&
    spi_.Init.FirstBit==SPI_FIRSTBIT_MSB&&spi_.Init.TIMode==SPI_TIMODE_DISABLE&&
    spi_.Init.CRCCalculation==SPI_CRCCALCULATION_DISABLE&&HAL_SPI_GetError(&spi_)==HAL_SPI_ERROR_NONE;
}
bool StmJ1979SpiPort::arm(const uint8_t* tx,uint8_t* rx,size_t length){
  if(armed_||!admitted()||!tx||!rx||length!=readlink::Size||
     HAL_SPI_GetState(&spi_)!=HAL_SPI_STATE_READY)return false;
  // HAL keeps the supplied buffers until IRQ transfer completion.
  if(HAL_SPI_TransmitReceive_IT(&spi_,const_cast<uint8_t*>(tx),rx,
      uint16_t(length))!=HAL_OK)return false;
  armed_=true;return admitted();
}
void StmJ1979SpiPort::ready(bool value){
  if(!armed_||!admitted()){fault();return;}
  ready_(value);
}
bool StmJ1979SpiPort::finishResponse(){
  if(armed_||!admitted()||HAL_SPI_GetState(&spi_)!=HAL_SPI_STATE_READY||
     spi_.RxXferCount||spi_.TxXferCount){fault();return false;}
  ready_(false);return admitted();
}
void StmJ1979SpiPort::fault(){
  if(failed_)return;
  failed_=true;armed_=false;
  // Independent physical interlock first; aborting HAL is not proof of stop.
  if(fault_)fault_();
  (void)HAL_SPI_Abort(&spi_);
}
bool StmJ1979SpiPort::completed(readlink::SpiSlave& slave,uint32_t bits,bool clean){
  if(!armed_||!admitted()||!clean||bits!=readlink::Size*8u||
     HAL_SPI_GetState(&spi_)!=HAL_SPI_STATE_READY||
     spi_.RxXferCount!=0||spi_.TxXferCount!=0){
    slave.abort();fault();return false;
  }
  armed_=false;
  return slave.completed(readlink::Size,true);
}
bool StmJ1979SpiPort::completed(maintenance_link::SpiSlave& slave,uint32_t bits,bool clean){
  if(!armed_||!admitted()||!clean||bits!=maintenance_link::Size*8u||
     HAL_SPI_GetState(&spi_)!=HAL_SPI_STATE_READY||spi_.RxXferCount||spi_.TxXferCount){
    slave.abort();fault();return false;
  }
  armed_=false;return slave.completed(maintenance_link::Size,true);
}
bool StmJ1979SpiPort::completed(archive_link::SpiSlave& slave,uint32_t bits,bool clean){
  if(!armed_||!admitted()||!clean||bits!=archive_link::Size*8u||
     HAL_SPI_GetState(&spi_)!=HAL_SPI_STATE_READY||spi_.RxXferCount||spi_.TxXferCount){
    slave.abort();fault();return false;
  }
  armed_=false;return slave.completed(archive_link::Size,true);
}
}
