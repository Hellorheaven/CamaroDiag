#pragma once
#include "j1979_maintenance_remote_runner.h"
#include "j1979_usb_crypto.h"

namespace cd {
// Exclusive locally provisioned CU/4 owner. The board implementation
// coordinates with other diagnostic interfaces and pins the independent
// STM32 approval; the host cannot grant itself a maintenance lease.
class J1979MaintenanceUsbOwner final:public J1979MaintenanceUsb::Security,
    public J1979MaintenanceRemoteRunner::Waiter {
 public:
  using Scope=J1979MaintenanceScope;
  struct Snapshot {
    uint64_t connection=0,credential=0,approval=0,lease=0;
    bool qualified=false,warningAccepted=false,confirmed=false,
      stationary=false,stable=false,exclusive=false,recovery=false,entropyReady=false;
  };
  class Board {
   public:
    virtual ~Board()=default;
    virtual Snapshot snapshot(const Scope& scope)=0;
    virtual uint32_t monotonicMs()=0;
    // Resolve an independently provisioned, immutable credential generation.
    // No USB supplied key or fallback default.
    virtual bool credential(uint64_t generation,uint8_t* key,size_t n)=0;
    // Acquire the exact pre-approved maintenance grant, not a generic bus
    // or programming lease. Partial failure also requires revocation.
    virtual bool grant(const Scope& scope,uint64_t lease)=0;
    virtual void revoke(uint64_t lease)=0;
    virtual bool waitMs(uint32_t n)=0;
  };
  J1979MaintenanceUsbOwner(Board& board,const Scope& scope):
    board_(board),scope_(scope),initial_(board.snapshot(scope)){}
  J1979MaintenanceUsbOwner(const J1979MaintenanceUsbOwner&)=delete;
  J1979MaintenanceUsbOwner& operator=(const J1979MaintenanceUsbOwner&)=delete;
  bool present()override{
    const auto now=board_.snapshot(scope_);
    return !revoked_&&initial_.connection&&initial_.credential&&
      now.connection==initial_.connection&&now.credential==initial_.credential;
  }
  bool approved(const Scope& scope)override{
    const auto s=board_.snapshot(scope_);
    return !revoked_&&scope_.same(scope)&&initial_.connection&&initial_.credential&&
      initial_.approval&&initial_.lease&&s.connection==initial_.connection&&s.credential==initial_.credential&&
      s.approval==initial_.approval&&s.lease==initial_.lease&&s.qualified&&s.warningAccepted&&
      s.confirmed&&s.stationary&&s.stable&&s.exclusive&&!s.recovery;
  }
  uint32_t monotonicMs()override{return board_.monotonicMs();}
  bool nonce(uint8_t* out,size_t n)override{
    if(!out||n!=J1979MaintenanceUsb::NonceSize||!approved(scope_)||
       !board_.snapshot(scope_).entropyReady)return false;
    return j1979UsbRandomNonce(out,n)&&approved(scope_)&&board_.snapshot(scope_).entropyReady;
  }
  bool hmac(const uint8_t* data,size_t n,uint8_t* tag)override{
    if(!data||n!=J1979MaintenanceUsb::TranscriptSize||!tag||
       (data[0]!='M'&&data[0]!='m')||!approved(scope_))return false;
    uint8_t wire[Scope::WireSize]{};
    if(!scope_.encode(wire,sizeof(wire))||std::memcmp(data+1,wire,sizeof(wire)))return false;
    uint8_t key[32]{};
    bool ok=board_.credential(initial_.credential,key,sizeof(key))&&approved(scope_)&&
      j1979UsbHmacSha256(key,sizeof(key),data,n,tag)&&approved(scope_);
    volatile uint8_t* clear=key;
    for(size_t i=0;i<sizeof(key);++i)clear[i]=0;
    return ok;
  }
  bool grantMaintenance(const Scope& scope)override{
    if(attempted_||!approved(scope))return false;
    attempted_=true;
    granted_=board_.grant(scope_,initial_.lease);
    return granted_&&approved(scope_);
  }
  void revokeMaintenance()override{
    if(revoked_)return;
    revoked_=true;granted_=false;
    if(attempted_)board_.revoke(initial_.lease);
  }
  bool current(const Scope& scope)override{return granted_&&approved(scope);}
  bool waitMs(uint32_t n)override{
    return n>0&&n<=20&&present()&&board_.waitMs(n)&&present();
  }
 private:
  Board& board_;const Scope scope_;const Snapshot initial_;
  bool attempted_=false,granted_=false,revoked_=false;
};
}
