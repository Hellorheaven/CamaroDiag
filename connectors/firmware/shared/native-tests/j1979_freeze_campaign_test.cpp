#include "../j1979_freeze_evidence.h"
#include "../j1979_freeze_sequence.h"
#include <cassert>
#include <vector>

using namespace cd;
using Campaign=J1979FreezeCampaign;
using State=Campaign::State;
static const char Vin[]="1G1FA1E30D1234567";

static J1979FreezeScope scope(){
  J1979FreezeScope s;
  std::memcpy(s.vin,Vin,17);s.frameCount=2;s.frames[0]=0;s.frames[1]=1;
  s.pidCount=2;s.pids[0]=12;s.pids[1]=13;return s;
}
class Link final:public Campaign::Link {
 public:
  explicit Link(const J1979FreezeScope& s):scope_(s){}
  bool active()override{return online;}
  bool matches(uint32_t tx,uint32_t rx,bool ext)const override{
    return tx==0x7e0&&rx==0x7e8&&!ext;
  }
  bool matchesFreeze(const J1979FreezeScope& s)const override{return scope_.same(s);}
  uint32_t monotonicMs()override{return now;}
  void exchange(const uint8_t* req,size_t n,uint32_t timeout,Campaign::Exchange::Result& out)override{
    assert(scope_.allows(req,n)&&timeout>0);
    requests.emplace_back(req,req+n);
    now+=5;out.responseSize=0;out.elapsedMs=5;out.state=State::Positive;
    if(faultAt&&requests.size()==faultAt){out.state=State::Fault;online=false;return;}
    if(n==2){
      const uint8_t head[]={0x49,2,1};std::memcpy(out.response,head,3);
      std::memcpy(out.response+3,Vin,17);out.responseSize=20;
      if(wrongVin)out.response[19]='8';
      return;
    }
    const auto pid=req[1],frame=req[2];
    out.response[0]=0x42;out.response[1]=pid;out.response[2]=frame;
    switch(pid){
      case 0:{
        const uint8_t bitmap[]={0x40,0x18,0,0};
        std::memcpy(out.response+3,bitmap,4);out.responseSize=7;break;
      }
      case 2:
        ++triggers[frame];out.response[3]=absent&&frame==0?0:3;
        out.response[4]=absent&&frame==0?0:1;
        if(changed&&frame==0&&triggers[0]>1)out.response[4]=2;
        out.responseSize=5;break;
      case 12:
        out.response[3]=0x1f;out.response[4]=0x40;out.responseSize=5;
        if(frame)out.state=State::Raw;
        break;
      case 13:
        out.response[3]=80;out.responseSize=4;
        if(frame)out.state=State::Raw;
        break;
      default:assert(false);
    }
  }
  std::vector<std::vector<uint8_t>> requests;
  bool online=true,wrongVin=false,absent=false,changed=false;
  unsigned faultAt=0,triggers[256]{};
  uint32_t now=0;
 private:
  J1979FreezeScope scope_;
};
class Writer final:public J1979FreezeEvidence::Writer {
 public:
  bool write(const uint8_t* bytes,size_t size)override{
    if(!accept)return false;
    data.insert(data.end(),bytes,bytes+size);return true;
  }
  bool accept=true;std::vector<uint8_t> data;
};

static Campaign::Summary run(Link& link,const J1979FreezeScope& s,Writer& writer){
  J1979FreezeEvidence evidence(writer);
  Campaign::Exchange::Result scratch{};
  assert(evidence.begin(0x7e0,0x7e8,false,60000,s));
  const auto summary=Campaign::runApplication(link,0x7e0,0x7e8,false,s,60000,evidence,scratch);
  assert(evidence.finish(summary));
  return summary;
}

int main(){
  const auto s=scope();
  assert(s.valid());
  uint8_t wire[J1979FreezeScope::WireSize]{};
  assert(s.encode(wire,sizeof(wire)));
  J1979FreezeScope recovered;assert(J1979FreezeScope::decode(wire,sizeof(wire),recovered));
  assert(s.same(recovered));wire[20]=7;
  assert(!J1979FreezeScope::decode(wire,sizeof(wire),recovered));
  const uint8_t unselected[]={2,14,0},wrongFrame[]={2,12,2},write[]={4};
  assert(!s.allows(unselected,sizeof(unselected)));
  assert(!s.allows(wrongFrame,sizeof(wrongFrame)));
  assert(!s.allows(write,sizeof(write)));
  const uint8_t request[]={2,13,1},wrong[]={0x42,13,0,80};
  assert(J1979ReadEvidence::classifyFreeze(s,request,sizeof(request),wrong,sizeof(wrong))==
    J1979ReadEvidence::Result::Invalid);

  Link link(s);Writer writer;const auto good=run(link,s,writer);
  assert(good.completed&&good.identityRechecked&&good.allFramesStable&&good.visited==2);
  assert(good.frames[0]==Campaign::FrameState::Complete&&good.frames[1]==Campaign::FrameState::Complete);
  assert(link.requests.size()==12&&writer.data[0]=='C'&&writer.data[1]=='F'&&writer.data[2]==1);
  assert(link.requests.front()==std::vector<uint8_t>({9,2}));
  assert(link.requests.back()==std::vector<uint8_t>({9,2}));

  J1979FreezeSequence sequence(s);
  Campaign::Exchange::Result observation{};
  Link replay(s);
  const uint8_t skip[]={2,12,0};
  assert(!sequence.allows(skip,3));
  for(const auto& request:link.requests){
    assert(sequence.allows(request.data(),request.size()));
    replay.exchange(request.data(),request.size(),1000,observation);
    assert(sequence.observe(request.data(),request.size(),observation));
  }
  assert(sequence.finished()&&!sequence.allows(skip,3));
  J1979FreezeSequence deniedVin(s);
  Link badIdentity(s);badIdentity.wrongVin=true;
  const uint8_t vinRequest[]={9,2};
  badIdentity.exchange(vinRequest,2,1000,observation);
  assert(deniedVin.observe(vinRequest,2,observation)&&deniedVin.finished());
  assert(!deniedVin.allows(skip,3));
  J1979FreezeSequence skipped(s);
  assert(!skipped.observe(skip,3,observation)&&skipped.finished());

  Link mismatch(s);mismatch.wrongVin=true;Writer rejected;
  const auto wrongVin=run(mismatch,s,rejected);
  assert(wrongVin.stop==Campaign::Stop::VinMismatch&&!wrongVin.completed&&mismatch.requests.size()==1);
  Link absent(s);absent.absent=true;Writer absentWriter;
  const auto absentResult=run(absent,s,absentWriter);
  assert(absentResult.frames[0]==Campaign::FrameState::Absent&&absentResult.allFramesStable);
  Link changed(s);changed.changed=true;Writer changedWriter;
  const auto changedResult=run(changed,s,changedWriter);
  assert(changedResult.frames[0]==Campaign::FrameState::Changed&&!changedResult.allFramesStable);
  Link failure(s);failure.faultAt=4;Writer faultWriter;
  const auto fault=run(failure,s,faultWriter);
  assert(fault.stop==Campaign::Stop::TransportError&&!fault.completed);

  J1979FreezeScope other=s;other.frames[1]=2;
  Link pinned(s);Writer denied;J1979FreezeEvidence evidence(denied);
  Campaign::Exchange::Result scratch{};
  assert(evidence.begin(0x7e0,0x7e8,false,60000,other));
  const auto deniedResult=Campaign::runApplication(pinned,0x7e0,0x7e8,false,other,60000,evidence,scratch);
  assert(!deniedResult.completed&&pinned.requests.empty());
  assert(!evidence.finish(deniedResult));
}
