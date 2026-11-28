#pragma once
#include "j1979_can_campaign.h"

namespace cd {
// Separate evidence stream (CJ/1), not a command protocol. A board owner
// may forward it on an exclusively owned authenticated host channel.
// The discovery USB parser is deliberately unchanged.
class J1979CampaignEvidence final:public J1979CanCampaign::Sink {
 public:
  class Writer {
   public:
    virtual ~Writer()=default;
    virtual bool write(const uint8_t* data,size_t size)=0;
  };
  explicit J1979CampaignEvidence(Writer& writer):writer_(writer){}
  bool begin(uint32_t tx,uint32_t rx,bool ext,uint32_t budget){
    if(started_||failed_||!J1979CanReadSession::physicalPair(tx,rx,ext)||
       budget<1||budget>600000u)return false;
    uint8_t body[13]{};
    put32(body,tx);put32(body+4,rx);body[8]=ext?1:0;put32(body+9,budget);
    started_=emit(1,body,sizeof(body),nullptr,0);
    return started_;
  }
  bool observation(const uint8_t* req,size_t n,const J1979CanReadExchange::Result& r)override{
    if(!started_||finished_||failed_||!req||n<1||n>7||
       !J1979ReadEvidence::readRequest(J1979ReadEvidence::Variant::ClassicCan,req,n)||
       r.responseSize>4095)return fail();
    using S=J1979CanReadSession::State;
    uint8_t state=0;
    switch(r.state){
      case S::Positive:state=1;break;
      case S::Raw:state=2;break;
      case S::Negative:state=3;break;
      case S::Timeout:state=4;break;
      case S::Fault:state=5;break;
      default:return fail();
    }
    if((state<=3)!=(r.responseSize>0))return fail();
    if(state<=3){
      const auto shape=J1979ReadEvidence::classify(J1979ReadEvidence::Variant::ClassicCan,
        req,n,r.response,r.responseSize);
      using R=J1979ReadEvidence::Result;
      if((state==1&&shape!=R::Positive)||(state==2&&shape!=R::Raw)||
         (state==3&&shape!=R::Negative))return fail();
    }
    uint8_t body[15]{};
    body[0]=state;body[1]=uint8_t(n);put32(body+2,r.elapsedMs);
    body[6]=uint8_t(r.responseSize);body[7]=uint8_t(r.responseSize>>8);
    std::memcpy(body+8,req,n);
    if(!emit(2,body,8+n,r.response,r.responseSize))return false;
    ++observations_;++counts_[state-1];return true;
  }
  bool finish(const J1979CanCampaign::Summary& s){
    if(!started_||finished_||failed_||s.steps!=observations_||
       s.positive!=counts_[0]||s.raw!=counts_[1]||s.negative!=counts_[2]||
       s.timeout!=counts_[3])return fail();
    using E=J1979CanCampaign::End;
    uint8_t body[22]{};
    switch(s.end){
      case E::Complete:body[0]=1;break;
      case E::InitialUnverified:body[0]=2;break;
      case E::Budget:body[0]=3;break;
      case E::LinkFault:body[0]=4;break;
      case E::SinkStopped:body[0]=5;break;
      default:return fail();
    }
    body[1]=(s.transportObserved?1:0)|(s.pidInventoryComplete?2:0)|
      (s.freezeTriggerObserved?4:0)|(s.freezeFramePresent?8:0)|
      (s.freezeInventoryComplete?16:0)|(s.monitorInventoryComplete?32:0)|
      (s.controlInventoryComplete?64:0)|(s.identityInventoryComplete?128:0);
    put32(body+2,s.steps);put32(body+6,s.positive);put32(body+10,s.raw);
    put32(body+14,s.negative);put32(body+18,s.timeout);
    finished_=emit(3,body,sizeof(body),nullptr,0);
    return finished_;
  }
 private:
  static void put32(uint8_t* p,uint32_t n){
    for(unsigned i=0;i<4;++i)p[i]=uint8_t(n>>(8*i));
  }
  static uint32_t update(uint32_t crc,const uint8_t* p,size_t n){
    for(size_t i=0;i<n;++i){
      crc^=p[i];
      for(unsigned b=0;b<8;++b)crc=(crc>>1)^((crc&1u)?0xedb88320u:0u);
    }
    return crc;
  }
  bool fail(){failed_=true;return false;}
  bool emit(uint8_t type,const uint8_t* body,size_t n,const uint8_t* extra,size_t m){
    if(failed_||sequence_>=2048||n+m>4110)return fail();
    uint8_t header[8]={'C','J',1,type,uint8_t(sequence_),uint8_t(sequence_>>8),
      uint8_t(n+m),uint8_t((n+m)>>8)};
    uint32_t crc=update(0xffffffffu,header,sizeof(header));
    crc=update(crc,body,n);if(m)crc=update(crc,extra,m);
    uint8_t trailer[4];put32(trailer,crc^0xffffffffu);
    if(!writer_.write(header,sizeof(header))||!writer_.write(body,n)||
       (m&&!writer_.write(extra,m))||!writer_.write(trailer,sizeof(trailer)))return fail();
    ++sequence_;return true;
  }
  Writer& writer_;
  uint16_t sequence_=0;
  uint32_t observations_=0,counts_[5]{};
  bool started_=false,finished_=false,failed_=false;
};
}
