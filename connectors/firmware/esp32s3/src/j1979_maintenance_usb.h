#pragma once
#include "../../shared/j1979_maintenance_journal.h"
#include "j1979_usb_service.h"

namespace cd {
// CU/4 is a separate one-shot authenticated maintenance protocol. It does
// not accept arbitrary ECU requests, and never falls back to CU/1..3.
// The local board must provide an independently authorized exact scope,
// qualified transport, durable journal and exclusive maintenance lease.
class J1979MaintenanceUsb {
 public:
  using Scope=J1979MaintenanceScope;
  using Channel=J1979UsbService::Channel;
  static constexpr size_t NonceSize=32,TagSize=32,HelloSize=4+Scope::WireSize+NonceSize;
  static constexpr size_t ChallengeSize=68,ProofSize=36,TranscriptSize=1+Scope::WireSize+2*NonceSize;
  static constexpr size_t ResultSize=12;
  class Security {
   public:
    virtual ~Security()=default;
    virtual bool present()=0;
    virtual bool approved(const Scope& scope)=0;
    virtual uint32_t monotonicMs()=0;
    virtual bool nonce(uint8_t* out,size_t n)=0;
    virtual bool hmac(const uint8_t* data,size_t n,uint8_t* tag)=0;
    virtual bool grantMaintenance(const Scope& scope)=0;
    virtual void revokeMaintenance()=0;
  };
  class Runner {
   public:
    virtual ~Runner()=default;
    // Execute only the locally approved one-shot scope; no caller-supplied
    // command bytes. A false return can still leave a durable journal.
    virtual void run(const Scope& scope)=0;
    // Read a stable original journal after physical shutdown. A journal
    // may be incomplete; no completed operation is implied by transfer.
    virtual bool journalSize(size_t& n)=0;
    virtual bool journalRead(size_t offset,uint8_t* out,size_t n)=0;
    virtual bool recoveryRequired()=0;
    virtual bool physicalStopVerified()=0;
  };
  enum class Outcome:uint8_t { Denied=0, Transferred=1, JournalUnavailable=2, TransferFailed=3 };
  struct Report {
    Outcome outcome=Outcome::Denied;
    bool credentialVerified=false,executionInvoked=false,recovery=false,physicalStop=false;
    size_t transferred=0;
  };
  static Report serve(Channel& channel,Security& security,Runner& runner){
    Report report;
    uint8_t hello[HelloSize]{},challenge[ChallengeSize]{},proof[ProofSize]{},transcript[TranscriptSize]{},tag[TagSize]{};
    Scope scope;
    bool granted=false;
    auto close=[&](){
      std::memset(transcript,0,sizeof(transcript));std::memset(tag,0,sizeof(tag));
      if(granted)security.revokeMaintenance();
      channel.close();
      return report;
    };
    auto current=[&](){return channel.present()&&security.present()&&security.approved(scope);};
    if(!channel.present()||!security.present()||!channel.readExact(hello,sizeof(hello),5000)||
       !header(hello,1)||!Scope::decode(hello+4,Scope::WireSize,scope)||!current())return close();
    std::memcpy(transcript+1,hello+4,Scope::WireSize+NonceSize);
    if(!nonzero(transcript+1+Scope::WireSize,NonceSize)||
       !security.nonce(transcript+1+Scope::WireSize+NonceSize,NonceSize)||
       !nonzero(transcript+1+Scope::WireSize+NonceSize,NonceSize)||!current())return close();
    transcript[0]='M';
    if(!security.hmac(transcript,sizeof(transcript),tag)||!current())return close();
    challenge[0]='C';challenge[1]='U';challenge[2]=4;challenge[3]=2;
    std::memcpy(challenge+4,transcript+1+Scope::WireSize+NonceSize,NonceSize);
    std::memcpy(challenge+36,tag,TagSize);
    const uint32_t issued=security.monotonicMs();
    if(!channel.writeExact(challenge,sizeof(challenge),1000)||!current()||
       !channel.readExact(proof,sizeof(proof),29000)||!header(proof,3)||!current()||
       uint32_t(security.monotonicMs()-issued)>=30000u)return close();
    transcript[0]='m';
    if(!security.hmac(transcript,sizeof(transcript),tag)||!sameTag(tag,proof+4)||!current()||
       uint32_t(security.monotonicMs()-issued)>=30000u)return close();
    report.credentialVerified=true;
    // Consume the grant before calling the runner. The local owner checks
    // presence and exact scope throughout execution independently.
    granted=security.grantMaintenance(scope);
    if(!granted||!current())return close();
    report.executionInvoked=true;
    runner.run(scope);
    report.recovery=runner.recoveryRequired();report.physicalStop=runner.physicalStopVerified();
    // Physical stop is required before reading/exporting the journal over
    // USB. Recovery may remain latched despite a stopped physical channel.
    if(!report.physicalStop||!channel.present()||!security.present()){
      report.outcome=Outcome::JournalUnavailable;return close();
    }
    size_t n=0;
    if(!runner.journalSize(n)||!n||n>J1979MaintenanceJournal::MaximumJournal){
      report.outcome=Outcome::JournalUnavailable;return close();
    }
    uint8_t result[ResultSize]={'C','U',4,4,0,0,0,0,0,0,0,0};
    result[4]=(report.recovery?1u:0u)|(report.physicalStop?2u:0u);
    for(unsigned i=0;i<4;++i)result[8+i]=uint8_t(uint32_t(n)>>(8*i));
    const uint32_t started=security.monotonicMs();
    auto available=[&](){
      // The operation lease may now be revoked or recovery latched. A
      // transfer is offline evidence only; require the same connection,
      // not an active diagnostic channel or a new maintenance approval.
      return channel.present()&&security.present()&&uint32_t(security.monotonicMs()-started)<15000u;
    };
    if(!available()||!channel.writeExact(result,sizeof(result),1000)){
      report.outcome=Outcome::TransferFailed;return close();
    }
    uint8_t buffer[128]{};
    while(report.transferred<n){
      const size_t count=n-report.transferred<sizeof(buffer)?n-report.transferred:sizeof(buffer);
      if(!available()||!runner.journalRead(report.transferred,buffer,count)||
         !available()||!channel.writeExact(buffer,count,1000)){
        report.outcome=Outcome::TransferFailed;return close();
      }
      report.transferred+=count;
    }
    size_t final=0;
    report.outcome=available()&&runner.journalSize(final)&&final==n?Outcome::Transferred:Outcome::TransferFailed;
    return close();
  }
 private:
  static bool header(const uint8_t* data,uint8_t op){return data[0]=='C'&&data[1]=='U'&&data[2]==4&&data[3]==op;}
  static bool nonzero(const uint8_t* p,size_t n){uint8_t value=0;for(size_t i=0;i<n;++i)value|=p[i];return value!=0;}
  static bool sameTag(const uint8_t* a,const uint8_t* b){
    volatile uint8_t value=0;for(size_t i=0;i<TagSize;++i)value=uint8_t(value|(a[i]^b[i]));
    return value==0;
  }
};
}
