#include "j1979_legacy_mutation_inspection.hpp"
#include <algorithm>
#include <cassert>
#include <deque>
#include <utility>
using namespace cd::legacy;
namespace {
constexpr std::string_view vin="1G1YY22G965109876";
std::string wire(Protocol p,unsigned s,Data payload){
    Data raw;
    if(p==Protocol::kwp_slow||p==Protocol::kwp_fast)
        raw={std::uint8_t(0x80|payload.size()),0xf1,std::uint8_t(s)};
    else raw={std::uint8_t(p==Protocol::pwm?0x41:0x48),0x6b,std::uint8_t(s)};
    raw.insert(raw.end(),payload.begin(),payload.end());raw.push_back(checksum(p,raw));return hex(raw)+"\r";
}
struct Fake:Link{
    Protocol p;
    std::deque<std::pair<std::string,std::string>> script;
    unsigned mutation_count{};
    bool baseline{},intent{},fail_mutation{},delay_intent{};
    std::string baseline_json,intent_json;
    explicit Fake(Protocol protocol):p(protocol){}
    void add(std::string cmd,std::string raw){script.emplace_back(std::move(cmd),std::move(raw));}
    void configure(){
        add("ATZ","ELM327");
        for(const char* cmd:{"ATE0","ATL0","ATS1","ATH1"})add(cmd,"OK");
        add("ATSP"+std::to_string(static_cast<unsigned>(p)),"OK");
        add(p==Protocol::pwm?"ATSH616AF1":p==Protocol::kwp_slow||p==Protocol::kwp_fast?"ATSHC033F1":"ATSH686AF1","OK");
    }
    std::string command(std::string_view cmd,unsigned timeout)override{
        assert(timeout>0&&timeout<=4000&&!script.empty()&&script.front().first==cmd);
        auto answer=std::move(script.front().second);script.pop_front();
        if(cmd=="04"||cmd=="08010000000000"){
            assert(baseline&&intent&&mutation_count==0);++mutation_count;
            if(fail_mutation)throw TransportError("SIMULATED_DISCONNECT");
        }
        return answer;
    }
    std::string pair(Data payload){
        return wire(p,0x10,payload)+wire(p,0x20,payload);
    }
    void scope(){add("0100",pair({0x41,0,0,0,0,0}));}
    void probe(){add("ATDPN",std::to_string(static_cast<unsigned>(p)));}
    void identity(std::string_view value=vin){
        add("0901",pair({0x49,1,5}));
        Data raw(3,0);raw.insert(raw.end(),value.begin(),value.end());
        std::string rows;
        for(unsigned i=0;i<5;++i){
            Data data{0x49,2,std::uint8_t(i+1)};data.insert(data.end(),raw.begin()+4*i,raw.begin()+4*i+4);
            rows+=pair(data);
        }
        add("0902",rows);
    }
    void parked(bool moving=false){
        add("010D",wire(p,0x10,{0x41,0x0d,std::uint8_t(moving?5:0)})+wire(p,0x20,{0x7f,1,0x12}));
        if(!moving)add("010C",wire(p,0x10,{0x41,0x0c,0,0}));
    }
    void snapshot(bool remaining=false,bool missing_pending=false){
        add("03",pair({0x43,0,std::uint8_t(remaining?0x33:0),0,0,0,0}));
        add("07",missing_pending?"NO DATA":pair({0x47,0,0,0,0,0,0}));
        add("0A","NO DATA");add("0101",pair({0x41,1,0,0,0,0}));
    }
    void admission(MutationOperation op){
        configure();scope();probe();identity();
        if(op==MutationOperation::clear)snapshot(true);
        else add("08000000000000",wire(p,0x10,{0x48,0,0,0x80,0,0,0})+wire(p,0x20,{0x48,0,0,0,0,0,0}));
        scope();parked();
    }
    void recheck(MutationOperation op=MutationOperation::clear){
        probe();scope();identity();
        if(op==MutationOperation::evap)add("08000000000000",wire(p,0x10,{0x48,0,0,0x80,0,0,0})+wire(p,0x20,{0x48,0,0,0,0,0,0}));
        parked();
    }
    void after(bool remaining=false,bool pending_missing=false,std::string_view final_vin=vin){snapshot(remaining,pending_missing);scope();identity(final_vin);}
};
MutationPlan plan(Protocol p,MutationOperation op){
    return {p,op,std::string(vin),0x10,60000,{0x10,0x20}};
}
template<class F>void rejects(F f){bool rejected=false;try{f();}catch(const std::invalid_argument&){rejected=true;}assert(rejected);}
MutationResult run(Fake& f,MutationOperation op,bool fail_intent=false){
    Elm elm(f,f.p,true,[]{return true;});elm.configure();
    MutationSession session(elm);
    std::uint64_t tick=100;
    const auto p=plan(f.p,op);
    auto clock=[&]{return tick++;};
    const auto current=[](){return true;};
    auto journal=[&](std::string_view stage,const MutationResult& r){
        assert(!r.attempted);
        auto saved=r;saved.outcome=stage;
        if(stage=="BASELINE"){assert(!f.baseline&&!f.intent);f.baseline=true;f.baseline_json=mutation_report(saved);}
        else{
            assert(stage=="INTENT"&&f.baseline&&!f.intent&&r.baseline_saved);
            if(fail_intent)throw TransportError("SIMULATED_DISK_FAILURE");
            f.intent=true;f.intent_json=mutation_report(saved);
            if(f.delay_intent)tick+=5001;
        }
    };
    rejects([&]{session.run(p,false,true,clock,current,journal);});
    auto result=session.run(p,true,true,clock,current,journal);
    rejects([&]{session.run(p,true,true,clock,current,journal);});
    const auto checked=inspect_mutation(mutation_report(result),f.baseline_json,f.intent_json,vin);
    assert(checked.result.outcome==result.outcome&&checked.result.attempted==result.attempted);
    assert(checked.baseline_correlated==f.baseline&&checked.intent_correlated==f.intent);
    MutationSession another(elm);
    rejects([&]{another.run(p,true,true,clock,current,journal);});
    return result;
}
void variants(){
    for(unsigned n=1;n<=5;++n){
        const auto p=static_cast<Protocol>(n);
        Fake clear(p);clear.admission(MutationOperation::clear);clear.recheck();
        clear.add("04",clear.pair({0x44}));clear.after();
        const auto result=run(clear,MutationOperation::clear);
        assert(result.attempted&&result.complete_ack&&result.baseline_saved&&result.intent_saved);
        assert(result.outcome=="ACKNOWLEDGED_READBACK"&&result.reason.empty()&&clear.mutation_count==1&&clear.script.empty());
        assert(result.before.at(0x10).at(3)->size()==1&&result.after.at(0x10).at(3)->empty());
        assert(!result.after.at(0x10).at(10));
        const auto json=mutation_report(result);
        assert(json.find("\"whole_vehicle_coverage_verified\":false")!=std::string::npos);
        assert(json.find("\"permits_automatic_retry\":false")!=std::string::npos);
        const auto checked=inspect_mutation(json,clear.baseline_json,clear.intent_json,vin);
        assert(checked.sequence_complete&&checked.fresh_checks&&checked.baseline_correlated&&checked.intent_correlated);
        rejects([&]{inspect_mutation(json,clear.baseline_json,{},vin);});
        rejects([&]{inspect_mutation(json,{},clear.intent_json,vin);});
        rejects([&]{inspect_mutation(json,clear.baseline_json,clear.intent_json,"1G1YY22G965109877");});
        auto forged=result;forged.after.at(0x10).at(3)=std::vector<std::string>{"P0133"};
        rejects([&]{inspect_mutation(mutation_report(forged),clear.baseline_json,clear.intent_json,vin);});
        forged=result;forged.observations.back().frames.clear();
        rejects([&]{inspect_mutation(mutation_report(forged),clear.baseline_json,clear.intent_json,vin);});
        forged=result;forged.observations.push_back(forged.observations.back());
        rejects([&]{inspect_mutation(mutation_report(forged),clear.baseline_json,clear.intent_json,vin);});
        Fake evap(p);evap.admission(MutationOperation::evap);evap.recheck(MutationOperation::evap);
        evap.add("08010000000000",wire(p,0x10,{0x48,1,0,0,0,0,0}));
        auto e=run(evap,MutationOperation::evap);
        assert(e.outcome=="ACKNOWLEDGED"&&e.reason=="LEAK_TEST_NOT_PERFORMED"&&evap.mutation_count==1);
        assert(mutation_report(e).find("\"leak_test_performed\":false")!=std::string::npos);
    }
}
void failures(){
    constexpr auto p=Protocol::vpw;
    Fake disk(p);disk.admission(MutationOperation::clear);disk.recheck();
    auto d=run(disk,MutationOperation::clear,true);
    assert(d.baseline_saved&&!d.intent_saved&&!d.attempted&&disk.mutation_count==0);
    Fake stale(p);stale.admission(MutationOperation::clear);stale.recheck();stale.delay_intent=true;
    auto late=run(stale,MutationOperation::clear);
    assert(late.intent_saved&&!late.attempted&&stale.mutation_count==0&&late.reason=="STALE_STATIONARY_OR_IDENTITY_EVIDENCE");
    Fake moving(p);moving.admission(MutationOperation::clear);moving.probe();moving.scope();moving.identity();moving.parked(true);
    auto m=run(moving,MutationOperation::clear);
    assert(m.baseline_saved&&!m.attempted&&moving.mutation_count==0);
    Fake disconnected(p);disconnected.admission(MutationOperation::clear);disconnected.recheck();
    disconnected.add("04","NO DATA");disconnected.fail_mutation=true;
    auto x=run(disconnected,MutationOperation::clear);
    assert(x.attempted&&x.outcome=="UNCERTAIN"&&disconnected.mutation_count==1);
    Fake unknown(p);unknown.admission(MutationOperation::clear);unknown.recheck();
    unknown.add("04",wire(p,0x30,{0x44}));
    auto u=run(unknown,MutationOperation::clear);
    assert(u.attempted&&u.outcome=="UNCERTAIN"&&u.unexpected_sources.contains(0x30));
    Fake refused(p);refused.admission(MutationOperation::clear);refused.recheck();
    refused.add("04",wire(p,0x10,{0x44})+wire(p,0x20,{0x7f,4,0x22}));refused.after();
    auto r=run(refused,MutationOperation::clear);
    assert(r.outcome=="UNCERTAIN"&&r.acknowledgments.at(0x20)=="REFUSED_22");
    Fake remaining(p);remaining.admission(MutationOperation::clear);remaining.recheck();
    remaining.add("04",remaining.pair({0x44}));remaining.after(true);
    auto residual=run(remaining,MutationOperation::clear);
    assert(residual.reason=="DTCS_STILL_PRESENT"&&residual.outcome=="ACKNOWLEDGED_READBACK");
    Fake optional(p);optional.admission(MutationOperation::clear);optional.recheck();
    optional.add("04",optional.pair({0x44}));optional.after(false,true);
    assert(run(optional,MutationOperation::clear).reason=="PENDING_READBACK_UNAVAILABLE");
    Fake changed(p);changed.admission(MutationOperation::clear);changed.recheck();
    changed.add("04",changed.pair({0x44}));changed.after(false,false,"1G1YY22G965109877");
    auto identity=run(changed,MutationOperation::clear);
    assert(identity.complete_ack&&identity.outcome=="ACKNOWLEDGED_PARTIAL"&&identity.reason=="FINAL_IDENTITY_UNVERIFIED");
    auto bad=plan(p,MutationOperation::clear);bad.engine=0x30;assert(!bad.valid());
    bad=plan(p,MutationOperation::clear);bad.sources.insert(0xff);assert(!bad.valid());
    rejects([&]{mutation_response_frames(plan(p,MutationOperation::clear),Data{8,1,0,0,0,0,0},"NO DATA");});
    assert(!read_allowed(Data{4}));
}
}
int main(){variants();failures();}
