#include "../src/j1979_usb_campaign.h"
#include <cassert>
#include <vector>
#include <cstring>
using U=cd::J1979UsbCampaign;
using Link=cd::J1979CanReadLink;
struct Owner final:U::Owner{
  bool present=true,qualified=true,stable=true,stopped=true,detachOnStop=false;
  bool recoveryOnStop=false,recovery=false;
  unsigned stops=0;
  cd::InterfaceEvidence currentEvidence(const U::Profile&)override{
    cd::InterfaceEvidence e;
    e.qualified=qualified;e.localConsent=true;e.authenticated=true;
    e.exactProfile=true;e.electricalStable=stable;e.diagnosticAllowlistEnforced=true;
    e.recoveryPending=recovery;
    return e;
  }
  bool currentHost()override{return present;}
  bool stopAndVerifyQuiescent()override{++stops;stable=false;if(detachOnStop)present=false;
    if(recoveryOnStop)recovery=true;return stopped;}
};
struct Bus final:Link{
  explicit Bus(Owner& o):owner(o){}
  Owner& owner;
  bool detachAfterSend=false,ready=false;
  unsigned sends=0;
  uint32_t now=0;
  Frame reply;
  bool active()override{return true;}
  uint32_t monotonicMs()override{return now;}
  bool discardReceive()override{ready=false;return true;}
  bool send(const Frame& f,uint32_t)override{
    ++sends;++now;
    reply=Frame{};reply.id=0x7e8;reply.length=8;
    if(f.data[1]==1&&f.data[2]==0){
      const uint8_t bytes[]={6,0x41,0,0,0,0,0,0};std::memcpy(reply.data,bytes,8);
    }else{
      const uint8_t bytes[]={3,0x7f,f.data[1],0x11,0,0,0,0};std::memcpy(reply.data,bytes,8);
    }
    ready=true;
    if(detachAfterSend)owner.present=false;
    return true;
  }
  Receive receive(Frame& f,uint32_t timeout)override{
    now+=timeout;if(!ready)return Receive::NoFrame;
    f=reply;ready=false;return Receive::Data;
  }
};
struct Writer final:cd::J1979CampaignEvidence::Writer{
  std::vector<uint8_t> bytes;
  bool accepted=true;
  bool write(const uint8_t* p,size_t n)override{
    if(!accepted)return false;
    bytes.insert(bytes.end(),p,p+n);return true;
  }
};
static bool hasSummary(const Writer& writer){
  size_t pos=0;
  while(pos+12<=writer.bytes.size()){
    const auto* p=writer.bytes.data()+pos;
    const size_t n=size_t(p[6])+(size_t(p[7])<<8);
    if(n+12>writer.bytes.size()-pos)return false;
    if(p[3]==3)return pos+n+12==writer.bytes.size();
    pos+=n+12;
  }
  return false;
}
int main(){
  U::Profile profile;profile.tx=0x7e0;profile.rx=0x7e8;profile.budgetMs=10000;
  cd::J1979CanReadExchange::Result scratch;
  cd::HostSessionArbiter arbiter;
  Owner owner;Bus bus(owner);Writer writer;
  owner.qualified=false;
  assert(U::run(arbiter,owner,bus,writer,profile,scratch).outcome==U::Outcome::Denied);
  assert(bus.sends==0&&writer.bytes.empty()&&owner.stops==0);
  owner.qualified=true;
  auto good=U::run(arbiter,owner,bus,writer,profile,scratch);
  assert(good.outcome==U::Outcome::Complete&&good.campaign.transportObserved);
  assert(owner.stops==1&&!owner.stable&&hasSummary(writer)&&!arbiter.recoveryRequired());
  Owner revoked;revoked.detachOnStop=true;Bus revBus(revoked);Writer revWriter;
  assert(U::run(arbiter,revoked,revBus,revWriter,profile,scratch).outcome==U::Outcome::EvidenceLost);
  assert(revoked.stops==1&&!hasSummary(revWriter)&&!arbiter.recoveryRequired());
  cd::HostSessionArbiter recoveryArbiter;
  Owner recovery;recovery.recoveryOnStop=true;Bus recBus(recovery);Writer recWriter;
  assert(U::run(recoveryArbiter,recovery,recBus,recWriter,profile,scratch).outcome==U::Outcome::EvidenceLost);
  assert(recovery.stops==1&&!hasSummary(recWriter)&&recoveryArbiter.recoveryRequired());
  Owner alternative;Bus altBus(alternative);Writer altWriter;
  assert(U::run(recoveryArbiter,alternative,altBus,altWriter,profile,scratch).outcome==U::Outcome::Denied);
  assert(altBus.sends==0&&altWriter.bytes.empty());
  Owner detached;Bus lost(detached);Writer prefix;lost.detachAfterSend=true;
  auto partial=U::run(arbiter,detached,lost,prefix,profile,scratch);
  assert(partial.outcome==U::Outcome::EvidenceLost&&lost.sends==1&&detached.stops==1);
  assert(prefix.bytes.size()==25); // only start record before host disappeared
  Owner broken;broken.stopped=false;Bus b(broken);Writer w;
  assert(U::run(arbiter,broken,b,w,profile,scratch).outcome==U::Outcome::QuiescenceUnverified);
  assert(arbiter.recoveryRequired()&&!hasSummary(w));
  Owner next;Bus n(next);Writer nw;
  assert(U::run(arbiter,next,n,nw,profile,scratch).outcome==U::Outcome::Denied);
  assert(n.sends==0&&nw.bytes.empty());
}
