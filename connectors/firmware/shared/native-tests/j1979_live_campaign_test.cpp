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
int main(){testSuccessful();testRejected();testScope();}
