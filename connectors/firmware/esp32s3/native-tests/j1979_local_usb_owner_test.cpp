#include "../src/j1979_local_usb_owner.h"
#include <cassert>
using namespace cd;
using Owner=J1979LocalUsbOwner;
struct Board final:Owner::Board {
  Owner::Snapshot value;unsigned starts=0,stops=0,latches=0;
  bool startOk=true,stopOk=true,swapOnStart=false;
  Board(){value.connection=1;value.credential=2;value.approval=3;
    value.qualified=value.consented=value.stable=value.allowlisted=value.entropyReady=true;}
  Owner::Snapshot snapshot(const Owner::Profile&)override{return value;}
  uint32_t monotonicMs()override{return 0;}
  bool hmac(uint64_t generation,const uint8_t*,size_t,uint8_t*)override{return generation==value.credential;}
  bool randomNonce(uint8_t* data,size_t n)override{std::memset(data,1,n);return true;}
  bool start(const Owner::Profile&,uint64_t generation)override{
    assert(generation);++starts;if(swapOnStart)++value.connection;return startOk;
  }
  bool stopAndVerify()override{++stops;return stopOk;}
  void latchRecovery()override{++latches;value.recovery=true;}
};
struct Fixture {
  Board board;HostSessionArbiter arbiter;Owner::Profile profile;Owner owner;
  static Owner::Profile make(){Owner::Profile p;p.tx=0x7e0;p.rx=0x7e8;p.budgetMs=10000;return p;}
  Fixture():profile(make()),owner(board,arbiter,profile){}
  bool lease(HostSessionArbiter::Lease& l){
    return owner.grantRead(profile)&&arbiter.acquire(HostInterface::NativeUsb,owner.currentEvidence(profile),l);
  }
};
int main(){
  HostSessionArbiter::Lease l;
  Fixture good;
  assert(good.lease(l)&&good.owner.startReadChannel(good.profile,l)&&good.owner.spiAdmitted());
  assert(good.owner.stopAndVerifyQuiescent()&&!good.owner.spiAdmitted()&&good.board.stops==1);
  good.owner.revokeRead();assert(!good.owner.currentHost()&&good.board.stops==1);
  Fixture other;auto wrong=other.profile;wrong.budgetMs++;
  assert(!other.owner.grantRead(wrong)&&other.board.starts==0);
  Fixture noEntropy;noEntropy.board.value.entropyReady=false;uint8_t nonce[32]{};
  assert(!noEntropy.owner.randomNonce(nonce,sizeof(nonce)));
  Fixture changed;changed.board.value.credential++;
  assert(!changed.owner.grantRead(changed.profile));
  Fixture partial;partial.board.startOk=false;
  assert(partial.lease(l)&&!partial.owner.startReadChannel(partial.profile,l));
  assert(partial.owner.stopAndVerifyQuiescent()&&partial.board.stops==1);
  Fixture replaced;replaced.board.swapOnStart=true;
  assert(replaced.lease(l)&&!replaced.owner.startReadChannel(replaced.profile,l));
  assert(replaced.owner.stopAndVerifyQuiescent()&&replaced.board.stops==1);
  Fixture failed;failed.board.stopOk=false;
  assert(failed.lease(l)&&failed.owner.startReadChannel(failed.profile,l));
  failed.owner.transportFault();
  assert(failed.arbiter.recoveryRequired()&&failed.board.latches==1&&!failed.owner.spiAdmitted());
  assert(!failed.owner.stopAndVerifyQuiescent()&&failed.board.stops==1);
  Fixture revoked;assert(revoked.lease(l)&&revoked.owner.startReadChannel(revoked.profile,l));
  ++revoked.board.value.approval;
  assert(!revoked.owner.spiAdmitted());
  revoked.owner.revokeRead();assert(revoked.board.stops==1);
  Fixture busy;HostSessionArbiter::Lease otherLease;
  auto evidence=busy.owner.currentEvidence(busy.profile);evidence.authenticated=true;
  assert(busy.arbiter.acquire(HostInterface::PcBridge,evidence,otherLease));
  assert(busy.owner.grantRead(busy.profile));
  assert(!busy.owner.startReadChannel(busy.profile,otherLease)&&busy.owner.stopAndVerifyQuiescent());
  assert(busy.board.starts==0&&busy.board.stops==0&&busy.arbiter.owns(otherLease));
}
