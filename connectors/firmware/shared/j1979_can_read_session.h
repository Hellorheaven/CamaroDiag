#pragma once
#include <cstddef>
#include <cstdint>
#include <cstring>
#include "isotp_observer.h"
#include "j1979_read_evidence.h"

namespace cd {
// Hardware-independent, single physical classic-CAN read session.
// The board transport owns transceiver safety, bus configuration and frame
// transmission. This class does not enable GPIO or access hardware.
class J1979CanReadSession {
 public:
  enum class State { Idle, Waiting, Positive, Negative, Timeout, Fault, Raw };
  struct Frame {
    uint32_t id=0;
    bool extended=false;
    uint8_t length=0;
    uint8_t data[8]{};
  };

  J1979CanReadSession(uint32_t requestId,uint32_t responseId,bool extended,
      const J1979FreezeScope* freeze=nullptr,const J1979LiveScope* live=nullptr)
    :tx_(requestId),rx_(responseId),extended_(extended),
     observer_(responseId,extended),valid_(physicalPair(requestId,responseId,extended)),
     scoped_(freeze!=nullptr),scope_(freeze?*freeze:J1979FreezeScope{}),
     liveScoped_(live!=nullptr),liveScope_(live?*live:J1979LiveScope{}){
    if((scoped_&&!scope_.valid())||(liveScoped_&&!liveScope_.valid())||(scoped_&&liveScoped_))valid_=false;
  }

  static bool physicalPair(uint32_t tx,uint32_t rx,bool ext){
    if(!ext)return tx>=0x7e0u&&tx<=0x7e7u&&rx==tx+8u;
    if((tx&0x1fff00ffu)!=0x18da00f1u||tx>0x1fffffffu)return false;
    uint32_t source=(tx>>8)&0xffu;
    return source<0xf0u&&rx==(0x18daf100u|source);
  }

  // Each session represents one request. No automatic resend.
  bool begin(const uint8_t* request,size_t size,uint32_t now,uint32_t timeout,Frame& outgoing){
    if(state_!=State::Idle||!valid_||timeout<1||timeout>4000u||
       !(scoped_?scope_.allows(request,size):liveScoped_?liveScope_.allows(request,size):
         J1979ReadEvidence::readRequest(J1979ReadEvidence::Variant::ClassicCan,request,size))||
       size>7)return false;
    std::memcpy(request_,request,size);
    requestSize_=size;started_=now;timeout_=timeout;
    outgoing=Frame{};outgoing.id=tx_;outgoing.extended=extended_;outgoing.length=8;
    outgoing.data[0]=uint8_t(size);
    std::memcpy(outgoing.data+1,request,size);
    state_=State::Waiting;
    return true;
  }

  // Feed only hardware-validated data frames; ignore other responders.
  // A flow control frame is returned only for a valid first frame from
  // the selected ECU. The caller must transmit it or abort the session.
  State feed(const Frame& incoming,uint32_t now,Frame& flow,bool& flowRequired){
    flowRequired=false;
    if(poll(now)!=State::Waiting)return state_;
    if(incoming.id!=rx_||incoming.extended!=extended_)return state_;
    if(incoming.length<1||incoming.length>8)return fail();
    const uint8_t type=incoming.data[0]>>4;
    const auto result=observer_.feed(incoming.id,incoming.extended,incoming.data,incoming.length,now);
    if(result==IsoTpObserver::Result::Fault)return fail();
    if(result==IsoTpObserver::Result::Pending&&type==1){
      flow=Frame{};flow.id=tx_;flow.extended=extended_;flow.length=8;
      flow.data[0]=0x30; // CTS, no block segmentation; transport owns timing.
      flowRequired=true;
    }
    if(result!=IsoTpObserver::Result::Complete)return state_;
    const auto evidence=scoped_?J1979ReadEvidence::classifyFreeze(scope_,
      request_,requestSize_,observer_.message(),observer_.size()):liveScoped_?
      J1979ReadEvidence::classifyLive(liveScope_,request_,requestSize_,observer_.message(),observer_.size()):
      J1979ReadEvidence::classify(J1979ReadEvidence::Variant::ClassicCan,
        request_,requestSize_,observer_.message(),observer_.size());
    if(evidence==J1979ReadEvidence::Result::Pending){
      if(!observer_.consume())return fail();
      // NRC 78 does not restart the absolute deadline or trigger a resend.
      return state_;
    }
    if(evidence==J1979ReadEvidence::Result::Positive)state_=State::Positive;
    else if(evidence==J1979ReadEvidence::Result::Negative)state_=State::Negative;
    else if(evidence==J1979ReadEvidence::Result::Raw)state_=State::Raw;
    else return fail();
    return state_;
  }
  State poll(uint32_t now){
    if(state_!=State::Waiting)return state_;
    if(uint32_t(now-started_)>=timeout_){state_=State::Timeout;return state_;}
    if(observer_.poll(now)==IsoTpObserver::Result::Fault)return fail();
    return state_;
  }
  void abort(){if(state_==State::Waiting)state_=State::Fault;}
  const uint8_t* response()const{
    return state_==State::Positive||state_==State::Negative||state_==State::Raw?observer_.message():nullptr;
  }
  size_t responseSize()const{return response()?observer_.size():0;}
  State state()const{return state_;}
 private:
  State fail(){state_=State::Fault;return state_;}
  uint32_t tx_,rx_;
  bool extended_;
  IsoTpObserver observer_;
  bool valid_;
  bool scoped_;
  const J1979FreezeScope scope_;
  bool liveScoped_;
  const J1979LiveScope liveScope_;
  State state_=State::Idle;
  uint32_t started_=0,timeout_=0;
  uint8_t request_[7]{};
  size_t requestSize_=0;
};
}
