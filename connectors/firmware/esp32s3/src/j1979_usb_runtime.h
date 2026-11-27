#pragma once
#include "j1979_spi_port.h"
#include "j1979_usb_service.h"

namespace cd {
// Dedicated USB byte channel. No flush/discard, auto-open, DTR toggling or
// protocol fallback. The owner supplies actual connection-generation and
// logical close callbacks; a serial available() count is not host identity.
class EspJ1979UsbChannel final:public J1979UsbService::Channel {
 public:
  using Present=bool(*)();
  using Close=void(*)();
  EspJ1979UsbChannel(Stream& stream,Present present,Close close)
    :stream_(stream),present_(present),close_(close){}
  bool present()override{return !closed_&&present_&&close_&&present_();}
  uint32_t monotonicMs()override{return millis();}
  bool readExact(uint8_t* data,size_t size,uint32_t timeout)override{
    if(!data||size>4096||!timeout||!present())return false;
    const uint32_t start=monotonicMs();
    size_t offset=0;
    while(offset<size){
      if(!within(start,timeout))return false;
      if(stream_.available()<=0){delay(1);continue;}
      const int byte=stream_.read();
      if(byte<0)return false;
      data[offset++]=uint8_t(byte);
    }
    return within(start,timeout);
  }
  bool writeExact(const uint8_t* data,size_t size,uint32_t timeout)override{
    if(!data||size>4096||!timeout||!present())return false;
    const uint32_t start=monotonicMs();
    size_t offset=0;
    while(offset<size){
      if(!within(start,timeout))return false;
      const int available=stream_.availableForWrite();
      if(available<=0){delay(1);continue;}
      size_t count=size-offset;
      if(count>64)count=64;
      if(count>size_t(available))count=size_t(available);
      // Never retry a short/uncertain write. The owner must use a USB
      // stream with bounded synchronous writes and an independent interlock.
      if(stream_.write(data+offset,count)!=count)return false;
      offset+=count;
    }
    return within(start,timeout);
  }
  void close()override{
    if(!closed_){closed_=true;if(close_)close_();}
  }
 private:
  bool within(uint32_t start,uint32_t timeout){
    return present()&&uint32_t(monotonicMs()-start)<timeout;
  }
  Stream& stream_;Present present_;Close close_;bool closed_=false;
};

// Complete remote diagnostic composition, one fixed locally approved CAN
// pair per invocation. The USB owner's start/stop methods coordinate with
// the independent STM32 owner; the SPI port is not the authorization.
inline J1979UsbCampaign::Report serveJ1979RemoteUsb(
    J1979UsbService::Channel& channel,J1979UsbAuthSession::Security& security,
    HostSessionArbiter& arbiter,J1979UsbCampaign::Owner& owner,
    readlink::SpiMasterPort& spi,const J1979UsbCampaign::Profile& profile,
    J1979CanReadExchange::Result& scratch){
  if(profile.freeze)return J1979UsbCampaign::Report{};
  readlink::SpiTransport transport(spi);
  readlink::Client client(transport,profile.tx,profile.rx,profile.extended);
  return J1979UsbService::serve(channel,security,arbiter,owner,client,scratch);
}

inline J1979UsbCampaign::Report serveJ1979RemoteFreezeUsb(
    J1979UsbService::Channel& channel,J1979UsbAuthSession::Security& security,
    HostSessionArbiter& arbiter,J1979UsbCampaign::Owner& owner,
    readlink::SpiMasterPort& spi,const J1979UsbCampaign::Profile& profile,
    J1979CanReadExchange::Result& scratch){
  if(!profile.freeze||!profile.freezeScope.valid())return J1979UsbCampaign::Report{};
  readlink::SpiTransport transport(spi);
  readlink::Client client(transport,profile.tx,profile.rx,profile.extended,&profile.freezeScope);
  return J1979UsbService::serveFreeze(channel,security,arbiter,owner,client,scratch);
}
}
