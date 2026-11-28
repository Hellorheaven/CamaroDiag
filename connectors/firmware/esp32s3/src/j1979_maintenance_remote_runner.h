#pragma once
#include "j1979_maintenance_usb.h"
#include "../../shared/j1979_maintenance_spi.h"

namespace cd {
// ESP32 CU/4-to-CI/4 composition. STM32 must independently match the scope,
// own the maintenance lease, and persist the original journal before TX.
// Caller supplies private capture memory and an interruptible board waiter.
class J1979MaintenanceRemoteRunner final:public J1979MaintenanceUsb::Runner {
 public:
  using Scope=J1979MaintenanceScope;
  using Client=maintenance_link::Client;
  using Progress=maintenance_link::Progress;
  using State=maintenance_link::RunState;
  struct Buffer { uint8_t bytes[J1979MaintenanceJournal::MaximumJournal]{}; };
  class Waiter {
   public:
    virtual ~Waiter()=default;
    virtual bool current(const Scope& scope)=0;
    // Same host/lease for offline transfer after physical stop, even if
    // a recovery condition now prevents any diagnostic operation.
    virtual bool present()=0;
    virtual bool waitMs(uint32_t duration)=0;
  };
  J1979MaintenanceRemoteRunner(maintenance_link::Transport& transport,Waiter& waiter,
      const Scope& scope,Buffer& buffer):transport_(transport),waiter_(waiter),scope_(scope),buffer_(buffer){}
  void run(const Scope& scope)override{
    if(invoked_||!scope_.same(scope)||!waiter_.current(scope_)){recovery_=true;return;}
    invoked_=true;size_=0;Client client(transport_,scope_);
    if(!client.prepare()||!waiter_.current(scope_)){client.abort();recovery_=true;return;}
    if(!client.start()){recovery_=true;return;}
    Progress progress;
    const uint32_t started=transport_.monotonicMs();
    bool finished=false;
    // No repeated Start. Poll only, with an absolute deadline and a bound
    // for a non-advancing clock. A lost poll aborts the remote lease.
    for(unsigned i=0;i<4000;++i){
      if(!waiter_.current(scope_)||uint32_t(transport_.monotonicMs()-started)>=scope_.budgetMs+5000u||
         !client.poll(progress)){client.abort();recovery_=true;return;}
      if(progress.state!=State::Running){finished=true;break;}
      if(!waiter_.waitMs(20)){client.abort();recovery_=true;return;}
    }
    if(!finished){client.abort();recovery_=true;return;}
    stopped_=progress.physicalStop;
    recovery_=progress.recoveryRequired;
    if(progress.state!=State::Stopped){
      if(!client.close())recovery_=true;
      return;
    }
    class Sink final:public Client::Sink {
     public:
      Sink(Buffer& buffer,size_t& offset,Waiter& waiter):
        buffer_(buffer),offset_(offset),waiter_(waiter){}
      bool append(const uint8_t* bytes,size_t n)override{
        if(!bytes||!n||offset_>sizeof(buffer_.bytes)||n>sizeof(buffer_.bytes)-offset_||
           !waiter_.present())return false;
        std::memcpy(buffer_.bytes+offset_,bytes,n);offset_+=n;return true;
      }
     private:Buffer& buffer_;size_t& offset_;Waiter& waiter_;
    } sink(buffer_,size_,waiter_);
    if(!client.fetch(sink)||size_!=progress.journalSize||!client.close()){
      client.abort();recovery_=true;return;
    }
    transferred_=true;
  }
  bool journalSize(size_t& n)override{
    if(!transferred_||!stopped_||!size_)return false;
    n=size_;return true;
  }
  bool journalRead(size_t offset,uint8_t* out,size_t n)override{
    size_t total=0;
    if(!out||!n||!journalSize(total)||offset>total||n>total-offset)return false;
    std::memcpy(out,buffer_.bytes+offset,n);return true;
  }
  bool recoveryRequired()override{return recovery_;}
  bool physicalStopVerified()override{return stopped_;}
  // The partial remote transfer is not advertised as a complete journal.
  // It is retained privately for local recovery diagnostics.
  size_t retainedBytes()const{return size_;}
 private:
  maintenance_link::Transport& transport_;Waiter& waiter_;const Scope scope_;Buffer& buffer_;
  size_t size_=0;
  bool invoked_=false,recovery_=false,stopped_=false,transferred_=false;
};

inline J1979MaintenanceUsb::Report serveJ1979RemoteMaintenanceUsb(
    J1979MaintenanceUsb::Channel& channel,J1979MaintenanceUsb::Security& security,
    readlink::SpiMasterPort& spi,J1979MaintenanceRemoteRunner::Waiter& waiter,
    const J1979MaintenanceScope& approved,J1979MaintenanceRemoteRunner::Buffer& buffer){
  maintenance_link::SpiTransport transport(spi);
  J1979MaintenanceRemoteRunner runner(transport,waiter,approved,buffer);
  return J1979MaintenanceUsb::serve(channel,security,runner);
}
}
