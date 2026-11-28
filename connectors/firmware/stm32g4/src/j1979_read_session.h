#pragma once
#include "../../shared/j1979_spi_transport.h"

namespace cd {
// One-shot STM32 task owner. Qualification and physical interlocks must
// come from local hardware, not CI/2. A fresh session is required after
// any failure; no automatic recovery or reset of the endpoint.
class StmJ1979ReadSession {
 public:
  class Board {
   public:
    virtual ~Board()=default;
    virtual bool qualified(uint32_t tx,uint32_t rx,bool ext)=0;
    virtual bool startReadChannel()=0;
    virtual bool admitted()=0;
    virtual bool stopAndVerifyQuiescent()=0;
    virtual void latchRecovery()=0;
  };
  enum class State { Idle, Active, Stopped, Fault, Recovery };
  StmJ1979ReadSession(Board& board,readlink::SpiSlave& slave,
      uint32_t tx,uint32_t rx,bool ext)
    :board_(board),slave_(slave),tx_(tx),rx_(rx),ext_(ext){}
  StmJ1979ReadSession(const StmJ1979ReadSession&)=delete;
  StmJ1979ReadSession& operator=(const StmJ1979ReadSession&)=delete;
  State state()const{return state_;}
  bool begin(uint32_t idleBudget){
    if(state_!=State::Idle)return false;
    if(idleBudget<1||idleBudget>600000u||!J1979CanReadSession::physicalPair(tx_,rx_,ext_)||
       !board_.qualified(tx_,rx_,ext_))return false;
    // Even a failed start may have enabled part of the hardware.
    state_=State::Active;
    if(!board_.startReadChannel()||!board_.admitted()||!slave_.begin(idleBudget)){
      terminate(true);return false;
    }
    return true;
  }
  bool poll(){
    if(state_!=State::Active)return false;
    if(!board_.admitted()||!slave_.poll()){terminate(true);return false;}
    return true;
  }
  bool stop(){
    if(state_!=State::Active)return state_==State::Stopped;
    return terminate(false);
  }
  void fault(){if(state_==State::Active)terminate(true);}
 private:
  bool terminate(bool faulted){
    // Mark inactive first: callbacks must not reenter the termination path.
    state_=State::Fault;
    if(faulted)slave_.abort();
    if(!board_.stopAndVerifyQuiescent()){
      state_=State::Recovery;board_.latchRecovery();return false;
    }
    // Normal stop is independently verified and the board must cancel
    // the armed SPI slot; the one-shot object can never restart.
    state_=faulted?State::Fault:State::Stopped;return !faulted;
  }
  Board& board_;readlink::SpiSlave& slave_;uint32_t tx_,rx_;bool ext_;
  State state_=State::Idle;
};
}
