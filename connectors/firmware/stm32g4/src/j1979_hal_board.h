#pragma once
#include <Arduino.h>
#include <stm32g4xx_hal_fdcan.h>
#include <stm32g4xx_hal_spi.h>
#include "j1979_read_session.h"

namespace cd {
// Concrete HAL lifecycle for one qualified board profile. MSP clock/GPIO,
// transceiver and independent hardware interlock are supplied by the board.
// No pin mapping, bit timing or proof is inferred from the MCU model.
class StmJ1979HalBoard final:public StmJ1979ReadSession::Board {
 public:
  struct Local {
    uint64_t generation=0;
    bool profileQualified=false,powerStable=false,hostPresent=false;
    bool recovery=false,gateDisabled=false,gateEnabled=false,spiIdle=false;
  };
  class Interlock {
   public:
    virtual ~Interlock()=default;
    virtual Local observe(uint32_t tx,uint32_t rx,bool ext)=0;
    // Independent hardware TX cutoff, not a FDCAN software flag.
    virtual bool disableTx()=0;
    virtual bool enableTx()=0;
    virtual void latchRecovery()=0;
  };
  StmJ1979HalBoard(FDCAN_HandleTypeDef& can,SPI_HandleTypeDef& spi,
      Interlock& interlock,uint32_t tx,uint32_t rx,bool ext)
    :can_(can),spi_(spi),interlock_(interlock),tx_(tx),rx_(rx),ext_(ext){}
  StmJ1979HalBoard(const StmJ1979HalBoard&)=delete;
  StmJ1979HalBoard& operator=(const StmJ1979HalBoard&)=delete;
  bool qualified(uint32_t tx,uint32_t rx,bool ext)override;
  bool startReadChannel()override;
  bool admitted()override;
  bool stopAndVerifyQuiescent()override;
  void latchRecovery()override;
  // Fault callback: immediately inhibit TX. The task then performs the
  // full HAL stop and independently verifies quiescence.
  void inhibitOnFault();
 private:
  Local observe(){return interlock_.observe(tx_,rx_,ext_);}
  bool fresh(const Local& s)const;
  bool halProfile()const;
  bool canStopped()const;
  FDCAN_HandleTypeDef& can_;SPI_HandleTypeDef& spi_;Interlock& interlock_;
  uint32_t tx_,rx_;bool ext_;
  uint64_t generation_=0;bool attempted_=false,active_=false,failed_=false,recovery_=false;
};
}
