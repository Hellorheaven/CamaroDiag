#pragma once
#include "../../shared/j1979_maintenance_storage.h"

namespace cd {
// ESP32 direct-CAN variant only. On the dual-MCU board the STM32 owner
// must persist the authoritative journal locally; this is not a substitute.
// The service never provisions NVS, erases a key or rotates an old journal.
class J1979MaintenanceNvs final:public J1979MaintenanceStorage::Medium {
 public:
  using Store=J1979MaintenanceStorage;
  using Lookup=Store::Lookup;
  using Admission=bool(*)();
  using Inhibit=void(*)();
  struct Scratch { uint8_t record[Store::Journal::MaximumRecord]{}; };
  J1979MaintenanceNvs(Scratch& scratch,Admission admission,Inhibit inhibit):
    scratch_(scratch),admission_(admission),inhibit_(inhibit){}
  J1979MaintenanceNvs(const J1979MaintenanceNvs&)=delete;
  J1979MaintenanceNvs& operator=(const J1979MaintenanceNvs&)=delete;
  bool readGuard(uint8_t* out,size_t n)override;
  bool writeGuard(const uint8_t* data,size_t n)override;
  bool syncGuard()override;
  Lookup slotSize(unsigned slot,size_t& size)override;
  bool readSlot(unsigned slot,size_t offset,uint8_t* out,size_t n)override;
  bool createSlot(unsigned slot,const uint8_t* data,size_t n)override;
  bool syncSlots()override;
  void inhibit()override{if(inhibit_)inhibit_();}
 private:
  bool admitted()const{return admission_&&inhibit_&&admission_();}
  static const char* key(unsigned slot);
  Scratch& scratch_;Admission admission_;Inhibit inhibit_;
  bool guardCommitted_=false,slotCommitted_=false;
};
}
