#include "../j1979_maintenance_archive_link.h"
#include "../../esp32s3/src/j1979_maintenance_archive_usb.h"
#include <cassert>
#include <vector>

namespace al=cd::archive_link;
using Archive=cd::J1979MaintenanceArchive;
using Scope=cd::J1979MaintenanceScope;
using Usb=cd::J1979MaintenanceArchiveUsb;

static Scope target(){
  Scope scope;scope.tx=0x7e0;scope.rx=0x7e8;scope.budgetMs=60000;
  std::memcpy(scope.vin,"1G1FA1RXXR0123456",17);return scope;
}
struct Source final:Archive::Source {
  Scope expected=target();
  Archive::Info declaration{};
  std::vector<uint8_t> data;
  size_t offset=0;unsigned opens=0,reads=0,finishes=0,closes=0;
  bool opened=false,allowed=true,finishAllowed=true,readAllowed=true;
  explicit Source(size_t count=517):data(count){
    for(size_t i=0;i<count;++i)data[i]=uint8_t(i%251);
    declaration.bytes=uint32_t(count);declaration.records=2;
    declaration.terminal=true;declaration.recoveryRequired=false;
  }
  bool open(const Scope& scope,Archive::Info& info)override{
    ++opens;if(opened||!allowed||!scope.same(expected))return false;
    opened=true;info=declaration;return true;
  }
  bool current()override{return opened&&allowed;}
  bool read(size_t pos,uint8_t* out,size_t n)override{
    ++reads;
    if(!current()||!readAllowed||pos!=offset||!out||!n||pos>data.size()||n>data.size()-pos)return false;
    std::memcpy(out,data.data()+pos,n);offset+=n;return true;
  }
  bool finish()override{++finishes;return finishAllowed&&current()&&offset==data.size();}
  void close()override{++closes;opened=false;}
};
struct Connection final:al::Endpoint::Connection {
  Scope expected=target();bool connected=true;uint32_t now=0;
  bool present(const Scope& scope)override{return connected&&scope.same(expected);}
  uint32_t monotonicMs()override{return now;}
};
struct Wire final:al::Client::Transport {
  al::Endpoint& endpoint;Connection& connection;
  bool failed=false,loseClose=false,badOffset=false,badSequence=false;
  unsigned requests=0,fetches=0,starts=0;
  Wire(al::Endpoint& e,Connection& c):endpoint(e),connection(c){}
  bool active()override{return !failed&&connection.connected;}
  uint32_t monotonicMs()override{return connection.now;}
  bool exchange(const uint8_t* input,uint8_t* output,uint32_t timeout)override{
    assert(timeout>0&&timeout<=1000);++requests;
    al::Packet p,q;
    if(!al::decode(input,al::Size,p))return false;
    if(p.op==al::Op::Start)++starts;
    if(p.op==al::Op::Fetch)++fetches;
    ++connection.now;
    if(!endpoint.transact(input,al::Size,output,al::Size))return false;
    if(!al::decode(output,al::Size,q))return false;
    if(p.op==al::Op::Close&&loseClose)return false;
    if(p.op==al::Op::Fetch&&badOffset){q.body[0]^=1;return al::encode(q,output,al::Size);}
    if(p.op==al::Op::Fetch&&badSequence){++q.sequence;return al::encode(q,output,al::Size);}
    return true;
  }
  void fault()override{failed=true;endpoint.fault();}
};
struct Fixture{
  Source source;Connection connection;al::Endpoint endpoint;Wire wire;al::Client client;
  explicit Fixture(size_t count=517):source(count),endpoint(source,connection,target()),
    wire(endpoint,connection),client(wire,target()){}
};
static void transfer(size_t n,size_t chunk,bool prefix){
  Fixture f(n);
  if(prefix){
    f.source.declaration.records=1;
    f.source.declaration.terminal=false;f.source.declaration.damagedTail=true;
    f.source.declaration.recoveryRequired=true;
  }
  Archive::Info info;
  assert(f.client.open(info)&&info.same(f.source.declaration));
  std::vector<uint8_t> output(n);
  for(size_t pos=0;pos<n;){
    size_t take=n-pos<chunk?n-pos:chunk;
    assert(f.client.read(pos,output.data()+pos,take));pos+=take;
  }
  assert(output==f.source.data);
  assert(f.client.finish()&&f.endpoint.closed()&&f.source.finishes==1);
  assert(!f.wire.starts&&f.wire.fetches==(n+al::ChunkBytes-1)/al::ChunkBytes);
  assert(f.source.opens==1&&f.source.closes==1);
}
static void malformed(){
  al::Packet p,q;uint8_t raw[al::Size]{};
  p.op=al::Op::Start;
  assert(!al::encode(p,raw,sizeof(raw)));
  assert(cd::maintenance_link::encode(p,raw,sizeof(raw)));
  assert(!al::decode(raw,sizeof(raw),q));
  raw[2]=5;cd::maintenance_link::p32(raw+28,cd::link::crc(raw,28));
  assert(!al::decode(raw,sizeof(raw),q));
  p.op=al::Op::Poll;assert(al::encode(p,raw,sizeof(raw)));
  assert(!cd::maintenance_link::decode(raw,sizeof(raw),q));
  raw[8]=1;cd::maintenance_link::p32(raw+28,cd::link::crc(raw,28));
  assert(!al::decode(raw,sizeof(raw),q));
  Archive::Info info;info.bytes=47;info.records=1;info.terminal=true;
  assert(!al::encodeInfo(info,p));
  info.terminal=false;info.recoveryRequired=false;assert(!al::encodeInfo(info,p));
  info.recoveryRequired=true;assert(al::encodeInfo(info,p)&&al::decodeInfo(p,info));
  p.body[2]=1;assert(!al::decodeInfo(p,info));
}
static void failures(){
  Archive::Info info;uint8_t out[517]{};
  {Fixture f;Scope wrong=target();wrong.vin[0]='2';al::Client client(f.wire,wrong);
   assert(!client.open(info)&&!f.source.opens&&f.endpoint.closed());}
  {Fixture f;assert(f.client.open(info));assert(!f.client.read(1,out,1));
   assert(f.endpoint.closed()&&!f.source.finishes);}
  {Fixture f;assert(f.client.open(info));assert(!f.client.finish());
   assert(f.endpoint.closed()&&!f.source.finishes);}
  {Fixture f;assert(f.client.open(info));f.source.allowed=false;
   assert(!f.endpoint.poll()&&f.endpoint.closed()&&!f.source.finishes);}
  {Fixture f;assert(f.client.open(info));f.connection.now=20001;
   assert(!f.client.read(0,out,1)&&f.endpoint.closed());}
  {Fixture f;assert(f.client.open(info));f.wire.badOffset=true;
   assert(!f.client.read(0,out,1)&&f.wire.failed&&!f.source.finishes);}
  {Fixture f;assert(f.client.open(info));f.wire.badSequence=true;
   assert(!f.client.read(0,out,1)&&f.wire.failed&&!f.source.finishes);}
  {Fixture f;assert(f.client.open(info));f.source.readAllowed=false;
   assert(!f.client.read(0,out,1)&&f.endpoint.closed());}
  {Fixture f;assert(f.client.open(info));assert(f.client.read(0,out,sizeof(out)));
   f.source.finishAllowed=false;assert(!f.client.finish()&&f.source.finishes==1&&f.endpoint.closed());}
  {Fixture f;assert(f.client.open(info));assert(f.client.read(0,out,sizeof(out)));
   f.wire.loseClose=true;assert(!f.client.finish()&&f.source.finishes==1&&f.endpoint.closed());}
  {Fixture f;assert(f.client.open(info));f.client.abort();
   assert(f.endpoint.closed()&&!f.source.finishes);}
}
struct Security final:Usb::Security {
  Scope expected=target();bool connected=true,permit=true,entropy=true;uint32_t now=0;
  unsigned macs=0;
  bool approved(const Scope& scope)override{return permit&&scope.same(expected);}
  bool present()override{return connected;}
  bool nonce(uint8_t* out,size_t n)override{
    if(!entropy||n!=32)return false;
    std::memset(out,'D',n);return true;
  }
  // Deterministic test double, deliberately NOT a real HMAC. The host
  // suites separately exercise the real HMAC-SHA256 implementation.
  static void tag(const uint8_t* data,size_t n,uint8_t* out){
    for(unsigned i=0;i<32;++i)out[i]=uint8_t(data[0]^data[1+i]^data[n-1-i]^i);
  }
  bool hmac(const uint8_t* data,size_t n,uint8_t* out)override{
    ++macs;if(n!=Usb::TranscriptSize||(data[0]!='R'&&data[0]!='r'))return false;
    tag(data,n,out);return true;
  }
  uint32_t monotonicMs()override{return now;}
};
struct Channel final:Usb::Channel {
  Security& security;Source& source;
  std::vector<uint8_t> hello,proof,output;
  bool opened=true,wrongProof=false,failReceipt=false,revokeAtProof=false,slowProof=false;
  unsigned reads=0,writes=0,closes=0;
  Channel(Security& s,Source& a):security(s),source(a),hello(Usb::HelloSize),proof(Usb::ProofSize){
    hello[0]='C';hello[1]='U';hello[2]=5;hello[3]=1;
    assert(s.expected.encode(hello.data()+4,Scope::WireSize));
    std::memset(hello.data()+4+Scope::WireSize,'H',32);
    uint8_t transcript[Usb::TranscriptSize]{};transcript[0]='r';
    std::memcpy(transcript+1,hello.data()+4,63);std::memset(transcript+64,'D',32);
    proof[0]='C';proof[1]='U';proof[2]=5;proof[3]=3;
    Security::tag(transcript,sizeof(transcript),proof.data()+4);
  }
  bool present()override{return opened;}
  uint32_t monotonicMs()override{return security.now;}
  bool readExact(uint8_t* out,size_t n,uint32_t timeout)override{
    ++reads;assert(timeout>0);
    if(reads==1&&n==hello.size()){std::memcpy(out,hello.data(),n);return true;}
    if(reads==2&&n==proof.size()){
      std::memcpy(out,proof.data(),n);
      if(wrongProof)out[4]^=1;
      if(revokeAtProof)security.permit=false;
      if(slowProof)security.now+=30000;
      return true;
    }
    return false;
  }
  bool writeExact(const uint8_t* data,size_t n,uint32_t timeout)override{
    ++writes;assert(timeout>0);
    if(n==Usb::TrailerSize&&data[0]=='C'&&data[1]=='U'&&data[2]==5&&data[3]==5){
      assert(source.finishes==1);
      if(failReceipt)return false;
    }
    output.insert(output.end(),data,data+n);return true;
  }
  void close()override{opened=false;++closes;}
};
static void usb(){
  for(unsigned scenario=0;scenario<9;++scenario){
    Source source;Security sec;Channel channel(sec,source);
    if(scenario==1)channel.wrongProof=true;
    if(scenario==2)channel.revokeAtProof=true;
    if(scenario==3)channel.slowProof=true;
    if(scenario==4)source.finishAllowed=false;
    if(scenario==5)channel.failReceipt=true;
    if(scenario==6)source.readAllowed=false;
    if(scenario==7)sec.entropy=false;
    if(scenario==8)channel.hello[2]=4;
    auto result=Usb::serve(channel,sec,source);
    assert(channel.closes==1&&!channel.opened&&!source.opened);
    assert(result.complete==(scenario==0));
    if(scenario==0){
      assert(result.authenticated&&result.sourceOpened&&result.bytes==source.data.size());
      assert(channel.output.size()==Usb::ChallengeSize+Usb::HeaderSize+source.data.size()+Usb::TrailerSize);
      const size_t start=Usb::ChallengeSize+Usb::HeaderSize;
      assert(std::memcmp(channel.output.data()+start,source.data.data(),source.data.size())==0);
      const auto* trailer=channel.output.data()+start+source.data.size();
      assert(trailer[3]==5&&cd::maintenance_link::u32(trailer+4)==source.data.size()&&
        cd::maintenance_link::u32(trailer+8)==cd::link::crc(source.data.data(),source.data.size()));
    }else if(scenario==4||scenario==5){
      assert(result.bytes==source.data.size()&&source.finishes==1);
      assert(channel.output.size()==Usb::ChallengeSize+Usb::HeaderSize+source.data.size());
    }else if(scenario!=6){
      assert(!source.opens&&!result.authenticated);
    }
  }
}
int main(){
  for(size_t n:{size_t(47),size_t(48),size_t(517),size_t(8193)})
    for(size_t chunk:{size_t(1),size_t(16),size_t(31),size_t(256)})transfer(n,chunk,false);
  transfer(47,13,true);malformed();failures();usb();
}
