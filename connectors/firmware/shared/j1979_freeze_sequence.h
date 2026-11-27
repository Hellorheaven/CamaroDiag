#pragma once
#include "j1979_can_read_exchange.h"

namespace cd {
// Independent receiver-side authorization for the native Mode 02 recipe.
// The immutable approved scope is necessary but not sufficient: a request
// must be the next permitted step of the acquisition derived from ECU replies.
// No heap, no bus ownership, no retry or reset of a failed identity check.
class J1979FreezeSequence {
 public:
  using Exchange=J1979CanReadExchange;
  using State=Exchange::State;
  enum class Stage:uint8_t { InitialVin, Page, Trigger, MorePages, Values, FinalTrigger, FinalVin, Done };
  explicit J1979FreezeSequence(const J1979FreezeScope& scope):scope_(scope){
    if(!scope_.valid())stage_=Stage::Done;
  }
  Stage stage()const{return stage_;}
  bool finished()const{return stage_==Stage::Done;}
  size_t next(uint8_t (&request)[3])const{
    if(stage_==Stage::Done)return 0;
    if(stage_==Stage::InitialVin||stage_==Stage::FinalVin){
      request[0]=9;request[1]=2;request[2]=0;return 2;
    }
    if(index_>=scope_.frameCount)return 0;
    request[0]=2;request[2]=scope_.frames[index_];
    switch(stage_){
      case Stage::Page:request[1]=0;break;
      case Stage::Trigger:case Stage::FinalTrigger:request[1]=2;break;
      case Stage::MorePages:request[1]=base_;break;
      case Stage::Values:request[1]=uint8_t(pid_);break;
      default:return 0;
    }
    return 3;
  }
  bool allows(const uint8_t* req,size_t n)const{
    uint8_t expected[3]{};
    const auto count=next(expected);
    return count&&n==count&&scope_.allows(req,n)&&std::memcmp(req,expected,count)==0;
  }
  // Called only once per physical exchange, before publishing its result.
  // The read transport already validates response shape; independently
  // check it here as well, including identity and exact echoed frame.
  bool observe(const uint8_t* req,size_t n,const Exchange::Result& result){
    if(!allows(req,n)){stage_=Stage::Done;return false;}
    if(result.state==State::Fault){
      stage_=Stage::Done;return result.responseSize==0;
    }
    using R=J1979ReadEvidence::Result;
    if(result.responseSize>sizeof(result.response)){stage_=Stage::Done;return false;}
    const auto shape=J1979ReadEvidence::classifyFreeze(scope_,req,n,result.response,result.responseSize);
    const bool positive=result.state==State::Positive&&shape==R::Positive;
    const bool raw=result.state==State::Raw&&shape==R::Raw;
    const bool negative=result.state==State::Negative&&shape==R::Negative;
    const bool timeout=result.state==State::Timeout&&result.responseSize==0;
    if(!positive&&!raw&&!negative&&!timeout){stage_=Stage::Done;return false;}
    switch(stage_){
      case Stage::InitialVin:
        if(positive&&scope_.vinMatches(result.response,result.responseSize))beginFrame();
        else stage_=Stage::Done;
        break;
      case Stage::Page:
        if(!positive||!bitmap(0,result)||!ids_[2])advanceFrame();
        else stage_=Stage::Trigger;
        break;
      case Stage::Trigger:
        if(!positive||result.responseSize!=5)advanceFrame();
        else if(!result.response[3]&&!result.response[4])advanceFrame();
        else if(more_){base_=32;stage_=Stage::MorePages;}
        else beginValues();
        break;
      case Stage::MorePages:
        if(positive&&bitmap(base_,result)&&more_&&base_<224)base_=uint8_t(base_+32);
        else beginValues();
        break;
      case Stage::Values:
        ++pid_;selectValue();break;
      case Stage::FinalTrigger:
        advanceFrame();break;
      case Stage::FinalVin:
        // The terminal summary separately determines whether identity
        // was rechecked. No new ECU request can follow this observation.
        stage_=Stage::Done;break;
      default:stage_=Stage::Done;return false;
    }
    return true;
  }
 private:
  void beginFrame(){
    if(index_>=scope_.frameCount){stage_=Stage::FinalVin;return;}
    std::memset(ids_,0,sizeof(ids_));more_=false;base_=0;pid_=1;
    stage_=Stage::Page;
  }
  void advanceFrame(){++index_;beginFrame();}
  bool bitmap(uint8_t base,const Exchange::Result& result){
    if(result.responseSize!=7||base>224||base%32)return false;
    for(unsigned bit=0;bit<31;++bit){
      if(result.response[3+bit/8]&(0x80u>>(bit%8)))
        ids_[unsigned(base)+bit+1]=true;
    }
    more_=(result.response[6]&1u)!=0;
    if(base==224&&more_)return false;
    return true;
  }
  void beginValues(){pid_=1;selectValue();}
  void selectValue(){
    while(pid_<256&&(!ids_[pid_]||!scope_.includesPid(uint8_t(pid_))))++pid_;
    stage_=pid_<256?Stage::Values:Stage::FinalTrigger;
  }
  const J1979FreezeScope scope_;
  Stage stage_=Stage::InitialVin;
  size_t index_=0;
  bool ids_[256]{};
  bool more_=false;
  uint8_t base_=0;
  unsigned pid_=1;
};
}
