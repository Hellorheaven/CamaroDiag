#include "../j1979_interprocessor_read.h"
#include <cassert>
using namespace cd;
using namespace cd::readlink;
static bool authorized=true;
static bool admission(){return authorized;}
struct Bus final:J1979CanReadLink {
  uint32_t now=0;unsigned sent=0;bool ready=false;Frame reply;
  bool active()override{return authorized;}
  uint32_t monotonicMs()override{return now;}
  bool discardReceive()override{ready=false;return true;}
  bool send(const Frame& f,uint32_t)override{
    ++sent;assert(f.id==0x7e0);
    reply=Frame{};reply.id=0x7e8;reply.length=8;
    if(f.data[1]==1&&f.data[2]==0){
      const uint8_t data[]={6,0x41,0,0,0,0,0,0};
      std::memcpy(reply.data,data,8);
    }else{
      const uint8_t data[]={3,0x7f,f.data[1],0x11,0,0,0,0};
      std::memcpy(reply.data,data,8);
    }
    ready=true;++now;return true;
  }
  Receive receive(Frame& f,uint32_t timeout)override{
    now+=timeout;if(!ready)return Receive::NoFrame;
    f=reply;ready=false;return Receive::Data;
  }
};
struct Loop final:Transport {
  explicit Loop(Endpoint& e):endpoint(e){}
  Endpoint& endpoint;unsigned calls=0;bool corrupt=false;
  uint32_t now=0,delay=1;
  bool active()override{return authorized;}
  uint32_t monotonicMs()override{return now;}
  bool exchange(const uint8_t* req,uint8_t* res,uint32_t)override{
    ++calls;now+=delay;
    if(!endpoint.transact(req,Size,res,Size))return false;
    if(corrupt)res[31]^=1;
    return true;
  }
};
struct Sink final:J1979CanCampaign::Sink {
  unsigned count=0;
  bool observation(const uint8_t*,size_t,const Exchange::Result&)override{++count;return true;}
};
int main(){
  Bus bus;Exchange::Result scratch,out;
  Endpoint endpoint(bus,scratch,0x7e0,0x7e8,false,admission);
  Loop wire(endpoint);Client client(wire);
  const uint8_t read[]={1,0},write[]={4};
  assert(client.read(read,2,1000,out)&&out.state==State::Positive&&out.responseSize==6);
  assert(bus.sent==1&&wire.calls==2);
  assert(client.read(read,2,1000,out)&&bus.sent==2&&wire.calls==4);
  assert(!client.read(write,1,1000,out)&&client.closed()&&bus.sent==2);
  assert(!client.read(read,2,1000,out)&&bus.sent==2);
  Bus second;Endpoint direct(second,scratch,0x7e0,0x7e8,false,admission);
  Packet p;p.op=Op::Read;p.length=3;put16(p.body,1000);p.body[2]=4;
  uint8_t tx[Size],rx[Size];assert(encode(p,tx,Size));
  assert(!direct.transact(tx,Size,rx,Size)&&direct.closed()&&second.sent==0);
  Endpoint replay(second,scratch,0x7e0,0x7e8,false,admission);
  p.length=4;p.body[2]=1;p.body[3]=0;
  assert(encode(p,tx,Size)&&replay.transact(tx,Size,rx,Size));
  assert(!replay.transact(tx,Size,rx,Size)&&replay.closed()&&second.sent==1);
  Endpoint damaged(second,scratch,0x7e0,0x7e8,false,admission);
  Loop bad(damaged);bad.corrupt=true;Client badClient(bad);
  assert(!badClient.read(read,2,1000,out)&&badClient.closed()&&second.sent==2);
  assert(!badClient.read(read,2,1000,out)&&second.sent==2);
  Bus campaignBus;Endpoint campaignEndpoint(campaignBus,scratch,0x7e0,0x7e8,false,admission);
  Loop campaignWire(campaignEndpoint);Client campaignClient(campaignWire);Sink sink;
  const auto campaign=J1979CanCampaign::runApplication(campaignClient,0x7e0,0x7e8,false,10000,sink,out);
  assert(campaign.end==J1979CanCampaign::End::Complete&&campaign.transportObserved&&
    campaign.pidInventoryComplete&&campaign.steps==sink.count&&campaignBus.sent==sink.count);
  Bus slowBus;Endpoint slowEndpoint(slowBus,scratch,0x7e0,0x7e8,false,admission);
  Loop slowWire(slowEndpoint);slowWire.delay=60;Client slowClient(slowWire);
  assert(!slowClient.read(read,2,100,out)&&slowClient.closed()&&slowBus.sent==1&&slowWire.calls==2);
  Endpoint revoked(second,scratch,0x7e0,0x7e8,false,admission);
  authorized=false;
  assert(!revoked.transact(tx,Size,rx,Size)&&revoked.closed()&&second.sent==2);
}
