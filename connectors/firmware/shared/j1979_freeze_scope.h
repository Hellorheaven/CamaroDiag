#pragma once
#include <cstddef>
#include <cstdint>
#include <cstring>

namespace cd {
// Immutable, locally approved Mode 02 plan. A generic CU/1 or CI/2
// session is never implicitly upgraded by a request for another frame.
// Bytes outside the selected lists must be zero for canonical HMAC input.
struct J1979FreezeScope {
  static constexpr size_t MaximumFrames=8,MaximumPids=64,VinSize=17;
  static constexpr size_t WireSize=VinSize+1+MaximumFrames+1+MaximumPids;
  uint8_t vin[VinSize]{};
  uint8_t frameCount=0,frames[MaximumFrames]{};
  uint8_t pidCount=0,pids[MaximumPids]{};

  static bool vinCharacter(uint8_t c){
    return (c>='0'&&c<='9')||
      (c>='A'&&c<='Z'&&c!='I'&&c!='O'&&c!='Q');
  }
  static bool measurementPid(uint8_t pid){return pid&&pid!=2&&pid%32!=0;}
  bool valid()const{
    if(!frameCount||frameCount>MaximumFrames||pidCount>MaximumPids)return false;
    for(uint8_t c:vin)if(!vinCharacter(c))return false;
    for(size_t i=0;i<MaximumFrames;++i){
      if(i>=frameCount){if(frames[i])return false;continue;}
      for(size_t j=0;j<i;++j)if(frames[i]==frames[j])return false;
    }
    for(size_t i=0;i<MaximumPids;++i){
      if(i>=pidCount){if(pids[i])return false;continue;}
      if(!measurementPid(pids[i]))return false;
      for(size_t j=0;j<i;++j)if(pids[i]==pids[j])return false;
    }
    return true;
  }
  bool includesFrame(uint8_t number)const{
    if(frameCount>MaximumFrames)return false;
    for(size_t i=0;i<frameCount;++i)if(frames[i]==number)return true;
    return false;
  }
  bool includesPid(uint8_t pid)const{
    if(pidCount>MaximumPids||!measurementPid(pid))return false;
    if(!pidCount)return true;
    for(size_t i=0;i<pidCount;++i)if(pids[i]==pid)return true;
    return false;
  }
  bool allows(const uint8_t* request,size_t size)const{
    if(!valid()||!request)return false;
    if(size==2)return request[0]==9&&request[1]==2;
    if(size!=3||request[0]!=2||!includesFrame(request[2]))return false;
    return request[1]%32==0||request[1]==2||includesPid(request[1]);
  }
  bool vinMatches(const uint8_t* response,size_t size)const{
    return response&&size==20&&response[0]==0x49&&response[1]==2&&response[2]==1&&
      std::memcmp(response+3,vin,VinSize)==0;
  }
  bool same(const J1979FreezeScope& other)const{
    return valid()&&other.valid()&&frameCount==other.frameCount&&pidCount==other.pidCount&&
      std::memcmp(vin,other.vin,VinSize)==0&&
      std::memcmp(frames,other.frames,MaximumFrames)==0&&
      std::memcmp(pids,other.pids,MaximumPids)==0;
  }
  bool encode(uint8_t* out,size_t size)const{
    if(!out||size!=WireSize||!valid())return false;
    std::memcpy(out,vin,VinSize);out[VinSize]=frameCount;
    std::memcpy(out+VinSize+1,frames,MaximumFrames);
    out[VinSize+1+MaximumFrames]=pidCount;
    std::memcpy(out+VinSize+2+MaximumFrames,pids,MaximumPids);
    return true;
  }
  static bool decode(const uint8_t* data,size_t size,J1979FreezeScope& out){
    out=J1979FreezeScope{};
    if(!data||size!=WireSize)return false;
    J1979FreezeScope candidate;
    std::memcpy(candidate.vin,data,VinSize);
    candidate.frameCount=data[VinSize];
    std::memcpy(candidate.frames,data+VinSize+1,MaximumFrames);
    candidate.pidCount=data[VinSize+1+MaximumFrames];
    std::memcpy(candidate.pids,data+VinSize+2+MaximumFrames,MaximumPids);
    if(!candidate.valid())return false;
    out=candidate;return true;
  }
};
}
