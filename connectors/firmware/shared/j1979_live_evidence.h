#pragma once
#include "j1979_live_campaign.h"
#include "j1979_campaign_evidence.h"

namespace cd {
// CL/1: streamed native raw live evidence. Separate from CJ/1 scans and
// CF/1 freeze records. CRC is corruption detection, not authentication.
// The owner must verify physical shutdown before calling finish().
class J1979LiveEvidence final:public J1979LiveCampaign::Sink {
 public:
  using Campaign=J1979LiveCampaign;
  using Sequence=Campaign::Sequence;
  using Phase=Campaign::Phase;
  using Stop=Campaign::Stop;
  using State=Campaign::State;
  using Summary=Campaign::Summary;
  using Observation=Campaign::Observation;
  using Exchange=Campaign::Exchange;
  using Writer=J1979CampaignEvidence::Writer;
  static constexpr size_t HeaderSize=8,TrailerSize=4;
  static constexpr size_t PlanSize=9+J1979LiveScope::WireSize;
  static constexpr size_t ObservationSize=22,FinalSize=128;
  static constexpr size_t MaximumBody=ObservationSize+4095;

  J1979LiveEvidence(Writer& writer,const J1979LiveScope& scope)
    :writer_(writer),scope_(scope),replay_(scope){}
  bool begin(uint32_t tx,uint32_t rx,bool ext){
    if(started_||failed_||!scope_.valid()||
       !J1979CanReadSession::physicalPair(tx,rx,ext)||!replay_.begin(0))return fail();
    uint8_t body[PlanSize]{};
    put32(body,tx);put32(body+4,rx);body[8]=ext?1:0;
    if(!scope_.encode(body+9,J1979LiveScope::WireSize))return fail();
    started_=emit(1,body,sizeof(body),nullptr,0);return started_;
  }
  bool observation(const Observation& e,const uint8_t* req,size_t n,const Exchange::Result& r)override{
    if(!started_||finished_||failed_||replay_.finished()||
       observations_>=Campaign::MaximumSteps||!req||n!=2||
       e.startedElapsed<lastEnd_||e.endedElapsed<e.startedElapsed||
       e.endedElapsed>J1979LiveScope::MaximumBudget+4000u||
       e.timeoutMs<1||e.timeoutMs>4000||r.responseSize>4095)return fail();
    if(e.phase!=replay_.phase(e.startedElapsed)||
       e.cycle!=(e.phase==Phase::Sample?replay_.cycle():0)||
       e.pid!=(e.phase==Phase::Sample?req[1]:0)||
       !replay_.admit(req,n,e.startedElapsed,e.timeoutMs))return fail();
    const uint8_t status=state(r.state);
    if(!status||(status<=3)!=(r.responseSize>0)||(!e.accepted&&status!=5))return fail();
    const bool accepted=replay_.observe(req,n,r,e.endedElapsed);
    if(accepted!=e.accepted)return fail();
    // Store absolute logical times and the underlying exchange duration.
    // The duration is needed by a separate replay implementation.
    uint8_t body[ObservationSize]{};
    body[0]=uint8_t(e.phase);body[1]=status;body[2]=e.accepted?1:0;
    put16(body+4,e.cycle);body[6]=e.pid;
    put32(body+8,e.startedElapsed);put32(body+12,e.endedElapsed);
    put16(body+16,uint16_t(e.timeoutMs));put16(body+18,uint16_t(r.responseSize));
    body[20]=req[0];body[21]=req[1];
    if(!emit(2,body,sizeof(body),r.response,r.responseSize))return false;
    ++observations_;lastEnd_=e.endedElapsed;return true;
  }
  bool finish(const Summary& summary){
    if(!started_||finished_||failed_||summary.steps!=observations_||
       summary.durationMs<lastEnd_||summary.durationMs>J1979LiveScope::MaximumBudget+4000u||
       summary.stop==Stop::SinkStopped||summary.stop==Stop::InvalidScope||
       summary.stop==Stop::InvalidSequence)return fail();
    if(!replay_.finished()){
      // External cancellation/context loss or an exhausted absolute budget
      // can terminate the stream between observations; never invent a
      // terminal ECU response in those cases.
      if(summary.stop==Stop::Budget&&replay_.remaining(summary.durationMs)>1)return fail();
      if(summary.stop!=Stop::Budget&&summary.stop!=Stop::Context)return fail();
      replay_.abort(summary.stop,summary.durationMs);
    }
    const auto& checked=replay_.summary();
    if(!same(checked,summary))return fail();
    uint8_t body[FinalSize]{};
    body[0]=uint8_t(summary.stop);
    body[1]=(summary.initialIdentity?1u:0u)|(summary.finalIdentity?2u:0u)|
      (summary.inventoryComplete?4u:0u)|(summary.completed?8u:0u);
    put16(body+2,summary.completedCycles);
    put32(body+4,summary.steps);put32(body+8,summary.samples);
    put32(body+12,summary.raw);put32(body+16,summary.negative);
    put32(body+20,summary.timeout);put32(body+24,summary.durationMs);
    put32(body+28,summary.rawBytes);
    std::memcpy(body+32,summary.advertised,32);
    for(size_t i=0;i<J1979LiveScope::MaximumPids;++i)put16(body+64+2*i,summary.rawPerPid[i]);
    finished_=emit(3,body,sizeof(body),nullptr,0);return finished_;
  }
 private:
  static bool same(const Summary& a,const Summary& b){
    return a.stop==b.stop&&a.steps==b.steps&&a.samples==b.samples&&a.raw==b.raw&&
      a.negative==b.negative&&a.timeout==b.timeout&&a.durationMs==b.durationMs&&
      a.rawBytes==b.rawBytes&&a.completedCycles==b.completedCycles&&
      a.initialIdentity==b.initialIdentity&&a.finalIdentity==b.finalIdentity&&
      a.inventoryComplete==b.inventoryComplete&&a.completed==b.completed&&
      std::memcmp(a.advertised,b.advertised,32)==0&&
      std::memcmp(a.rawPerPid,b.rawPerPid,sizeof(a.rawPerPid))==0;
  }
  static uint8_t state(State s){
    switch(s){
      case State::Positive:return 1;case State::Raw:return 2;
      case State::Negative:return 3;case State::Timeout:return 4;
      case State::Fault:return 5;default:return 0;
    }
  }
  static void put16(uint8_t* p,uint16_t v){p[0]=uint8_t(v);p[1]=uint8_t(v>>8);}
  static void put32(uint8_t* p,uint32_t v){for(unsigned i=0;i<4;++i)p[i]=uint8_t(v>>(8*i));}
  static uint32_t crc(uint32_t value,const uint8_t* p,size_t n){
    for(size_t i=0;i<n;++i){
      value^=p[i];
      for(unsigned bit=0;bit<8;++bit)value=(value>>1)^((value&1u)?0xedb88320u:0u);
    }
    return value;
  }
  bool fail(){failed_=true;return false;}
  bool emit(uint8_t type,const uint8_t* body,size_t n,const uint8_t* extra,size_t m){
    if(failed_||!body||!n||(m&&!extra)||n+m>MaximumBody||
       sequence_>=Campaign::MaximumSteps+2u)return fail();
    uint8_t header[HeaderSize]={'C','L',1,type,0,0,0,0};
    put16(header+4,sequence_);put16(header+6,uint16_t(n+m));
    uint32_t checksum=crc(0xffffffffu,header,sizeof(header));
    checksum=crc(checksum,body,n);
    if(m)checksum=crc(checksum,extra,m);
    uint8_t trailer[TrailerSize]{};put32(trailer,checksum^0xffffffffu);
    if(!writer_.write(header,sizeof(header))||!writer_.write(body,n)||
       (m&&!writer_.write(extra,m))||!writer_.write(trailer,sizeof(trailer)))return fail();
    ++sequence_;return true;
  }
  Writer& writer_;const J1979LiveScope scope_;Sequence replay_;
  uint16_t sequence_=0;uint32_t observations_=0,lastEnd_=0;
  bool started_=false,finished_=false,failed_=false;
};
}
