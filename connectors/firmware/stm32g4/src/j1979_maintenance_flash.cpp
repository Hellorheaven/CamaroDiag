#include "j1979_maintenance_flash.h"

namespace cd {
bool StmJ1979MaintenanceFlash::reserved(const Region& guard,const Region& slots){
  // Factory flash-size register is in KiB. Never infer capacity from
  // the target name, and reject a missing or nonsensical size register.
  const uint32_t kib=*reinterpret_cast<volatile const uint16_t*>(FLASHSIZE_BASE);
  const uint64_t end=uint64_t(FLASH_BASE)+uint64_t(kib)*1024u;
  return kib>0&&kib!=0xffffu&&same(guard,guard_)&&same(slots,slots_)&&
    guard.valid(Medium::GuardBytes)&&slots.valid(Medium::SlotBytes)&&
    guard.start>=FLASH_BASE&&slots.start>=FLASH_BASE&&
    uint64_t(guard.start)+guard.size<=end&&uint64_t(slots.start)+slots.size<=end&&
    (uint64_t(guard.start)+guard.size<=slots.start||uint64_t(slots.start)+slots.size<=guard.start)&&
    board_.storageAvailable()&&board_.reserved(guard,slots);
}
bool StmJ1979MaintenanceFlash::inside(uint32_t address,size_t n)const{
  if(!n)return false;
  const uint64_t end=uint64_t(address)+n;
  return (address>=guard_.start&&end<=uint64_t(guard_.start)+guard_.size)||
    (address>=slots_.start&&end<=uint64_t(slots_.start)+slots_.size);
}
bool StmJ1979MaintenanceFlash::read(uint32_t address,uint8_t* out,size_t n){
  if(!out||!inside(address,n)||!reserved(guard_,slots_))return false;
  // Volatile reads do not allow the compiler to reuse a pre-write value.
  // The qualified board must configure cache/ECC handling independently.
  auto* memory=reinterpret_cast<volatile const uint8_t*>(address);
  for(size_t i=0;i<n;++i)out[i]=memory[i];
  return reserved(guard_,slots_);
}
bool StmJ1979MaintenanceFlash::program(uint32_t address,const uint8_t* bytes,size_t n){
  if(failed_||!bytes||n!=Medium::Unit||address%Medium::Unit||
     !inside(address,n)||!reserved(guard_,slots_)||!board_.programAllowed())return fault();
  uint8_t old[Medium::Unit]{};
  if(!read(address,old,sizeof(old)))return fault();
  for(uint8_t value:old)if(value!=0xff)return fault();
  uint64_t data=0;
  for(unsigned i=0;i<Medium::Unit;++i)data|=uint64_t(bytes[i])<<(8*i);
  if(data==UINT64_MAX)return true;
  if(HAL_FLASH_Unlock()!=HAL_OK)return fault();
  const bool programmed=board_.programAllowed()&&
    HAL_FLASH_Program(FLASH_TYPEPROGRAM_DOUBLEWORD,address,data)==HAL_OK&&
    HAL_FLASH_GetError()==HAL_FLASH_ERROR_NONE;
  const bool locked=HAL_FLASH_Lock()==HAL_OK;
  uint8_t actual[Medium::Unit]{};
  if(!programmed||!locked||!read(address,actual,sizeof(actual))||
     std::memcmp(actual,bytes,n)||!board_.programAllowed())return fault();
  return true;
}
bool StmJ1979MaintenanceFlash::sync(){
  // HAL programming is synchronous; independent readback remains mandatory
  // at the medium and journal layers. A prior fault can never be cleared.
  return !failed_&&reserved(guard_,slots_)&&HAL_FLASH_GetError()==HAL_FLASH_ERROR_NONE;
}
}
