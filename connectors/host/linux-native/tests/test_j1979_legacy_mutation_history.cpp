#include "j1979_legacy_mutation_history.hpp"
#include "j1979_native_report_detail.hpp"
#include <cassert>
#include <stdexcept>
using namespace cd::legacy;
using namespace cd::legacy::history;
using cd::json::Value;
namespace {
using O=Value::Object;using A=Value::Array;
Value s(std::string_view v){return Value{std::string(v)};}
Value n(unsigned v){return Value{cd::json::Integer{std::to_string(v)}};}
constexpr std::string_view vin="1G1FA1RXXR0123456";
struct Reply{unsigned source;Data payload;};
Reply r(unsigned source,std::string_view payload){return {source,unhex(payload)};}
Data wire(Protocol p,const Reply& r){
    Data out=p==Protocol::kwp_slow||p==Protocol::kwp_fast?
        Data{static_cast<std::uint8_t>(0x80|r.payload.size()),0xf1,static_cast<std::uint8_t>(r.source)}:
        Data{static_cast<std::uint8_t>(p==Protocol::pwm?0x41:0x48),0x6b,static_cast<std::uint8_t>(r.source)};
    out.insert(out.end(),r.payload.begin(),r.payload.end());out.push_back(checksum(p,out));return out;
}
Value row(Protocol p,std::string_view stage,std::string_view command,std::vector<Reply> replies){
    std::string raw;A frames;
    for(const auto& r:replies){
        const auto f=hex(wire(p,r));if(!raw.empty())raw+='\r';raw+=f;
        frames.emplace_back(O{{"source",s(hex(Data{static_cast<std::uint8_t>(r.source)}))},{"raw_hex",s(f)},{"payload_hex",s(hex(r.payload))}});
    }
    return Value{O{{"stage",s(stage)},{"request_hex",s(command)},{"raw_response",s(raw.empty()?"NO DATA":raw)},{"frames",Value{frames}},{"error",Value{}}}};
}
Value snapshot(bool clear,bool before){
    return Value{O{
        {"10",Value{O{{"03",clear?Value{before?A{s("P0123")}:A{}}:Value{}},{"07",clear?Value{A{}}:Value{}},{"0A",clear?Value{A{s("P0123")}}:Value{}}}}},
        {"11",Value{O{{"03",clear?Value{A{}}:Value{}},{"07",clear?Value{A{}}:Value{}},{"0A",Value{}}}}}
    }};
}
struct Pair{O final,base;};
Pair capture(Protocol p,bool clear,bool missing=false){
    O common{{"variant",n(static_cast<unsigned>(p))},{"operation",s(clear?"clear":"evap")},{"scope",s("FUNCTIONAL_VEHICLE_WIDE")},
        {"expected_vin",s(vin)},{"engine_source",s("10")},{"documented_sources",Value{A{s("10"),s("11")}}},
        {"observed_vin",s(vin)},{"before_dtcs",snapshot(clear,true)}};
    A rows;
    auto emit=[&](std::string_view stage,std::string_view cmd,std::vector<Reply> replies){rows.push_back(row(p,stage,cmd,std::move(replies)));};
    auto scope=[&]{emit("SCOPE","0100",{r(16,"410080000000"),r(17,"410080000000")});};
    auto stationary=[&]{emit("STATIONARY","010D",{r(16,"410D00")});emit("STATIONARY","010C",{r(16,"410C0000")});};
    scope();emit("IDENTITY","0901",{r(16,"490105")});
    Data v{0,0,0};v.insert(v.end(),vin.begin(),vin.end());std::vector<Reply> parts;
    for(unsigned i=0;i<5;++i){Data f{0x49,2,static_cast<std::uint8_t>(i+1)};f.insert(f.end(),v.begin()+4*i,v.begin()+4*i+4);parts.push_back({16,f});}
    emit("IDENTITY","0902",parts);
    auto dtcs=[&](std::string_view stage,bool before){
        emit(stage,"03",{r(16,before?"43012300000000":"43000000000000"),r(17,"43000000000000")});
        emit(stage,"07",{r(16,"47000000000000"),r(17,"47000000000000")});
        emit(stage,"0A",{r(16,"4A012300000000")});emit(stage,"0101",{r(16,"410100000000")});
    };
    if(clear)dtcs("BEFORE",true);else emit("SUPPORT","08000000000000",{r(16,"48000080000000")});
    scope();stationary();
    O base=common;base["schema"]=s("camarodiag-j1979-legacy-baseline-v1");base["observations"]=Value{rows};
    base["after_dtcs"]=snapshot(false,false);base["outcome"]=s("BASELINE_SAVED");base["reason"]=Value{};
    base["command_attempted"]=Value{false};base["acknowledgments"]=Value{O{{"10",s("NOT_TESTED")},{"11",s("NOT_TESTED")}}};
    scope();stationary();
    if(clear){std::vector<Reply> ack{r(16,"44")};if(!missing)ack.push_back(r(17,"44"));emit("MUTATION","04",ack);dtcs("AFTER",false);}
    else emit("MUTATION","08010000000000",{r(16,"48010000000000")});
    O final=common;final["schema"]=s("camarodiag-functional-j1979-legacy-mutation-v1");final["observations"]=Value{rows};
    final["after_dtcs"]=snapshot(clear,false);final["outcome"]=s(missing?"UNCERTAIN":clear?"ACKNOWLEDGED_READBACK":"ACKNOWLEDGED");
    final["reason"]=clear?Value{}:s("LEAK_TEST_NOT_PERFORMED");final["command_attempted"]=Value{true};
    final["acknowledgments"]=Value{O{{"10",s("ACKNOWLEDGED")},{"11",s(clear&&!missing?"ACKNOWLEDGED":"NOT_TESTED")}}};
    return {final,base};
}
void rejects(O final,const O* base=nullptr){
    const auto b=base?Value{*base}:Value{};
    bool failed=false;try{(void)inspect_historical_maintenance(Value{final},base?&b:nullptr,std::string(vin));}
    catch(const std::exception&){failed=true;}assert(failed);
}
}
int main(){
    for(unsigned proto=1;proto<=5;++proto)for(bool clear:{true,false}){
        auto f=capture(static_cast<Protocol>(proto),clear);const Value base{f.base};
        const auto proof=inspect_historical_maintenance(Value{f.final},&base,std::string(vin));
        assert(proof.attempted&&proof.acknowledged&&proof.baseline_consistent);
        assert(!proof.proof.at("permits_automatic_retry").boolean()&&!proof.proof.at("durable_before_tx_verified").boolean());
        const auto no_base=inspect_historical_maintenance(Value{f.final},nullptr,std::string(vin));
        assert(no_base.proof.at("baseline_required_missing").boolean());
        auto corrupt=f.final;corrupt["expected_vin"]=s("1G1FA1RXXR0123457");rejects(corrupt);
        corrupt=f.final;corrupt["whole_vehicle_coverage_verified"]=Value{true};rejects(corrupt);
        corrupt=f.final;corrupt["command_attempted"]=Value{false};rejects(corrupt);
        corrupt=f.final;auto rows=corrupt.at("observations").array();rows.push_back(rows.back());corrupt["observations"]=Value{rows};rejects(corrupt);
        auto badbase=f.base;badbase["engine_source"]=s("11");rejects(f.final,&badbase);
        corrupt=f.final;corrupt["acknowledgments"]=Value{O{{"10",s("UNCERTAIN")},{"11",s("NOT_TESTED")}}};rejects(corrupt);
    }
    const auto f=capture(Protocol::vpw,true,true);const Value base{f.base};
    const auto partial=inspect_historical_maintenance(Value{f.final},&base,std::string(vin));
    assert(partial.attempted&&!partial.acknowledged&&partial.baseline_consistent);
    auto invalid=f.final;invalid["outcome"]=s("ACKNOWLEDGED_READBACK");rejects(invalid);
}
