#include "../src/j1979_read_session.h"
#include <cassert>
using namespace cd;
using namespace cd::readlink;
static bool admission(){return true;}
struct Bus final:J1979CanReadLink {
  bool active()override{return true;}
  uint32_t monotonicMs()override{return 0;}
  bool discardReceive()override{return true;}
  bool send(const Frame&,uint32_t)override{return false;}
  Receive receive(Frame&,uint32_t)override{return Receive::NoFrame;}
};
struct Port final:SpiSlavePort {
  bool allowed=true,armOk=true;unsigned faults=0;
  uint32_t now=0;
  bool admitted()override{return allowed;}
  uint32_t monotonicMs()override{return now;}
  bool arm(const uint8_t*,uint8_t*,size_t)override{return armOk;}
  void ready(bool)override{}
  void fault()override{++faults;allowed=false;}
};
struct Board final:StmJ1979ReadSession::Board {
  bool qualifiedOk=true,startOk=true,live=true,stopOk=true;
  unsigned starts=0,stops=0,recovery=0;
  bool qualified(uint32_t,uint32_t,bool)override{return qualifiedOk;}
  bool startReadChannel()override{++starts;return startOk;}
  bool admitted()override{return live;}
  bool stopAndVerifyQuiescent()override{++stops;live=false;return stopOk;}
  void latchRecovery()override{++recovery;}
};
struct Fixture {
  Board board;Bus bus;Port port;Exchange::Result scratch;
  Endpoint endpoint;SpiSlave slave;StmJ1979ReadSession session;
  Fixture():endpoint(bus,scratch,0x7e0,0x7e8,false,admission),slave(port,endpoint),
    session(board,slave,0x7e0,0x7e8,false){}
};
int main(){
  using S=StmJ1979ReadSession::State;
  Fixture denied;denied.board.qualifiedOk=false;
  assert(!denied.session.begin(100)&&denied.board.starts==0);
  Fixture good;assert(good.session.begin(100)&&good.session.poll());
  assert(good.session.stop()&&good.session.state()==S::Stopped&&good.board.stops==1);
  assert(!good.session.begin(100)&&good.board.starts==1);
  Fixture failed;failed.board.startOk=false;
  assert(!failed.session.begin(100)&&failed.board.stops==1&&failed.session.state()==S::Fault);
  Fixture unsafe;unsafe.board.startOk=false;unsafe.board.stopOk=false;
  assert(!unsafe.session.begin(100)&&unsafe.session.state()==S::Recovery&&unsafe.board.recovery==1);
  Fixture expired;assert(expired.session.begin(100));expired.port.now=100;
  assert(!expired.session.poll()&&expired.board.stops==1&&expired.session.state()==S::Fault);
  Fixture revoked;assert(revoked.session.begin(100));revoked.board.live=false;
  assert(!revoked.session.poll()&&revoked.board.stops==1&&revoked.port.faults==1);
  Fixture armFailure;armFailure.port.armOk=false;
  assert(!armFailure.session.begin(100)&&armFailure.board.stops==1);
}
