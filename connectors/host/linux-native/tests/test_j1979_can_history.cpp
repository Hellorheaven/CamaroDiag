#include "j1979_can_history.hpp"
#include <cassert>
#include <algorithm>
#include <sstream>
namespace {
using namespace cd::canhistory;
constexpr std::string_view vin="1G1FA1RXXR0123456";
template<class F>void rejects(F f){bool failed=false;try{f();}catch(const std::exception&){failed=true;}assert(failed);}
std::string quote(std::string_view s){return cd::json::quote(s);}
std::string vin_reply(){
    Data r{0x49,2,1};r.insert(r.end(),vin.begin(),vin.end());return cd::legacy::hex(r);
}
std::string row(std::string_view stage,std::string_view q,std::string_view response,bool camel=false){
    return "{\"stage\":"+quote(stage)+(camel?",\"requestHex\":":",\"request_hex\":")+quote(q)+
        (camel?",\"responseHex\":":",\"response_hex\":")+(response.empty()?"null":quote(response))+",\"error\":null}";
}
std::vector<std::string> initial(bool camel=false){
    return {row("PRECONDITION","0902",vin_reply(),camel),row("BEFORE","03","43010123",camel),
        row("BEFORE","07","4700",camel),row("BEFORE","0A","4A010123",camel),
        row("BEFORE","0101","410181000000",camel),row("PRECONDITION","010D","410D00",camel),
        row("PRECONDITION","010C","410C0000",camel)};
}
std::string body(bool baseline,bool camel=false,bool recheck=true,bool movement=false,std::string_view ack="44"){
    auto rows=initial(camel);
    if(!baseline&&recheck){
        rows.push_back(row("RECHECK","0902",vin_reply(),camel));
        rows.push_back(row("RECHECK","010D",movement?"410D01":"410D00",camel));
        if(!movement)rows.push_back(row("RECHECK","010C","410C0000",camel));
    }
    const bool attempted=!baseline&&!movement;
    const bool positive=attempted&&ack=="44";
    if(attempted)rows.push_back(row("RESET","04",ack,camel));
    if(positive){
        rows.push_back(row("AFTER","03","4300",camel));rows.push_back(row("AFTER","07","4700",camel));
        rows.push_back(row("AFTER","0A","4A010123",camel));rows.push_back(row("AFTER","0101","410100000000",camel));
    }
    std::string outcome=baseline?"BASELINE_SAVED":movement?"NOT_TESTED":positive?"ACKNOWLEDGED_READBACK":ack=="7F0422"?"REFUSED":"UNCERTAIN";
    std::string reason=outcome=="REFUSED"?"\"NRC_22\"":"null";
    std::ostringstream o;
    o<<"{\"outcome\":"<<quote(outcome)<<",\"reason\":"<<reason
        <<(camel?",\"commandAttempted\":":",\"command_attempted\":")<<(attempted?"true":"false")
        <<",\"acknowledged\":"<<(positive?"true":"false")<<(camel?",\"observedVin\":":",\"observed_vin\":")<<quote(vin)
        <<(camel?",\"beforeDtcs\":":",\"before_dtcs\":")<<"{\"03\":[\"P0123\"],\"07\":[],\"0A\":[\"P0123\"]}"
        <<(camel?",\"afterDtcs\":":",\"after_dtcs\":")
        <<(positive?"{\"03\":[],\"07\":[],\"0A\":[\"P0123\"]}":"{\"03\":null,\"07\":null,\"0A\":null}")
        <<",\"observations\":[";
    for(size_t i=0;i<rows.size();++i){if(i)o<<',';o<<rows[i];}
    o<<"]";
    if(camel)o<<",\"elmProtocol\":6,\"requestCanId\":\"7E0\",\"responseCanId\":\"7E8\"";
    o<<'}';return o.str();
}
std::string document(bool base,bool extended=false,bool recheck=true,bool movement=false,std::string_view ack="44"){
    const auto schema=base?"camarodiag-j1979-can-clear-baseline-v1":"camarodiag-functional-j1979-clear-v1";
    return "{\"schema\":"+quote(schema)+",\"expected_vin\":"+quote(vin)+
        ",\"tx_can_id\":"+quote(extended?"18DA10F1":"7E0")+",\"rx_can_id\":"+quote(extended?"18DAF110":"7E8")+
        ","+body(base,false,recheck,movement,ack).substr(1);
}
std::string evap(std::string_view ack){
    std::string out="{\"schema\":\"camarodiag-functional-j1979-evap-v1\",\"expected_vin\":"+quote(vin)+
        ",\"tx_can_id\":\"7E0\",\"rx_can_id\":\"7E8\",\"command_attempted\":true,\"observed_vin\":"+quote(vin)+
        ",\"outcome\":"+quote(ack=="4801"?"ACKNOWLEDGED":ack=="7F0822"?"REFUSED":"UNCERTAIN")+
        ",\"reason\":"+(ack=="7F0822"?"\"NRC_22\"":ack=="4801"?"\"LEAK_TEST_NOT_PERFORMED\"":"null")+
        ",\"exchanges\":["+row("","0902",vin_reply())+","+row("","0800","480080000000")+","+
        row("","010D","410D00")+","+row("","010C","410C0000")+","+row("","0801",ack)+"]}";
    return out;
}
void replace(std::string& s,std::string_view before,std::string_view after){
    const auto i=s.find(before);assert(i!=std::string::npos);s.replace(i,before.size(),after);
}
void clear(){
    for(bool ext:{false,true}){
        const auto raw=document(false,ext),base=document(true,ext);
        auto r=inspect(raw,base,vin);
        assert(r.attempted&&r.acknowledged&&r.baseline_consistent&&r.fresh&&r.outcome=="ACKNOWLEDGED_READBACK");
        assert(r.after[2]&&r.after[2]->at(0)=="P0123");
        assert(report(r,std::string(64,'a'),std::string(64,'b')).find("\"durable_before_tx_verified\":false")!=std::string::npos);
        auto no=inspect(raw,{},vin);assert(!no.baseline_consistent);
        auto old=inspect(document(false,ext,false),base,vin);assert(!old.fresh&&old.acknowledged);
        auto moving=inspect(document(false,ext,true,true),base,vin);assert(!moving.attempted);
        auto refused=inspect(document(false,ext,true,false,"7F0422"),base,vin);assert(refused.outcome=="REFUSED"&&!refused.acknowledged);
        auto lost=inspect(document(false,ext,true,false,""),base,vin);assert(lost.outcome=="UNCERTAIN");
        auto bad=raw;replace(bad,"\"acknowledged\":true","\"acknowledged\":false");
        rejects([&]{inspect(bad,base,vin);});
        bad=raw;replace(bad,"\"command_attempted\":true","\"command_attempted\":true,\"commandAttempted\":true");
        rejects([&]{inspect(bad,base,vin);});
        bad=raw;replace(bad,"410C0000","410C0001");rejects([&]{inspect(bad,base,vin);});
        auto badbase=base;replace(badbase,"43010123","43010124");rejects([&]{inspect(raw,badbase,vin);});
        rejects([&]{inspect(raw,document(true,!ext),vin);});
    }
}
void wrappers(){
    const auto android="{\"schema\":\"camarodiag-functional-j1979-clear-v1\",\"selectedVin\":"+quote(vin)+
        ",\"clear\":"+body(false,true)+",\"clearBaseline\":"+body(true,true)+"}";
    auto a=inspect(android,{},vin);
    assert(a.platform=="ANDROID"&&a.baseline_origin=="EMBEDDED_COPY"&&a.baseline_consistent);
    const auto windows="{\"schema\":\"camarodiag-functional-j1979-clear-v1\",\"expectedVin\":"+quote(vin)+
        ",\"requestCanId\":\"7E0\",\"responseCanId\":\"7E8\",\"result\":"+body(false)+",\"baseline\":"+body(true)+"}";
    auto w=inspect(windows,{},vin);
    assert(w.platform=="J2534"&&w.baseline_consistent);
}
void evap_cases(){
    for(auto ack:{"4801","7F0822",""}){
        auto r=inspect(evap(ack),{},vin);
        assert(r.scope.evap&&r.attempted&&r.acknowledged==(std::string_view(ack)=="4801")&&!r.baseline_consistent);
    }
    auto bad=evap("4801");replace(bad,"480080000000","480000000000");
    rejects([&]{inspect(bad,{},vin);});
}
}
int main(){clear();wrappers();evap_cases();}
