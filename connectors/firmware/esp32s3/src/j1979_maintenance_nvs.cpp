#include "j1979_maintenance_nvs.h"
#include <nvs.h>

namespace cd {
namespace {
constexpr const char* GuardNamespace="cd-mguard";
constexpr const char* SlotsNamespace="cd-mjournal";
constexpr const char* GuardKey="state";
struct Handle {
  nvs_handle_t value=0;
  bool open=false;
  Handle(const char* name,nvs_open_mode_t mode):open(nvs_open(name,mode,&value)==ESP_OK){}
  ~Handle(){if(open)nvs_close(value);}
  Handle(const Handle&)=delete;
  Handle& operator=(const Handle&)=delete;
};
}
const char* J1979MaintenanceNvs::key(unsigned slot){
  static const char* const names[]={"r0","r1","r2","r3"};
  return slot<Store::Slots?names[slot]:nullptr;
}
bool J1979MaintenanceNvs::readGuard(uint8_t* out,size_t n){
  if(!out||n!=Store::GuardSize||!admitted())return false;
  Handle h(GuardNamespace,NVS_READONLY);if(!h.open)return false;
  size_t actual=n;
  return nvs_get_blob(h.value,GuardKey,out,&actual)==ESP_OK&&actual==n&&admitted();
}
bool J1979MaintenanceNvs::writeGuard(const uint8_t* data,size_t n){
  guardCommitted_=false;
  Store::State next,old;
  uint8_t previous[Store::GuardSize]{};
  if(!Store::decode(data,n,next)||!readGuard(previous,sizeof(previous))||
     !Store::decode(previous,sizeof(previous),old))return false;
  using G=Store::Guard;
  const bool legal=
    (old.guard==G::Provisioned&&next.guard==G::Active&&old.generation!=UINT64_MAX&&
      next.generation==old.generation+1&&!next.records)||
    (old.guard==G::Active&&next.generation==old.generation&&next.records==old.records+1&&
      (next.guard==G::Active||next.guard==G::Sealed||next.guard==G::Recovery))||
    (next.guard==G::Recovery&&next.generation==(old.generation?old.generation:1)&&
      next.records==old.records);
  if(!legal||!admitted())return false;
  Handle h(GuardNamespace,NVS_READWRITE);if(!h.open)return false;
  size_t actual=sizeof(previous);
  uint8_t check[Store::GuardSize]{};
  if(nvs_get_blob(h.value,GuardKey,check,&actual)!=ESP_OK||actual!=sizeof(check)||
     std::memcmp(check,previous,sizeof(check))||!admitted())return false;
  guardCommitted_=nvs_set_blob(h.value,GuardKey,data,n)==ESP_OK&&nvs_commit(h.value)==ESP_OK;
  return guardCommitted_&&admitted();
}
bool J1979MaintenanceNvs::syncGuard(){
  const bool committed=guardCommitted_;guardCommitted_=false;
  return committed&&admitted();
}
J1979MaintenanceNvs::Lookup J1979MaintenanceNvs::slotSize(unsigned slot,size_t& size){
  size=0;
  const char* name=key(slot);
  if(!name||!admitted())return Lookup::Error;
  Handle h(SlotsNamespace,NVS_READONLY);if(!h.open)return Lookup::Error;
  const auto result=nvs_get_blob(h.value,name,nullptr,&size);
  if(!admitted())return Lookup::Error;
  if(result==ESP_ERR_NVS_NOT_FOUND){size=0;return Lookup::Missing;}
  return result==ESP_OK&&size>0&&size<=sizeof(scratch_.record)?Lookup::Present:Lookup::Error;
}
bool J1979MaintenanceNvs::readSlot(unsigned slot,size_t offset,uint8_t* out,size_t n){
  size_t size=0;
  if(!out||!n||slotSize(slot,size)!=Lookup::Present||offset>size||n>size-offset)return false;
  Handle h(SlotsNamespace,NVS_READONLY);if(!h.open)return false;
  size_t actual=size;
  if(nvs_get_blob(h.value,key(slot),scratch_.record,&actual)!=ESP_OK||actual!=size||!admitted())return false;
  std::memcpy(out,scratch_.record+offset,n);
  return admitted();
}
bool J1979MaintenanceNvs::createSlot(unsigned slot,const uint8_t* data,size_t n){
  slotCommitted_=false;size_t size=0;
  if(!data||!n||n>sizeof(scratch_.record)||slotSize(slot,size)!=Lookup::Missing)return false;
  Handle h(SlotsNamespace,NVS_READWRITE);if(!h.open)return false;
  if(nvs_get_blob(h.value,key(slot),nullptr,&size)!=ESP_ERR_NVS_NOT_FOUND||!admitted())return false;
  slotCommitted_=nvs_set_blob(h.value,key(slot),data,n)==ESP_OK&&nvs_commit(h.value)==ESP_OK;
  return slotCommitted_&&admitted();
}
bool J1979MaintenanceNvs::syncSlots(){
  const bool committed=slotCommitted_;slotCommitted_=false;
  return committed&&admitted();
}
}
