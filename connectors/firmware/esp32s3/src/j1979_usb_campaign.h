#pragma once
#include "host_session_arbiter.h"
#include "../../shared/j1979_campaign_evidence.h"

namespace cd {
// Integration boundary for a qualified native USB owner, not discovery.
// The caller must provide authenticated host identity and local electrical
// evidence from the board, never from the incoming command payload.
class J1979UsbCampaign {
 public:
  struct Profile {
    uint32_t tx=0,rx=0,budgetMs=0;
    bool extended=false;
  };
  class Owner {
   public:
    virtual ~Owner()=default;
    virtual InterfaceEvidence currentEvidence(const Profile& profile)=0;
    // True only if the authorized host and local operator are still present.
    virtual bool currentHost()=0;
    // Stop and verify a quiescent channel, including pending TX. No auto-recovery.
    virtual bool stopAndVerifyQuiescent()=0;
  };
  enum class Outcome { Denied, Complete, Partial, EvidenceLost, QuiescenceUnverified };
  struct Report {
    Outcome outcome=Outcome::Denied;
    J1979CanCampaign::Summary campaign;
  };

  static Report run(HostSessionArbiter& arbiter,Owner& owner,
      J1979CanReadLink& hardware,J1979CampaignEvidence::Writer& writer,
      const Profile& profile,J1979CanReadExchange::Result& scratch){
    Report report;
    if(!J1979CanReadSession::physicalPair(profile.tx,profile.rx,profile.extended)||
       profile.budgetMs<1||profile.budgetMs>600000u||!owner.currentHost())return report;
    HostSessionArbiter::Lease lease;
    if(!arbiter.acquire(HostInterface::NativeUsb,owner.currentEvidence(profile),lease))return report;
    class GuardedLink final:public J1979CanReadLink {
     public:
      GuardedLink(HostSessionArbiter& a,Owner& o,J1979CanReadLink& h,
          const Profile& p,const HostSessionArbiter::Lease& l):a_(a),o_(o),h_(h),p_(p),l_(l){}
      bool admitted(){return o_.currentHost()&&a_.allowsRead(l_,o_.currentEvidence(p_));}
      bool active()override{return admitted()&&h_.active();}
      uint32_t monotonicMs()override{return h_.monotonicMs();}
      bool discardReceive()override{return active()&&h_.discardReceive()&&active();}
      bool send(const Frame& frame,uint32_t timeout)override{
        return active()&&h_.send(frame,timeout)&&active();
      }
      Receive receive(Frame& frame,uint32_t timeout)override{
        if(!active())return Receive::Fault;
        const auto result=h_.receive(frame,timeout);
        return active()?result:Receive::Fault;
      }
     private:
      HostSessionArbiter& a_;Owner& o_;J1979CanReadLink& h_;
      const Profile& p_;const HostSessionArbiter::Lease& l_;
    } link(arbiter,owner,hardware,profile,lease);
    class GuardedWriter final:public J1979CampaignEvidence::Writer {
     public:
      GuardedWriter(GuardedLink& l,J1979CampaignEvidence::Writer& w,
          HostSessionArbiter& a,Owner& o,const Profile& p,const HostSessionArbiter::Lease& lease)
        :l_(l),w_(w),a_(a),o_(o),p_(p),lease_(lease){}
      void afterVerifiedStop(){finalizing_=true;}
      bool permitted(){
        if(!finalizing_)return l_.admitted();
        // Final record is offline evidence, not a diagnostic operation.
        // A stopped bus is no longer required to be electrically active;
        // the same authorized host and unreleased lease remain mandatory.
        if(!o_.currentHost()||!a_.owns(lease_)||a_.recoveryRequired())return false;
        const auto e=o_.currentEvidence(p_);
        return e.qualified&&e.localConsent&&e.authenticated&&e.exactProfile&&
          !e.recoveryPending&&e.diagnosticAllowlistEnforced;
      }
      bool write(const uint8_t* data,size_t size)override{
        return permitted()&&w_.write(data,size)&&permitted();
      }
     private:
      GuardedLink& l_;J1979CampaignEvidence::Writer& w_;
      HostSessionArbiter& a_;Owner& o_;const Profile& p_;
      const HostSessionArbiter::Lease& lease_;
      bool finalizing_=false;
    } output(link,writer,arbiter,owner,profile,lease);

    J1979CampaignEvidence stream(output);
    const bool started=link.active()&&stream.begin(profile.tx,profile.rx,profile.extended,profile.budgetMs);
    if(started)report.campaign=J1979CanCampaign::run(link,profile.tx,profile.rx,profile.extended,
        profile.budgetMs,stream,scratch);
    // Never publish a complete capture while physical shutdown is unverified.
    // The readable prefix remains available but has no final summary.
    if(!owner.stopAndVerifyQuiescent()){
      arbiter.latchRecovery();report.outcome=Outcome::QuiescenceUnverified;return report;
    }
    output.afterVerifiedStop();
    if(!started||!output.permitted()||!stream.finish(report.campaign))
      report.outcome=Outcome::EvidenceLost;
    else report.outcome=report.campaign.end==J1979CanCampaign::End::Complete?
      Outcome::Complete:Outcome::Partial;
    if(!arbiter.release(lease)){
      report.outcome=Outcome::Partial;
    }
    return report;
  }
};
}
