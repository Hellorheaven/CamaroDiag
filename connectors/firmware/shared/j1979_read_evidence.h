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
  enum class Result { Invalid, Negative, Pending, Positive, Raw };

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

  // Widths shared with the host PID definitions. Unknown widths are
  // reported as correlated raw records, not validated decoded measurements.
  static size_t pidWidth(uint8_t pid){
    switch(pid){
      case 0x01:case 0xa6:return 4;
      case 0x0c:case 0x10:case 0x1f:case 0x21:case 0x22:case 0x23:
      case 0x31:case 0x32:case 0x3c:case 0x3d:case 0x3e:case 0x3f:
      case 0x42:case 0x43:case 0x44:case 0x4d:case 0x4e:case 0x53:
      case 0x54:case 0x59:case 0x5d:case 0x5e:case 0x63:return 2;
      case 0x04:case 0x05:case 0x06:case 0x07:case 0x08:case 0x09:
      case 0x0a:case 0x0b:case 0x0d:case 0x0e:case 0x0f:case 0x11:
      case 0x13:case 0x1d:case 0x2c:case 0x2d:case 0x2e:case 0x2f:
      case 0x30:case 0x33:case 0x45:case 0x46:case 0x47:case 0x48:
      case 0x49:case 0x4a:case 0x4b:case 0x4c:case 0x52:case 0x5a:
      case 0x5b:case 0x5c:case 0x61:case 0x62:return 1;
      default:return 0;
    }
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
      const size_t width=pidWidth(req[1]);
      if(width)return len==offset+width?Result::Positive:Result::Invalid;
      return len>offset&&(!legacy||len<=7)?Result::Raw:Result::Invalid;
    }
    if(mode==5)return len==(req[1]>=1&&req[1]<=4?4u:6u)?Result::Positive:Result::Invalid;
    if(mode==6){
      if(legacy)return len==7?Result::Positive:Result::Invalid;
      if(len<10||(len-1)%9!=0)return Result::Invalid;
      for(size_t i=1;i<len;i+=9)if(reply[i]!=req[1])return Result::Invalid;
      return Result::Positive;
    }
    if(mode==9){
      if(legacy){
        const uint8_t type=req[1];
        const bool count=type==1||type==3||type==5||type==7||
          type==9||type==12||type==14;
        return len==(count?3u:7u)&&reply[2]>0?
          Result::Positive:Result::Invalid;
      }
      if(req[1]==2){
        if(len!=20||reply[2]!=1)return Result::Invalid;
        for(size_t i=3;i<len;++i){
          uint8_t c=reply[i];
          if(!((c>='0'&&c<='9')||(c>='A'&&c<='Z'))||c=='I'||c=='O'||c=='Q')return Result::Invalid;
        }
        return Result::Positive;
      }
      size_t width=req[1]==4?16u:req[1]==6?4u:req[1]==10?20u:0u;
      if(width)return len>=3&&reply[2]>0&&len==3u+reply[2]*width?
        Result::Positive:Result::Invalid;
      return len>2?Result::Raw:Result::Invalid;
    }
    return Result::Invalid;
  }
};
}
