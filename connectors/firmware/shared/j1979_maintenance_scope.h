#pragma once
#include "j1979_read_evidence.h"
#include <cstring>

namespace cd {
// Separate, immutable maintenance scope. This is not a general write
// permission and cannot be supplied to the read-only CAN session or mailbox.
struct J1979MaintenanceScope {
  enum class Operation:uint8_t { Clear=1, Evap=2 };
  static constexpr size_t WireSize=31;
  Operation operation=Operation::Clear;
  uint32_t tx=0,rx=0,budgetMs=0;
  bool extended=false;
  uint8_t vin[17]{};
  bool valid()const{
    if(operation!=Operation::Clear&&operation!=Operation::Evap)return false;
    if(extended?(tx!=0x18da10f1u||rx!=0x18daf110u):(tx!=0x7e0u||rx!=0x7e8u))return false;
    if(budgetMs<1000u||budgetMs>60000u)return false;
    for(uint8_t c:vin)if(!J1979FreezeScope::vinCharacter(c))return false;
    return true;
  }
  bool same(const J1979MaintenanceScope& other)const{
    return valid()&&other.valid()&&operation==other.operation&&tx==other.tx&&rx==other.rx&&
      extended==other.extended&&budgetMs==other.budgetMs&&std::memcmp(vin,other.vin,17)==0;
  }
  bool vinMatches(const uint8_t* p,size_t n)const{
    return valid()&&p&&n==20&&p[0]==0x49&&p[1]==2&&p[2]==1&&std::memcmp(p+3,vin,17)==0;
  }
  bool mutation(const uint8_t* p,size_t n)const{
    if(!valid()||!p)return false;
    return operation==Operation::Clear?(n==1&&p[0]==4):(n==2&&p[0]==8&&p[1]==1);
  }
  bool read(const uint8_t* p,size_t n)const{
    if(!valid()||!p)return false;
    if(n==1)return operation==Operation::Clear&&(p[0]==3||p[0]==7||p[0]==10);
    if(n!=2)return false;
    if(p[0]==9&&p[1]==2)return true;
    if(p[0]==1&&(p[1]==0x0c||p[1]==0x0d))return true;
    if(operation==Operation::Clear)return p[0]==1&&p[1]==1;
    return p[0]==8&&p[1]==0;
  }
  using Shape=J1979ReadEvidence::Result;
  Shape classify(const uint8_t* req,size_t n,const uint8_t* reply,size_t len)const{
    if(read(req,n))return J1979ReadEvidence::classify(J1979ReadEvidence::Variant::ClassicCan,req,n,reply,len);
    if(!mutation(req,n)||!reply||!len)return Shape::Invalid;
    if(reply[0]==0x7f){
      if(len!=3||reply[1]!=req[0]||!reply[2])return Shape::Invalid;
      return reply[2]==0x78?Shape::Pending:Shape::Negative;
    }
    if(operation==Operation::Clear)return len==1&&reply[0]==0x44?Shape::Positive:Shape::Invalid;
    return len==2&&reply[0]==0x48&&reply[1]==1?Shape::Positive:Shape::Invalid;
  }
  bool encode(uint8_t* out,size_t n)const{
    if(!out||n!=WireSize||!valid())return false;
    out[0]=uint8_t(operation);out[1]=extended?1:0;
    put32(out+2,tx);put32(out+6,rx);put32(out+10,budgetMs);
    std::memcpy(out+14,vin,17);return true;
  }
  static bool decode(const uint8_t* bytes,size_t n,J1979MaintenanceScope& out){
    out=J1979MaintenanceScope{};
    if(!bytes||n!=WireSize||bytes[1]>1)return false;
    J1979MaintenanceScope p;
    p.operation=static_cast<Operation>(bytes[0]);p.extended=bytes[1]==1;
    p.tx=get32(bytes+2);p.rx=get32(bytes+6);p.budgetMs=get32(bytes+10);
    std::memcpy(p.vin,bytes+14,17);
    if(!p.valid())return false;
    out=p;return true;
  }
 private:
  static void put32(uint8_t* p,uint32_t n){for(unsigned i=0;i<4;++i)p[i]=uint8_t(n>>(8*i));}
  static uint32_t get32(const uint8_t* p){uint32_t n=0;for(unsigned i=0;i<4;++i)n|=uint32_t(p[i])<<(8*i);return n;}
};
}
