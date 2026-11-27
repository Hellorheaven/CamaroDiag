#include "../j1979_read_evidence.h"
#include "../isotp_observer.h"
#include <cassert>
#include <cstring>
using E=cd::J1979ReadEvidence;
using V=E::Variant;
using R=E::Result;
int main(){
  const uint8_t dtc[]={3},canZero[]={0x43,0},legacyZero[]={0x43,0,0,0,0,0,0};
  assert(E::classify(V::ClassicCan,dtc,1,canZero,2)==R::Positive);
  assert(E::classify(V::Legacy,dtc,1,canZero,2)==R::Invalid);
  assert(E::classify(V::Legacy,dtc,1,legacyZero,7)==R::Positive);
  assert(E::classify(V::ClassicCan,dtc,1,legacyZero,7)==R::Invalid);
  const uint8_t truncated[]={0x43,1,0x12};
  assert(E::classify(V::ClassicCan,dtc,1,truncated,3)==R::Invalid);
  const uint8_t zeroDeclared[]={0x43,1,0,0};
  assert(E::classify(V::ClassicCan,dtc,1,zeroDeclared,4)==R::Invalid);
  const uint8_t pending[]={0x7f,3,0x78},negative[]={0x7f,3,0x11};
  assert(E::classify(V::ClassicCan,dtc,1,pending,3)==R::Pending);
  assert(E::classify(V::Legacy,dtc,1,negative,3)==R::Negative);
  const uint8_t clear[]={4},clearAck[]={0x44},evap[]={8,1};
  assert(!E::readRequest(V::ClassicCan,clear,1)&&!E::readRequest(V::ClassicCan,evap,2));
  assert(E::classify(V::ClassicCan,clear,1,clearAck,1)==R::Invalid);
  const uint8_t oxygen[]={5,0,1},oxygenPage[]={0x45,0,1,0x80,0,0,0};
  assert(!E::readRequest(V::ClassicCan,oxygen,3));
  assert(E::classify(V::Legacy,oxygen,3,oxygenPage,7)==R::Positive);
  const uint8_t sensorMask[]={5,0,3};
  assert(!E::readRequest(V::Legacy,sensorMask,3));
  const uint8_t inventory[]={8,0,0,0,0,0,0},controlPage[]={0x48,0,0,0x80,0,0,0};
  assert(E::classify(V::Legacy,inventory,7,controlPage,7)==R::Positive);
  const uint8_t invalidInventory[]={8,0,0,0,0,0,1};
  assert(!E::readRequest(V::Legacy,invalidInventory,7));
  const uint8_t mid[]={6,1},canMonitor[]={0x46,1,1,0x24,0,1,0,0,0,2},
    legacyMonitor[]={0x46,1,0x81,0,1,0,2};
  assert(E::classify(V::ClassicCan,mid,2,canMonitor,10)==R::Positive);
  assert(E::classify(V::Legacy,mid,2,legacyMonitor,7)==R::Positive);
  assert(E::classify(V::Legacy,mid,2,canMonitor,10)==R::Invalid);
  const uint8_t info[]={9,0},legacyInfo[]={0x49,0,1,0xc0,0,0,0};
  assert(E::classify(V::Legacy,info,2,legacyInfo,7)==R::Positive);
  assert(E::classify(V::ClassicCan,info,2,legacyInfo,7)==R::Invalid);
  const uint8_t iptCount[]={9,7},iptCountReply[]={0x49,7,9};
  const uint8_t dieselIpt[]={9,11},dieselFragment[]={0x49,11,1,0,1,0,2};
  const uint8_t serialCount[]={9,12},serialCountReply[]={0x49,12,5};
  const uint8_t serial[]={9,13},serialFragment[]={0x49,13,1,0,0,0,0};
  assert(E::classify(V::Legacy,iptCount,2,iptCountReply,3)==R::Positive);
  assert(E::classify(V::Legacy,dieselIpt,2,dieselFragment,7)==R::Positive);
  assert(E::classify(V::Legacy,dieselIpt,2,dieselFragment,3)==R::Invalid);
  assert(E::classify(V::Legacy,serialCount,2,serialCountReply,3)==R::Positive);
  assert(E::classify(V::Legacy,serial,2,serialFragment,7)==R::Positive);
  const uint8_t coolant[]={1,5},goodCoolant[]={0x41,5,90},badCoolant[]={0x41,5,90,0};
  assert(E::classify(V::ClassicCan,coolant,2,goodCoolant,3)==R::Positive);
  assert(E::classify(V::ClassicCan,coolant,2,badCoolant,4)==R::Invalid);
  const uint8_t odometer[]={1,0xa6},odoReply[]={0x41,0xa6,0x00,0x12,0xd6,0x87};
  assert(E::classify(V::ClassicCan,odometer,2,odoReply,sizeof(odoReply))==R::Positive);
  assert(E::classify(V::ClassicCan,odometer,2,odoReply,sizeof(odoReply)-1)==R::Invalid);
  const uint8_t unknown[]={1,0xa1},raw[]={0x41,0xa1,1,2},echoOnly[]={0x41,0xa1};
  assert(E::classify(V::ClassicCan,unknown,2,raw,4)==R::Raw);
  assert(E::classify(V::ClassicCan,unknown,2,echoOnly,2)==R::Invalid);
  const uint8_t finalPage[]={1,0xe0},badNext[]={0x41,0xe0,0,0,0,1};
  assert(E::classify(V::ClassicCan,finalPage,2,badNext,6)==R::Invalid);
  // Known multi-signal replies must be structurally sound even when the
  // firmware deliberately leaves interpretation to the host.
  const uint8_t aecdReq[]={1,0x81};
  uint8_t aecd[43]={0x41,0x81,0x1f};
  std::memset(aecd+3,0xff,40);
  assert(E::classify(V::ClassicCan,aecdReq,2,aecd,sizeof(aecd))==R::Raw);
  assert(E::classify(V::ClassicCan,aecdReq,2,aecd,sizeof(aecd)-1)==R::Invalid);
  assert(E::classify(V::Legacy,aecdReq,2,aecd,sizeof(aecd))==R::Invalid);
  aecd[2]=0x20;
  assert(E::classify(V::ClassicCan,aecdReq,2,aecd,sizeof(aecd))==R::Invalid);
  const uint8_t egrReq[]={1,0x6b};
  uint8_t egr[]={0x41,0x6b,0x21,100,100,0,0};
  assert(E::classify(V::ClassicCan,egrReq,2,egr,sizeof(egr))==R::Raw);
  assert(E::classify(V::Legacy,egrReq,2,egr,sizeof(egr))==R::Raw);
  egr[2]=0x11;
  assert(E::classify(V::ClassicCan,egrReq,2,egr,sizeof(egr))==R::Invalid);
  const uint8_t scrReq[]={1,0x85};
  uint8_t scr[12]={0x41,0x85,0x0f};
  assert(E::classify(V::ClassicCan,scrReq,2,scr,sizeof(scr))==R::Raw);
  scr[2]=0x10;
  assert(E::classify(V::ClassicCan,scrReq,2,scr,sizeof(scr))==R::Invalid);
  const uint8_t sparkIpt[]={9,8};
  uint8_t sparkReply[35]={0x49,8,1};
  assert(E::classify(V::ClassicCan,sparkIpt,2,sparkReply,sizeof(sparkReply))==R::Raw);
  assert(E::classify(V::ClassicCan,sparkIpt,2,sparkReply,sizeof(sparkReply)-1)==R::Invalid);
  sparkReply[2]=2;
  assert(E::classify(V::ClassicCan,sparkIpt,2,sparkReply,sizeof(sparkReply))==R::Invalid);
  // Feed only captured synthetic frames through the passive observer.
  const uint8_t vinReq[]={9,2};
  uint8_t vin[20]={0x49,2,1};
  std::memcpy(vin+3,"1G1FA1RXXR0123456",17);
  uint8_t ff[8]={0x10,20},cf1[8]={0x21},cf2[8]={0x22};
  std::memcpy(ff+2,vin,6);
  std::memcpy(cf1+1,vin+6,7);
  std::memcpy(cf2+1,vin+13,7);
  cd::IsoTpObserver observer(0x7e8,false);
  using T=cd::IsoTpObserver::Result;
  assert(observer.feed(0x7e8,false,ff,8,0)==T::Pending);
  assert(observer.feed(0x7e9,false,cf1,8,1)==T::Ignored);
  assert(observer.feed(0x7e8,false,cf1,8,2)==T::Pending);
  assert(observer.feed(0x7e8,false,cf2,8,3)==T::Complete);
  assert(E::classify(V::ClassicCan,vinReq,2,observer.message(),observer.size())==R::Positive);
  vin[3]='I';
  assert(E::classify(V::ClassicCan,vinReq,2,vin,20)==R::Invalid);
}
