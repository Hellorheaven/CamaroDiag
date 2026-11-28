#include "../j1979_can_read_session.h"
#include <cassert>
#include <cstring>
using S=cd::J1979CanReadSession;
using State=S::State;

static S::Frame make(uint32_t id,bool ext,const uint8_t* data,uint8_t len){
  S::Frame frame;frame.id=id;frame.extended=ext;frame.length=len;
  if(len<=8)std::memcpy(frame.data,data,len);
  return frame;
}

int main(){
  assert(S::physicalPair(0x7e0,0x7e8,false));
  assert(S::physicalPair(0x7e7,0x7ef,false));
  assert(!S::physicalPair(0x7df,0x7e8,false));
  assert(!S::physicalPair(0x7e0,0x7e9,false));
  assert(S::physicalPair(0x18da10f1,0x18daf110,true));
  assert(!S::physicalPair(0x18db33f1,0x18daf110,true));
  assert(!S::physicalPair(0x18da10f1,0x18daf111,true));

  S::Frame tx,flow;bool sendFlow=false;
  const uint8_t readVin[]={9,2},clear[]={4},evap[]={8,1};
  S session(0x7e0,0x7e8,false);
  assert(!session.begin(clear,1,0,1000,tx));
  assert(!session.begin(evap,2,0,1000,tx));
  assert(session.begin(readVin,2,100,1000,tx));
  assert(tx.id==0x7e0&&!tx.extended&&tx.length==8);
  assert(tx.data[0]==2&&tx.data[1]==9&&tx.data[2]==2&&tx.data[7]==0);
  assert(!session.begin(readVin,2,101,1000,tx));

  uint8_t vin[20]={0x49,2,1};std::memcpy(vin+3,"1G1FA1RXXR0123456",17);
  uint8_t ff[8]={0x10,20},cf1[8]={0x21},cf2[8]={0x22};
  std::memcpy(ff+2,vin,6);std::memcpy(cf1+1,vin+6,7);std::memcpy(cf2+1,vin+13,7);
  assert(session.feed(make(0x7e9,false,ff,8),101,flow,sendFlow)==State::Waiting&&!sendFlow);
  assert(session.feed(make(0x7e8,false,ff,8),102,flow,sendFlow)==State::Waiting&&sendFlow);
  assert(flow.id==0x7e0&&!flow.extended&&flow.length==8&&flow.data[0]==0x30);
  assert(session.feed(make(0x7e8,false,cf1,8),103,flow,sendFlow)==State::Waiting&&!sendFlow);
  assert(session.feed(make(0x7e8,false,cf2,8),104,flow,sendFlow)==State::Positive&&!sendFlow);
  assert(session.responseSize()==20&&!std::memcmp(session.response(),vin,20));

  S pending(0x18da10f1,0x18daf110,true);
  const uint8_t rpm[]={1,0x0c},nrc[]={3,0x7f,1,0x78,0,0,0,0};
  assert(pending.begin(rpm,2,0xffffff00u,500,tx)&&tx.extended);
  assert(pending.feed(make(0x18daf110,true,nrc,8),0xffffff10u,flow,sendFlow)==State::Waiting);
  assert(pending.poll(0x000000f4u)==State::Timeout);
  assert(!pending.response());

  S refusal(0x7e0,0x7e8,false);
  const uint8_t denied[]={3,0x7f,1,0x11,0,0,0,0};
  assert(refusal.begin(rpm,2,0,100,tx));
  assert(refusal.feed(make(0x7e8,false,denied,8),1,flow,sendFlow)==State::Negative);
  assert(refusal.responseSize()==3&&refusal.response()[2]==0x11);

  S bad(0x7e0,0x7e8,false);
  const uint8_t wrong[]={4,0x41,0x0d,0,0,0,0,0};
  assert(bad.begin(rpm,2,0,100,tx));
  assert(bad.feed(make(0x7e8,false,wrong,8),1,flow,sendFlow)==State::Fault);
  assert(!bad.response());

  S truncated(0x7e0,0x7e8,false);
  assert(truncated.begin(readVin,2,0,2000,tx));
  assert(truncated.feed(make(0x7e8,false,ff,8),1,flow,sendFlow)==State::Waiting);
  assert(truncated.feed(make(0x7e8,false,cf2,8),2,flow,sendFlow)==State::Fault);

  S aborted(0x7e0,0x7e8,false);
  assert(aborted.begin(rpm,2,0,100,tx));aborted.abort();
  assert(aborted.state()==State::Fault&&!aborted.response());
}
