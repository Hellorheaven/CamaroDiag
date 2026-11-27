#include "../j1979_maintenance_interprocessor.h"
#include <cassert>
#include <vector>

using namespace cd::maintenance_link;
static Scope target(){
  Scope s;s.tx=0x7e0;s.rx=0x7e8;s.budgetMs=60000;
  std::memcpy(s.vin,"1G1FA1RXXR0123456",17);return s;
}
struct Worker final:Runner{
  Scope scope=target();bool present=true,permit=true,done=false,unavailable=false;
  unsigned starts=0,cancels=0,releases=0;uint32_t now=0;
  std::vector<uint8_t> bytes=std::vector<uint8_t>(65,0x42);
  bool approved(const Scope& s)override{return permit&&scope.same(s);}
  bool connectionPresent()override{return present;}
  uint32_t monotonicMs()override{return now;}
  bool schedule(const Scope& s)override{++starts;return approved(s);}
  Progress progress()override{
    Progress p;
    if(unavailable){p.recoveryRequired=true;return p;}
    if(done){p.state=RunState::Stopped;p.physicalStop=true;p.journalSize=uint32_t(bytes.size());}
    else p.state=RunState::Running;
    return p;
  }
  bool readJournal(size_t offset,uint8_t* p,size_t n)override{
    if(!done||offset>bytes.size()||n>bytes.size()-offset)return false;
    std::memcpy(p,bytes.data()+offset,n);return true;
  }
  void cancelAndLatchRecovery()override{++cancels;permit=false;}
  bool releaseVerified()override{++releases;return done;}
};
struct Wire final:Transport{
  Worker& worker;Endpoint& endpoint;bool broken=false,loseStart=false;
  Wire(Worker& w,Endpoint& e):worker(w),endpoint(e){}
  bool active()override{return !broken&&worker.present;}
  uint32_t monotonicMs()override{return worker.now;}
  bool exchange(const uint8_t* req,uint8_t* out,uint32_t)override{
    Packet p;if(!decode(req,Size,p))return false;
    const bool ok=endpoint.transact(req,Size,out,Size);
    return ok&&!(loseStart&&p.op==Op::Start);
  }
  void fault()override{broken=true;endpoint.fault();}
};
struct Sink final:Client::Sink{
  std::vector<uint8_t> bytes;bool accept=true;
  bool append(const uint8_t* p,size_t n)override{if(!accept)return false;bytes.insert(bytes.end(),p,p+n);return true;}
};
struct Fixture{
  Worker worker;Endpoint endpoint;Wire wire;Client client;
  Fixture():endpoint(worker,worker.scope),wire(worker,endpoint),client(wire,worker.scope){}
  void start(){assert(client.prepare()&&client.start());assert(worker.starts==1);}
};
static void success(){
  Fixture f;f.start();Progress p;
  assert(f.client.poll(p)&&p.state==RunState::Running);
  f.worker.done=true;assert(f.client.poll(p)&&p.state==RunState::Stopped);
  Sink s;assert(f.client.fetch(s)&&s.bytes==f.worker.bytes);
  assert(f.client.close()&&f.endpoint.closed());
  assert(f.worker.starts==1&&f.worker.releases==1&&!f.worker.cancels);
}
static void faults(){
  {Fixture f;f.wire.loseStart=true;assert(f.client.prepare());assert(!f.client.start());
   assert(f.client.attempted()&&f.client.failed());assert(!f.client.start());
   assert(f.worker.starts==1&&f.worker.cancels==1);}
  {Fixture f;f.start();f.worker.now=65000;assert(!f.endpoint.poll());
   assert(f.worker.cancels==1&&f.endpoint.closed());}
  {Fixture f;f.start();f.worker.present=false;assert(!f.endpoint.poll());
   assert(f.worker.cancels==1);}
  {Fixture f;f.start();f.worker.done=true;Progress p;assert(f.client.poll(p));
   Sink s;s.accept=false;assert(!f.client.fetch(s));assert(f.worker.cancels==1);}
  {Fixture f;f.start();f.worker.done=true;Progress p;assert(f.client.poll(p));
   assert(!f.client.close());assert(f.worker.releases==0);}
  {Fixture f;f.start();f.client.abort();assert(f.endpoint.closed()&&f.worker.cancels==1);}
  {Fixture f;f.worker.permit=false;assert(!f.client.prepare());assert(!f.worker.starts);}
  {Fixture f;Scope wrong=target();wrong.vin[0]='2';Client c(f.wire,wrong);
   assert(!c.prepare());assert(!f.worker.starts);}
}
static void frames(){
  Packet p,q;p.op=Op::Poll;p.sequence=123;
  uint8_t data[Size]{};
  assert(encode(p,data,Size)&&decode(data,Size,q)&&q.sequence==123);
  data[2]=3;assert(!decode(data,Size,q));
  assert(encode(p,data,Size));data[8]=1;assert(!decode(data,Size,q));
  assert(!decode(data,Size-1,q));
  Fixture f;assert(f.client.prepare());
  p=Packet{};p.op=Op::Start;p.sequence=0;
  uint8_t reply[Size]{};assert(encode(p,data,Size));
  assert(!f.endpoint.transact(data,Size,reply,Size));assert(!f.worker.starts);
}
int main(){success();faults();frames();}
