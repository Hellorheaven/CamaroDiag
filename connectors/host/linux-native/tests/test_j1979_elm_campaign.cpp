#include "j1979_elm_can_campaign.hpp"
#include <algorithm>
#include <cassert>
#include <deque>
#include <stdexcept>
using namespace cd::elmcan;
namespace {
constexpr std::string_view vin="1G1FA1RXXR0123456";
template<class F>void rejects(F f){bool caught=false;try{f();}catch(const std::exception&){caught=true;}assert(caught);}
struct Fake:cd::legacy::Link{
    std::deque<std::pair<std::string,std::string>> rows;
    uint64_t* clock{};
    bool extended{};
    unsigned profile{};
    unsigned calls{};
    explicit Fake(unsigned p):extended(p==7||p==9),profile(p){}
    void add(std::string q,std::string r){rows.emplace_back(std::move(q),std::move(r));}
    std::string frame(std::string_view data)const{return std::string(extended?"18DAF110 ":"7E8 ")+std::string(data);}
    void setup(){
        add("ATZ","ELM327");
        for(auto q:{"ATE0","ATL0","ATH1","ATS1","ATD0"})add(q,"OK");
        add("ATSP"+std::to_string(profile),"OK");add("ATCAF0","OK");
        add(extended?"ATSH18DA10F1":"ATSH7E0","OK");
        add(extended?"ATCRA18DAF110":"ATCRA7E8","OK");
        add(extended?"ATFCSH18DA10F1":"ATFCSH7E0","OK");
        for(auto q:{"ATFCSD300000","ATFCSM1","ATCFC1"})add(q,"OK");
        add("ATDPN",std::to_string(profile));
    }
    void identity(bool wrong=false){
        add("020902",frame("10 14 49 02 01 31 47 31")+"\r"+frame("21 46 41 31 52 58 58 52")+"\r"+
            frame(wrong?"22 30 31 32 33 34 35 37":"22 30 31 32 33 34 35 36"));
    }
    void scan(){
        setup();identity();
        add("0103",frame("02 43 00"));add("0107",frame("02 47 00"));add("010A",frame("02 4A 00"));
        add("020100",frame("06 41 00 00 10 00 00"));add("02010C",frame("04 41 0C 1F 40"));
        add("03020200",frame("05 42 02 00 00 00"));
        add("020600",frame("06 46 00 00 00 00 00"));
        add("020800",frame("06 48 00 00 00 00 00"));
        add("020900",frame("06 49 00 40 00 00 00"));identity();
    }
    void live(bool wrong=false,bool missing=false){
        setup();identity();add("020100",frame("06 41 00 00 10 00 00"));
        add("02010C",frame("04 41 0C 1F 40"));
        add("02010C",missing?"NO DATA":frame("04 41 0C 20 D0"));identity(wrong);
    }
    std::string command(std::string_view q,unsigned timeout)override{
        assert(!rows.empty()&&rows.front().first==q&&timeout>0);
        ++calls;if(clock)*clock+=2;
        auto r=std::move(rows.front().second);rows.pop_front();return r;
    }
};
CampaignPlan plan(unsigned profile,bool live){
    const bool ext=profile==7||profile==9;
    return {{profile,ext?0x18da10f1u:0x7e0u,ext?0x18daf110u:0x7e8u},std::string(vin),"/dev/ttyUSB0",
        live?TraceOperation::live:TraceOperation::scan,60000,live?2u:0u,live?100u:0u,live?Data{12}:Data{}};
}
CampaignCapture acquire(Fake& fake,const CampaignPlan& p,bool want_completed=true){
    uint64_t clock=100;fake.clock=&clock;
    Session session(fake,p.target,[]{return true;},{},[&]{return clock;});session.configure();
    CampaignCapture captured;
    auto r=record_campaign(session,p,[&]{return clock;},[&](unsigned wait){clock+=wait;return true;},captured);
    assert(fake.rows.empty()&&r.completed==want_completed&&r.steps>0&&!r.native_report.empty());
    const auto replay=replay_campaign(captured);
    assert(replay.completed==r.completed&&replay.native_report==r.native_report);
    return captured;
}
void variants(){
    for(unsigned p=6;p<=9;++p){
        for(bool live:{false,true}){
            Fake fake(p);if(live)fake.live();else fake.scan();
            const auto captured=acquire(fake,plan(p,live));
            const auto wire=encode_campaign(captured);
            auto parsed=decode_campaign(wire);
            assert(parsed.events==captured.events&&encode_trace(parsed.trace)==encode_trace(captured.trace));
            assert(replay_campaign(parsed).completed);
            auto bad=wire;bad.back()^=1;rejects([&]{decode_campaign(bad);});
            bad=wire;bad.pop_back();rejects([&]{decode_campaign(bad);});
            bad=wire;bad.push_back(0);rejects([&]{decode_campaign(bad);});
            auto changed=captured;changed.plan.vin.back()='7';rejects([&]{replay_campaign(changed);});
            changed=captured;changed.events.erase(changed.events.begin()+1);rejects([&]{replay_campaign(changed);});
            changed=captured;changed.events.push_back(changed.events.back());rejects([&]{replay_campaign(changed);});
            changed=captured;
            auto at=std::find_if(changed.events.begin(),changed.events.end(),[](const Event& e){return e.kind==EventKind::exchange;});
            assert(at!=changed.events.end());++at->aux;rejects([&]{replay_campaign(changed);});
            changed=captured;changed.trace.rows.pop_back();rejects([&]{replay_campaign(changed);});
            if(live){
                changed=captured;
                auto wait=std::find_if(changed.events.begin(),changed.events.end(),[](const Event& e){return e.kind==EventKind::wait;});
                assert(wait!=changed.events.end());++wait->value;rejects([&]{replay_campaign(changed);});
                changed=captured;changed.plan.pids={13};rejects([&]{replay_campaign(changed);});
            }
        }
    }
}
void incomplete(){
    Fake wrong(6);wrong.live(true,false);
    auto changed=acquire(wrong,plan(6,true),false);
    assert(replay_campaign(changed).stop=="VIN_MISMATCH");
    Fake missing(6);missing.live(false,true);
    auto partial=acquire(missing,plan(6,true),false);
    assert(replay_campaign(partial).stop=="INCOMPLETE_SAMPLES");
    auto invalid=plan(6,true);invalid.pids={12,12};assert(!invalid.valid());
    invalid=plan(6,false);invalid.cycles=1;assert(!invalid.valid());
}
}
int main(){variants();incomplete();}
