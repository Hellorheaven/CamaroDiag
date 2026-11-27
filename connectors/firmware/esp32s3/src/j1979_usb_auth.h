#pragma once
#include "j1979_usb_campaign.h"
#include "binary_frame.h"

namespace cd {
// Dedicated authenticated read-only USB command channel CU/1. Never parsed
// by discovery. HMAC proof binds both nonces and the exact campaign profile.
// Authentication is not electrical qualification or operator consent.
class J1979UsbSecurity {
 public:
  using Profile=J1979UsbCampaign::Profile;
   public:
    virtual ~J1979UsbSecurity()=default;
    // Bound to one provisioned host credential and unchanged physical USB
    // connection; authorization/revocation must not be supplied by packets.
    virtual bool connectionPresent()=0;
    virtual bool localApproval(const Profile& profile)=0;
    virtual bool randomNonce(uint8_t* nonce,size_t size)=0;
    virtual bool hmac(const uint8_t* data,size_t size,uint8_t* tag)=0;
    virtual uint32_t monotonicMs()=0;
    virtual bool grantRead(const Profile& profile)=0;
    virtual void revokeRead()=0;
  };

template<bool Freeze> class J1979UsbAuthSessionT {
 public:
  using Profile=J1979UsbCampaign::Profile;
  static constexpr size_t NonceSize=32,TagSize=32,ProfileSize=13+(Freeze?J1979FreezeScope::WireSize:0);
  static constexpr size_t HelloSize=4+ProfileSize+NonceSize;
  static constexpr size_t ChallengeSize=4+NonceSize+TagSize;
  static constexpr size_t ProofSize=4+TagSize;
  static constexpr size_t TranscriptSize=1+ProfileSize+2*NonceSize;
  using Security=J1979UsbSecurity;
  enum class State { Fresh, Challenged, Closed };
  explicit J1979UsbAuthSessionT(Security& security):security_(security){}
  J1979UsbAuthSessionT(const J1979UsbAuthSessionT&)=delete;
  J1979UsbAuthSessionT& operator=(const J1979UsbAuthSessionT&)=delete;
  State state()const{return state_;}
  bool hello(const uint8_t* data,size_t size,uint8_t* reply,size_t capacity){
    if(state_!=State::Fresh||!data||!reply||size!=HelloSize||
       capacity<ChallengeSize||!header(data,1)||!security_.connectionPresent())return close();
    const auto* profile=data+4;
    profile_.tx=le32(profile);profile_.rx=le32(profile+4);
    profile_.extended=profile[8]==1;profile_.budgetMs=le32(profile+9);
    profile_.freeze=Freeze;
    if(Freeze&&!J1979FreezeScope::decode(profile+13,J1979FreezeScope::WireSize,
        profile_.freezeScope))return close();
    if(profile[8]>1||!J1979CanReadSession::physicalPair(profile_.tx,profile_.rx,profile_.extended)||
       profile_.budgetMs<1||profile_.budgetMs>600000u||
       !security_.localApproval(profile_))return close();
    std::memcpy(transcript_+1,profile,ProfileSize+NonceSize);
    if(!nonzero(transcript_+1+ProfileSize,NonceSize)||
       !security_.randomNonce(transcript_+1+ProfileSize+NonceSize,NonceSize)||
       !nonzero(transcript_+1+ProfileSize+NonceSize,NonceSize))return close();
    transcript_[0]=Freeze?0x46:0x53; // server proof, distinct from client proof
    uint8_t tag[TagSize]{};
    if(!security_.hmac(transcript_,TranscriptSize,tag)||!security_.connectionPresent())return close();
    reply[0]='C';reply[1]='U';reply[2]=Freeze?2:1;reply[3]=2;
    std::memcpy(reply+4,transcript_+1+ProfileSize+NonceSize,NonceSize);
    std::memcpy(reply+4+NonceSize,tag,TagSize);
    issued_=security_.monotonicMs();state_=State::Challenged;return true;
  }
  bool poll(){
    if(state_!=State::Challenged)return false;
    if(!security_.connectionPresent()||!security_.localApproval(profile_)||
       uint32_t(security_.monotonicMs()-issued_)>=30000u)return close();
    return true;
  }
  J1979UsbCampaign::Report execute(const uint8_t* data,size_t size,
      HostSessionArbiter& arbiter,J1979UsbCampaign::Owner& owner,
      J1979CanCampaign::ApplicationLink& link,J1979CampaignEvidence::Writer& writer,
      J1979CanReadExchange::Result& scratch){
    J1979UsbCampaign::Report report;
    if(!poll()||!data||size!=ProofSize||!header(data,3)){close();return report;}
    // Consume challenge before any operation; no retry or second command.
    state_=State::Closed;
    transcript_[0]=Freeze?0x66:0x43;
    uint8_t expected[TagSize]{};
    if(!security_.hmac(transcript_,TranscriptSize,expected)||
       !equalTag(expected,data+4)||!security_.connectionPresent()||
       !security_.localApproval(profile_)||
       uint32_t(security_.monotonicMs()-issued_)>=30000u||
       !security_.grantRead(profile_)){close();return report;}
    report=Freeze?J1979UsbCampaign::runFreezeApplication(arbiter,owner,link,writer,profile_,scratch):
      J1979UsbCampaign::runApplication(arbiter,owner,link,writer,profile_,scratch);
    close();return report;
  }
  bool close(){
    state_=State::Closed;std::memset(transcript_,0,sizeof(transcript_));
    security_.revokeRead();return false;
  }
 private:
  static bool header(const uint8_t* p,uint8_t op){return p[0]=='C'&&p[1]=='U'&&p[2]==(Freeze?2:1)&&p[3]==op;}
  static bool nonzero(const uint8_t* p,size_t n){uint8_t x=0;for(size_t i=0;i<n;++i)x|=p[i];return x!=0;}
  static bool equalTag(const uint8_t* a,const uint8_t* b){
    volatile uint8_t difference=0;
    for(size_t i=0;i<TagSize;++i)difference=uint8_t(difference|(a[i]^b[i]));
    return difference==0;
  }
  Security& security_;Profile profile_;
  uint8_t transcript_[TranscriptSize]{};
  uint32_t issued_=0;State state_=State::Fresh;
};
using J1979UsbAuthSession=J1979UsbAuthSessionT<false>;
using J1979FreezeUsbAuthSession=J1979UsbAuthSessionT<true>;
}
