#pragma once
#include "j1979_fdcan_link.h"
#include "j1979_spi_port.h"
#include "j1979_read_session.h"

namespace cd {
// Fully composed one-shot STM32 runtime for a board owner. HAL handles and
// local interlock callbacks must be initialized externally; the discovery
// firmware does not instantiate this object.
class StmJ1979Runtime {
 public:
  StmJ1979Runtime(StmJ1979ReadSession::Board& board,FDCAN_HandleTypeDef& can,
      SPI_HandleTypeDef& spi,StmJ1979FdcanLink::Admission admission,
      StmJ1979SpiPort::Ready ready,StmJ1979FdcanLink::Fault interlockFault,
      J1979CanReadExchange::Result& scratch,uint32_t tx,uint32_t rx,bool ext,
      const J1979FreezeScope* freeze=nullptr)
    :can_(can,admission,interlockFault),spi_(spi,admission,ready,interlockFault),
     endpoint_(can_,scratch,tx,rx,ext,admission,freeze),slave_(spi_,endpoint_),
     session_(board,slave_,tx,rx,ext){}
  StmJ1979Runtime(const StmJ1979Runtime&)=delete;
  StmJ1979Runtime& operator=(const StmJ1979Runtime&)=delete;
  bool start(uint32_t idleBudget){return session_.begin(idleBudget);}
  bool poll(){return session_.poll();}
  // The board task calls this only after NSS rises and independent clock
  // monitor reports framing. Never invoke this from SPI IRQ.
  bool slotCompleted(uint32_t clockBits,bool clean){
    if(!session_.poll())return false;
    if(!spi_.completed(slave_,clockBits,clean)){
      session_.fault();return false;
    }
    return session_.poll();
  }
  bool stop(){return session_.stop();}
  void fault(){session_.fault();}
  StmJ1979ReadSession::State state()const{return session_.state();}
 private:
  StmJ1979FdcanLink can_;StmJ1979SpiPort spi_;
  readlink::Endpoint endpoint_;readlink::SpiSlave slave_;
  StmJ1979ReadSession session_;
};
}
