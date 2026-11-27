#pragma once
#include "j1979_usb_auth.h"

namespace cd {
// One already selected, exclusively owned native USB connection. Transport
// reads/writes must be bounded and never reset, retry or switch protocols.
class J1979UsbService {
 public:
  using Auth=J1979UsbAuthSession;
  class Channel {
   public:
    virtual ~Channel()=default;
    virtual bool present()=0;
    virtual uint32_t monotonicMs()=0;
    virtual bool readExact(uint8_t* data,size_t size,uint32_t timeout)=0;
    virtual bool writeExact(const uint8_t* data,size_t size,uint32_t timeout)=0;
    virtual void close()=0;
  };
  static J1979UsbCampaign::Report serve(Channel& channel,Auth::Security& security,
      HostSessionArbiter& arbiter,J1979UsbCampaign::Owner& owner,
      J1979CanCampaign::ApplicationLink& link,J1979CanReadExchange::Result& scratch){
    return serveTyped<Auth>(channel,security,arbiter,owner,link,scratch);
  }
  static J1979UsbCampaign::Report serveFreeze(Channel& channel,Auth::Security& security,
      HostSessionArbiter& arbiter,J1979UsbCampaign::Owner& owner,
      J1979CanCampaign::ApplicationLink& link,J1979CanReadExchange::Result& scratch){
    return serveTyped<J1979FreezeUsbAuthSession>(channel,security,arbiter,owner,link,scratch);
  }
 private:
  template<class Session> static J1979UsbCampaign::Report serveTyped(Channel& channel,Auth::Security& security,
      HostSessionArbiter& arbiter,J1979UsbCampaign::Owner& owner,
      J1979CanCampaign::ApplicationLink& link,J1979CanReadExchange::Result& scratch){
    Session auth(security);
    J1979UsbCampaign::Report report;
    uint8_t hello[Session::HelloSize]{},challenge[Session::ChallengeSize]{},proof[Session::ProofSize]{};
    // No fallback to discovery after malformed or unauthorized CU/1.
    if(!channel.present()||!channel.readExact(hello,sizeof(hello),5000)||
       !auth.hello(hello,sizeof(hello),challenge,sizeof(challenge))||
       !channel.present()||!channel.writeExact(challenge,sizeof(challenge),1000)||
       !channel.readExact(proof,sizeof(proof),29000)||!channel.present()){
      auth.close();channel.close();return report;
    }
    class Output final:public J1979CampaignEvidence::Writer {
     public:
      explicit Output(Channel& channel):channel_(channel){}
      bool write(const uint8_t* data,size_t size)override{
        return channel_.present()&&channel_.writeExact(data,size,1000)&&channel_.present();
      }
     private:
      Channel& channel_;
    } output(channel);
    report=auth.execute(proof,sizeof(proof),arbiter,owner,link,output,scratch);
    channel.close();
    return report;
  }
};
}
