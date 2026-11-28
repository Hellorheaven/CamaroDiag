#pragma once
#include <cstddef>
#include <cstdint>
#include <cstring>
#include "j1979_freeze_scope.h"

namespace cd {
// Immutable explicit authorization for a read-only Mode 01 raw recording.
// This structure is not itself authorization: each board must establish the
// same locally approved scope and bind it to its qualified host lease.
struct J1979LiveScope {
  static constexpr size_t VinSize=17, MaximumPids=32;
  static constexpr uint32_t MaximumSamples=10000, MaximumBudget=600000;
  static constexpr uint32_t IdentityInterval=30000;
  static constexpr size_t WireSize=VinSize+1+MaximumPids+2+2+4;
  uint8_t vin[VinSize]{};
  uint8_t count=0,pids[MaximumPids]{};
  uint16_t cycles=0,intervalMs=0;
  uint32_t budgetMs=0;

  static bool measurementPid(uint8_t pid){return pid!=0&&pid%32!=0;}
  bool valid()const{
    if(!count||count>MaximumPids||!cycles||cycles>1000||
       uint32_t(cycles)*count>MaximumSamples||intervalMs<100||
       intervalMs>60000||budgetMs<1000||budgetMs>MaximumBudget)return false;
    for(uint8_t c:vin)if(!J1979FreezeScope::vinCharacter(c))return false;
    for(size_t i=0;i<MaximumPids;++i){
      if(i>=count){if(pids[i])return false;continue;}
      if(!measurementPid(pids[i]))return false;
      for(size_t j=0;j<i;++j)if(pids[i]==pids[j])return false;
    }
    return true;
  }
  bool includes(uint8_t pid)const{
    if(!count||count>MaximumPids||!measurementPid(pid))return false;
    for(size_t i=0;i<count;++i)if(pids[i]==pid)return true;
    return false;
  }
  bool allows(const uint8_t* req,size_t n)const{
    if(!valid()||!req||n!=2)return false;
    return (req[0]==9&&req[1]==2)||
      (req[0]==1&&(req[1]%32==0||includes(req[1])));
  }
  bool vinMatches(const uint8_t* reply,size_t n)const{
    return reply&&n==20&&reply[0]==0x49&&reply[1]==2&&reply[2]==1&&
      std::memcmp(reply+3,vin,VinSize)==0;
  }
  bool same(const J1979LiveScope& other)const{
    return valid()&&other.valid()&&count==other.count&&cycles==other.cycles&&
      intervalMs==other.intervalMs&&budgetMs==other.budgetMs&&
      std::memcmp(vin,other.vin,VinSize)==0&&
      std::memcmp(pids,other.pids,MaximumPids)==0;
  }
  bool encode(uint8_t* out,size_t n)const{
    if(!out||n!=WireSize||!valid())return false;
    std::memcpy(out,vin,VinSize);out[VinSize]=count;
    std::memcpy(out+VinSize+1,pids,MaximumPids);
    const size_t offset=VinSize+1+MaximumPids;
    put16(out+offset,cycles);put16(out+offset+2,intervalMs);
    for(unsigned i=0;i<4;++i)out[offset+4+i]=uint8_t(budgetMs>>(8*i));
    return true;
  }
  static bool decode(const uint8_t* data,size_t n,J1979LiveScope& out){
    out=J1979LiveScope{};
    if(!data||n!=WireSize)return false;
    J1979LiveScope candidate;
    std::memcpy(candidate.vin,data,VinSize);candidate.count=data[VinSize];
    std::memcpy(candidate.pids,data+VinSize+1,MaximumPids);
    const size_t offset=VinSize+1+MaximumPids;
    candidate.cycles=get16(data+offset);candidate.intervalMs=get16(data+offset+2);
    for(unsigned i=0;i<4;++i)candidate.budgetMs|=uint32_t(data[offset+4+i])<<(8*i);
    if(!candidate.valid())return false;
    out=candidate;return true;
  }
 private:
  static void put16(uint8_t* p,uint16_t value){p[0]=uint8_t(value);p[1]=uint8_t(value>>8);}
  static uint16_t get16(const uint8_t* p){return uint16_t(p[0])|(uint16_t(p[1])<<8);}
};
}
