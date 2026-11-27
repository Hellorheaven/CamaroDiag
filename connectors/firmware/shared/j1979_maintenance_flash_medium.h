#pragma once
#include "j1979_maintenance_storage.h"

namespace cd {
// Adapter for a board-reserved flash/FRAM region with erased value FF.
// No erase, raw address discovery, partition creation or provisioning.
// The board supplies a qualified driver and TWO independently reserved
// non-overlapping regions. The diagnostic code never accesses ECU flash.
class J1979MaintenanceFlashMedium final:public J1979MaintenanceStorage::Medium {
 public:
  using Storage=J1979MaintenanceStorage;
  using Lookup=Storage::Lookup;
  static constexpr size_t Unit=8,GuardEntry=32,GuardEntries=8,SlotHeader=16;
  static constexpr size_t SlotStride=SlotHeader+((Storage::Journal::MaximumRecord+Unit-1)/Unit)*Unit;
  static constexpr size_t GuardBytes=GuardEntries*GuardEntry,SlotBytes=Storage::Slots*SlotStride;
  struct Region {
    uint32_t start=0,size=0;
    bool valid(size_t required)const{
      return !(start%Unit)&&!(size%Unit)&&size>=required&&uint64_t(start)+size<=uint64_t(UINT32_MAX)+1;
    }
  };
  class Flash {
   public:
    virtual ~Flash()=default;
    // Board must verify that ranges are entirely reserved, within the
    // physical medium and independently protected against other writers.
    virtual bool reserved(const Region& guard,const Region& slots)=0;
    virtual bool read(uint32_t address,uint8_t* out,size_t n)=0;
    // One aligned, previously erased program unit. Never erase or retry.
    virtual bool program(uint32_t address,const uint8_t* bytes,size_t n)=0;
    virtual bool sync()=0;
    virtual void inhibit()=0;
  };
  J1979MaintenanceFlashMedium(Flash& flash,const Region& guard,const Region& slots):
    flash_(flash),guard_(guard),slots_(slots){}
  J1979MaintenanceFlashMedium(const J1979MaintenanceFlashMedium&)=delete;
  J1979MaintenanceFlashMedium& operator=(const J1979MaintenanceFlashMedium&)=delete;
  bool readGuard(uint8_t* out,size_t n)override{
    unsigned count=0;
    return out&&n==Storage::GuardSize&&guardScan(out,count)&&count>0;
  }
  bool writeGuard(const uint8_t* data,size_t n)override{
    guardSynced_=false;
    Storage::State old,next;
    uint8_t previous[Storage::GuardSize]{};
    unsigned count=0;
    if(!Storage::decode(data,n,next)||!guardScan(previous,count)||!count||count>=GuardEntries||
       !Storage::decode(previous,sizeof(previous),old)||!legal(old,next))return false;
    const uint32_t address=guard_.start+uint32_t(count*GuardEntry);
    // Data first, commit marker last. A torn entry is never a missing entry.
    if(!writeUnits(address,data,n)||!flash_.sync()||!equal(address,data,n))return false;
    uint8_t commit[Unit]={'M','G','C',1,uint8_t(count),0,0,0};
    if(!writeUnits(address+Storage::GuardSize,commit,sizeof(commit))||!flash_.sync())return false;
    uint8_t actual[Storage::GuardSize]{};unsigned after=0;
    guardSynced_=guardScan(actual,after)&&after==count+1&&!std::memcmp(actual,data,n);
    return guardSynced_;
  }
  bool syncGuard()override{
    const bool ok=guardSynced_;guardSynced_=false;return ok&&admitted()&&flash_.sync();
  }
  Lookup slotSize(unsigned slot,size_t& size)override{
    size=0;
    if(slot>=Storage::Slots||!admitted())return Lookup::Error;
    uint8_t head[SlotHeader]{};
    const uint32_t address=slotAddress(slot);
    if(!flash_.read(address,head,sizeof(head)))return Lookup::Error;
    if(erased(head,sizeof(head))){
      // A power cut before the commit header can leave body bytes. This
      // must be detected even when the first unit still reads FF.
      return blank(address+SlotHeader,SlotStride-SlotHeader)?Lookup::Missing:Lookup::Error;
    }
    if(head[0]!='M'||head[1]!='S'||head[2]!=1||head[3]!=slot||
       head[8]!='M'||head[9]!='S'||head[10]!='C'||head[11]!=1||
       u32(head+12)!=~u32(head+4))return Lookup::Error;
    size=u32(head+4);
    if(!size||size>Storage::Journal::MaximumRecord){size=0;return Lookup::Error;}
    return Lookup::Present;
  }
  bool readSlot(unsigned slot,size_t offset,uint8_t* out,size_t n)override{
    size_t size=0;
    if(!out||!n||slotSize(slot,size)!=Lookup::Present||offset>size||n>size-offset)return false;
    return flash_.read(slotAddress(slot)+SlotHeader+uint32_t(offset),out,n)&&admitted();
  }
  bool createSlot(unsigned slot,const uint8_t* data,size_t n)override{
    slotSynced_=false;size_t previous=0;
    if(!data||!n||n>Storage::Journal::MaximumRecord||slotSize(slot,previous)!=Lookup::Missing)return false;
    const uint32_t address=slotAddress(slot);
    if(!writeUnits(address+SlotHeader,data,n)||!flash_.sync()||!equal(address+SlotHeader,data,n))return false;
    uint8_t header[SlotHeader]={'M','S',1,uint8_t(slot),0,0,0,0,'M','S','C',1,0,0,0,0};
    p32(header+4,uint32_t(n));p32(header+12,~uint32_t(n));
    // Both header units are required by the reader.
    if(!writeUnits(address,header,sizeof(header))||!flash_.sync())return false;
    size_t actual=0;
    slotSynced_=slotSize(slot,actual)==Lookup::Present&&actual==n&&equal(address+SlotHeader,data,n);
    return slotSynced_;
  }
  bool syncSlots()override{
    const bool ok=slotSynced_;slotSynced_=false;return ok&&admitted()&&flash_.sync();
  }
  void inhibit()override{flash_.inhibit();}
 private:
  bool admitted(){
    return guard_.valid(GuardBytes)&&slots_.valid(SlotBytes)&&
      (uint64_t(guard_.start)+guard_.size<=slots_.start||uint64_t(slots_.start)+slots_.size<=guard_.start)&&
      flash_.reserved(guard_,slots_);
  }
  static bool erased(const uint8_t* data,size_t n){
    for(size_t i=0;i<n;++i)if(data[i]!=0xff)return false;
    return true;
  }
  static uint32_t u32(const uint8_t* p){uint32_t v=0;for(unsigned i=0;i<4;++i)v|=uint32_t(p[i])<<(8*i);return v;}
  static void p32(uint8_t* p,uint32_t n){for(unsigned i=0;i<4;++i)p[i]=uint8_t(n>>(8*i));}
  uint32_t slotAddress(unsigned slot)const{return slots_.start+uint32_t(slot*SlotStride);}
  bool blank(uint32_t address,size_t n){
    uint8_t buffer[64];
    while(n){
      const size_t count=n<sizeof(buffer)?n:sizeof(buffer);
      if(!admitted()||!flash_.read(address,buffer,count)||!erased(buffer,count))return false;
      n-=count;if(n)address+=uint32_t(count);
    }
    return true;
  }
  bool equal(uint32_t address,const uint8_t* data,size_t n){
    uint8_t buffer[64];
    while(n){
      const size_t count=n<sizeof(buffer)?n:sizeof(buffer);
      if(!admitted()||!flash_.read(address,buffer,count)||std::memcmp(buffer,data,count))return false;
      n-=count;data+=count;if(n)address+=uint32_t(count);
    }
    return true;
  }
  bool writeUnits(uint32_t address,const uint8_t* data,size_t n){
    if(!data||!n||address%Unit)return false;
    while(n){
      uint8_t bytes[Unit],actual[Unit];
      std::memset(bytes,0xff,sizeof(bytes));
      const size_t count=n<Unit?n:Unit;
      std::memcpy(bytes,data,count);
      if(!admitted()||!flash_.read(address,actual,sizeof(actual))||!erased(actual,sizeof(actual)))return false;
      // Do not program an all-FF unit on flash where ECC would make it
      // unavailable for later writes. The region remains write-once.
      if(!erased(bytes,sizeof(bytes))&&(!flash_.program(address,bytes,sizeof(bytes))||
         !flash_.read(address,actual,sizeof(actual))||std::memcmp(bytes,actual,sizeof(bytes))))return false;
      n-=count;data+=count;if(n)address+=Unit;
    }
    return true;
  }
  static bool legal(const Storage::State& old,const Storage::State& next){
    using G=Storage::Guard;
    if(old.guard==G::Provisioned&&next.guard==G::Active&&old.generation!=UINT64_MAX)
      return next.generation==old.generation+1&&!next.records;
    if(old.guard==G::Active&&next.generation==old.generation&&next.records==old.records+1)
      return next.guard==G::Active||next.guard==G::Sealed||next.guard==G::Recovery;
    return next.guard==G::Recovery&&next.generation==(old.generation?old.generation:1)&&
      next.records==old.records;
  }
  bool guardScan(uint8_t* last,unsigned& count){
    count=0;
    if(!last||!admitted())return false;
    bool gap=false;
    Storage::State previous;
    for(unsigned i=0;i<GuardEntries;++i){
      uint8_t bytes[GuardEntry]{};
      if(!flash_.read(guard_.start+uint32_t(i*GuardEntry),bytes,sizeof(bytes)))return false;
      if(erased(bytes,sizeof(bytes))){gap=true;continue;}
      if(gap)return false;
      const uint8_t expected[Unit]={'M','G','C',1,uint8_t(i),0,0,0};
      Storage::State current;
      if(std::memcmp(bytes+Storage::GuardSize,expected,Unit)||
         !Storage::decode(bytes,Storage::GuardSize,current))return false;
      if(!i){if(current.guard!=Storage::Guard::Provisioned)return false;}
      else if(!legal(previous,current))return false;
      previous=current;
      std::memcpy(last,bytes,Storage::GuardSize);++count;
    }
    return admitted();
  }
  Flash& flash_;const Region guard_,slots_;
  bool guardSynced_=false,slotSynced_=false;
};
}
