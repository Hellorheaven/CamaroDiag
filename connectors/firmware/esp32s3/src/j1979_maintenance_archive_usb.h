#pragma once
#include "../../shared/j1979_maintenance_archive.h"
#include "j1979_usb_service.h"

namespace cd {
// CU/5 is read-only export of an existing maintenance journal. It never
// calls the CU/4 runner, starts CAN, or releases a recovery latch.
class J1979MaintenanceArchiveUsb {
 public:
  using Scope=J1979MaintenanceScope;
  using Archive=J1979MaintenanceArchive;
  using Channel=J1979UsbService::Channel;
  static constexpr size_t HelloSize=67,ChallengeSize=68,ProofSize=36,TranscriptSize=96;
  static constexpr size_t HeaderSize=12,NonceSize=32,TagSize=32;
  class Security {
   public:
    virtual ~Security()=default;
    // A separate approval for read-only export while TX is inhibited.
    // Unlike CU/4, an unresolved journal does not itself deny export.
    virtual bool approved(const Scope& scope)=0;
    virtual bool present()=0;
    virtual bool nonce(uint8_t* out,size_t n)=0;
    virtual bool hmac(const uint8_t* data,size_t n,uint8_t* tag)=0;
    virtual uint32_t monotonicMs()=0;
  };
  struct Report {
    bool authenticated=false,sourceOpened=false,complete=false;
    size_t bytes=0;
    Archive::Info info{};
  };
  static Report serve(Channel& channel,Security& security,Archive::Source& source){
    Report result;
    Scope scope;
    uint8_t hello[HelloSize]{},challenge[ChallengeSize]{},proof[ProofSize]{},transcript[TranscriptSize]{},tag[TagSize]{};
    auto close=[&](){
      source.close();
      volatile uint8_t* a=transcript;for(size_t i=0;i<sizeof(transcript);++i)a[i]=0;
      volatile uint8_t* b=tag;for(size_t i=0;i<sizeof(tag);++i)b[i]=0;
      channel.close();return result;
    };
    auto current=[&](){return channel.present()&&security.present()&&security.approved(scope);};
    if(!channel.present()||!security.present()||!channel.readExact(hello,sizeof(hello),5000)||
       !header(hello,1)||!Scope::decode(hello+4,Scope::WireSize,scope)||!current())return close();
    std::memcpy(transcript+1,hello+4,Scope::WireSize+NonceSize);
    if(!nonzero(transcript+32,NonceSize)||!security.nonce(transcript+64,NonceSize)||
       !nonzero(transcript+64,NonceSize)||!current())return close();
    transcript[0]='R';
    if(!security.hmac(transcript,sizeof(transcript),tag)||!current())return close();
    challenge[0]='C';challenge[1]='U';challenge[2]=5;challenge[3]=2;
    std::memcpy(challenge+4,transcript+64,NonceSize);
    std::memcpy(challenge+36,tag,TagSize);
    const uint32_t issued=security.monotonicMs();
    if(!channel.writeExact(challenge,sizeof(challenge),1000)||!current()||
       !channel.readExact(proof,sizeof(proof),29000)||!header(proof,3)||!current()||
       uint32_t(security.monotonicMs()-issued)>=30000u)return close();
    transcript[0]='r';
    if(!security.hmac(transcript,sizeof(transcript),tag)||!sameTag(tag,proof+4)||!current()||
       uint32_t(security.monotonicMs()-issued)>=30000u)return close();
    result.authenticated=true;
    if(!source.open(scope,result.info)||!result.info.valid()||!current()||!source.current())return close();
    result.sourceOpened=true;
    uint8_t out[HeaderSize]={'C','U',5,4,0,0,0,0,0,0,0,0};
    out[4]=(result.info.terminal?1:0)|(result.info.damagedTail?2:0)|(result.info.recoveryRequired?4:0);
    out[5]=result.info.records;
    for(unsigned i=0;i<4;++i)out[8+i]=uint8_t(result.info.bytes>>(8*i));
    const uint32_t start=security.monotonicMs();
    auto admitted=[&](){return current()&&source.current()&&uint32_t(security.monotonicMs()-start)<15000u;};
    if(!admitted()||!channel.writeExact(out,sizeof(out),1000))return close();
    uint8_t buffer[256]{};
    while(result.bytes<result.info.bytes){
      const size_t n=result.info.bytes-result.bytes<sizeof(buffer)?result.info.bytes-result.bytes:sizeof(buffer);
      if(!admitted()||!source.read(result.bytes,buffer,n)||!admitted()||!channel.writeExact(buffer,n,1000))return close();
      result.bytes+=n;
    }
    result.complete=admitted()&&source.finish();return close();
  }
 private:
  static bool header(const uint8_t* p,uint8_t op){return p[0]=='C'&&p[1]=='U'&&p[2]==5&&p[3]==op;}
  static bool nonzero(const uint8_t* p,size_t n){uint8_t b=0;for(size_t i=0;i<n;++i)b|=p[i];return b!=0;}
  static bool sameTag(const uint8_t* a,const uint8_t* b){
    volatile uint8_t d=0;for(size_t i=0;i<TagSize;++i)d=uint8_t(d|(a[i]^b[i]));return d==0;
  }
};
}
