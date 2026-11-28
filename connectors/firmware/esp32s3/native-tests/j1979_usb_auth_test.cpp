#include "../src/j1979_usb_service.h"
#include <vector>
#include <cassert>
using namespace cd;
using Auth=J1979UsbAuthSession;
// Structural fake for authorization logic only, NOT a cryptographic test.
struct Security final:Auth::Security {
  bool present=true,approved=true,granted=false;
  unsigned grants=0,revokes=0;uint32_t now=0;
  bool connectionPresent()override{return present;}
  bool localApproval(const Auth::Profile&)override{return approved;}
  bool randomNonce(uint8_t* out,size_t n)override{std::memset(out,'D',n);return true;}
  bool hmac(const uint8_t* data,size_t n,uint8_t* tag)override{
    const auto c=crc32(data,n);
    for(size_t i=0;i<Auth::TagSize;++i)tag[i]=uint8_t(c>>(8*(i%4)));
    return true;
  }
  uint32_t monotonicMs()override{return now;}
  bool grantRead(const Auth::Profile&)override{++grants;granted=true;return true;}
  void revokeRead()override{++revokes;granted=false;}
};
struct Owner final:J1979UsbCampaign::Owner {
  explicit Owner(Security& security):security_(security){}
  Security& security_;bool started=false;unsigned starts=0,stops=0;
  InterfaceEvidence currentEvidence(const Auth::Profile&)override{
    InterfaceEvidence e;e.qualified=true;e.localConsent=security_.approved;
    e.authenticated=security_.granted;e.exactProfile=true;e.electricalStable=true;
    e.diagnosticAllowlistEnforced=true;return e;
  }
  bool currentHost()override{return security_.present&&security_.granted;}
  bool startReadChannel(const Auth::Profile&,const HostSessionArbiter::Lease&)override{
    ++starts;started=true;return true;
  }
  bool stopAndVerifyQuiescent()override{++stops;started=false;return true;}
};
struct Link final:J1979CanCampaign::ApplicationLink {
  explicit Link(Owner& o):owner(o){}
  Owner& owner;unsigned exchanges=0;
  bool active()override{return owner.started;}
  bool matches(uint32_t tx,uint32_t rx,bool ext)const override{return tx==0x7e0&&rx==0x7e8&&!ext;}
  uint32_t monotonicMs()override{return exchanges;}
  void exchange(const uint8_t*,size_t,uint32_t,J1979CanReadExchange::Result& out)override{
    ++exchanges;out.state=J1979CanReadExchange::State::Timeout;out.responseSize=0;out.elapsedMs=1;
  }
};
struct Writer final:J1979CampaignEvidence::Writer {
  size_t bytes=0;bool write(const uint8_t*,size_t n)override{bytes+=n;return true;}
};
struct Fixture {
  Security sec;Auth auth;Owner owner;Link link;Writer writer;HostSessionArbiter arbiter;
  J1979CanReadExchange::Result scratch;
  uint8_t hello[Auth::HelloSize]{},challenge[Auth::ChallengeSize]{},proof[Auth::ProofSize]{};
  Fixture():auth(sec),owner(sec),link(owner){
    hello[0]='C';hello[1]='U';hello[2]=1;hello[3]=1;
    hello[4]=0xe0;hello[5]=7;hello[8]=0xe8;hello[9]=7;
    hello[13]=0x10;hello[14]=0x27;std::memset(hello+17,'H',32);
  }
  bool begin(){
    if(!auth.hello(hello,sizeof(hello),challenge,sizeof(challenge)))return false;
    proof[0]='C';proof[1]='U';proof[2]=1;proof[3]=3;
    uint8_t transcript[Auth::TranscriptSize]{};transcript[0]=0x43;
    std::memcpy(transcript+1,hello+4,45);std::memcpy(transcript+46,challenge+4,32);
    return sec.hmac(transcript,sizeof(transcript),proof+4);
  }
  J1979UsbCampaign::Report execute(){
    return auth.execute(proof,sizeof(proof),arbiter,owner,link,writer,scratch);
  }
};
struct Channel final:J1979UsbService::Channel {
  explicit Channel(Fixture& f):fixture(f){}
  Fixture& fixture;std::vector<uint8_t> output;
  unsigned reads=0,closes=0,writes=0;
  bool truncate=false,disconnectAfterChallenge=false,failEvidence=false;
  bool present()override{return fixture.sec.present&&closes==0;}
  uint32_t monotonicMs()override{return fixture.sec.now;}
  bool readExact(uint8_t* out,size_t n,uint32_t)override{
    ++reads;
    if(reads==1&&n==Auth::HelloSize){std::memcpy(out,fixture.hello,n);return true;}
    if(reads!=2||n!=Auth::ProofSize||truncate)return false;
    out[0]='C';out[1]='U';out[2]=1;out[3]=3;
    uint8_t transcript[Auth::TranscriptSize]{};transcript[0]=0x43;
    std::memcpy(transcript+1,fixture.hello+4,45);std::memcpy(transcript+46,output.data()+4,32);
    return fixture.sec.hmac(transcript,sizeof(transcript),out+4);
  }
  bool writeExact(const uint8_t* in,size_t n,uint32_t)override{
    ++writes;
    if(failEvidence&&writes>1)return false;
    output.insert(output.end(),in,in+n);
    if(disconnectAfterChallenge&&writes==1)fixture.sec.present=false;
    return true;
  }
  void close()override{++closes;}
  J1979UsbCampaign::Report serve(){
    return J1979UsbService::serve(*this,fixture.sec,fixture.arbiter,fixture.owner,
      fixture.link,fixture.scratch);
  }
};
int main(){
  using O=J1979UsbCampaign::Outcome;
  Fixture good;assert(good.begin()&&good.owner.starts==0);
  assert(good.execute().outcome==O::Partial&&good.owner.starts==1&&good.owner.stops==1);
  assert(good.sec.grants==1&&!good.sec.granted&&good.writer.bytes>0);
  assert(good.execute().outcome==O::Denied&&good.owner.starts==1);
  Fixture bad;assert(bad.begin());bad.proof[4]^=1;
  assert(bad.execute().outcome==O::Denied&&bad.owner.starts==0&&bad.sec.grants==0);
  Fixture expired;assert(expired.begin());expired.sec.now=30000;
  assert(expired.execute().outcome==O::Denied&&expired.owner.starts==0);
  Fixture revoked;assert(revoked.begin());revoked.sec.approved=false;
  assert(revoked.execute().outcome==O::Denied&&revoked.owner.starts==0);
  Fixture disconnected;assert(disconnected.begin());disconnected.sec.present=false;
  assert(disconnected.execute().outcome==O::Denied&&disconnected.owner.starts==0);
  Fixture invalid;invalid.hello[8]=0xe9;
  assert(!invalid.begin()&&invalid.owner.starts==0);
  Fixture serviced;Channel channel(serviced);
  assert(channel.serve().outcome==O::Partial&&channel.reads==2&&channel.closes==1);
  assert(channel.output.size()>Auth::ChallengeSize&&serviced.owner.starts==1&&serviced.owner.stops==1);
  assert(!serviced.sec.granted);
  Fixture shortProof;Channel shortChannel(shortProof);shortChannel.truncate=true;
  assert(shortChannel.serve().outcome==O::Denied&&shortProof.owner.starts==0&&shortChannel.closes==1);
  Fixture gone;Channel goneChannel(gone);goneChannel.disconnectAfterChallenge=true;
  assert(goneChannel.serve().outcome==O::Denied&&gone.owner.starts==0&&goneChannel.closes==1);
  Fixture noOutput;Channel broken(noOutput);broken.failEvidence=true;
  assert(broken.serve().outcome==O::EvidenceLost&&noOutput.owner.starts==1&&noOutput.owner.stops==1);
  assert(noOutput.link.exchanges==0&&broken.closes==1&&!noOutput.sec.granted);
}
