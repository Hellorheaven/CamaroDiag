#include "../src/j1979_read_guard.h"
#include <cassert>
using G=cd::J1979ReadGuard;
struct Store final:G::Store {
  uint8_t bytes[G::Size]{};bool readable=true,writable=true,corruptWrite=false;
  unsigned writes=0;
  Store(){G::Record clean;assert(G::encode(clean,bytes,sizeof(bytes)));}
  bool read(uint8_t* out,size_t n)override{
    if(!readable||n!=sizeof(bytes))return false;
    std::memcpy(out,bytes,n);return true;
  }
  bool writeVerified(const uint8_t* in,size_t n)override{
    ++writes;if(!writable||n!=sizeof(bytes))return false;
    std::memcpy(bytes,in,n);if(corruptWrite)bytes[7]^=1;return true;
  }
};
int main(){
  Store normal;G first(normal);
  assert(first.inspect()&&first.begin()&&first.inspect()&&normal.writes==1);
  G reboot(normal);
  assert(!reboot.inspect()&&reboot.locked()&&!reboot.finishVerified()&&!reboot.begin());
  assert(first.finishVerified()&&normal.writes==2);
  G second(normal);assert(second.inspect()&&second.begin()&&second.finishVerified());
  G::Record r;assert(G::decode(normal.bytes,sizeof(normal.bytes),r)&&r.generation==2);
  Store missing;missing.readable=false;G blocked(missing);
  assert(!blocked.inspect()&&!blocked.begin()&&missing.writes==0);
  Store bad;bad.corruptWrite=true;G uncertain(bad);
  assert(!uncertain.begin()&&uncertain.locked()&&!uncertain.finishVerified());
  Store stop;G active(stop);assert(active.begin());stop.writable=false;
  assert(!active.finishVerified()&&active.locked());
  G interrupted(stop);assert(!interrupted.inspect());
  Store changed;G session(changed);assert(session.begin());
  G::Record clean;assert(G::encode(clean,changed.bytes,sizeof(changed.bytes)));
  assert(!session.finishVerified()&&session.locked());
}
