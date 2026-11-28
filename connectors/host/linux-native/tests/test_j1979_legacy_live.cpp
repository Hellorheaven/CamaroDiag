#include "j1979_legacy_live_wire.hpp"
#include <algorithm>
#include <array>
#include <cassert>
#include <utility>
using namespace cd::legacy;
namespace {
constexpr std::string_view VIN="1G1FA1E30D1234567";
constexpr std::string_view OTHER="1G1FA1E30D1234568";
std::string wire(Protocol p,unsigned source,const Data& payload){
    Data frame;
    if(p==Protocol::kwp_slow||p==Protocol::kwp_fast)
        frame={std::uint8_t(0x80|payload.size()),0xf1,std::uint8_t(source)};
    else frame={std::uint8_t(p==Protocol::pwm?0x41:0x48),0x6b,std::uint8_t(source)};
    frame.insert(frame.end(),payload.begin(),payload.end());
    frame.push_back(checksum(p,frame));return hex(frame)+"\r";
}
std::string vin_rows(Protocol p,std::string_view vin){
    Data raw(3,0);raw.insert(raw.end(),vin.begin(),vin.end());
    std::string rows;
    for(unsigned i=0;i<5;++i){
        Data payload{0x49,2,std::uint8_t(i+1)};
        payload.insert(payload.end(),raw.begin()+4*i,raw.begin()+4*i+4);
        rows+=wire(p,0x10,payload);
    }
    return rows;
}
using Trace=std::vector<LiveExchange>;
unsigned end(const Trace& t){return t.empty()?0:t.back().ended_ms;}
void add(Trace& t,LivePhase phase,unsigned cycle,std::string command,std::string raw,unsigned duration=10){
    auto start=end(t);bool attempted=command!="WAIT";
    t.push_back({{std::move(command),std::move(raw),FreezeOutcome::received,duration,attempted},
        phase,cycle,start,start+duration});
}
void identity(Trace& t,Protocol p,LivePhase phase,unsigned cycle,std::string_view vin){
    add(t,phase,cycle,"0100",wire(p,0x10,{0x41,0,0,0x10,0,0})+
        wire(p,0x20,{0x41,0,0,0,0,0}));
    add(t,phase,cycle,"ATDPN",std::to_string(static_cast<unsigned>(p)));
    add(t,phase,cycle,"0900",wire(p,0x10,{0x49,0,1,0xc0,0,0,0}));
    add(t,phase,cycle,"0901",wire(p,0x10,{0x49,1,5}));
    add(t,phase,cycle,"0902",vin_rows(p,vin));
}
LivePlan plan(bool raw=false,unsigned interval=100){return {std::string(VIN),{12},2,interval,60000,raw};}
Trace fixture(Protocol p,unsigned interval=100){
    Trace t;identity(t,p,LivePhase::initial,0,VIN);
    add(t,LivePhase::inventory,0,"0100",wire(p,0x10,{0x41,0,0,0x10,0,0}));
    add(t,LivePhase::sample,1,"WAIT","",0);
    add(t,LivePhase::sample,1,"010C",wire(p,0x10,{0x41,12,0x1f,0x40}));
    add(t,LivePhase::sample,2,"WAIT","",interval-10);
    if(interval>=30000)identity(t,p,LivePhase::periodic,2,VIN);
    add(t,LivePhase::sample,2,"010C",wire(p,0x10,{0x41,12,0x20,0xd0}));
    identity(t,p,LivePhase::final,0,VIN);return t;
}
template<class F>void rejects(F f){bool caught=false;try{f();}catch(const std::invalid_argument&){caught=true;}assert(caught);}
struct Sim:Link{
    Trace trace;std::size_t index{};unsigned configured{};Protocol protocol;
    Sim(Protocol p,Trace t):trace(std::move(t)),protocol(p){}
    std::string command(std::string_view request,unsigned timeout)override{
        assert(timeout>=1&&timeout<=4000);
        if(configured<7){
            const std::array<std::string,7> setup{"ATZ","ATE0","ATL0","ATS1","ATH1",
                "ATSP"+std::to_string(static_cast<unsigned>(protocol)),
                protocol==Protocol::pwm?"ATSH616AF1":protocol==Protocol::kwp_slow||protocol==Protocol::kwp_fast?"ATSHC033F1":"ATSH686AF1"};
            assert(setup[configured]==request);return configured++==0?"ELM327":"OK";
        }
        while(index<trace.size()&&trace[index].exchange.command=="WAIT")++index;
        assert(index<trace.size()&&trace[index].exchange.command==request);
        return trace[index++].exchange.raw;
    }
};
void variants(){
    for(unsigned n=1;n<=5;++n){
        auto p=static_cast<Protocol>(n);auto t=fixture(p);
        auto r=replay_live(p,0x10,plan(),t);
        assert(r.completed&&r.identity_rechecked&&r.inventory_complete&&r.completed_cycles==2);
        assert(r.samples.size()==2&&r.samples[0].status=="PASSED"&&r.advertised.contains(12));
        assert(r.other_sources==std::set<unsigned>{0x20});
        auto report=live_report(r);
        const auto csv=live_csv(r);
        assert(csv.find("cycle,pid_hex,started_elapsed_ms,elapsed_ms")!=std::string::npos);
        assert(csv.find("2000,\"rpm\",\"010C\",\"410C1F40\"")!=std::string::npos);
        assert(csv.find("2100,\"rpm\",\"010C\",\"410C20D0\"")!=std::string::npos);
        assert(report.find("2000")!=std::string::npos&&report.find("2100")!=std::string::npos);
        assert(report.find("\"physical_vehicle_verified\":false")!=std::string::npos);
        auto raw=encode_live_capture({p,0x10,plan(),t});
        auto decoded=decode_live_capture(raw);
        assert(decoded.plan==plan()&&decoded.trace==t&&decoded.source==0x10&&decoded.protocol==p);
        const auto reconstructed=inspect_live_capture(raw,p,0x10,VIN);
        assert(live_report(reconstructed)==report);
        assert(live_csv(reconstructed)==csv);
        rejects([&]{inspect_live_capture(raw,p,0x20,VIN);});
        rejects([&]{inspect_live_capture(raw,p,0x10,OTHER);});
        auto corrupt=raw;corrupt.back()^=1;rejects([&]{decode_live_capture(corrupt);});
        corrupt=raw;corrupt.pop_back();rejects([&]{decode_live_capture(corrupt);});
        corrupt=raw;corrupt.push_back(0);rejects([&]{decode_live_capture(corrupt);});
        auto raw_result=replay_live(p,0x10,plan(true),t);
        assert(raw_result.completed&&raw_result.samples[0].status=="RAW");
        const auto raw_csv=live_csv(raw_result);
        assert(raw_csv.find("\"RAW\",,,\"010C\"")!=std::string::npos);
        assert(raw_csv.find("\"rpm\"")==std::string::npos);
        auto periodic=replay_live(p,0x10,plan(false,31000),fixture(p,31000));
        assert(periodic.completed&&periodic.trace.size()==t.size()+5);
        Sim sim(p,t);Elm elm(sim,p,true,[]{return true;});elm.configure();
        std::uint64_t clock=100;
        auto acquired=acquire_live(elm,0x10,plan(),[&]{return clock++;},[]{return true;},[&](unsigned ms){clock+=ms;});
        assert(acquired.completed&&sim.index==t.size());
        assert(replay_live(p,0x10,plan(),acquired.trace).completed);
    }
}
void failures(){
    auto p=Protocol::vpw;auto t=fixture(p);
    auto missing=t;missing.erase(missing.begin()+7);rejects([&]{replay_live(p,0x10,plan(),missing);});
    auto extra=t;extra.push_back(t.back());rejects([&]{replay_live(p,0x10,plan(),extra);});
    auto early=t;early[8].ended_ms=early[8].started_ms;early[8].exchange.duration_ms=0;
    rejects([&]{replay_live(p,0x10,plan(),early);});
    auto wrong=t;wrong.back().exchange.raw=vin_rows(p,OTHER);
    auto changed=replay_live(p,0x10,plan(),wrong);
    assert(!changed.completed&&!changed.identity_rechecked&&changed.error=="FINAL_VIN_MISMATCH");
    assert(live_csv(changed).find("\"rpm\"")==std::string::npos);
    wrong=t;wrong[7].exchange.raw+=wrong[7].exchange.raw;wrong.resize(8);
    auto duplicate=replay_live(p,0x10,plan(),wrong);
    assert(!duplicate.completed&&duplicate.error=="AMBIGUOUS_SELECTED_SOURCE");
    wrong=t;wrong[7].exchange.raw=wire(p,0x10,{0x41,12,0x1f});wrong.resize(8);
    assert(replay_live(p,0x10,plan(),wrong).error=="INVALID_MEASUREMENT");
    wrong=t;wrong[7].exchange.raw=wire(p,0x20,{0x41,12,0x1f,0x40});
    wrong[9].exchange.raw=wire(p,0x20,{0x41,12,0x20,0xd0});
    assert(replay_live(p,0x10,plan(),wrong).error=="SELECTED_PID_NO_RESPONSE");
    auto cancelled=t;cancelled.resize(7);auto& row=cancelled.back();
    row.exchange.outcome=FreezeOutcome::cancelled;
    assert(replay_live(p,0x10,plan(),cancelled).error=="CONTEXT_CHANGED");
    auto invalid=plan();invalid.pids={12,12};assert(!invalid.valid());
    invalid=plan();invalid.pids={0};assert(!invalid.valid());
    invalid=plan();invalid.interval_ms=99;assert(!invalid.valid());
    assert(!plan().allows(Data{4})&&!plan().allows(Data{1,13}));
}
}
int main(){variants();failures();}
