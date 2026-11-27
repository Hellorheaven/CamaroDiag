#include "../j1979_maintenance_local.h"
#include <cassert>
#include <vector>
#include <cstring>

using namespace cd;
using Campaign=J1979MaintenanceCampaign;
using Scope=J1979MaintenanceScope;
using Journal=J1979MaintenanceJournal;
using Outcome=Campaign::Outcome;
using Reason=Campaign::Reason;

static Scope scope(Scope::Operation op=Scope::Operation::Clear){
  Scope s;s.operation=op;s.tx=0x7e0;s.rx=0x7e8;s.budgetMs=60000;
  std::memcpy(s.vin,"1G1FA1RXXR0123456",17);return s;
}
struct Storage final:Journal::Storage {
  std::vector<uint8_t> bytes;
  unsigned appends=0,flushes=0,latches=0,failAppend=0,failFlush=0;
  bool corruptPrevious=false,deny=false;
  bool ready()override{return !deny;}
  bool size(size_t& n)override{n=bytes.size();return true;}
  bool append(const uint8_t* p,size_t n)override{
    ++appends;
    if(appends==failAppend){bytes.insert(bytes.end(),p,p+n/2);return false;}
    bytes.insert(bytes.end(),p,p+n);return true;
  }
  bool flush()override{
    ++flushes;
    if(corruptPrevious&&flushes==3)bytes[15]^=1;
    return flushes!=failFlush;
  }
  bool read(size_t offset,uint8_t* p,size_t n)override{
    if(offset>bytes.size()||n>bytes.size()-offset)return false;
    std::memcpy(p,bytes.data()+offset,n);return true;
  }
  void latchRecovery()override{++latches;deny=true;}
};
struct Board final:J1979MaintenanceLocal::Board {
  unsigned starts=0,stops=0,latches=0;
  bool qualified=true,consent=true,stable=true,stationary=true,exclusive=true,
    recovery=false,stopOk=true,startOk=true;
  uint64_t generation=1;
  J1979MaintenanceLocal::Snapshot snapshot(const Scope&)override{
    J1979MaintenanceLocal::Snapshot s;
    s.connection=generation;s.approval=2;s.credential=3;s.lease=4;
    s.qualified=qualified;s.authenticated=true;s.warningAccepted=consent;s.operationConfirmed=consent;
    s.stationaryInterlock=stationary;s.stableSupply=stable;s.exclusive=exclusive;s.recovery=recovery;
    return s;
  }
  bool start(const Scope&,uint64_t lease)override{assert(lease==4);++starts;return startOk;}
  bool stopAndVerify(uint64_t lease)override{assert(lease==4);++stops;return stopOk;}
  void latchRecovery()override{++latches;recovery=true;}
};
struct Bus final:J1979CanReadLink {
  explicit Bus(Board& board,Storage& store,const Scope& scope):board(board),store(store),scope_(scope){}
  Board& board;Storage& store;Scope scope_;
  uint32_t now=0;
  unsigned requests=0,commands=0,identities=0,flow=0,readbacks=0;
  bool wrongVin=false,changedVin=false,moving=false,unsupported=false,noAck=false,nrc=false,
    pending=false,readbackMissing=false,remainingDtc=false,revokedAfterSend=false,segmentedAck=false;
  bool waitingFlow=false,pendingNrc=false,replyReady=false;
  size_t size=0,offset=0;
  uint8_t response[32]{},sn=1;
  bool active()override{return board.starts==1&&!board.stops;}
  uint32_t monotonicMs()override{return now;}
  bool discardReceive()override{size=offset=0;replyReady=false;waitingFlow=false;pendingNrc=false;return true;}
  void set(const uint8_t* bytes,size_t n){size=n;std::memcpy(response,bytes,n);replyReady=true;}
  bool send(const Frame& f,uint32_t timeout)override{
    assert(active()&&f.id==scope_.tx&&f.extended==scope_.extended&&f.length==8&&timeout>0);
    if(f.data[0]==0x30){assert(waitingFlow);waitingFlow=false;++flow;return true;}
    ++requests;now+=5;
    const uint8_t mode=f.data[1],pid=f.data[2];
    if(mode==4||(mode==8&&pid==1)){
      assert(store.appends==3&&store.flushes==3&&commands==0);
      ++commands;
      if(revokedAfterSend)++board.generation;
      if(noAck)return true;
      if(nrc){const uint8_t b[]={0x7f,mode,0x22};set(b,sizeof(b));return true;}
      if(segmentedAck){size=20;replyReady=true;return true;}
      const uint8_t b[]={uint8_t(mode+0x40),1};set(b,mode==4?1:2);
      pendingNrc=pending;return true;
    }
    if(mode==9){
      ++identities;response[0]=0x49;response[1]=2;response[2]=1;
      std::memcpy(response+3,scope_.vin,17);
      if(wrongVin||(changedVin&&identities>=2))response[3]='2';
      size=20;replyReady=true;return true;
    }
    if(mode==3||mode==7||mode==10){
      if(commands)++readbacks;
      if(readbackMissing&&commands&&mode==7)return true;
      const bool dtc=mode==3&&(!commands||remainingDtc);
      const uint8_t b[]={uint8_t(mode+0x40),uint8_t(dtc?1:0),0x01,0x23};set(b,dtc?4:2);return true;
    }
    if(mode==8){const uint8_t b[]={0x48,0,uint8_t(unsupported?0:0x80),0,0,0};set(b,6);return true;}
    assert(mode==1);
    if(pid==0x0d){const uint8_t b[]={0x41,pid,uint8_t(moving?1:0)};set(b,3);return true;}
    if(pid==0x0c){const uint8_t b[]={0x41,pid,0,0};set(b,4);return true;}
    assert(pid==1);const uint8_t b[]={0x41,1,0,0,0,0};set(b,6);return true;
  }
  Receive receive(Frame& frame,uint32_t timeout)override{
    if(!replyReady){now+=timeout;return Receive::NoFrame;}
    frame=Frame{};frame.id=scope_.rx;frame.extended=scope_.extended;frame.length=8;
    if(pendingNrc){
      pendingNrc=false;frame.data[0]=3;frame.data[1]=0x7f;
      frame.data[2]=scope_.operation==Scope::Operation::Clear?4:8;frame.data[3]=0x78;return Receive::Data;
    }
    if(size<=7){
      frame.data[0]=uint8_t(size);std::memcpy(frame.data+1,response,size);
      replyReady=false;return Receive::Data;
    }
    if(!offset){
      frame.data[0]=uint8_t(0x10|(size>>8));frame.data[1]=uint8_t(size);
      std::memcpy(frame.data+2,response,6);offset=6;waitingFlow=true;sn=1;return Receive::Data;
    }
    assert(!waitingFlow);
    frame.data[0]=uint8_t(0x20|sn);sn=uint8_t((sn+1)&15);
    const size_t n=size-offset<7?size-offset:7;
    std::memcpy(frame.data+1,response+offset,n);offset+=n;
    if(offset==size)replyReady=false;
    return Receive::Data;
  }
};
struct Fixture {
  Scope target;Board board;Storage store;Bus bus;
  Journal::Scratch journalScratch{};
  Campaign::Evidence evidence{};
  J1979CanReadExchange::Result scratch{};
  explicit Fixture(Scope::Operation op=Scope::Operation::Clear):target(scope(op)),bus(board,store,target){}
  void run(){runJ1979LocalMaintenance(board,store,journalScratch,bus,target,evidence,scratch);}
};
static void validClear(){
  Fixture f;f.bus.pending=true;f.run();
  assert(f.evidence.summary.outcome==Outcome::AcknowledgedReadback&&f.evidence.summary.acknowledged);
  assert(f.evidence.summary.finalVin&&f.evidence.summary.afterConfirmedEmpty&&f.evidence.summary.afterPendingEmpty);
  assert(f.evidence.count==16&&f.bus.requests==16&&f.bus.commands==1&&f.bus.identities==3&&f.bus.flow==3);
  assert(f.evidence.physicalStopVerified&&f.evidence.terminalPersisted&&!f.evidence.recoveryRequired);
  assert(f.store.appends==4&&f.store.flushes==4&&f.board.stops==1&&f.board.latches==0);
  // A second run with the same nonempty durable journal is denied.
  f.run();assert(f.evidence.recoveryRequired&&f.bus.commands==1&&f.board.starts==1);
}
static void validEvap(){
  Fixture f(Scope::Operation::Evap);f.run();
  assert(f.evidence.summary.outcome==Outcome::AcknowledgedEvap&&f.evidence.count==9);
  assert(f.bus.commands==1&&f.bus.identities==2&&f.evidence.terminalPersisted&&!f.evidence.recoveryRequired);
}
static void deniedPrerequisites(){
  for(unsigned i=0;i<5;++i){
    Fixture f(Scope::Operation::Evap);
    f.board.consent=i!=0;f.bus.wrongVin=i==1;f.bus.moving=i==2;
    f.bus.unsupported=i==3;f.bus.changedVin=i==4;f.run();
    assert(f.bus.commands==0&&f.evidence.summary.outcome==Outcome::NotTested);
    if(i==0)assert(f.board.starts==0&&f.store.appends==0);
    else assert(f.board.stops==1);
  }
}
static void failedStorage(){
  for(unsigned i=0;i<4;++i){
    Fixture f;
    if(i==0)f.store.failAppend=2;
    if(i==1)f.store.failFlush=3;
    if(i==2)f.store.corruptPrevious=true;
    if(i==3)f.store.failAppend=4;
    f.run();
    assert(f.evidence.recoveryRequired&&f.board.recovery&&f.store.deny);
    assert(f.bus.commands==(i==3?1u:0u));
    assert(f.board.stops==1);
  }
}
static void outcomes(){
  for(unsigned i=0;i<7;++i){
    Fixture f;
    f.bus.nrc=i==0;f.bus.noAck=i==1;f.bus.revokedAfterSend=i==2;f.bus.segmentedAck=i==3;
    f.bus.readbackMissing=i==4;f.bus.remainingDtc=i==5;f.board.stopOk=i!=6;
    f.run();assert(f.bus.commands==1&&f.board.stops==1);
    if(i==0)assert(f.evidence.summary.outcome==Outcome::Refused&&f.evidence.summary.nrc==0x22);
    if(i>=1&&i<=3)assert(f.evidence.summary.outcome==Outcome::Uncertain&&f.evidence.recoveryRequired);
    if(i==4)assert(f.evidence.summary.outcome==Outcome::AcknowledgedPartial);
    if(i==5)assert(f.evidence.summary.reason==Reason::DtcRemaining);
    if(i==6)assert(!f.evidence.physicalStopVerified&&!f.evidence.terminalPersisted&&f.evidence.recoveryRequired);
  }
}
static void scopeAndSequence(){
  auto s=scope();uint8_t bytes[Scope::WireSize]{};
  Scope decoded;assert(s.encode(bytes,sizeof(bytes))&&Scope::decode(bytes,sizeof(bytes),decoded)&&s.same(decoded));
  bytes[1]=2;assert(!Scope::decode(bytes,sizeof(bytes),decoded));
  s.tx=0x7e1;s.rx=0x7e9;assert(!s.valid());
  s=scope();const uint8_t command[]={4};
  assert(s.mutation(command,1)&&!s.read(command,1));
  assert(!J1979ReadEvidence::readRequest(J1979ReadEvidence::Variant::ClassicCan,command,1));
  J1979MaintenanceSequence seq(s);assert(seq.begin(0));
  assert(!seq.admit(command,1,0,1000)&&seq.finished()&&!seq.summary().attempted);
}
int main(){validClear();validEvap();deniedPrerequisites();failedStorage();outcomes();scopeAndSequence();}
