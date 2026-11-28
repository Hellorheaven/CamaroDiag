#include "j1979_cj1.hpp"
#include "j1979_cj1_history.hpp"
#include "j1979_native_report_detail.hpp"
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
cd::json::Value historic_report(const cd::cj1::Evidence& e,bool live=false){
    using cd::json::Value;
    auto root=cd::json::parse_report(cd::cj1::report(e,vin,std::string(64,'a'))).object();
    const auto profile=root.at("profile").object();
    for(const auto& [k,v]:profile)root[k]=v;
    root.erase("profile");
    root["schema"]=Value{std::string(live?"camarodiag-functional-j1979-native-usb-v1":"camarodiag-functional-j1979-embedded-evidence-v1")};
    root["source"]=Value{std::string(live?"LIVE_USB_CU1":"OFFLINE_CAPTURE")};
    root["device_credential_authenticated"]=Value{live};
    root["frame_integrity_verified"]=Value{live};
    root["records"]=Value{cd::json::Integer{std::to_string(e.steps.size()+2)}};
    root["cancelled"]=Value{false};
    // The complete fixture has no scalar or monitor rows; older exports omit
    // optional projection families and only carry the core replayed claims.
    for(auto key:{"multi_signal_records","status_records","extended_vehicle_info"})root.erase(key);
    return Value{std::move(root)};
}
void historical_checks(){
    using cd::json::Value;
    auto raw=complete();const auto e=cd::cj1::inspect(raw);
    const std::string a(64,'a'),b(64,'b');
    for(bool live:{false,true}){
        auto saved=historic_report(e,live);
        auto checked=cd::cj1::verify_historical(raw,saved,a,b,vin);
        assert(checked.consistent&&checked.verified);
        assert(checked.proof.at("vin_match").string()=="MATCH");
        assert(!checked.proof.at("device_credential_independently_verified").boolean());
        auto fields=saved.object();
        fields["observed_vin"]=Value{std::string("1G1FA1RXXR0123456")};
        checked=cd::cj1::verify_historical(raw,Value{fields},a,b,vin);
        assert(!checked.consistent&&!checked.verified);
        fields=saved.object();fields["cancelled"]=Value{true};
        checked=cd::cj1::verify_historical(raw,Value{fields},a,b,vin);
        assert(!checked.verified);
        fields=saved.object();fields["physical_vehicle_verified"]=Value{true};
        assert(!cd::cj1::verify_historical(raw,Value{fields},a,b,vin).consistent);
        fields=saved.object();
        auto rows=fields.at("steps").array();auto row=rows[1].object();row["duration_ms"]=Value{cd::json::Integer{"999"}};
        rows[1]=Value{row};fields["steps"]=Value{rows};
        checked=cd::cj1::verify_historical(raw,Value{fields},a,b,vin);
        assert(!checked.consistent);
        bool found=false;
        for(const auto& reason:checked.proof.at("differences").array())if(reason.string()=="steps[1].duration_ms")found=true;
        assert(found);
        fields=saved.object();fields["scaled_monitor_records"]=cd::json::parse_report("[{}]");
        assert(!cd::cj1::verify_historical(raw,Value{fields},a,b,vin).consistent);
    }
    auto saved=historic_report(e);
    auto different=cd::cj1::verify_historical(raw,saved,a,b,std::string("1G1FA1RXXR0123456"));
    assert(!different.verified&&different.proof.at("vin_match").string()=="MISMATCH");
    raw.pop_back();
    assert(!cd::cj1::verify_historical(raw,saved,a,b,vin).verified);
}

}
int main(){
    historical_checks();
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
