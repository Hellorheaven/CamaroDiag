#include "../j1979_maintenance_flash_medium.h"
#include <cassert>
#include <vector>

using namespace cd;
using Store=J1979MaintenanceStorage;
using Medium=J1979MaintenanceFlashMedium;
using Journal=J1979MaintenanceJournal;
using Scope=J1979MaintenanceScope;
using Campaign=J1979MaintenanceCampaign;
using Sequence=J1979MaintenanceSequence;

struct Flash final:Medium::Flash {
  std::vector<uint8_t> memory;
  unsigned writes=0,failWrite=0,inhibits=0,syncs=0,failSync=0;
  bool allowed=true;
  Medium::Region guard,slots;
  Flash():memory(Medium::GuardBytes+Medium::SlotBytes,0xff){
    guard.start=0;guard.size=Medium::GuardBytes;
    slots.start=Medium::GuardBytes;slots.size=Medium::SlotBytes;
    Store::State state;state.guard=Store::Guard::Provisioned;
    assert(Store::encode(state,memory.data(),Store::GuardSize));
    const uint8_t commit[]={ 'M','G','C',1,0,0,0,0 };
    std::memcpy(memory.data()+Store::GuardSize,commit,sizeof(commit));
  }
  bool reserved(const Medium::Region& a,const Medium::Region& b)override{
    return allowed&&a.start==guard.start&&a.size==guard.size&&b.start==slots.start&&b.size==slots.size;
  }
  bool read(uint32_t address,uint8_t* out,size_t n)override{
    if(address>memory.size()||n>memory.size()-address)return false;
    std::memcpy(out,memory.data()+address,n);return true;
  }
  bool program(uint32_t address,const uint8_t* bytes,size_t n)override{
    assert(n==Medium::Unit&&address%Medium::Unit==0&&address+n<=memory.size());
    ++writes;
    for(size_t i=0;i<n;++i)assert(memory[address+i]==0xff);
    if(writes==failWrite){
      std::memcpy(memory.data()+address,bytes,n/2);return false;
    }
    std::memcpy(memory.data()+address,bytes,n);return true;
  }
  bool sync()override{++syncs;return syncs!=failSync;}
  void inhibit()override{++inhibits;}
};
static Scope target(){
  Scope s;s.tx=0x7e0;s.rx=0x7e8;s.budgetMs=60000;
  std::memcpy(s.vin,"1G1FA1RXXR0123456",17);return s;
}
struct Fixture{
  Flash flash;
  Medium medium;
  Store::Scratch storeScratch{};
  Store store;
  Journal::Scratch journalScratch{};
  Journal journal;
  Fixture():medium(flash,flash.guard,flash.slots),store(medium,storeScratch),journal(store,journalScratch){}
};
static void bootAndSeal(){
  Fixture f;
  assert(f.store.inspect()&&f.store.ready()&&f.store.permitsOtherDiagnostic());
  const Scope scope=target();
  assert(f.journal.begin(scope));
  assert(f.store.ready()&&!f.store.permitsOtherDiagnostic());
  size_t size=0;assert(f.store.size(size)&&size==47);
  uint8_t raw[47]{};assert(f.store.read(0,raw,sizeof(raw)));
  assert(raw[0]=='C'&&raw[1]=='M'&&raw[3]==1);
  Campaign::Evidence evidence{};evidence.scope=scope;
  evidence.summary.outcome=Sequence::Outcome::NotTested;
  evidence.summary.reason=Sequence::Reason::Context;
  evidence.physicalStopVerified=true;
  assert(f.journal.finish(evidence));
  assert(f.store.sealed()&&!f.store.ready()&&f.store.permitsOtherDiagnostic());
  assert(f.store.size(size)&&size==114);
  std::vector<uint8_t> original(size);assert(f.store.read(0,original.data(),size));
  Store::Scratch scratch{};Store reopened(f.medium,scratch);
  assert(!reopened.inspect()&&reopened.sealed()&&!reopened.recoveryRequired());
  assert(reopened.permitsOtherDiagnostic());
  std::vector<uint8_t> after(size);assert(reopened.read(0,after.data(),size)&&after==original);
  assert(!reopened.ready());
}
static void interruptedBoot(){
  Fixture f;assert(f.journal.begin(target()));
  Store::Scratch scratch{};Store rebooted(f.medium,scratch);
  assert(!rebooted.inspect()&&rebooted.recoveryRequired());
  assert(!rebooted.ready()&&!rebooted.permitsOtherDiagnostic()&&f.flash.inhibits>0);
  size_t n=0;assert(rebooted.size(n)&&n==47);
  uint8_t bytes[47];assert(rebooted.read(0,bytes,sizeof(bytes)));
  assert(bytes[3]==1);
  Store next(f.medium,scratch);assert(!next.inspect()&&next.recoveryRequired());
}
static void corruption(){
  {
    Fixture f;f.flash.memory[0]^=1;
    assert(!f.store.inspect()&&f.store.recoveryRequired()&&f.flash.inhibits);
    assert(!f.journal.begin(target()));
  }
  {
    Fixture f;f.flash.memory[f.flash.slots.start+Medium::SlotHeader]=0;
    assert(!f.store.inspect()&&f.store.recoveryRequired());
    assert(!f.journal.begin(target()));
  }
  {
    Fixture f;assert(f.journal.begin(target()));
    Campaign::Evidence e{};e.scope=target();e.summary.outcome=Sequence::Outcome::NotTested;
    e.summary.reason=Sequence::Reason::Context;e.physicalStopVerified=true;
    assert(f.journal.finish(e));
    f.flash.memory[f.flash.slots.start+Medium::SlotHeader+10]^=1;
    Store::Scratch scratch{};Store rebooted(f.medium,scratch);
    assert(!rebooted.inspect()&&rebooted.recoveryRequired()&&!rebooted.permitsOtherDiagnostic());
  }
}
static void powerCuts(){
  Fixture reference;assert(reference.journal.begin(target()));
  const unsigned openWrites=reference.flash.writes;
  for(unsigned cut=1;cut<=openWrites;++cut){
    Fixture f;f.flash.failWrite=cut;
    assert(!f.journal.begin(target()));
    assert(f.flash.inhibits&&f.store.recoveryRequired());
    Store::Scratch scratch{};Store rebooted(f.medium,scratch);
    assert(!rebooted.inspect()&&rebooted.recoveryRequired());
  }
  for(unsigned cut=1;cut<=reference.flash.syncs;++cut){
    Fixture f;f.flash.failSync=cut;
    assert(!f.journal.begin(target())&&f.store.recoveryRequired());
    Store::Scratch scratch{};Store rebooted(f.medium,scratch);
    assert(!rebooted.inspect()&&rebooted.recoveryRequired());
  }
}
static void guardCodec(){
  Store::State state;state.guard=Store::Guard::Active;state.generation=UINT64_MAX;
  state.records=3;uint8_t bytes[Store::GuardSize]{};
  Store::State decoded;
  assert(Store::encode(state,bytes,sizeof(bytes))&&Store::decode(bytes,sizeof(bytes),decoded));
  assert(decoded.guard==state.guard&&decoded.generation==state.generation&&decoded.records==3);
  bytes[5]=1;assert(!Store::decode(bytes,sizeof(bytes),decoded));
  state.guard=Store::Guard::Provisioned;
  assert(!Store::encode(state,bytes,sizeof(bytes)));
}
int main(){bootAndSeal();interruptedBoot();corruption();powerCuts();guardCodec();}
