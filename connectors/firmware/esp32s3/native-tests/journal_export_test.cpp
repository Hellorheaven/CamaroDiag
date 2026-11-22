#include "../src/journal_export.h"
#include <cassert>
#include <cstring>
using namespace cd::journal;
int main(){
  ExportRequest request;
  const char* command="CDJ/1 READ\n";
  for(size_t i=0;i<std::strlen(command)-1;++i)assert(!request.feed(uint8_t(command[i]),uint32_t(i)));
  assert(request.feed('\n',20)&&request.closed());
  assert(!request.feed('C',21));
  ExportRequest malformed;
  assert(!malformed.feed('X',0)&&malformed.closed());
  ExportRequest expired;
  assert(!expired.feed('C',0));expired.poll(2000);assert(expired.closed());
  ExportRequest slow;
  assert(!slow.feed('C',1));assert(!slow.feed('D',2001)&&slow.closed());
  // A wrong byte, a repeated request or a stalled partial command must
  // never release two snapshots during the same service-firmware boot.
  ExportRequest wrongSuffix;
  const char* partial="CDJ/1 READ";
  for(size_t i=0;i<std::strlen(partial);++i)
    assert(!wrongSuffix.feed(uint8_t(partial[i]),uint32_t(i)));
  assert(!wrongSuffix.feed('X',50)&&wrongSuffix.closed());
  assert(!wrongSuffix.feed('\\n',51));
  ExportRequest repeated;
  for(size_t i=0;i<std::strlen(command);++i)
    assert(repeated.feed(uint8_t(command[i]),uint32_t(i))==(i==std::strlen(command)-1));
  for(size_t i=0;i<std::strlen(command);++i)
    assert(!repeated.feed(uint8_t(command[i]),uint32_t(i+40)));
  ExportRequest elapsedBeforeFinish;
  assert(!elapsedBeforeFinish.feed('C',123));
  elapsedBeforeFinish.poll(2123);
  assert(elapsedBeforeFinish.closed());
  Record rec;rec.transaction[0]=1;rec.manifestDigest[0]=2;
  rec.stage=Stage::EraseIntent;rec.sequence=1;
  char line[140]{};
  size_t count=exportLine(BootDisposition::RecoveryRequired,rec,line,sizeof(line));
  assert(count==135&&std::memcmp(line,"CDJ/1 43444a010200000001000000",30)==0&&line[count-1]=='\n');
  assert(exportLine(BootDisposition::RecoveryRequired,rec,line,134)==0);
  assert(exportLine(BootDisposition::NoRecord,rec,line,sizeof(line))==11);
  assert(std::memcmp(line,"CDJ/1 NONE\n",11)==0);
  assert(exportLine(BootDisposition::SafeButBlocked,rec,line,sizeof(line))==14);
  assert(std::memcmp(line,"CDJ/1 CORRUPT\n",14)==0);
}
