#pragma once
#include "journal_record.h"

namespace cd { namespace journal {
// CDJ/USB/1 is a separate, isolated recovery-inspection firmware mode.
// Exactly one request per boot, no mutation, no ECU traffic, no reset command.
// This line protocol exports an NVS snapshot, not authenticated evidence.
class ExportRequest {
 public:
  bool feed(uint8_t byte,uint32_t now){
    if(closed_)return false;
    if(started_&&uint32_t(now-start_)>=2000u){closed_=true;return false;}
    if(!started_){started_=true;start_=now;}
    const char* expected="CDJ/1 READ\n";
    if(byte!=uint8_t(expected[pos_])){closed_=true;return false;}
    ++pos_;
    if(expected[pos_]=='\0'){closed_=true;return true;}
    return false;
  }
  void poll(uint32_t now){
    if(started_&&!closed_&&uint32_t(now-start_)>=2000u)closed_=true;
  }
  bool closed()const{return closed_;}
 private:
  size_t pos_=0;
  bool started_=false,closed_=false;
  uint32_t start_=0;
};
inline size_t exportLine(BootDisposition status,const Record& rec,char* out,size_t n){
  if(!out)return 0;
  const char* error=status==BootDisposition::NoRecord?"CDJ/1 NONE\n":"CDJ/1 CORRUPT\n";
  uint8_t bytes[RecordSize]{};
  if((status==BootDisposition::SafeButBlocked||status==BootDisposition::RecoveryRequired)&&
      classify(rec)==status&&encode(rec,bytes,sizeof(bytes))){
    constexpr size_t prefix=6;
    constexpr size_t length=prefix+RecordSize*2+1;
    if(n<length)return 0;
    std::memcpy(out,"CDJ/1 ",prefix);
    const char* hex="0123456789abcdef";
    for(size_t i=0;i<RecordSize;++i){out[prefix+2*i]=hex[bytes[i]>>4];out[prefix+2*i+1]=hex[bytes[i]&15];}
    out[length-1]='\n';return length;
  }
  const size_t length=std::strlen(error);
  if(n<length)return 0;
  std::memcpy(out,error,length);return length;
}
} }
