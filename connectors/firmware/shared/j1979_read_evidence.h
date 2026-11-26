#pragma once
#include <cstddef>
#include <cstdint>

namespace cd {
// Passive application record classifier, after the caller has verified link
// framing and selected the responder. Does not send frames, enable a driver,
// aggregate anonymous records, authorize a write or certify hardware support.
class J1979ReadEvidence {
 public:
  enum class Variant { ClassicCan, Legacy };
  enum class Result { Invalid, Negative, Pending, Positive };

  static bool readRequest(Variant variant,const uint8_t* req,size_t n) {
    if(!req||!n)return false;
    const uint8_t mode=req[0];
    if(n==1)return mode==3||mode==7||mode==10;
    if(n==2)return mode==1||mode==6||mode==9||
      (variant==Variant::ClassicCan&&mode==8&&req[1]%32==0);
    if(n==3){
      if(mode==2)return req[2]==0;
      return variant==Variant::Legacy&&mode==5&&req[2]!=0&&
        (req[2]&(req[2]-1u))==0;
    }
    if(variant!=Variant::Legacy||n!=7||mode!=8||req[1]%32!=0)return false;
    for(size_t i=2;i<n;++i)if(req[i])return false;
    return true;
  }

  static Result classify(Variant variant,const uint8_t* req,size_t n,
                         const uint8_t* reply,size_t len) {
    if(!readRequest(variant,req,n)||!reply||!len||len>4095)return Result::Invalid;
    const uint8_t mode=req[0];
    if(reply[0]==0x7f){
      if(len!=3||reply[1]!=mode||reply[2]==0)return Result::Invalid;
      return reply[2]==0x78?Result::Pending:Result::Negative;
    }
    if(reply[0]!=mode+0x40u)return Result::Invalid;
    const bool legacy=variant==Variant::Legacy;
    if(mode==3||mode==7||mode==10){
      if(legacy)return len==7?Result::Positive:Result::Invalid;
      if(len<2||len!=2u+2u*reply[1])return Result::Invalid;
      for(size_t i=2;i<len;i+=2)if(!reply[i]&&!reply[i+1])return Result::Invalid;
      return Result::Positive;
    }
    if(len<2||reply[1]!=req[1])return Result::Invalid;
    const bool page=req[1]%32==0;
    if(mode==2||mode==5){
      if(len<3||reply[2]!=req[2])return Result::Invalid;
    }
    if(page){
      size_t offset=mode==2||mode==5||
        (legacy&&(mode==6||mode==8||mode==9))?3u:2u;
      if(len!=offset+4)return Result::Invalid;
      if(legacy&&mode==6&&reply[2]!=0xff)return Result::Invalid;
      if(legacy&&mode==8&&reply[2]!=0)return Result::Invalid;
      if(legacy&&mode==9&&reply[2]!=1)return Result::Invalid;
      if(req[1]==0xe0&&(reply[len-1]&1u))return Result::Invalid;
      return Result::Positive;
    }
    if(mode==1||mode==2){
      size_t offset=mode==2?3u:2u;
      if(mode==2&&req[1]==2)return len==5?Result::Positive:Result::Invalid;
      if(req[1]==1)return len==offset+4?Result::Positive:Result::Invalid;
      if(req[1]==0x0c)return len==offset+2?Result::Positive:Result::Invalid;
      if(req[1]==0x0d)return len==offset+1?Result::Positive:Result::Invalid;
      // Unlisted PID widths remain unclassified, not invented.
      return Result::Invalid;
    }
    if(mode==5)return len==(req[1]>=1&&req[1]<=4?4u:6u)?Result::Positive:Result::Invalid;
    if(mode==6){
      if(legacy)return len==7?Result::Positive:Result::Invalid;
      if(len<10||(len-1)%9!=0)return Result::Invalid;
      for(size_t i=1;i<len;i+=9)if(reply[i]!=req[1])return Result::Invalid;
      return Result::Positive;
    }
    if(mode==9){
      if(legacy)return len==(req[1]%2?3u:7u)&&reply[2]>0?
        Result::Positive:Result::Invalid;
      if(req[1]==2){
        if(len!=20||reply[2]!=1)return Result::Invalid;
        for(size_t i=3;i<len;++i){
          uint8_t c=reply[i];
          if(!((c>='0'&&c<='9')||(c>='A'&&c<='Z'))||c=='I'||c=='O'||c=='Q')return Result::Invalid;
        }
        return Result::Positive;
      }
      size_t width=req[1]==4?16u:req[1]==6?4u:req[1]==10?20u:0u;
      return width&&len>=3&&reply[2]>0&&len==3u+reply[2]*width?
        Result::Positive:Result::Invalid;
    }
    return Result::Invalid;
  }
};
}
