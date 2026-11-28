#include "j1979_legacy_freeze_wire.hpp"
#include <algorithm>
#include <cassert>
#include <deque>
#include <utility>
using namespace cd::legacy;
namespace {
constexpr std::string_view VIN="1G1FA1E30D1234567";
constexpr std::string_view DIFFERENT="1G1FA1E30D1234568";
std::string wire(Protocol p,unsigned source,const Data& payload){
    Data frame;
    if(p==Protocol::kwp_slow||p==Protocol::kwp_fast)frame={std::uint8_t(0x80|payload.size()),0xf1,std::uint8_t(source)};
    else frame={std::uint8_t(p==Protocol::pwm?0x41:0x48),0x6b,std::uint8_t(source)};
    frame.insert(frame.end(),payload.begin(),payload.end());frame.push_back(checksum(p,frame));
    return hex(frame)+"\r";
}
std::string vin_rows(Protocol p,std::string_view value,unsigned source=0x10){
    Data bytes(3,0);bytes.insert(bytes.end(),value.begin(),value.end());
    std::string raw;
    for(unsigned i=0;i<5;++i){
        Data payload{0x49,2,std::uint8_t(i+1)};
        payload.insert(payload.end(),bytes.begin()+4*i,bytes.begin()+4*(i+1));
        raw+=wire(p,source,payload);
    }
    return raw;
}
using Trace=std::vector<FreezeExchange>;
void add(Trace& trace,std::string command,std::string raw){
    trace.push_back({std::move(command),std::move(raw),FreezeOutcome::received,10,true});
}
void identity(Trace& trace,Protocol p,std::string_view vin){
    add(trace,"0100",wire(p,0x10,{0x41,0,0,0,0,0})+wire(p,0x20,{0x41,0,0,0,0,0}));
    add(trace,"ATDPN",std::to_string(static_cast<unsigned>(p)));
    add(trace,"0900",wire(p,0x10,{0x49,0,1,0xc0,0,0,0}));
    add(trace,"0901",wire(p,0x10,{0x49,1,5}));
    add(trace,"0902",vin_rows(p,vin));
}
FreezePlan plan(){return {std::string(VIN),{0,7},{5,12},60000};}
Trace fixture(Protocol p){
    Trace t;identity(t,p,VIN);
    add(t,"020000",wire(p,0x10,{0x42,0,0,0x48,0x10,0,0}));
    add(t,"020200",wire(p,0x10,{0x42,2,0,1,0x33}));
    add(t,"020500",wire(p,0x10,{0x42,5,0,0x5a}));
    add(t,"020C00",wire(p,0x10,{0x42,12,0,0x1f,0x40}));
    add(t,"020200",wire(p,0x10,{0x42,2,0,1,0x33}));
    add(t,"020007",wire(p,0x10,{0x42,0,7,0x40,0,0,0}));
    add(t,"020207",wire(p,0x10,{0x42,2,7,0,0}));
    identity(t,p,VIN);return t;
}
template<class F>void rejects(F f){bool rejected=false;try{f();}catch(const std::invalid_argument&){rejected=true;}assert(rejected);}
struct Sim:Link{
    Trace trace;
    std::size_t index{};
    unsigned configured{};
    Protocol protocol;
    explicit Sim(Protocol p,Trace t):trace(std::move(t)),protocol(p){}
    std::string command(std::string_view request,unsigned timeout)override{
        assert(timeout>=1&&timeout<=4000);
        if(configured<7){
            const std::array<std::string,7> setup{"ATZ","ATE0","ATL0","ATS1","ATH1",
                "ATSP"+std::to_string(static_cast<unsigned>(protocol)),
                protocol==Protocol::pwm?"ATSH616AF1":protocol==Protocol::kwp_slow||protocol==Protocol::kwp_fast?"ATSHC033F1":"ATSH686AF1"};
            assert(setup[configured]==request);
            return configured++==0?"ELM327":"OK";
        }
        assert(index<trace.size()&&trace[index].command==request);
        return trace[index++].raw;
    }
};
void all_variants(){
    for(unsigned n=1;n<=5;++n){
        const auto p=static_cast<Protocol>(n);
        const auto t=fixture(p);
        const auto result=replay_freeze(p,0x10,plan(),t);
        assert(result.completed&&result.all_frames_stable&&result.identity_rechecked);
        assert(result.initial_vin==VIN&&result.final_vin==VIN);
        assert(result.frames.size()==2&&result.frames[0].state=="COMPLETE"&&result.frames[1].state=="ABSENT");
        assert(result.frames[0].read==std::set<unsigned>({5,12}));
        assert(result.other_sources==std::set<unsigned>{0x20});
        const auto capture=encode_freeze_capture({p,0x10,plan(),t});
        const auto decoded=decode_freeze_capture(capture);
        assert(decoded.plan==plan()&&decoded.trace==t&&decoded.protocol==p&&decoded.source==0x10);
        const auto verified=inspect_freeze_capture(capture,p,0x10,VIN);
        assert(freeze_report(result)==freeze_report(verified));
        assert(freeze_report(result).find("\"number\":2000")!=std::string::npos);
        assert(freeze_report(result).find("\"physical_vehicle_verified\":false")!=std::string::npos);
        rejects([&]{inspect_freeze_capture(capture,p,0x20,VIN);});
        rejects([&]{inspect_freeze_capture(capture,p,0x10,DIFFERENT);});
        auto corrupted=capture;corrupted[16]^=1;rejects([&]{decode_freeze_capture(corrupted);});
        corrupted=capture;corrupted.back()^=1;rejects([&]{decode_freeze_capture(corrupted);});
        corrupted=capture;corrupted.pop_back();rejects([&]{decode_freeze_capture(corrupted);});
        corrupted=capture;corrupted.push_back(0);rejects([&]{decode_freeze_capture(corrupted);});
        auto missing=t;missing.erase(missing.begin()+7);rejects([&]{replay_freeze(p,0x10,plan(),missing);});
        auto extra=t;extra.push_back(t.back());rejects([&]{replay_freeze(p,0x10,plan(),extra);});
        Sim sim(p,t);
        Elm elm(sim,p,true,[]{return true;});elm.configure();
        std::uint64_t now=100;
        const auto acquired=acquire_freeze(elm,0x10,plan(),[&]{return now++;},[]{return true;});
        assert(acquired.completed&&acquired.all_frames_stable&&sim.index==t.size());
        assert(replay_freeze(p,0x10,plan(),acquired.trace).all_frames_stable);
    }
}
void numbered_frame(){
    for(unsigned n=1;n<=5;++n){
        const auto p=static_cast<Protocol>(n);
        Trace t;identity(t,p,VIN);
        add(t,"020007",wire(p,0x10,{0x42,0,7,0x40,0x10,0,0}));
        add(t,"020207",wire(p,0x10,{0x42,2,7,1,0x33}));
        add(t,"020C07",wire(p,0x10,{0x42,12,7,0x1f,0x40}));
        add(t,"020207",wire(p,0x10,{0x42,2,7,1,0x33}));
        identity(t,p,VIN);
        const FreezePlan selected{std::string(VIN),{7},{12},60000};
        const auto result=replay_freeze(p,0x10,selected,t);
        assert(result.completed&&result.frames[0].state=="COMPLETE");
        const auto json=freeze_report(result);
        assert(json.find("\"mode\":2,\"frame\":7,\"pid\":12")!=std::string::npos);
        assert(json.find("\"number\":2000")!=std::string::npos);
        t[7].raw=wire(p,0x10,{0x42,12,0,0x1f,0x40});
        const auto wrong=replay_freeze(p,0x10,selected,t);
        assert(wrong.frames[0].state=="PARTIAL");
        assert(freeze_report(wrong).find("\"number\":2000")==std::string::npos);
    }
}
void interrupted_and_corrupt(){
    const auto p=Protocol::vpw;
    auto trace=fixture(p);
    trace[9].raw=wire(p,0x10,{0x42,2,0,1,0x34});
    auto changed=replay_freeze(p,0x10,plan(),trace);
    assert(changed.completed&&!changed.all_frames_stable&&changed.frames[0].state=="CHANGED");
    assert(freeze_report(changed).find("\"number\":2000")==std::string::npos);
    trace=fixture(p);trace[8].raw=wire(p,0x10,{0x42,12,0,0x1f});
    auto malformed=replay_freeze(p,0x10,plan(),trace);
    assert(malformed.completed&&!malformed.all_frames_stable&&malformed.frames[0].state=="PARTIAL");
    trace=fixture(p);trace[8].raw=wire(p,0x20,{0x42,12,0,0x1f,0x40});
    auto other=replay_freeze(p,0x10,plan(),trace);
    assert(other.completed&&!other.all_frames_stable);
    trace=fixture(p);trace[1].raw="A2";trace.resize(2);
    auto mismatch=replay_freeze(p,0x10,plan(),trace);
    assert(!mismatch.completed&&mismatch.error=="PROTOCOL_MISMATCH");
    trace=fixture(p);trace[4].raw=vin_rows(p,DIFFERENT);trace.resize(5);
    auto wrong=replay_freeze(p,0x10,plan(),trace);
    assert(!wrong.completed&&wrong.error=="VIN_MISMATCH");
    trace=fixture(p);trace.back().raw=vin_rows(p,DIFFERENT);
    auto wrong_final=replay_freeze(p,0x10,plan(),trace);
    assert(!wrong_final.completed&&wrong_final.error=="FINAL_VIN_MISMATCH"&&!wrong_final.identity_rechecked);
    assert(freeze_report(wrong_final).find("\"number\":2000")==std::string::npos);
    trace=fixture(p);trace.resize(8);
    trace.back()={"020500","",FreezeOutcome::cancelled,0,false};
    auto cancelled=replay_freeze(p,0x10,plan(),trace);
    assert(!cancelled.completed&&cancelled.error=="CONTEXT_CHANGED");
    trace=fixture(p);trace.resize(6);trace.back().raw="NOT HEX";
    auto bad=replay_freeze(p,0x10,plan(),trace);
    assert(!bad.completed&&bad.error=="MALFORMED_LEGACY_RESPONSE");
    trace=fixture(p);trace[0].duration_ms=60000;
    rejects([&]{replay_freeze(p,0x10,plan(),trace);});
}
void plan_admission(){
    const auto valid=plan();
    assert(valid.valid()&&valid.allows(Data{2,12,7}));
    for(const Data& q:{Data{4},Data{8,1,0,0,0,0,0},Data{2,12,8},Data{2,13,7},Data{1,12}})
        assert(!valid.allows(q));
    auto invalid=valid;invalid.frames.push_back(0);assert(!invalid.valid());
    invalid=valid;invalid.pids.push_back(0x20);assert(!invalid.valid());
    invalid=valid;invalid.pids.push_back(2);assert(!invalid.valid());
    invalid=valid;invalid.budget_ms=999;assert(!invalid.valid());
    invalid=valid;invalid.vin[0]='I';assert(!invalid.valid());
}
}
int main(){plan_admission();all_variants();numbered_frame();interrupted_and_corrupt();}
