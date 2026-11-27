#pragma once
#include "j1979_maintenance_archive_usb.h"
#include "j1979_usb_crypto.h"
#include "../../shared/j1979_maintenance_spi.h"

namespace cd {
class J1979MaintenanceArchiveRemote final:public J1979MaintenanceArchive::Source {
 public:
  using Scope=J1979MaintenanceScope;
  using Info=J1979MaintenanceArchive::Info;
  J1979MaintenanceArchiveRemote(readlink::SpiMasterPort& port,const Scope& approved):
    transport_(port,true),client_(transport_,approved),scope_(approved){}
  ~J1979MaintenanceArchiveRemote(){close();}
  bool open(const Scope& scope,Info& info)override{
    if(attempted_||closed_||!scope_.same(scope))return false;
    attempted_=true;return client_.open(info);
  }
  bool read(size_t offset,uint8_t* bytes,size_t n)override{return current()&&client_.read(offset,bytes,n);}
  bool current()override{return !closed_&&client_.active();}
  bool finish()override{
    if(!current()||!client_.finish())return false;
    finished_=true;return true;
  }
  void close()override{
    if(!closed_){closed_=true;if(attempted_&&!finished_)client_.abort();}
  }
 private:
  maintenance_link::SpiTransport transport_;archive_link::Client client_;const Scope scope_;
  bool attempted_=false,closed_=false,finished_=false;
};
// Separate board authorization and credential resolver for recovery. No
// maintenance grant can be requested via this type.
class J1979MaintenanceArchiveSecurity final:public J1979MaintenanceArchiveUsb::Security {
 public:
  using Scope=J1979MaintenanceScope;
  struct Snapshot {
    uint64_t connection=0,credential=0,approval=0;
    bool archiveApproved=false,txInhibited=false,storageExclusive=false,entropyReady=false;
  };
  class Board {
   public:
    virtual ~Board()=default;
    virtual Snapshot snapshot(const Scope& scope)=0;
    virtual bool credential(uint64_t generation,uint8_t* key,size_t n)=0;
    virtual uint32_t monotonicMs()=0;
  };
  J1979MaintenanceArchiveSecurity(Board& board,const Scope& scope):
    board_(board),scope_(scope),initial_(board.snapshot(scope)){}
  bool present()override{
    const auto s=board_.snapshot(scope_);
    return initial_.connection&&initial_.credential&&s.connection==initial_.connection&&s.credential==initial_.credential;
  }
  bool approved(const Scope& scope)override{
    const auto s=board_.snapshot(scope_);
    return scope_.same(scope)&&initial_.connection&&initial_.credential&&initial_.approval&&
      s.connection==initial_.connection&&s.credential==initial_.credential&&s.approval==initial_.approval&&
      s.archiveApproved&&s.txInhibited&&s.storageExclusive;
  }
  uint32_t monotonicMs()override{return board_.monotonicMs();}
  bool nonce(uint8_t* out,size_t n)override{
    return out&&n==32&&approved(scope_)&&board_.snapshot(scope_).entropyReady&&
      j1979UsbRandomNonce(out,n)&&approved(scope_)&&board_.snapshot(scope_).entropyReady;
  }
  bool hmac(const uint8_t* data,size_t n,uint8_t* tag)override{
    if(!data||n!=J1979MaintenanceArchiveUsb::TranscriptSize||!tag||
       (data[0]!='R'&&data[0]!='r')||!approved(scope_))return false;
    uint8_t profile[Scope::WireSize]{};
    if(!scope_.encode(profile,sizeof(profile))||std::memcmp(profile,data+1,sizeof(profile)))return false;
    uint8_t key[32]{};
    const bool ok=board_.credential(initial_.credential,key,sizeof(key))&&approved(scope_)&&
      j1979UsbHmacSha256(key,sizeof(key),data,n,tag)&&approved(scope_);
    volatile uint8_t* wipe=key;for(size_t i=0;i<sizeof(key);++i)wipe[i]=0;
    return ok;
  }
 private:
  Board& board_;const Scope scope_;const Snapshot initial_;
};
inline J1979MaintenanceArchiveUsb::Report serveJ1979RemoteArchive(
    J1979MaintenanceArchiveUsb::Channel& channel,J1979MaintenanceArchiveSecurity& security,
    readlink::SpiMasterPort& port,const J1979MaintenanceScope& scope){
  J1979MaintenanceArchiveRemote source(port,scope);
  return J1979MaintenanceArchiveUsb::serve(channel,security,source);
}
inline J1979MaintenanceArchiveUsb::Report serveJ1979LocalArchive(
    J1979MaintenanceArchiveUsb::Channel& channel,J1979MaintenanceArchiveSecurity& security,
    J1979MaintenanceStorage& storage,J1979MaintenanceArchive::Owner& owner,const J1979MaintenanceScope& scope){
  J1979MaintenanceArchive::LocalSource source(storage,owner,scope);
  return J1979MaintenanceArchiveUsb::serve(channel,security,source);
}
}
