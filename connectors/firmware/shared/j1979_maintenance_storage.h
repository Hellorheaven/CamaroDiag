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
      if(state_.records!=count_||!count_||!validateSlot(count_-1,4))return fail();
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
    if(!inventory())return fail();
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
    size_t total=0;
    if(!out||!n||!size(total)||offset>total||n>total-offset)return false;
    for(unsigned i=0;i<count_&&n;++i){
      if(offset>=sizes_[i]){offset-=sizes_[i];continue;}
      const size_t take=n<sizes_[i]-offset?n:sizes_[i]-offset;
      if(!medium_.readSlot(i,offset,out,take))return fail();
      out+=take;n-=take;offset=0;
    }
    return n==0;
  }
  void latchRecovery()override{fail();}
  bool recoveryRequired()const{return locked_;}
  bool sealed()const{return sealed_;}
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
      // Do not invent a generation for a missing/corrupt guard. The
      // missing/corrupt state itself prevents all future admission.
      if(load(old)&&old.generation){
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
  bool validateSlot(unsigned slot,uint8_t wanted){
    if(slot>=count_||sizes_[slot]>sizeof(scratch_.pending))return false;
    if(!medium_.readSlot(slot,0,scratch_.pending,sizes_[slot]))return false;
    return scratch_.pending[3]==wanted&&recordHeader(scratch_.pending,sizes_[slot],slot,wanted-1);
  }
  Medium& medium_;Scratch& scratch_;State state_{};
  size_t sizes_[Slots]{},pendingSize_=0;
  unsigned count_=0,lastKind_=0;
  bool inspected_=false,locked_=false,sealed_=false;
};
}
