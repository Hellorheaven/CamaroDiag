#include "j1979_can_network.hpp"
#include <algorithm>
#include <cassert>
#include <stdexcept>
using namespace cd::network;
namespace {
constexpr std::string_view vin="1G1YY22G965109876";
Frame positive(unsigned id,bool ext=false){
    return {id,ext,false,false,{6,0x41,0,0x80,0,0,1,0xcc}};
}
Frame negative(unsigned id,unsigned nrc,bool ext=false){
    return {id,ext,false,false,{3,0x7f,1,static_cast<std::uint8_t>(nrc),0xcc,0xcc,0xcc,0xcc}};
}
void address_families(){
    assert(functional_request(false).id==0x7df&&functional_request(true).id==0x18db33f1);
    assert(functional_request(false).data==std::vector<std::uint8_t>({2,1,0,0xcc,0xcc,0xcc,0xcc,0xcc}));
    for(unsigned i=0;i<8;++i){
        const auto pair=response_pair(0x7e8+i,false);
        assert(pair&&pair->tx==0x7e0+i&&pair->rx==0x7e8+i);
    }
    for(unsigned i=0;i<0xf0;++i){
        const auto pair=response_pair(0x18daf100|i,true);
        assert(pair&&pair->tx==(0x18da00f1|(i<<8))&&pair->rx==(0x18daf100|i));
    }
    assert(!response_pair(0x7df,false)&&!response_pair(0x7e7,false));
    assert(!response_pair(0x18daf1f0,true)&&!response_pair(0x18daf110,false)&&!response_pair(0x7e8,true));
}
void classification(){
    auto a=positive(0x7e8),b=positive(0x7e9),c=negative(0x7ea,0x11);
    auto pending=negative(0x7e8,0x78),unresolved=negative(0x7eb,0x78);
    auto conflicting=b;conflicting.data[3]=0;
    auto invalid=positive(0x7ec);invalid.data.pop_back();
    auto unrelated=positive(0x100),rtr=a;rtr.rtr=true;
    auto ext=positive(0x18daf110,true);
    auto error=a;error.error=true;
    std::vector<Frame> frames{pending,a,a,b,conflicting,c,unresolved,invalid,unrelated,rtr,ext,error};
    const auto d=replay_discovery(false,true,frames,"",123);
    assert(d.completed&&d.eligible==std::vector<std::uint32_t>{0x7e8});
    assert(d.ignored==4&&d.responders.size()==5&&d.responders[0].frames.size()==3);
    assert(d.responders[1].classification.state==State::conflicting);
    assert(d.responders[2].classification.state==State::negative);
    assert(d.responders[3].classification.state==State::pending);
    assert(d.responders[4].classification.state==State::invalid);
    assert(report(d).find("\"physical_vehicle_verified\":false")!=std::string::npos);
    assert(report(d).find("CONFLICTING_RESPONSES")!=std::string::npos);
    auto forged=d;forged.eligible.push_back(0x7e9);
    const std::array<std::uint32_t,1> bad{0x7e9},good{0x7e8};
    assert(!valid_targets(forged,bad)&&valid_targets(d,good));
    const std::array<std::uint32_t,2> duplicate{0x7e8,0x7e8};
    assert(!valid_targets(d,duplicate));
    auto cancelled=replay_discovery(false,true,frames,"CANCELLED",10);
    assert(!cancelled.completed&&cancelled.eligible.empty()&&!valid_targets(cancelled,good));
    auto limited=replay_discovery(false,true,std::vector<Frame>(max_frames,a),"FRAME_LIMIT",10);
    assert(!limited.completed&&limited.eligible.empty());
    auto forged_limit=limited;forged_limit.completed=true;forged_limit.stop.clear();
    forged_limit.eligible={0x7e8};
    assert(!valid_targets(forged_limit,good));
    auto malformed=d;malformed.frames.front().data.resize(9);
    assert(!valid_targets(malformed,good));
    bool rejected=false;
    try{(void)replay_discovery(false,true,limited.frames,"",10);}
    catch(const std::invalid_argument&){rejected=true;}
    assert(rejected);
    auto negative_mixture=replay_discovery(false,true,std::array{a,negative(0x7e8,0x22)},"",10);
    assert(!negative_mixture.completed&&negative_mixture.responders[0].classification.state==State::conflicting);
}
void multi_ecu(){
    const auto d=replay_discovery(false,true,std::array{positive(0x7e8),positive(0x7e9)},"",100);
    const std::array<std::uint32_t,2> ids{0x7e8,0x7e9};
    std::uint64_t tick=100;
    unsigned opened=0;
    auto current=[](){return true;};
    auto clock=[&]{return tick;};
    auto scan=[&](Pair pair,unsigned left){
        assert(pair.rx==ids[opened]&&left==10000-opened*100);
        ++opened;tick+=100;
        return EcuReport{pair,"PASSED","","{\"completed\":true}"};
    };
    auto result=scan_selected(d,ids,vin,10000,clock,current,scan);
    assert(result.completed&&opened==2&&result.ecus.size()==2);
    assert(report(result).find("EXPLICIT_PHYSICAL_RESPONDER_SET")!=std::string::npos);
    const std::array<std::uint32_t,2> bad{0x7e8,0x7ea};
    opened=0;
    auto refused=scan_selected(d,bad,vin,10000,clock,current,scan);
    assert(!refused.completed&&opened==0&&refused.stop=="SELECTED_RESPONDER_UNVERIFIED");
    auto fault=scan_selected(d,ids,vin,10000,clock,current,[&](Pair pair,unsigned){
        ++opened;return EcuReport{pair,"NOT_TESTED","VIN_MISMATCH",""};
    });
    assert(!fault.completed&&opened==1&&fault.ecus[1].error=="VIN_MISMATCH");
    opened=0;
    auto timeout=scan_selected(d,ids,vin,10000,clock,current,[&](Pair pair,unsigned){
        ++opened;tick+=10000;return EcuReport{pair,"PASSED","","{}"};
    });
    assert(!timeout.completed&&opened==1&&timeout.stop=="BUDGET_EXHAUSTED");
    opened=0;
    auto cancelled=scan_selected(d,ids,vin,10000,clock,[]{return false;},scan);
    assert(!cancelled.completed&&opened==0&&cancelled.stop=="CANCELLED");
}
}
int main(){address_families();classification();multi_ecu();}
