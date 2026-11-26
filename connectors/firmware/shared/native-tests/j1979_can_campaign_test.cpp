#include "../j1979_can_campaign.h"
#include <cassert>
#include <vector>
#include <cstring>
using Link=cd::J1979CanReadLink;
using C=cd::J1979CanCampaign;

class Fake final:public Link {
 public:
  uint32_t now=0;
  bool enabled=true,failSend=false;
  std::vector<std::vector<uint8_t>> requests;
  Frame response{};
  bool ready=false;
  bool active()override{return enabled;}
  uint32_t monotonicMs()override{return now;}
  bool discardReceive()override{ready=false;return true;}
  bool send(const Frame& f,uint32_t timeout)override{
    assert(timeout>0&&f.id==0x7e0&&!f.extended);
    if(failSend)return false;
    unsigned n=f.data[0];assert(n>=1&&n<=3);
    std::vector<uint8_t> req(f.data+1,f.data+1+n);
    requests.push_back(req);
    std::vector<uint8_t> reply={0x7f,req[0],0x11};
    if(req==std::vector<uint8_t>{1,0})reply={0x41,0,0x08,0x10,0,0};
    if(req==std::vector<uint8_t>{1,5})reply={0x41,5,90};
    if(req==std::vector<uint8_t>{1,12})reply={0x41,12,0,0};
    if(req==std::vector<uint8_t>{3})reply={0x43,0};
    if(req==std::vector<uint8_t>{2,2,0})reply={0x42,2,0,0,0};
    if(req==std::vector<uint8_t>{6,0})reply={0x46,0,0,0,0,0};
    if(req==std::vector<uint8_t>{8,0})reply={0x48,0,0x80,0,0,0};
    if(req==std::vector<uint8_t>{9,0})reply={0x49,0,0,0,0,0};
    response=Frame{};response.id=0x7e8;response.length=8;
    response.data[0]=static_cast<uint8_t>(reply.size());
    std::memcpy(response.data+1,reply.data(),reply.size());
    ready=true;++now;return true;
  }
  Receive receive(Frame& f,uint32_t timeout)override{
    now+=timeout;if(!ready)return Receive::NoFrame;
    f=response;ready=false;return Receive::Data;
  }
};
class Sink final:public C::Sink{
 public:
  unsigned count=0,stopAfter=0;
  bool observation(const uint8_t* req,size_t n,const C::Exchange::Result& r)override{
    assert(n>0&&req[0]!=4);
    assert(req[0]!=8||req[1]%32==0);
    assert(r.state!=C::State::Positive||r.responseSize>0);
    ++count;return !stopAfter||count<stopAfter;
  }
};
int main(){
  C::Exchange::Result scratch;
  Fake f;Sink sink;
  auto done=C::run(f,0x7e0,0x7e8,false,10000,sink,scratch);
  assert(done.end==C::End::Complete&&done.transportObserved);
  assert(done.steps==sink.count&&done.steps==f.requests.size());
  assert(done.positive==8&&done.negative==3);
  assert(done.pidInventoryComplete&&done.freezeTriggerObserved&&!done.freezeFramePresent);
  assert(!done.freezeInventoryComplete&&done.monitorInventoryComplete&&
         done.controlInventoryComplete&&done.identityInventoryComplete);
  assert(f.requests.front()==std::vector<uint8_t>({1,0}));
  assert(f.requests[1]==std::vector<uint8_t>({9,2}));
  Fake budget;Sink b;
  auto shortRun=C::run(budget,0x7e0,0x7e8,false,30,b,scratch);
  assert(shortRun.end==C::End::Budget||shortRun.end==C::End::InitialUnverified);
  assert(b.count==budget.requests.size());
  Fake cancelled;Sink c;c.stopAfter=2;
  auto stopped=C::run(cancelled,0x7e0,0x7e8,false,10000,c,scratch);
  assert(stopped.end==C::End::SinkStopped&&cancelled.requests.size()==2);
  Fake bad;Sink d;bad.failSend=true;
  auto failed=C::run(bad,0x7e0,0x7e8,false,10000,d,scratch);
  assert(failed.end==C::End::LinkFault&&!failed.transportObserved&&d.count==1);
  Fake invalid;Sink e;
  assert(C::run(invalid,0x7df,0x7e8,false,10000,e,scratch).end==C::End::LinkFault);
  assert(invalid.requests.empty());
}
