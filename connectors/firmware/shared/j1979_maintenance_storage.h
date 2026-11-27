#pragma once
#include "j1979_maintenance_journal.h"

namespace cd {
// Persistent one-operation store. Backend keys/regions must be reserved,
// provisioned and exclusively owned by the qualified board. Never erase,
// initialise or rotate an existing journal in this diagnostic path.
class J1979MaintenanceStorage final:public J1979MaintenanceJournal::Storage {
 public:
  using Journal=J1979MaintenanceJournal;
  static constexpr size_t GuardSize=24,Slots=4;
  enum class Guard:uint8_t { Provisioned=1, Active=2, Sealed=3, Recovery=4 };
  struct State {
    Guard guard=Guard::Recovery;
    uint64_t generation=0;
    uint8_t records=0;
  };
  enum class Lookup:uint8_t { Present, Missing, Error };
  class Medium {
   public:
    virtual ~Medium()=default;
    // Independent persistent guard, not stored in the CM/1 record region.
    // Missing is not a clean board. No automatic provisioning.
    virtual bool readGuard(uint8_t* out,size_t n)=0;
    virtual bool writeGuard(const uint8_t* data,size_t n)=0;
    virtual bool syncGuard()=0;
    virtual Lookup slotSize(unsigned slot,size_t& size)=0;
    virtual bool readSlot(unsigned slot,size_t offset,uint8_t* out,size_t n)=0;
    // Write once into an explicitly empty slot. Do not replace existing bytes.
    virtual bool createSlot(unsigned slot,const uint8_t* data,size_t n)=0;
    virtual bool syncSlots()=0;
    // Inhibit independently of the success of a persistent write.
    virtual void inhibit()=0;
  };
  struct Scratch { uint8_t pending[Journal::MaximumRecord]{}; };
  J1979MaintenanceStorage(Medium& medium,Scratch& scratch):medium_(medium),scratch_(scratch){}
  J1979MaintenanceStorage(const J1979MaintenanceStorage&)=delete;
  J1979MaintenanceStorage& operator=(const J1979MaintenanceStorage&)=delete;
  static bool encode(const State& s,uint8_t* out,size_t n){
    if(!out||n!=GuardSize||s.records>Slots||uint8_t(s.guard)<1||uint8_t(s.guard)>4||
       (s.guard==Guard::Provisioned&&s.records)||
       (s.guard!=Guard::Provisioned&&!s.generation))return false;
    std::memset(out,0,n);out[0]='M';out[1]='G';out[2]=1;out[3]=uint8_t(s.guard);
    out[4]=s.records;
    for(unsigned i=0;i<8;++i)out[8+i]=uint8_t(s.generation>>(8*i));
    p32(out+20,crc(out,20));return true;
  }
  static bool decode(const uint8_t* in,size_t n,State& s){
    s=State{};
    if(!in||n!=GuardSize||in[0]!='M'||in[1]!='G'||in[2]!=1||
       in[3]<1||in[3]>4||in[4]>Slots||in[5]||in[6]||in[7]||
       in[16]||in[17]||in[18]||in[19]||u32(in+20)!=crc(in,20))return false;
    State v;v.guard=static_cast<Guard>(in[3]);v.records=in[4];
    for(unsigned i=0;i<8;++i)v.generation|=uint64_t(in[8+i])<<(8*i);
    if((v.guard==Guard::Provisioned&&v.records)||(v.guard!=Guard::Provisioned&&!v.generation))return false;
    s=v;return true;
  }
  // At boot: only a provisioned, empty store is admitted. A previously
  // active guard, unexpected slot, unreadable storage or corrupt guard
  // requires independent recovery. A sealed journal is preserved read-only.
  bool inspect(){
    if(inspected_)return !locked_&&!sealed_;
    inspected_=true;
    if(!load(state_)||!inventory())return fail();
    if(state_.guard==Guard::Sealed){
      if(state_.records!=count_||!count_||!validateSealed())return fail();
      sealed_=true;return false;
    }
    if(state_.guard!=Guard::Provisioned||count_||state_.generation==UINT64_MAX)return fail();
    return true;
  }
  bool ready()override{
    if(!inspected_&&!inspect())return false;
    if(locked_||sealed_)return false;
    State actual;
    return load(actual)&&same(actual,state_)&&checkInventory()?true:fail();
  }
  bool size(size_t& bytes)override{
    bytes=0;
    // Export can be permitted while locked, but never reinterpret unknown
    // slot sizes as zero. Inspect original bytes without opening a writer.
    if(!inspected_){(void)inspect();}
    if(!locked_&&!sealed_){if(!checkInventory())return fail();}
    else if(!inventory())return fail();
    for(unsigned i=0;i<count_;++i)bytes+=sizes_[i];
    return bytes<=Journal::MaximumJournal;
  }
  bool append(const uint8_t* bytes,size_t n)override{
    if(!ready()||pendingSize_||!bytes||count_>=Slots||
       n<Journal::Header+J1979MaintenanceScope::WireSize+Journal::Trailer||
       n>Journal::MaximumRecord||!recordHeader(bytes,n,count_,lastKind_))return fail();
    // Before the first record or any hardware start, persist the active
    // guard. A reset anywhere after this point is fail-closed.
    if(state_.guard==Guard::Provisioned){
      State next=state_;next.guard=Guard::Active;++next.generation;
      if(!persist(next))return fail();
    }
    if(state_.guard!=Guard::Active||state_.records!=count_)return fail();
    std::memcpy(scratch_.pending,bytes,n);pendingSize_=n;return true;
  }
  bool flush()override{
    if(!ready()||!pendingSize_||count_>=Slots||state_.guard!=Guard::Active)return fail();
    size_t existing=0;
    if(medium_.slotSize(count_,existing)!=Lookup::Missing||
       !medium_.createSlot(count_,scratch_.pending,pendingSize_)||!medium_.syncSlots())return fail();
    size_t actual=0;
    if(medium_.slotSize(count_,actual)!=Lookup::Present||actual!=pendingSize_||
       !exact(count_,scratch_.pending,pendingSize_))return fail();
    sizes_[count_]=pendingSize_;
    lastKind_=scratch_.pending[3];
    // The terminal must already declare verified physical stop. An
    // uncertain operation remains latched even when the terminal exists.
    bool terminal=lastKind_==4;
    bool uncertain=terminal&&((scratch_.pending[Journal::Header+J1979MaintenanceScope::WireSize+5]&32u)!=0);
    ++count_;pendingSize_=0;
    State next=state_;next.records=uint8_t(count_);
    if(terminal)next.guard=uncertain?Guard::Recovery:Guard::Sealed;
    if(!persist(next))return fail();
    if(terminal){sealed_=true;if(uncertain){locked_=true;medium_.inhibit();}}
    return true;
  }
  bool read(size_t offset,uint8_t* out,size_t n)override{
    if(!inspected_){size_t ignored=0;if(!size(ignored))return false;}
    size_t total=0;
    for(unsigned i=0;i<count_;++i)total+=sizes_[i];
    if(!out||!n||offset>total||n>total-offset)return false;
    // The exclusive medium owner pins the inventory. The journal calls
    // size() before each whole-record verification; per-chunk reads check
    // only the addressed slot, avoiding quadratic scans of erased flash.
    for(unsigned i=0;i<count_&&n;++i){
      if(offset>=sizes_[i]){offset-=sizes_[i];continue;}
      const size_t take=n<sizes_[i]-offset?n:sizes_[i]-offset;
      size_t actual=0;
      if(medium_.slotSize(i,actual)!=Lookup::Present||actual!=sizes_[i]||
         !medium_.readSlot(i,offset,out,take))return fail();
      out+=take;n-=take;offset=0;
    }
    return n==0;
  }
  void latchRecovery()override{fail();}
  struct Archive {
    size_t size=0,lengths[Slots]{};
    uint32_t checksums[Slots]{};
    unsigned records=0;
    bool terminal=false,damagedTail=false,recoveryRequired=true;
    J1979MaintenanceScope scope{};
  };
  // Boot-time archive access is independent of maintenance admission.
  // Report only complete, CRC-checked contiguous records; do not discard
  // or repair damaged bytes on the original medium. The caller must
  // hold a read-only storage lease and independently verify CAN quiescence.
  bool archive(Archive& out){
    out=Archive{};
    // Boot admission must have run before taking the read-only lease.
    // In particular, archive access never writes a persistent guard.
    if(!inspected_)return false;
    if(pendingSize_||(!locked_&&!sealed_&&state_.guard==Guard::Active))return false;
    Archive found;
    unsigned prior=0;
    bool stopped=false;
    for(unsigned i=0;i<Slots;++i){
      size_t n=0;const auto lookup=medium_.slotSize(i,n);
      if(lookup==Lookup::Missing){stopped=true;continue;}
      if(lookup!=Lookup::Present||stopped||n>Journal::MaximumRecord||!n||
         !medium_.readSlot(i,0,scratch_.pending,n)||
         !recordHeader(scratch_.pending,n,i,prior)){
        found.damagedTail=true;stopped=true;continue;
      }
      J1979MaintenanceScope scope;
      if(!J1979MaintenanceScope::decode(scratch_.pending+Journal::Header,J1979MaintenanceScope::WireSize,scope)||
         (i&& !found.scope.same(scope))){
        found.damagedTail=true;stopped=true;continue;
      }
      if(!i)found.scope=scope;
      found.lengths[i]=n;found.checksums[i]=u32(scratch_.pending+n-4);
      found.size+=n;++found.records;prior=scratch_.pending[3];
      if(prior==4){found.terminal=true;stopped=true;}
    }
    if(!found.records||!found.scope.valid()||found.size>Journal::MaximumJournal)return false;
    found.recoveryRequired=locked_||!sealed_||!found.terminal||found.damagedTail;
    if(found.damagedTail||!found.terminal){locked_=true;medium_.inhibit();}
    out=found;return true;
  }
  bool readArchive(const Archive& pinned,size_t offset,uint8_t* out,size_t n){
    if(!out||!n||offset>pinned.size||n>pinned.size-offset||!pinned.records||pinned.records>Slots||
       pendingSize_||(!locked_&&!sealed_&&state_.guard==Guard::Active))return false;
    // Opening validates every record. Under the exclusive read-only lease,
    // read only the addressed slot here; the full archive is revalidated
    // at finish. This avoids rereading the entire journal per 16-byte SPI
    // chunk (quadratic work on large replies).
    for(unsigned i=0;i<pinned.records&&n;++i){
      const size_t length=pinned.lengths[i];
      if(!length||length>Journal::MaximumRecord)return false;
      if(offset>=length){offset-=length;continue;}
      size_t actual=0;
      if(medium_.slotSize(i,actual)!=Lookup::Present||actual!=length)return false;
      const size_t take=n<length-offset?n:length-offset;
      if(!medium_.readSlot(i,offset,out,take))return false;
      out+=take;n-=take;offset=0;
    }
    return n==0;
  }
  bool verifyArchive(const Archive& pinned){
    Archive actual;
    if(!archive(actual)||!actual.scope.same(pinned.scope)||actual.size!=pinned.size||
       actual.records!=pinned.records||actual.terminal!=pinned.terminal||
       actual.damagedTail!=pinned.damagedTail||actual.recoveryRequired!=pinned.recoveryRequired)return false;
    for(unsigned i=0;i<actual.records;++i)
      if(actual.lengths[i]!=pinned.lengths[i]||actual.checksums[i]!=pinned.checksums[i])return false;
    return true;
  }
  bool recoveryRequired()const{return locked_;}
  bool sealed()const{return sealed_;}
  // Other J1979 operations may use an independently admitted read lease
  // only when boot state is known and no maintenance intent is unresolved.
  bool permitsOtherDiagnostic(){
    if(!inspected_)(void)inspect();
    State actual;
    if(locked_||!load(actual)||!same(actual,state_)||!checkInventory())return fail();
    return (state_.guard==Guard::Provisioned&&!count_&&!pendingSize_)||
      (state_.guard==Guard::Sealed&&sealed_);
  }
 private:
  static void p32(uint8_t* p,uint32_t v){for(unsigned i=0;i<4;++i)p[i]=uint8_t(v>>(8*i));}
  static uint32_t u32(const uint8_t* p){uint32_t v=0;for(unsigned i=0;i<4;++i)v|=uint32_t(p[i])<<(8*i);return v;}
  static uint32_t update(uint32_t v,const uint8_t* p,size_t n){
    for(size_t i=0;i<n;++i){v^=p[i];for(unsigned bit=0;bit<8;++bit)v=(v>>1)^((v&1u)?0xedb88320u:0);}
    return v;
  }
  static uint32_t crc(const uint8_t* p,size_t n){return update(0xffffffffu,p,n)^0xffffffffu;}
  static bool same(const State& a,const State& b){
    return a.guard==b.guard&&a.generation==b.generation&&a.records==b.records;
  }
  bool load(State& state){
    uint8_t raw[GuardSize]{};
    return medium_.readGuard(raw,sizeof(raw))&&decode(raw,sizeof(raw),state);
  }
  bool persist(const State& next){
    uint8_t raw[GuardSize]{},actual[GuardSize]{};
    if(!encode(next,raw,sizeof(raw))||!medium_.writeGuard(raw,sizeof(raw))||
       !medium_.syncGuard()||!medium_.readGuard(actual,sizeof(actual))||
       std::memcmp(raw,actual,sizeof(raw)))return false;
    state_=next;return true;
  }
  bool fail(){
    medium_.inhibit();
    if(!locked_){
      locked_=true;
      State old;
      // Never provision a missing/corrupt guard. If an existing blank
      // provisioned generation is valid, persist a nonzero recovery epoch.
      if(load(old)){
        if(!old.generation)old.generation=1;
        old.guard=Guard::Recovery;(void)persist(old);
      }
    }
    return false;
  }
  bool inventory(){
    size_t found[Slots]{};
    unsigned count=0;bool gap=false;
    for(unsigned i=0;i<Slots;++i){
      size_t n=0;
      auto result=medium_.slotSize(i,n);
      if(result==Lookup::Missing){gap=true;continue;}
      if(result!=Lookup::Present||gap||!n||n>Journal::MaximumRecord)return false;
      found[i]=n;++count;
    }
    count_=count;std::memcpy(sizes_,found,sizeof(found));return true;
  }
  bool checkInventory(){
    const unsigned count=count_;
    size_t previous[Slots];std::memcpy(previous,sizes_,sizeof(previous));
    return inventory()&&count==count_&&!std::memcmp(previous,sizes_,sizeof(previous));
  }
  static bool recordHeader(const uint8_t* b,size_t n,unsigned index,unsigned previous){
    if(n<Journal::Header+Journal::Trailer||b[0]!='C'||b[1]!='M'||b[2]!=1||
       b[4]!=index||b[5]>J1979MaintenanceCampaign::MaxRows||b[6]||b[7]||
       u32(b+8)!=n-Journal::Header-Journal::Trailer||u32(b+n-4)!=crc(b,n-4))return false;
    const unsigned kind=b[3];
    if(index==0)return kind==1&&b[5]==0&&n==Journal::Header+J1979MaintenanceScope::WireSize+Journal::Trailer;
    if(kind<2||kind>4||previous>=4||(kind!=previous+1&&kind!=4)||
       n<Journal::Header+J1979MaintenanceScope::WireSize+Journal::SummarySize+Journal::Trailer)return false;
    return kind!=4||(b[Journal::Header+J1979MaintenanceScope::WireSize+5]&16u)!=0;
  }
  bool exact(unsigned slot,const uint8_t* expected,size_t n){
    uint8_t actual[64];
    for(size_t offset=0;offset<n;){
      const size_t count=n-offset<sizeof(actual)?n-offset:sizeof(actual);
      if(!medium_.readSlot(slot,offset,actual,count)||std::memcmp(expected+offset,actual,count))return false;
      offset+=count;
    }
    return true;
  }
  bool validateSealed(){
    unsigned previous=0;
    uint8_t scope[J1979MaintenanceScope::WireSize]{};
    for(unsigned i=0;i<count_;++i){
      if(!medium_.readSlot(i,0,scratch_.pending,sizes_[i])||
         !recordHeader(scratch_.pending,sizes_[i],i,previous))return false;
      const uint8_t* current=scratch_.pending+Journal::Header;
      if(!i)std::memcpy(scope,current,sizeof(scope));
      else if(std::memcmp(scope,current,sizeof(scope)))return false;
      previous=scratch_.pending[3];
      if(previous==4&&(i+1!=count_||
         (scratch_.pending[Journal::Header+J1979MaintenanceScope::WireSize+5]&32u)))return false;
    }
    J1979MaintenanceScope decoded;
    return previous==4&&J1979MaintenanceScope::decode(scope,sizeof(scope),decoded);
  }
  Medium& medium_;Scratch& scratch_;State state_{};
  size_t sizes_[Slots]{},pendingSize_=0;
  unsigned count_=0,lastKind_=0;
  bool inspected_=false,locked_=false,sealed_=false;
};
}
