#include "j1979_cj1.hpp"
#include <cassert>
#include <cstdint>
#include <initializer_list>
#include <string>
#include <vector>

namespace {
using Data=std::vector<std::uint8_t>;
void put16(Data& d,unsigned n){
    d.push_back(static_cast<std::uint8_t>(n));
    d.push_back(static_cast<std::uint8_t>(n>>8));
}
void put32(Data& d,std::uint32_t n){
    for(unsigned i=0;i<4;++i)d.push_back(static_cast<std::uint8_t>(n>>(8*i)));
}
std::uint32_t crc(const Data& d){
    std::uint32_t c=0xffffffffu;
    for(auto v:d){
        c^=v;
        for(unsigned i=0;i<8;++i)c=(c>>1)^((c&1)?0xedb88320u:0u);
    }
    return c^0xffffffffu;
}
void record(Data& capture,unsigned kind,unsigned seq,const Data& body){
    Data d{'C','J',1,static_cast<std::uint8_t>(kind)};
    put16(d,seq);put16(d,static_cast<unsigned>(body.size()));
    d.insert(d.end(),body.begin(),body.end());
    put32(d,crc(d));
    capture.insert(capture.end(),d.begin(),d.end());
}
void obs(Data& capture,unsigned seq,unsigned state,const Data& q,const Data& r){
    Data d{static_cast<std::uint8_t>(state),static_cast<std::uint8_t>(q.size())};
    put32(d,10);put16(d,static_cast<unsigned>(r.size()));
    d.insert(d.end(),q.begin(),q.end());d.insert(d.end(),r.begin(),r.end());
    record(capture,2,seq,d);
}
void terminal(Data& d,unsigned seq,unsigned end,unsigned flags,unsigned steps,unsigned passed,
              unsigned raw=0,unsigned negative=0,unsigned timeout=0){
    Data b{static_cast<std::uint8_t>(end),static_cast<std::uint8_t>(flags)};
    for(auto n:{steps,passed,raw,negative,timeout})put32(b,n);
    record(d,3,seq,b);
}
const std::string vin="1G1FC3DD0C9000001";
Data start(){
    const auto p=cd::cj1::encode_profile({0x7e0,0x7e8,10000,false});
    Data d;record(d,1,0,Data(p.begin(),p.end()));return d;
}
Data complete(unsigned flags=231,bool swap=false,bool wrong_width=false){
    auto d=start();
    Data identity{0x49,2,1};identity.insert(identity.end(),vin.begin(),vin.end());
    obs(d,1,1,{1,0},{0x41,0,0,0,0,0});
    if(swap){
        obs(d,2,1,{3},{0x43,0});obs(d,3,1,{9,2},identity);
    }else{
        obs(d,2,1,{9,2},identity);obs(d,3,1,{3},{0x43,0});
    }
    obs(d,4,1,{7},{0x47,0});
    obs(d,5,1,{10},{0x4a,0});
    obs(d,6,1,{2,2,0},{0x42,2,0,0,0});
    if(wrong_width)obs(d,7,1,{6,0},{0x46,0,0});
    else obs(d,7,1,{6,0},{0x46,0,0,0,0,0});
    obs(d,8,1,{8,0},{0x48,0,0,0,0,0});
    obs(d,9,1,{9,0},{0x49,0,0,0,0,0});
    terminal(d,10,1,flags,9,9);
    return d;
}
}
int main(){
    assert(cd::cj1::valid_profile({0x7e0,0x7e8,10000,false}));
    assert(!cd::cj1::valid_profile({0x7df,0x7e8,10000,false}));
    assert(!cd::cj1::valid_profile({0x7e0,0x7e8,0,false}));
    auto d=complete();auto e=cd::cj1::inspect(d);
    assert(e.integrity_verified&&e.profile_verified&&e.end==1&&e.flags==231);
    assert(e.vin==vin&&e.steps.size()==9&&e.counts[1]==9);
    const auto report=cd::cj1::report(e,vin,std::string(64,'a'));
    assert(report.find("\"vin_match\":\"MATCH\"")!=std::string::npos);
    assert(report.find("\"physical_vehicle_verified\":false")!=std::string::npos);
    assert(report.find("\"permits_programming\":false")!=std::string::npos);
    assert(report.find("\"device_credential_authenticated\":false")!=std::string::npos);
    assert(!cd::cj1::inspect(complete(255)).integrity_verified);
    assert(!cd::cj1::inspect(complete(231,true)).integrity_verified);
    assert(!cd::cj1::inspect(complete(231,false,true)).integrity_verified);
    d.push_back(0);assert(!cd::cj1::inspect(d).integrity_verified);
    d=complete();d.pop_back();e=cd::cj1::inspect(d);
    assert(!e.integrity_verified&&e.steps.size()==9&&!e.error.empty());
    d=complete();d[12]^=1;assert(!cd::cj1::inspect(d).profile_verified);
    d=start();obs(d,1,1,{4},{0x44});
    assert(!cd::cj1::inspect(d).integrity_verified);
    d=start();obs(d,1,1,{8,1},{0x48,1,0});
    assert(!cd::cj1::inspect(d).integrity_verified);
    d=start();obs(d,1,4,{1,0},{});terminal(d,2,2,0,1,0,0,0,1);
    e=cd::cj1::inspect(d);assert(e.integrity_verified&&e.end==2&&e.counts[4]==1);
    d=start();obs(d,1,3,{1,0},{0x7f,1,0x78});
    assert(!cd::cj1::inspect(d).integrity_verified);
    d=start();obs(d,1,1,{1,0},{0x41,0,0,0,0,0});
    terminal(d,2,3,1,1,1);
    e=cd::cj1::inspect(d);assert(e.integrity_verified&&e.end==3&&e.flags==1);
}
