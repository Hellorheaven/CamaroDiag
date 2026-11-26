#include "j1979_nvs_read_guard.h"
#include <nvs.h>

namespace cd {
bool J1979NvsReadGuardStore::read(uint8_t* data,size_t size){
  if(!data||size!=J1979ReadGuard::Size)return false;
  nvs_handle_t h;
  if(nvs_open("cd-readguard",NVS_READONLY,&h)!=ESP_OK)return false;
  size_t n=size;const auto rc=nvs_get_blob(h,"state",data,&n);
  nvs_close(h);return rc==ESP_OK&&n==size;
}
bool J1979NvsReadGuardStore::writeVerified(const uint8_t* data,size_t size){
  J1979ReadGuard::Record next,old;
  uint8_t previous[J1979ReadGuard::Size]{},check[J1979ReadGuard::Size]{};
  if(!J1979ReadGuard::decode(data,size,next)||!read(previous,sizeof(previous))||
     !J1979ReadGuard::decode(previous,sizeof(previous),old))return false;
  using S=J1979ReadGuard::State;
  if(!((old.state==S::Clean&&next.state==S::Active&&old.generation!=UINT64_MAX&&
        next.generation==old.generation+1)||
       (old.state==S::Active&&next.state==S::Clean&&next.generation==old.generation)))return false;
  nvs_handle_t h;
  if(nvs_open("cd-readguard",NVS_READWRITE,&h)!=ESP_OK)return false;
  const bool ok=nvs_set_blob(h,"state",data,size)==ESP_OK&&nvs_commit(h)==ESP_OK;
  nvs_close(h);
  return ok&&read(check,sizeof(check))&&std::memcmp(check,data,size)==0;
}
}
