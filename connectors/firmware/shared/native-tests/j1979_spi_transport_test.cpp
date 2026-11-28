#include "../j1979_spi_transport.h"
#include <cassert>
using namespace cd;
using namespace cd::readlink;
static bool allow=true;
static bool admission(){return allow;}
struct Bus final:J1979CanReadLink {
  unsigned sent=0;uint32_t now=0;bool pending=false;
  bool active()override{return allow;}
  uint32_t monotonicMs()override{return now;}
  bool discardReceive()override{pending=false;return true;}
  bool send(const Frame&,uint32_t)override{++sent;pending=true;return true;}
  Receive receive(Frame& frame,uint32_t)override{
    if(!pending)return Receive::Fault;
    pending=false;frame=Frame{};frame.id=0x7e8;frame.length=8;
    const uint8_t response[]={6,0x41,0,0,0,0,0,0};
    std::memcpy(frame.data,response,8);return Receive::Data;
  }
};
struct Wire final:SpiSlavePort,SpiMasterPort {
  SpiSlave* slave=nullptr;uint32_t now=0;bool level=false,armed=false,allowed=true;
  bool drop=false,shortSlot=false,holdReady=false,dirtyDummy=false;
  unsigned faults=0,transfers=0;
  const uint8_t* tx=nullptr;uint8_t* rx=nullptr;
  bool admitted()override{return allowed;}
  uint32_t monotonicMs()override{return now;}
  bool responseReady()override{return level;}
  void yield()override{++now;}
  void ready(bool value)override{if(!holdReady)level=value;}
  bool arm(const uint8_t* t,uint8_t* r,size_t n)override{
    assert(n==Size&&!armed);tx=t;rx=r;armed=true;return true;
  }
  void fault()override{++faults;allowed=false;armed=false;}
  bool transfer(const uint8_t* master,uint8_t* response,uint32_t)override{
    ++transfers;++now;
    if(drop||!armed)return false;
    std::memcpy(response,tx,Size);std::memcpy(rx,master,Size);
    if(dirtyDummy&&level)rx[0]=1;
    armed=false;
    return slave->completed(shortSlot?Size-1:Size,true);
  }
};
struct Fixture {
  Bus bus;Exchange::Result scratch,out;
  Endpoint endpoint;Wire wire;SpiSlave slave;SpiTransport transport;Client client;
  Fixture():endpoint(bus,scratch,0x7e0,0x7e8,false,admission),
    slave(wire,endpoint),transport(wire),client(transport,0x7e0,0x7e8,false){
    wire.slave=&slave;assert(slave.begin(10000));
  }
};
int main(){
  const uint8_t read[]={1,0};
  Fixture good;
  assert(good.client.read(read,2,1000,good.out));
  assert(good.out.responseSize==6&&good.bus.sent==1&&good.wire.transfers==4);
  assert(good.client.read(read,2,1000,good.out)&&good.bus.sent==2);
  Fixture shortRead;shortRead.wire.shortSlot=true;
  assert(!shortRead.client.read(read,2,1000,shortRead.out)&&shortRead.bus.sent==0);
  assert(shortRead.slave.phase()==SpiSlave::Phase::Closed);
  Fixture stale;stale.wire.level=true;
  assert(!stale.client.read(read,2,1000,stale.out)&&stale.wire.transfers==0);
  Fixture missing;missing.wire.holdReady=true;
  assert(!missing.client.read(read,2,100,missing.out)&&missing.bus.sent==1);
  assert(missing.wire.transfers==1&&!missing.transport.active());
  Fixture malformed;malformed.wire.dirtyDummy=true;
  assert(!malformed.client.read(read,2,1000,malformed.out)&&malformed.bus.sent==1);
  Fixture expired;expired.wire.now=10000;
  assert(!expired.slave.poll()&&expired.wire.faults==1);
  Fixture dropped;dropped.wire.drop=true;
  assert(!dropped.client.read(read,2,1000,dropped.out)&&dropped.bus.sent==0);
  assert(!dropped.client.read(read,2,1000,dropped.out)&&dropped.wire.transfers==1);
}
