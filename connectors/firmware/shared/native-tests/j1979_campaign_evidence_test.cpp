#include "../j1979_campaign_evidence.h"
#include <cassert>
#include <vector>
#include <cstring>
using E=cd::J1979CampaignEvidence;
using S=cd::J1979CanReadSession::State;
struct Writer final:E::Writer{
  std::vector<uint8_t> bytes;
  unsigned calls=0,failAt=0;
  bool write(const uint8_t* data,size_t n)override{
    if(++calls==failAt)return false;
    bytes.insert(bytes.end(),data,data+n);return true;
  }
};
static uint32_t get32(const uint8_t* p){
  return uint32_t(p[0])|(uint32_t(p[1])<<8)|(uint32_t(p[2])<<16)|(uint32_t(p[3])<<24);
}
static uint32_t crc(const uint8_t* p,size_t n){
  uint32_t value=0xffffffffu;
  for(size_t i=0;i<n;++i){
    value^=p[i];
    for(unsigned b=0;b<8;++b)value=(value>>1)^((value&1u)?0xedb88320u:0u);
  }
  return value^0xffffffffu;
}
int main(){
  Writer writer;E e(writer);
  assert(e.begin(0x7e0,0x7e8,false,180000));
  const uint8_t req[]={1,0};
  cd::J1979CanReadExchange::Result result;
  result.state=S::Positive;result.elapsedMs=21;result.responseSize=6;
  const uint8_t reply[]={0x41,0,0x80,0,0,0};
  std::memcpy(result.response,reply,6);
  assert(e.observation(req,2,result));
  cd::J1979CanCampaign::Summary summary;
  summary.end=cd::J1979CanCampaign::End::Complete;
  summary.steps=1;summary.positive=1;summary.transportObserved=true;
  assert(e.finish(summary));
  size_t offset=0;
  for(unsigned seq=0;seq<3;++seq){
    const auto* p=writer.bytes.data()+offset;
    assert(p[0]=='C'&&p[1]=='J'&&p[2]==1&&p[3]==seq+1);
    assert(p[4]==seq&&p[5]==0);
    size_t length=size_t(p[6])+(size_t(p[7])<<8);
    assert(get32(p+8+length)==crc(p,8+length));
    if(seq==0){assert(length==13&&get32(p+8)==0x7e0&&get32(p+12)==0x7e8);}
    if(seq==1){assert(length==16&&p[8]==1&&p[9]==2&&get32(p+10)==21);}
    if(seq==2){assert(length==22&&p[8]==1&&p[9]==1&&get32(p+10)==1);}
    offset+=length+12;
  }
  assert(offset==writer.bytes.size());
  assert(!e.observation(req,2,result));
  Writer blocked;E denied(blocked);
  assert(!denied.begin(0x7df,0x7e8,false,1000)&&blocked.bytes.empty());
  Writer partial;E broken(partial);partial.failAt=5;
  assert(broken.begin(0x7e0,0x7e8,false,1000));
  assert(!broken.observation(req,2,result));
  assert(!broken.finish(summary));
  Writer unsafe;E restricted(unsafe);
  assert(restricted.begin(0x7e0,0x7e8,false,1000));
  const uint8_t clear[]={4};
  assert(!restricted.observation(clear,1,result));
}
