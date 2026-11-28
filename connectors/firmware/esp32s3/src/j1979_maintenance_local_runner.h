#pragma once
#include "j1979_maintenance_usb.h"
#include "../../shared/j1979_maintenance_local.h"

namespace cd {
// Direct physical CAN composition for a qualified local board. No implied
// support on the dual-MCU prototype: a remote runner requires independently
// qualified STM32-side maintenance storage, interlocks and transport.
class J1979MaintenanceLocalRunner final:public J1979MaintenanceUsb::Runner {
 public:
  using Scope=J1979MaintenanceScope;
  J1979MaintenanceLocalRunner(J1979MaintenanceLocal::Board& owner,
      J1979MaintenanceJournal::Storage& storage,J1979MaintenanceJournal::Scratch& journalScratch,
      J1979CanReadLink& bus,const Scope& approved,J1979MaintenanceCampaign::Evidence& evidence,
      J1979CanReadExchange::Result& scratch):
    owner_(owner),storage_(storage),journalScratch_(journalScratch),bus_(bus),
    scope_(approved),evidence_(evidence),scratch_(scratch){}
  void run(const Scope& scope)override{
    if(invoked_||!scope_.same(scope)){denied_=true;return;}
    invoked_=true;
    runJ1979LocalMaintenance(owner_,storage_,journalScratch_,bus_,scope_,evidence_,scratch_);
  }
  bool journalSize(size_t& n)override{
    return invoked_&&!denied_&&evidence_.physicalStopVerified&&storage_.size(n)&&
      n&&n<=J1979MaintenanceJournal::MaximumJournal;
  }
  bool journalRead(size_t offset,uint8_t* out,size_t n)override{
    size_t total=0;
    return out&&n&&journalSize(total)&&offset<=total&&n<=total-offset&&storage_.read(offset,out,n);
  }
  bool recoveryRequired()override{return denied_||(invoked_&&evidence_.recoveryRequired);}
  bool physicalStopVerified()override{return invoked_&&!denied_&&evidence_.physicalStopVerified;}
 private:
  J1979MaintenanceLocal::Board& owner_;J1979MaintenanceJournal::Storage& storage_;
  J1979MaintenanceJournal::Scratch& journalScratch_;J1979CanReadLink& bus_;
  const Scope scope_;J1979MaintenanceCampaign::Evidence& evidence_;
  J1979CanReadExchange::Result& scratch_;
  bool invoked_=false,denied_=false;
};
}
