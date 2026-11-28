#pragma once
#include "j1979_freeze_campaign.h"
#include "j1979_campaign_evidence.h"

namespace cd {
// CF/1 is a separate evidence stream for explicit Mode 02. It is not a
// command protocol. CRC and sequence detect corruption only. The writer
// belongs to the authenticated USB session; the terminal record is emitted
// only after independent physical shutdown by the board owner.
class J1979FreezeEvidence final:public J1979FreezeCampaign::Sink {
 public:
  using Writer=J1979CampaignEvidence::Writer;
  using Campaign=J1979FreezeCampaign;
  using Outcome=Campaign::Outcome;
  using Stop=Campaign::Stop;
  using State=J1979CanReadSession::State;
  static constexpr size_t HeaderSize=8,TrailerSize=4;
  static constexpr size_t PlanSize=13+J1979FreezeScope::WireSize;
  static constexpr size_t FinalSize=20;
  static constexpr size_t MaximumBody=10+3+4095;

  explicit J1979FreezeEvidence(Writer& writer):writer_(writer){}
  bool begin(uint32_t tx,uint32_t rx,bool ext,uint32_t budget,const J1979FreezeScope& scope){
    if(started_||failed_||!scope.valid()||budget<1000u||budget>600000u||
       !J1979CanReadSession::physicalPair(tx,rx,ext))return fail();
    uint8_t body[PlanSize]{};
    put32(body,tx);put32(body+4,rx);body[8]=ext?1:0;put32(body+9,budget);
    if(!scope.encode(body+13,J1979FreezeScope::WireSize))return fail();
    scope_=scope;
    started_=emit(1,body,sizeof(body),nullptr,0);
    return started_;
  }
  bool observation(const uint8_t* req,size_t n,Outcome outcome,
      bool attempted,uint32_t duration,const J1979CanReadExchange::Result& r)override{
    if(!started_||finished_||failed_||observations_>=Campaign::MaximumSteps||
       !scope_.allows(req,n)||r.responseSize>4095||duration>3600000u)return fail();
    const bool received=outcome==Outcome::Received||outcome==Outcome::LateResponse;
    if(received!=(r.responseSize>0))return fail();
    if((received||outcome==Outcome::Timeout||outcome==Outcome::TransportError)&&!attempted)return fail();
    if((outcome==Outcome::Budget||outcome==Outcome::ContextChanged)&&attempted)return fail();
    if(received){
      const auto shape=J1979ReadEvidence::classifyFreeze(scope_,req,n,r.response,r.responseSize);
      using R=J1979ReadEvidence::Result;
      if(!((r.state==State::Positive&&shape==R::Positive)||
           (r.state==State::Raw&&shape==R::Raw)||
           (r.state==State::Negative&&shape==R::Negative)))return fail();
    }else if(outcome==Outcome::Timeout){
      if(r.state!=State::Timeout)return fail();
    }else if(r.state!=State::Fault)return fail();
    const uint8_t code=uint8_t(outcome);
    if(code<1||code>6)return fail();
    uint8_t body[13]{};
    body[0]=code;body[1]=attempted?1:0;body[2]=uint8_t(n);
    put32(body+4,duration);put16(body+8,uint16_t(r.responseSize));
    std::memcpy(body+10,req,n);
    if(!emit(2,body,10+n,r.response,r.responseSize))return false;
    ++observations_;
    return true;
  }
  bool finish(const Campaign::Summary& summary){
    if(!started_||finished_||failed_||!observations_||summary.steps!=observations_||
       summary.visited>scope_.frameCount||summary.durationMs>3600000u||
       uint8_t(summary.stop)>uint8_t(Stop::FinalVinMismatch)||
       summary.completed!=(summary.stop==Stop::None&&summary.visited==scope_.frameCount)||
       summary.identityRechecked!=summary.completed)return fail();
    bool stable=summary.completed;
    uint8_t body[FinalSize]{};
    body[0]=uint8_t(summary.stop);body[1]=summary.visited;
    body[2]=(summary.completed?1u:0u)|(summary.identityRechecked?2u:0u)|
      (summary.allFramesStable?4u:0u);
    put32(body+4,summary.steps);put32(body+8,summary.durationMs);
    using F=Campaign::FrameState;
    for(size_t i=0;i<J1979FreezeScope::MaximumFrames;++i){
      if(i>=summary.visited&&summary.frames[i]!=F::Unavailable)return fail();
      if(uint8_t(summary.frames[i])>uint8_t(F::Changed))return fail();
      if(i<summary.visited&&summary.frames[i]!=F::Complete&&summary.frames[i]!=F::Absent)
        stable=false;
      body[12+i]=uint8_t(summary.frames[i]);
    }
    if(stable!=summary.allFramesStable)return fail();
    finished_=emit(3,body,sizeof(body),nullptr,0);
    return finished_;
  }
 private:
  static void put16(uint8_t* p,uint16_t value){p[0]=uint8_t(value);p[1]=uint8_t(value>>8);}
  static void put32(uint8_t* p,uint32_t value){
    for(unsigned i=0;i<4;++i)p[i]=uint8_t(value>>(i*8));
  }
  static uint32_t crc(uint32_t value,const uint8_t* p,size_t n){
    for(size_t i=0;i<n;++i){
      value^=p[i];
      for(unsigned bit=0;bit<8;++bit)value=(value>>1)^((value&1u)?0xedb88320u:0u);
    }
    return value;
  }
  bool fail(){failed_=true;return false;}
  bool emit(uint8_t type,const uint8_t* body,size_t n,const uint8_t* extra,size_t m){
    if(failed_||sequence_>=4096u||n+m>MaximumBody||!body||!n||(m&&!extra))return fail();
    uint8_t header[HeaderSize]={'C','F',1,type,0,0,0,0};
    put16(header+4,sequence_);put16(header+6,uint16_t(n+m));
    uint32_t checksum=crc(0xffffffffu,header,sizeof(header));
    checksum=crc(checksum,body,n);
    if(m)checksum=crc(checksum,extra,m);
    uint8_t trailer[TrailerSize]{};put32(trailer,checksum^0xffffffffu);
    if(!writer_.write(header,sizeof(header))||!writer_.write(body,n)||
       (m&&!writer_.write(extra,m))||!writer_.write(trailer,sizeof(trailer)))return fail();
    ++sequence_;return true;
  }
  Writer& writer_;J1979FreezeScope scope_;
  uint16_t sequence_=0;uint32_t observations_=0;
  bool started_=false,finished_=false,failed_=false;
};
}
