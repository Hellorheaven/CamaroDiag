#pragma once
#include "binary_frame.h"
#include <cstring>

namespace cd {
// Separate from ECU programming journal: a read channel can be interrupted
// without flash modification, but an unverified electrical stop still blocks
// reopening. Missing/corrupt storage is never assumed clean.
class J1979ReadGuard {
 public:
  enum class State:uint8_t { Clean=1, Active=2 };
  struct Record { State state=State::Clean;uint64_t generation=0; };
  static constexpr size_t Size=20;
  class Store {
   public:
    virtual ~Store()=default;
    virtual bool read(uint8_t* data,size_t size)=0;
    // Durable write and independent readback; false is uncertain persistence.
    virtual bool writeVerified(const uint8_t* data,size_t size)=0;
  };
  explicit J1979ReadGuard(Store& store):store_(store){}
  J1979ReadGuard(const J1979ReadGuard&)=delete;
  J1979ReadGuard& operator=(const J1979ReadGuard&)=delete;
  static bool encode(const Record& r,uint8_t* data,size_t n){
    if(!data||n!=Size||(r.state!=State::Clean&&r.state!=State::Active)||
       (r.state==State::Active&&!r.generation))return false;
    std::memset(data,0,n);data[0]='C';data[1]='R';data[2]=1;data[3]=uint8_t(r.state);
    for(unsigned i=0;i<8;++i)data[4+i]=uint8_t(r.generation>>(8*i));
    const uint32_t crc=crc32(data,16);
    for(unsigned i=0;i<4;++i)data[16+i]=uint8_t(crc>>(8*i));
    return true;
  }
  static bool decode(const uint8_t* data,size_t n,Record& out){
    out=Record{};
    if(!data||n!=Size||data[0]!='C'||data[1]!='R'||data[2]!=1||
       (data[3]!=1&&data[3]!=2)||data[12]||data[13]||data[14]||data[15]||
       le32(data+16)!=crc32(data,16))return false;
    Record r;r.state=static_cast<State>(data[3]);
    for(unsigned i=0;i<8;++i)r.generation|=uint64_t(data[4+i])<<(8*i);
    if(r.state==State::Active&&!r.generation)return false;
    out=r;return true;
  }
  bool inspect(){
    if(locked_)return false;
    Record r;
    if(!read(r))return lock();
    if(owned_)return r.state==State::Active&&r.generation==generation_?true:lock();
    if(r.state!=State::Clean)return lock();
    return true;
  }
  bool begin(){
    if(locked_||owned_)return false;
    Record r;
    if(!read(r)||r.state!=State::Clean||r.generation==UINT64_MAX)return lock();
    r.state=State::Active;++r.generation;
    if(!persist(r))return lock();
    generation_=r.generation;owned_=true;return true;
  }
  // Call only after independent, physical quiescence was verified. An
  // active record from a previous boot can never be cleared by this method.
  bool finishVerified(){
    if(locked_||!owned_)return false;
    Record r;
    if(!read(r)||r.state!=State::Active||r.generation!=generation_)return lock();
    r.state=State::Clean;
    if(!persist(r))return lock();
    owned_=false;return true;
  }
  void uncertain(){lock();}
  bool locked()const{return locked_;}
 private:
  bool lock(){locked_=true;return false;}
  bool read(Record& r){uint8_t bytes[Size]{};return store_.read(bytes,Size)&&decode(bytes,Size,r);}
  bool persist(const Record& r){
    uint8_t bytes[Size]{},actual[Size]{};
    return encode(r,bytes,Size)&&store_.writeVerified(bytes,Size)&&
      store_.read(actual,Size)&&std::memcmp(bytes,actual,Size)==0;
  }
  Store& store_;uint64_t generation_=0;bool owned_=false,locked_=false;
};
}
