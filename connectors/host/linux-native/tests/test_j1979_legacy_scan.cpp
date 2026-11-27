#include "j1979_legacy_scan.hpp"
#include <cassert>
#include <deque>
#include <utility>
using namespace cd::legacy;
namespace {
constexpr std::string_view vin="1G1YY22G965109876";
struct Fake:Link{
    std::deque<std::pair<std::string,std::string>> script;
    std::vector<std::string> sent;
    std::string command(std::string_view q,unsigned timeout)override{
        assert(timeout>=1&&timeout<=4000);
        sent.emplace_back(q);
        assert(!script.empty()&&script.front().first==q);
        auto r=std::move(script.front().second);script.pop_front();return r;
    }
    void add(std::string q,std::string r){script.emplace_back(std::move(q),std::move(r));}
    void config(){
        add("ATZ","ELM327");
        for(const char* q:{"ATE0","ATL0","ATS1","ATH1","ATSP2","ATSH686AF1"})add(q,"OK");
    }
};
std::string wire(Data p,unsigned source=0x10){
    Data raw{0x48,0x6b,static_cast<std::uint8_t>(source)};
    raw.insert(raw.end(),p.begin(),p.end());raw.push_back(checksum(Protocol::vpw,raw));
    return hex(raw)+"\r";
}
std::string vin_rows(std::string_view value){
    Data v(3,0);v.insert(v.end(),value.begin(),value.end());assert(v.size()==20);
    std::string result;
    for(unsigned i=0;i<5;++i){
        Data p{0x49,2,static_cast<std::uint8_t>(i+1)};
        p.insert(p.end(),v.begin()+i*4,v.begin()+(i+1)*4);result+=wire(p);
    }
    return result;
}
void beginning(Fake& f,std::string_view initial=vin){
    f.config();
    f.add("0100",wire({0x41,0,0,0x10,0,0})+wire({0x41,0,0xff,0,0,0},0x20));
    f.add("ATDPN","2");
    f.add("0900",wire({0x49,0,1,0xc0,0,0,0}));
    f.add("0901",wire({0x49,1,5}));
    f.add("0902",vin_rows(initial));
}
void end(Fake& f,std::string_view final=vin){
    f.add("03",wire({0x43,0x01,0x33,0,0,0,0}));
    f.add("07","NO DATA");
    f.add("0A","NO DATA");
    f.add("010C",wire({0x41,0x0c,0x1f,0x40}));
    f.add("020200",wire({0x42,2,0,0,0}));
    f.add("0600","NO DATA");
    f.add("08000000000000","NO DATA");
    f.add("ATDPN","2");
    f.add("0901",wire({0x49,1,5}));
    f.add("0902",vin_rows(final));
}
Scan run(Fake& f){
    Elm elm(f,Protocol::vpw,true,[]{return true;});
    elm.configure();
    std::uint64_t tick=100;
    return scan_selected(elm,0x10,vin,180000,[&]{return tick++;},[]{return true;});
}
}
int main(){
    Fake ok;beginning(ok);end(ok);
    const auto s=run(ok);
    assert(s.completed&&s.identity_rechecked&&s.initial_vin==vin&&s.final_vin==vin);
    assert(s.other_sources==std::set<unsigned>{0x20}&&ok.script.empty());
    const auto report=scan_report(s);
    assert(report.find("\"physical_vehicle_verified\":false")!=std::string::npos);
    assert(report.find("P0133")!=std::string::npos);
    assert(report.find("\"identity_rechecked\":true")!=std::string::npos);
    assert(report.find("\"number\":2000")!=std::string::npos);
    Fake wrong;beginning(wrong,"1G1YY22G965109877");
    const auto refused=run(wrong);
    assert(!refused.completed&&!refused.identity_rechecked&&wrong.script.empty());
    assert(refused.error=="VIN_UNVERIFIED_OR_MISMATCH");
    Fake changed;beginning(changed);end(changed,"1G1YY22G965109877");
    const auto invalid=run(changed);
    assert(!invalid.completed&&!invalid.identity_rechecked&&changed.script.empty());
    assert(scan_report(invalid).find("\"number\":2000")==std::string::npos);
    Fake duplicate;duplicate.config();
    const auto p=wire({0x41,0,0,0,0,0});
    duplicate.add("0100",p+p);
    const auto ambiguous=run(duplicate);
    assert(!ambiguous.completed&&ambiguous.error=="NO_SELECTED_PID00");
    assert(duplicate.script.empty());
    return 0;
}
