#include "../j1979_live_evidence.h"
#include <cassert>
#include <vector>
#include <cstring>

using namespace cd;
using Campaign=J1979LiveCampaign;
using State=Campaign::State;
using Stop=Campaign::Stop;

static J1979LiveScope scope(){
  J1979LiveScope s{};
  const char* vin="1G1FA1RXXR0123456";
  std::memcpy(s.vin,vin,17);
  s.count=2;s.pids[0]=5;s.pids[1]=12;
  s.cycles=2;s.intervalMs=100;s.budgetMs=1000;
  return s;
}

class Fake final:public Campaign::Link,public Campaign::Waiter {
 public:
  explicit Fake(const J1979LiveScope& s):approved(s){}
  J1979LiveScope approved;
  uint32_t now=0;
  bool enabled=true,wrongVin=false,missingPid=false,cancelWait=false,negative=false;
  unsigned requests=0,reads=0,identities=0;
  bool active()override{return enabled;}
  bool matches(uint32_t tx,uint32_t rx,bool ext)const override{
    return tx==0x7e0&&rx==0x7e8&&!ext;
  }
  bool matchesLive(const J1979LiveScope& s)const override{return approved.same(s);}
  uint32_t monotonicMs()override{return now;}
  bool waitMs(uint32_t duration)override{
    if(cancelWait){enabled=false;return false;}
    now+=duration;return true;
  }
  void exchange(const uint8_t* req,size_t n,uint32_t timeout,Campaign::Exchange::Result& result)override{
    assert(n==2&&timeout>0&&enabled);
    ++requests;now+=5;result.elapsedMs=5;
    if(req[0]==9&&req[1]==2){
      ++identities;result.state=State::Positive;result.responseSize=20;
      result.response[0]=0x49;result.response[1]=2;result.response[2]=1;
      std::memcpy(result.response+3,approved.vin,17);
      if(wrongVin)result.response[3]='2';
    }else if(req[0]==1&&req[1]==0){
      result.state=State::Positive;result.responseSize=6;
      const uint8_t bytes[]={0x41,0,8,0x10,0,0};
      std::memcpy(result.response,bytes,6);
      if(missingPid)result.response[3]=0;
    }else{
      assert(req[0]==1&&(req[1]==5||req[1]==12));
      ++reads;result.responseSize=3;
      result.response[0]=0x41;result.response[1]=req[1];result.response[2]=0x40;
      result.state=State::Raw;
      if(negative&&req[1]==12){
        result.state=State::Negative;
        result.response[0]=0x7f;result.response[1]=1;result.response[2]=0x11;
      }
    }
  }
};

class Writer final:public J1979LiveEvidence::Writer {
 public:
  std::vector<uint8_t> bytes;
  bool write(const uint8_t* data,size_t size)override{
    bytes.insert(bytes.end(),data,data+size);return true;
  }
};

static void testSuccessful(){
  const auto plan=scope();Fake link(plan);Writer writer;
  Campaign::Exchange::Result scratch{};
  J1979LiveEvidence evidence(writer,plan);
  assert(evidence.begin(0x7e0,0x7e8,false));
  const auto summary=Campaign::runApplication(link,0x7e0,0x7e8,false,plan,link,evidence,scratch);
  assert(summary.completed&&summary.stop==Stop::None);
  assert(summary.steps==7&&summary.samples==4&&summary.raw==4);
  assert(summary.rawPerPid[0]==2&&summary.rawPerPid[1]==2);
  assert(summary.initialIdentity&&summary.finalIdentity&&summary.inventoryComplete);
  assert(link.identities==2&&link.reads==4&&link.now>=125);
  assert(evidence.finish(summary));
  assert(writer.bytes.size()>8&&writer.bytes[0]=='C'&&writer.bytes[1]=='L');
  assert(!evidence.finish(summary));
}
static void testRejected(){
  const auto plan=scope();
  for(unsigned which=0;which<4;++which){
    Fake link(plan);Writer writer;Campaign::Exchange::Result scratch{};
    J1979LiveEvidence evidence(writer,plan);
    assert(evidence.begin(0x7e0,0x7e8,false));
    link.wrongVin=which==0;link.missingPid=which==1;
    link.cancelWait=which==2;link.negative=which==3;
    const auto s=Campaign::runApplication(link,0x7e0,0x7e8,false,plan,link,evidence,scratch);
    assert(!s.completed);
    if(which<2)assert(link.reads==0);
    if(which==2)assert(link.reads==2);
    if(which==3)assert(s.negative==2&&s.raw==2);
    assert(evidence.finish(s));
  }
}
static void testScope(){
  auto plan=scope();assert(plan.valid());
  uint8_t wire[J1979LiveScope::WireSize]{};
  assert(plan.encode(wire,sizeof(wire)));
  J1979LiveScope duplicate=plan;duplicate.pids[1]=5;assert(!duplicate.valid());
  auto altered=plan;altered.pids[1]=0x20;assert(!altered.valid());
  Fake link(plan);Writer writer;Campaign::Exchange::Result scratch{};
  J1979LiveEvidence evidence(writer,plan);
  altered=plan;altered.cycles=3;
  auto result=Campaign::runApplication(link,0x7e0,0x7e8,false,altered,link,evidence,scratch);
  assert(!result.completed&&result.stop==Stop::InvalidScope&&link.requests==0);
}
class Large final:public Campaign::Link,public Campaign::Waiter {
 public:
  explicit Large(const J1979LiveScope& plan):plan_(plan){}
  bool active()override{return true;}
  bool matches(uint32_t tx,uint32_t rx,bool ext)const override{return tx==0x7e0&&rx==0x7e8&&!ext;}
  bool matchesLive(const J1979LiveScope& s)const override{return plan_.same(s);}
  uint32_t monotonicMs()override{return now;}
  bool waitMs(uint32_t wait)override{now+=wait;return true;}
  void exchange(const uint8_t* req,size_t n,uint32_t timeout,Campaign::Exchange::Result& r)override{
    assert(n==2&&timeout>=5);
    now+=5;r.elapsedMs=5;
    if(req[0]==9){
      ++identities;r.state=State::Positive;r.responseSize=20;
      r.response[0]=0x49;r.response[1]=2;r.response[2]=1;
      std::memcpy(r.response+3,plan_.vin,17);
      return;
    }
    if(req[1]==0){
      r.state=State::Positive;r.responseSize=6;
      const uint8_t bytes[]={0x41,0,0xff,0xff,0xff,0xfe};
      std::memcpy(r.response,bytes,6);return;
    }
    assert(plan_.includes(req[1]));
    ++reads;r.state=State::Raw;r.responseSize=4095;
    std::memset(r.response,0,r.responseSize);r.response[0]=0x41;r.response[1]=req[1];
  }
  uint32_t now=0,reads=0,identities=0;
 private:
  const J1979LiveScope plan_;
};
class CountingWriter final:public J1979LiveEvidence::Writer {
 public:
  bool write(const uint8_t*,size_t size)override{total+=size;return true;}
  size_t total=0;
};
static void testCapacity(){
  auto plan=scope();plan.count=31;plan.cycles=200;plan.budgetMs=600000;
  for(unsigned i=0;i<31;++i)plan.pids[i]=uint8_t(i+1);
  Large link(plan);CountingWriter writer;Campaign::Exchange::Result scratch{};
  J1979LiveEvidence evidence(writer,plan);
  assert(evidence.begin(0x7e0,0x7e8,false));
  auto summary=Campaign::runApplication(link,0x7e0,0x7e8,false,plan,link,evidence,scratch);
  assert(!summary.completed&&summary.stop==Stop::EvidenceLimit&&summary.initialIdentity);
  assert(summary.raw==link.reads&&summary.samples==link.reads&&link.identities==1);
  assert(summary.rawBytes<=J1979LiveSequence::MaximumRawBytes);
  assert(J1979LiveSequence::MaximumRawBytes-summary.rawBytes<4095);
  assert(summary.steps==summary.samples+2&&summary.completedCycles==summary.samples/31);
  assert(evidence.finish(summary));
  assert(writer.total>summary.rawBytes);
  // A false capacity terminal at the beginning has no evidentiary basis.
  CountingWriter other;J1979LiveEvidence invalid(other,plan);
  assert(invalid.begin(0x7e0,0x7e8,false));
  Campaign::Summary fake;fake.stop=Stop::EvidenceLimit;
  assert(!invalid.finish(fake));
}
static void testSequenceBounds(){
  auto plan=scope();J1979LiveSequence sequence(plan);
  assert(sequence.begin(0xfffffff0u));
  const uint8_t vin[]={9,2};Campaign::Exchange::Result reply{};
  assert(sequence.admit(vin,2,0xfffffff0u,100));
  reply.state=State::Positive;reply.responseSize=20;
  reply.response[0]=0x49;reply.response[1]=2;reply.response[2]=1;
  std::memcpy(reply.response+3,plan.vin,17);reply.elapsedMs=32;
  assert(sequence.observe(vin,2,reply,0x10u)); // wrap-safe monotonic duration
  const uint8_t page[]={1,0};
  assert(sequence.admit(page,2,0x10u,100));
  reply.state=State::Positive;reply.responseSize=6;
  const uint8_t bits[]={0x41,0,8,0x10,0,0};std::memcpy(reply.response,bits,6);reply.elapsedMs=1;
  assert(sequence.observe(page,2,reply,0x11u));
  const uint8_t wrong[]={1,12};
  assert(!sequence.admit(wrong,2,0x11u,100)&&sequence.finished());
  assert(sequence.summary().stop==Stop::InvalidSequence);
}
int main(){testSuccessful();testRejected();testScope();testCapacity();testSequenceBounds();}
