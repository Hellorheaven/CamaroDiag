#pragma once
#include "j1979_usb_auth.h"
#include "j1979_read_guard.h"

namespace cd {
// One local operator-approved session, bound to an immutable connection and
// credential generation. All evidence is acquired locally, not from USB.
// The board implementation owns physical interlock/STM32 start and stop;
// this class owns the authorization, exclusive lease, and fail-closed policy.
class J1979LocalUsbOwner final:public J1979UsbAuthSession::Security,
    public J1979UsbCampaign::Owner {
 public:
  using Profile=J1979UsbCampaign::Profile;
  struct Snapshot {
    uint64_t connection=0,credential=0,approval=0;
    bool qualified=false,consented=false,stable=false,allowlisted=false,recovery=false;
    bool entropyReady=false;
  };
  class Board {
   public:
    virtual ~Board()=default;
    virtual Snapshot snapshot(const Profile& profile)=0;
    virtual uint32_t monotonicMs()=0;
    // Credential provider must use the pinned generation; no implicit
    // default key or reprovisioning during a connection.
    virtual bool hmac(uint64_t generation,const uint8_t* data,size_t n,uint8_t* tag)=0;
    virtual bool randomNonce(uint8_t* data,size_t n)=0;
    virtual bool start(const Profile& profile,uint64_t leaseGeneration)=0;
    virtual bool stopAndVerify()=0;
    virtual void latchRecovery()=0;
  };
  J1979LocalUsbOwner(Board& board,HostSessionArbiter& arbiter,J1979ReadGuard& durable,
      const Profile& approved)
    :board_(board),arbiter_(arbiter),durable_(durable),profile_(approved),initial_(board.snapshot(approved)){
    if(!durable_.inspect()){board_.latchRecovery();arbiter_.latchRecovery();}
  }
  J1979LocalUsbOwner(const J1979LocalUsbOwner&)=delete;
  J1979LocalUsbOwner& operator=(const J1979LocalUsbOwner&)=delete;
  bool connectionPresent()override{
    const auto s=board_.snapshot(profile_);
    return !revoked_&&same(s);
  }
  bool localApproval(const Profile& p)override{
    const auto s=board_.snapshot(profile_);
    return !revoked_&&matches(p)&&same(s)&&s.qualified&&s.consented&&
      s.stable&&s.allowlisted&&!s.recovery&&!arbiter_.recoveryRequired();
  }
  bool randomNonce(uint8_t* data,size_t n)override{
    if(!data||n!=J1979UsbAuthSession::NonceSize||!localApproval(profile_)||
       !board_.snapshot(profile_).entropyReady)return false;
    return board_.randomNonce(data,n)&&localApproval(profile_)&&board_.snapshot(profile_).entropyReady;
  }
  bool hmac(const uint8_t* data,size_t n,uint8_t* tag)override{
    return data&&tag&&n==J1979UsbAuthSession::TranscriptSize&&localApproval(profile_)&&
      board_.hmac(initial_.credential,data,n,tag)&&localApproval(profile_);
  }
  uint32_t monotonicMs()override{return board_.monotonicMs();}
  bool grantRead(const Profile& p)override{
    if(granted_||!localApproval(p))return false;
    granted_=true;return true;
  }
  void revokeRead()override{
    granted_=false;revoked_=true;
    if(attempted_&&!stopped_)stopAndVerifyQuiescent();
  }
  InterfaceEvidence currentEvidence(const Profile& p)override{
    const auto s=board_.snapshot(profile_);
    const bool bound=!revoked_&&same(s);
    InterfaceEvidence e;
    e.qualified=bound&&s.qualified;e.localConsent=bound&&s.consented;
    e.authenticated=bound&&granted_;e.exactProfile=matches(p);
    e.electricalStable=s.stable;e.diagnosticAllowlistEnforced=s.allowlisted;
    e.recoveryPending=s.recovery||arbiter_.recoveryRequired()||failedStop_;
    return e;
  }
  bool currentHost()override{return granted_&&connectionPresent();}
  bool startReadChannel(const Profile& p,const HostSessionArbiter::Lease& lease)override{
    if(attempted_||!granted_||lease.mode!=HostInterface::NativeUsb||
       !localApproval(p)||!arbiter_.allowsRead(lease,currentEvidence(p)))return false;
    if(!durable_.begin()){
      failedStop_=true;board_.latchRecovery();arbiter_.latchRecovery();return false;
    }
    attempted_=true;
    return board_.start(p,lease.generation)&&localApproval(p)&&
      arbiter_.allowsRead(lease,currentEvidence(p));
  }
  bool stopAndVerifyQuiescent()override{
    if(failedStop_)return false;
    if(stopped_)return true;
    // Never stop a different owner's channel if this session never tried
    // to start. A partially failed start still requires a verified stop.
    if(!attempted_)return true;
    if(!board_.stopAndVerify()||!durable_.finishVerified()){
      durable_.uncertain();failedStop_=true;board_.latchRecovery();arbiter_.latchRecovery();return false;
    }
    stopped_=true;return true;
  }
  bool spiAdmitted(){
    return attempted_&&!stopped_&&!failedStop_&&currentHost()&&localApproval(profile_);
  }
  void transportFault(){
    if(attempted_&&!stopped_)stopAndVerifyQuiescent();
  }
 private:
  bool matches(const Profile& p)const{
    return p.tx==profile_.tx&&p.rx==profile_.rx&&p.extended==profile_.extended&&
      p.budgetMs==profile_.budgetMs;
  }
  bool same(const Snapshot& s)const{
    return initial_.connection&&initial_.credential&&initial_.approval&&
      s.connection==initial_.connection&&s.credential==initial_.credential&&
      s.approval==initial_.approval;
  }
  Board& board_;HostSessionArbiter& arbiter_;J1979ReadGuard& durable_;
  const Profile profile_;const Snapshot initial_;
  bool granted_=false,revoked_=false,attempted_=false,stopped_=false,failedStop_=false;
};
}
