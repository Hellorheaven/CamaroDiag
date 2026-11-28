#pragma once
#include "j1979_maintenance_campaign.h"

namespace cd {
// CM/1 append-only journal for the dedicated maintenance recipe.
// A record is usable only after flush and exact independent readback.
// Storage is supplied by the local board owner, not the USB host.
class J1979MaintenanceJournal final:public J1979MaintenanceCampaign::Store {
 public:
  using Campaign=J1979MaintenanceCampaign;
  using Scope=Campaign::Scope;
  using Evidence=Campaign::Evidence;
  using Outcome=Campaign::Outcome;
  using Stage=Campaign::Stage;
  enum class Kind:uint8_t { Open=1, Baseline=2, Intent=3, Terminal=4 };
  static constexpr size_t Header=12,Trailer=4,SummarySize=20,RowHeader=18;
  static constexpr size_t MaximumRecord=Header+Scope::WireSize+SummarySize+
    Campaign::MaxRows*(RowHeader+Campaign::MaxReply)+Trailer;
  static constexpr size_t MaximumJournal=4*MaximumRecord;
  class Storage {
   public:
    virtual ~Storage()=default;
    // On boot, false means recovery or an unreadable/unknown state.
    virtual bool ready()=0;
    virtual bool size(size_t& bytes)=0;
    // New journal only. Existing bytes are never overwritten or truncated.
    virtual bool append(const uint8_t* bytes,size_t count)=0;
    virtual bool flush()=0;
    virtual bool read(size_t offset,uint8_t* bytes,size_t count)=0;
    // Must persist the recovery condition independently of the journal.
    virtual void latchRecovery()=0;
  };
  // Allocate this scratch in static/owned memory, not on a firmware stack.
  struct Scratch { uint8_t bytes[MaximumRecord]{}; };
  J1979MaintenanceJournal(Storage& storage,Scratch& scratch):storage_(storage),scratch_(scratch){}
  J1979MaintenanceJournal(const J1979MaintenanceJournal&)=delete;
  J1979MaintenanceJournal& operator=(const J1979MaintenanceJournal&)=delete;
  bool begin(const Scope& scope)override{
    size_t n=0;
    if(phase_||failed_||!scope.valid()||!storage_.ready()||!storage_.size(n)||n)return fail();
    scope_=scope;
    return write(Kind::Open,nullptr);
  }
  bool baseline(const Evidence& e)override{
    if(phase_!=uint8_t(Kind::Open)||!same(e)||e.summary.attempted||
       e.summary.baselinePersisted||e.summary.intentPersisted||e.summary.acknowledged||
       !e.summary.initialVin||e.summary.outcome!=Outcome::Running||
       e.count!=(scope_.operation==Scope::Operation::Clear?7u:4u))return fail();
    return write(Kind::Baseline,&e);
  }
  bool intent(const Evidence& e)override{
    if(phase_!=uint8_t(Kind::Baseline)||!same(e)||!e.summary.baselinePersisted||
       !e.summary.recheckedVin||e.summary.attempted||e.summary.intentPersisted||
       e.summary.acknowledged||e.summary.outcome!=Outcome::Running||
       e.count!=(scope_.operation==Scope::Operation::Clear?10u:8u))return fail();
    return write(Kind::Intent,&e);
  }
  // The dedicated local command interlock calls this once, immediately
  // before transmission. Re-read all journal bytes and match the verified
  // image, not just the terminal kind or a host-provided assertion.
  bool consumeIntent(const Scope& scope){
    if(failed_||consumed_||phase_!=uint8_t(Kind::Intent)||!scope_.same(scope)||
       !storage_.ready()||!verifyLast())return fail();
    consumed_=true;return true;
  }
  bool finish(const Evidence& e)override{
    if(!phase_||phase_>=uint8_t(Kind::Terminal)||failed_||!same(e)||
       !e.physicalStopVerified||e.summary.outcome==Outcome::Running||
       (e.summary.attempted&&phase_!=uint8_t(Kind::Intent))||
       (e.summary.acknowledged&&!consumed_))return fail();
    return write(Kind::Terminal,&e);
  }
  void uncertain()override{fail();}
  bool failed()const{return failed_;}
  bool consumed()const{return consumed_;}
 private:
  bool same(const Evidence& e)const{
    return scope_.same(e.scope)&&e.count<=Campaign::MaxRows&&e.count==e.summary.observations;
  }
  static void p16(uint8_t* p,uint16_t v){p[0]=uint8_t(v);p[1]=uint8_t(v>>8);}
  static void p32(uint8_t* p,uint32_t v){for(unsigned i=0;i<4;++i)p[i]=uint8_t(v>>(8*i));}
  static uint32_t crcUpdate(uint32_t v,const uint8_t* p,size_t n){
    for(size_t i=0;i<n;++i){v^=p[i];for(unsigned k=0;k<8;++k)v=(v>>1)^((v&1)?0xedb88320u:0u);}
    return v;
  }
  static uint32_t crc(const uint8_t* p,size_t n){return crcUpdate(0xffffffffu,p,n)^0xffffffffu;}
  bool fail(){if(!failed_){failed_=true;storage_.latchRecovery();}return false;}
  bool exact(size_t offset,const uint8_t* data,size_t count){
    uint8_t check[64];
    while(count){
      const size_t n=count<sizeof(check)?count:sizeof(check);
      if(!storage_.read(offset,check,n)||std::memcmp(check,data,n))return false;
      offset+=n;data+=n;count-=n;
    }
    return true;
  }
  bool verifyLast(){
    size_t total=0;
    if(!lastSize_||!storage_.size(total)||total!=offset_||
       !exact(lastOffset_,scratch_.bytes,lastSize_))return false;
    uint32_t rolling=0xffffffffu;
    uint8_t buffer[64];
    for(size_t pos=0;pos<total;){
      const size_t count=total-pos<sizeof(buffer)?total-pos:sizeof(buffer);
      if(!storage_.read(pos,buffer,count))return false;
      rolling=crcUpdate(rolling,buffer,count);pos+=count;
    }
    // CRC detects corruption, not deliberate replacement by an attacker.
    // The board must protect the local storage from untrusted writes.
    return rolling==rolling_;
  }
  bool write(Kind kind,const Evidence* evidence){
    if(failed_||!storage_.ready()||(phase_&&!verifyLast()))return fail();
    auto* out=scratch_.bytes;
    std::memset(out,0,Header);
    out[0]='C';out[1]='M';out[2]=1;out[3]=uint8_t(kind);
    out[4]=records_;out[5]=evidence?uint8_t(evidence->count):0;
    size_t pos=Header;
    if(!scope_.encode(out+pos,Scope::WireSize))return fail();
    pos+=Scope::WireSize;
    if(evidence){
      const auto& s=evidence->summary;
      auto* b=out+pos;std::memset(b,0,SummarySize);
      b[0]=uint8_t(s.outcome);b[1]=uint8_t(s.reason);b[2]=s.observations;b[3]=s.nrc;
      b[4]=(s.baselinePersisted?1:0)|(s.intentPersisted?2:0)|(s.attempted?4:0)|(s.acknowledged?8:0)|
        (s.initialVin?16:0)|(s.recheckedVin?32:0)|(s.finalVin?64:0)|(s.beforeConfirmed?128:0);
      b[5]=(s.afterConfirmed?1:0)|(s.afterPending?2:0)|(s.afterConfirmedEmpty?4:0)|(s.afterPendingEmpty?8:0)|
        (evidence->physicalStopVerified?16:0)|(evidence->recoveryRequired?32:0);
      p32(b+8,s.durationMs);pos+=SummarySize;
      for(size_t i=0;i<evidence->count;++i){
        const auto& row=evidence->rows[i];
        if(!row.requestSize||row.requestSize>2||row.responseSize>Campaign::MaxReply||
           row.endMs<row.startMs||!row.timeoutMs||row.timeoutMs>1800||
           pos+RowHeader+row.responseSize+Trailer>MaximumRecord)return fail();
        auto* h=out+pos;std::memset(h,0,RowHeader);
        h[0]=uint8_t(row.stage);h[1]=uint8_t(row.status);h[2]=row.requestSize;
        std::memcpy(h+3,row.request,row.requestSize);p16(h+6,row.responseSize);
        p32(h+8,row.startMs);p32(h+12,row.endMs);p16(h+16,uint16_t(row.timeoutMs));
        pos+=RowHeader;
        if(row.responseSize)std::memcpy(out+pos,row.response,row.responseSize);
        pos+=row.responseSize;
      }
    }
    p32(out+8,uint32_t(pos-Header));p32(out+pos,crc(out,pos));pos+=Trailer;
    size_t actual=0;
    if(pos>MaximumRecord||offset_+pos>MaximumJournal||!storage_.size(actual)||actual!=offset_||
       !storage_.append(out,pos)||!storage_.flush()||!storage_.size(actual)||actual!=offset_+pos||
       !exact(offset_,out,pos))return fail();
    rolling_=crcUpdate(rolling_,out,pos);
    lastOffset_=offset_;lastSize_=pos;offset_+=pos;phase_=uint8_t(kind);++records_;
    return true;
  }
  Storage& storage_;Scratch& scratch_;Scope scope_{};
  size_t offset_=0,lastOffset_=0,lastSize_=0;
  uint32_t rolling_=0xffffffffu;
  uint8_t phase_=0,records_=0;
  bool failed_=false,consumed_=false;
};
}
