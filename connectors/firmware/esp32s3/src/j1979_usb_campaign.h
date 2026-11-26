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
    // Preflight evidence describes a qualified electrical configuration,
    // including stable supply, without requiring an enabled CAN channel.
    // Only this call may start the channel, under the exclusive lease.
    // A false return may still mean partial activation: stop is mandatory.
    virtual bool startReadChannel(const Profile& profile,
        const HostSessionArbiter::Lease& lease)=0;
    // Stop and verify a quiescent channel, including pending TX. No auto-recovery.
    virtual bool stopAndVerifyQuiescent()=0;
  };
  enum class Outcome { Denied, Complete, Partial, EvidenceLost, QuiescenceUnverified, StartFailed };
  struct Report {
    Outcome outcome=Outcome::Denied;
    J1979CanCampaign::Summary campaign;
  };

  static Report run(HostSessionArbiter& arbiter,Owner& owner,
      J1979CanReadLink& hardware,J1979CampaignEvidence::Writer& writer,
      const Profile& profile,J1979CanReadExchange::Result& scratch){
    return runImpl(arbiter,owner,&hardware,nullptr,writer,profile,scratch);
  }
  // Interprocessor CI/2 client: its SPI port and STM32 endpoint must both
  // independently enforce live admission throughout the application exchange.
  static Report runApplication(HostSessionArbiter& arbiter,Owner& owner,
      J1979CanCampaign::ApplicationLink& hardware,J1979CampaignEvidence::Writer& writer,
      const Profile& profile,J1979CanReadExchange::Result& scratch){
    if(!hardware.matches(profile.tx,profile.rx,profile.extended))return Report{};
    return runImpl(arbiter,owner,nullptr,&hardware,writer,profile,scratch);
  }
 private:
  static Report runImpl(HostSessionArbiter& arbiter,Owner& owner,
      J1979CanReadLink* hardware,J1979CanCampaign::ApplicationLink* application,
      J1979CampaignEvidence::Writer& writer,const Profile& profile,
      J1979CanReadExchange::Result& scratch){
    Report report;
    if(!J1979CanReadSession::physicalPair(profile.tx,profile.rx,profile.extended)||
       profile.budgetMs<1||profile.budgetMs>600000u||!owner.currentHost())return report;
    HostSessionArbiter::Lease lease;
    const auto admission=owner.currentEvidence(profile);
    if(admission.recoveryPending){arbiter.latchRecovery();return report;}
    if(!arbiter.acquire(HostInterface::NativeUsb,admission,lease))return report;
    class Admission {
     public:
      Admission(HostSessionArbiter& a,Owner& o,const Profile& p,
          const HostSessionArbiter::Lease& l):a_(a),o_(o),p_(p),l_(l){}
      bool admitted(){return o_.currentHost()&&a_.allowsRead(l_,o_.currentEvidence(p_));}
     private:
      HostSessionArbiter& a_;Owner& o_;const Profile& p_;
      const HostSessionArbiter::Lease& l_;
    } guard(arbiter,owner,profile,lease);
    class GuardedLink final:public J1979CanReadLink {
     public:
      GuardedLink(Admission& a,J1979CanReadLink* h):a_(a),h_(h){}
      bool active()override{return h_&&a_.admitted()&&h_->active();}
      uint32_t monotonicMs()override{return h_?h_->monotonicMs():0;}
      bool discardReceive()override{return active()&&h_->discardReceive()&&active();}
      bool send(const Frame& frame,uint32_t timeout)override{
        return active()&&h_->send(frame,timeout)&&active();
      }
      Receive receive(Frame& frame,uint32_t timeout)override{
        if(!active())return Receive::Fault;
        const auto result=h_->receive(frame,timeout);
        return active()?result:Receive::Fault;
      }
     private:
      Admission& a_;J1979CanReadLink* h_;
    } link(guard,hardware);
    class GuardedApplication final:public J1979CanCampaign::ApplicationLink {
     public:
      GuardedApplication(Admission& a,J1979CanCampaign::ApplicationLink* h):a_(a),h_(h){}
      bool active()override{return h_&&a_.admitted()&&h_->active();}
      bool matches(uint32_t tx,uint32_t rx,bool ext)const override{
        return h_&&h_->matches(tx,rx,ext);
      }
      uint32_t monotonicMs()override{return h_?h_->monotonicMs():0;}
      void exchange(const uint8_t* req,size_t size,uint32_t timeout,
          J1979CanReadExchange::Result& result)override{
        if(active()){
          h_->exchange(req,size,timeout,result);
          if(active())return;
        }
        result.state=J1979CanReadExchange::State::Fault;result.responseSize=0;
      }
     private:
      Admission& a_;J1979CanCampaign::ApplicationLink* h_;
    } remote(guard,application);
    class GuardedWriter final:public J1979CampaignEvidence::Writer {
     public:
      GuardedWriter(Admission& l,J1979CampaignEvidence::Writer& w,
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
      Admission& l_;J1979CampaignEvidence::Writer& w_;
      HostSessionArbiter& a_;Owner& o_;const Profile& p_;
      const HostSessionArbiter::Lease& lease_;
      bool finalizing_=false;
    } output(guard,writer,arbiter,owner,profile,lease);

    J1979CampaignEvidence stream(output);
    // Never start an electrical channel before exclusive ownership. Recheck
    // authorization immediately before and after the hardware start.
    const bool channelStarted=guard.admitted()&&owner.startReadChannel(profile,lease);
    const bool started=channelStarted&&(application?remote.active():link.active())&&
      stream.begin(profile.tx,profile.rx,profile.extended,profile.budgetMs);
    if(started){
      report.campaign=application?
        J1979CanCampaign::runApplication(remote,profile.tx,profile.rx,profile.extended,
          profile.budgetMs,stream,scratch):
        J1979CanCampaign::run(link,profile.tx,profile.rx,profile.extended,
          profile.budgetMs,stream,scratch);
    }
    // Never publish a complete capture while physical shutdown is unverified.
    // The readable prefix remains available but has no final summary.
    if(!owner.stopAndVerifyQuiescent()){
      arbiter.latchRecovery();report.outcome=Outcome::QuiescenceUnverified;return report;
    }
    // Physical quiescence is not the same as recovery clearance. If the
    // board owner reports an unresolved recovery state, keep the shared
    // arbiter latched so a different host cannot acquire a fresh lease.
    if(owner.currentEvidence(profile).recoveryPending){
      arbiter.latchRecovery();report.outcome=Outcome::EvidenceLost;return report;
    }
    output.afterVerifiedStop();
    if(!channelStarted)report.outcome=Outcome::StartFailed;
    else if(!started||!output.permitted()||!stream.finish(report.campaign))
      report.outcome=Outcome::EvidenceLost;
    else report.outcome=report.campaign.end==J1979CanCampaign::End::Complete?
      Outcome::Complete:Outcome::Partial;
    if(owner.currentEvidence(profile).recoveryPending){
      arbiter.latchRecovery();report.outcome=Outcome::EvidenceLost;return report;
    }
    if(!arbiter.release(lease)){
      report.outcome=Outcome::Partial;
    }
    return report;
  }
};
}
