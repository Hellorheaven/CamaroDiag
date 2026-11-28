#include "../j1979_can_read_exchange.h"
#include <cassert>
#include <vector>
#include <cstring>
using Link=cd::J1979CanReadLink;
using Exchange=cd::J1979CanReadExchange;
using State=Exchange::State;

class FakeLink final: public Link {
 public:
  bool enabled=true,clearOk=true,sendOk=true,failFlow=false,receiveFault=false;
  bool freezeClock=false;
  uint32_t now=0;
  unsigned clears=0,reads=0;
  std::vector<Frame> sent,in;
  size_t next=0;
  bool active()override{return enabled;}
  uint32_t monotonicMs()override{return now;}
  bool discardReceive()override{++clears;return clearOk;}
  bool send(const Frame& frame,uint32_t timeout)override{
    assert(timeout>0);sent.push_back(frame);
    if(!freezeClock)++now;
    return sendOk&&!(failFlow&&sent.size()>1);
  }
  Receive receive(Frame& frame,uint32_t timeout)override{
    assert(timeout>=1&&timeout<=20);++reads;
    if(!freezeClock)now+=timeout;
    if(receiveFault)return Receive::Fault;
    if(next>=in.size())return Receive::NoFrame;
    frame=in[next++];return Receive::Data;
  }
};
static Link::Frame frame(uint32_t id,const uint8_t* bytes){
  Link::Frame f;f.id=id;f.length=8;std::memcpy(f.data,bytes,8);return f;
}
int main(){
  const uint8_t vinRequest[]={9,2},speedRequest[]={1,0x0d},clear[]={4};
  Exchange::Result result;
  uint8_t data[20]={0x49,2,1};std::memcpy(data+3,"1G1FA1RXXR0123456",17);
  uint8_t ff[8]={0x10,20},cf1[8]={0x21},cf2[8]={0x22};
  std::memcpy(ff+2,data,6);std::memcpy(cf1+1,data+6,7);std::memcpy(cf2+1,data+13,7);
  FakeLink success;success.in={frame(0x7e9,ff),frame(0x7e8,ff),frame(0x7e8,cf1),frame(0x7e8,cf2)};
  Exchange::run(success,0x7e0,0x7e8,false,vinRequest,2,1000,result);
  assert(result.state==State::Positive&&result.responseSize==20&&!std::memcmp(result.response,data,20));
  assert(success.clears==1&&success.sent.size()==2&&success.sent[1].data[0]==0x30);
  FakeLink blocked;blocked.enabled=false;
  Exchange::run(blocked,0x7e0,0x7e8,false,speedRequest,2,1000,result);
  assert(result.state==State::Fault&&blocked.clears==0&&blocked.sent.empty());
  FakeLink mutation;
  Exchange::run(mutation,0x7e0,0x7e8,false,clear,1,1000,result);
  assert(result.state==State::Fault&&mutation.clears==0&&mutation.sent.empty());
  FakeLink dirty;dirty.clearOk=false;
  Exchange::run(dirty,0x7e0,0x7e8,false,speedRequest,2,1000,result);
  assert(result.state==State::Fault&&dirty.sent.empty());
  FakeLink failure;failure.failFlow=true;failure.in={frame(0x7e8,ff)};
  Exchange::run(failure,0x7e0,0x7e8,false,vinRequest,2,1000,result);
  assert(result.state==State::Fault&&failure.sent.size()==2&&result.responseSize==0);
  FakeLink timeout;
  Exchange::run(timeout,0x7e0,0x7e8,false,speedRequest,2,100,result);
  assert(result.state==State::Timeout&&timeout.sent.size()==1);
  FakeLink flooded;flooded.freezeClock=true;
  Exchange::run(flooded,0x7e0,0x7e8,false,speedRequest,2,100,result);
  assert(result.state==State::Fault&&flooded.reads==8192&&flooded.sent.size()==1);
  FakeLink fault;fault.receiveFault=true;
  Exchange::run(fault,0x7e0,0x7e8,false,speedRequest,2,100,result);
  assert(result.state==State::Fault&&fault.sent.size()==1);
}
