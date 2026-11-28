#include <algorithm>
#include "j1979_host_maintenance.hpp"
#include "j1979_cm1.hpp"
#include "j1979_elm_can_trace.hpp"
#include <cassert>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <iterator>
#include <sys/stat.h>
#include <unistd.h>
#include <vector>
#include <deque>
#include <utility>
namespace {
using namespace cd::hostmaint;
struct Directory{
    std::filesystem::path path;
    Directory(){
        char name[]="/tmp/camarodiag-maintenance-XXXXXX";
        const char* result=mkdtemp(name);assert(result);path=result;
    }
    ~Directory(){std::error_code ec;std::filesystem::remove_all(path,ec);}
};
template<class F>void rejected(F f){bool threw=false;try{f();}catch(const std::exception&){threw=true;}assert(threw);}
Scope scope(){
    Scope s{};s.tx=0x7e0;s.rx=0x7e8;s.budgetMs=60000;
    std::memcpy(s.vin,"1G1FA1RXXR0123456",17);return s;
}
std::vector<uint8_t> read(const std::filesystem::path& p){
    std::ifstream stream(p,std::ios::binary);
    assert(stream);
    return {std::istreambuf_iterator<char>(stream),std::istreambuf_iterator<char>()};
}
void durable_open(){
    Directory d;
    {
        FileStorage storage(d.path.c_str(),"session.cm");
        Journal::Scratch scratch{};
        Journal journal(storage,scratch);
        assert(journal.begin(scope()));
        size_t size=0;assert(storage.size(size)&&size==47);
        assert(std::filesystem::exists(d.path/"RECOVERY_REQUIRED"));
        rejected([&]{FileStorage competing(d.path.c_str(),"different.cm");});
        const auto bytes=read(d.path/"session.cm");
        const auto evidence=cd::cm1::inspect(bytes,"1G1FA1RXXR0123456",true);
        assert(evidence.records==std::vector<unsigned>{1});
        assert(evidence.recovery_required());
        std::array<uint8_t,47> restored{};
        assert(storage.read(0,restored.data(),restored.size()));
        assert(std::equal(restored.begin(),restored.end(),bytes.begin(),bytes.end()));
        assert(!storage.read(size,restored.data(),1));
    }
    rejected([&]{FileStorage unresolved(d.path.c_str(),"new.cm");});
    struct stat statbuf{};
    assert(stat((d.path/"session.cm").c_str(),&statbuf)==0&&!(statbuf.st_mode&077));
    assert(stat((d.path/"RECOVERY_REQUIRED").c_str(),&statbuf)==0&&!(statbuf.st_mode&077));
}
void unsafe_paths(){
    Directory d;
    rejected([&]{FileStorage a(d.path.c_str(),"../outside");});
    rejected([&]{FileStorage a(d.path.c_str(),"RECOVERY_REQUIRED");});
    assert(chmod(d.path.c_str(),0755)==0);
    rejected([&]{FileStorage a(d.path.c_str(),"session.cm");});
    assert(chmod(d.path.c_str(),0700)==0);
    const auto target=d.path/"existing";std::ofstream(target)<<"original";
    const auto link=d.path/"session.cm";assert(symlink(target.c_str(),link.c_str())==0);
    rejected([&]{FileStorage a(d.path.c_str(),"session.cm");});
    assert(read(target)==std::vector<uint8_t>({'o','r','i','g','i','n','a','l'}));
}
void changed_storage(){
    {
        Directory d;FileStorage storage(d.path.c_str(),"session.cm");
        Journal::Scratch scratch{};Journal journal(storage,scratch);
        assert(journal.begin(scope()));
        std::filesystem::rename(d.path/"session.cm",d.path/"old.cm");
        std::ofstream(d.path/"session.cm")<<"replacement";
        assert(!storage.ready());
        const uint8_t byte=1;assert(!storage.append(&byte,1)&&storage.latched());
    }
    {
        Directory d;FileStorage storage(d.path.c_str(),"session.cm");
        Journal::Scratch scratch{};Journal journal(storage,scratch);
        assert(journal.begin(scope()));
        std::filesystem::remove(d.path/"RECOVERY_REQUIRED");
        std::ofstream(d.path/"RECOVERY_REQUIRED")<<"replacement";
        assert(!storage.ready()&&!storage.flush()&&storage.latched());
    }
}
struct ElmFixture final:cd::legacy::Link {
    std::deque<std::pair<std::string,std::string>> rows;
    std::filesystem::path directory;
    unsigned writes{},mutations{};
    bool closed{},close_ok{true},erase_intent{},disconnect{};
    explicit ElmFixture(const std::filesystem::path& dir):directory(dir){}
    void add(std::string q,std::string r){rows.emplace_back(std::move(q),std::move(r));}
    std::string command(std::string_view cmd,unsigned timeout)override{
        assert(!closed&&timeout&&timeout<=4000&&!rows.empty()&&rows.front().first==cmd);
        ++writes;
        if(cmd=="0104"||cmd=="020801"){
            ++mutations;assert(mutations==1);
            const auto raw=read(directory/"session.cm");
            const auto inspected=cd::cm1::inspect(raw,"1G1FA1RXXR0123456",true);
            assert(inspected.baseline&&inspected.intent&&!inspected.terminal&&inspected.recovery_required());
            assert(std::filesystem::exists(directory/"RECOVERY_REQUIRED"));
            if(disconnect)throw cd::legacy::TransportError("SIMULATED_DISCONNECT_AFTER_WRITE");
        }
        auto r=std::move(rows.front().second);rows.pop_front();
        if(erase_intent&&cmd=="02010C"&&rows.size()==1)
            std::filesystem::remove(directory/"RECOVERY_REQUIRED");
        return r;
    }
    bool finish()override{assert(!closed);closed=true;return close_ok;}
    void setup(unsigned profile){
        const bool ext=profile==7||profile==9;
        add("ATZ","ELM327");
        for(auto q:{"ATE0","ATL0","ATH1","ATS1","ATD0"})add(q,"OK");
        add("ATSP"+std::to_string(profile),"OK");add("ATCAF0","OK");
        add(ext?"ATSH18DA10F1":"ATSH7E0","OK");
        add(ext?"ATCRA18DAF110":"ATCRA7E8","OK");
        add(ext?"ATFCSH18DA10F1":"ATFCSH7E0","OK");
        for(auto q:{"ATFCSD300000","ATFCSM1","ATCFC1"})add(q,"OK");
        add("ATDPN",std::to_string(profile));
    }
    void identity(const std::string& header){
        add("020902",header+"10 14 49 02 01 31 47 31\r"+
            header+"21 46 41 31 52 58 58 52\r"+
            header+"22 30 31 32 33 34 35 36");
    }
    void stationary(const std::string& header,bool moving=false){
        add("02010D",header+(moving?"03 41 0D 05":"03 41 0D 00"));
        if(!moving)add("02010C",header+"04 41 0C 00 00");
    }
    void snapshot(const std::string& header,bool initial=false){
        add("0103",header+(initial?"04 43 01 01 33":"02 43 00"));
        add("0107",header+"02 47 00");
        add("010A","NO DATA");
        add("020101",header+"06 41 01 00 00 00 00");
    }
    void admission(const std::string& header,bool clear,bool moving=false){
        identity(header);
        if(clear)snapshot(header,true);
        else add("020800",header+"06 48 00 80 00 00 00");
        stationary(header,moving);
    }
    void recheck(const std::string& header,bool clear){
        identity(header);
        if(!clear)add("020800",header+"06 48 00 80 00 00 00");
        stationary(header);
    }
};
void elm_maintenance(){
    for(unsigned profile=6;profile<=9;++profile){
        const bool ext=profile==7||profile==9;
        auto p=scope();
        if(ext){p.extended=true;p.tx=0x18da10f1;p.rx=0x18daf110;}
        const cd::elmcan::Scope target{profile,p.tx,p.rx};
        const std::string header=ext?"18 DA F1 10 ":"7E8 ";
        for(bool clear:{false,true}){
            p.operation=clear?Scope::Operation::Clear:Scope::Operation::Evap;
            Directory d;ElmFixture fake(d.path);
            fake.setup(profile);fake.admission(header,clear);fake.recheck(header,clear);
            if(clear){fake.add("0104",header+"01 44");fake.snapshot(header);fake.identity(header);}
            else fake.add("020801",header+"02 48 01");
            const auto result=run_elm(p,target,fake,[]{return true;},d.path.c_str(),"session.cm",true,true);
            const auto& r=result.result;
            assert(r.evidence.summary.acknowledged&&r.evidence.terminalPersisted&&r.local_serial_closed);
            assert(r.elm_transport&&!r.local_socket_closed&&!r.recovery_latched&&r.error.empty());
            assert(fake.closed&&fake.mutations==1&&fake.rows.empty()&&result.observations.size()==fake.writes);
            assert(r.evidence.summary.outcome==(clear?Campaign::Outcome::AcknowledgedReadback:Campaign::Outcome::AcknowledgedEvap));
            const auto raw=read(d.path/"session.cm");
            const auto inspected=cd::cm1::inspect(raw,"1G1FA1RXXR0123456",false);
            assert(inspected.terminal&&!inspected.recovery_required()&&inspected.records.size()==4);
            const cd::elmcan::Trace trace{target,"1G1FA1RXXR0123456",cd::elmcan::TraceOperation::maintenance,result.observations};
            const auto encoded=cd::elmcan::encode_trace(trace);
            const auto parsed=cd::elmcan::decode_trace(encoded);
            const auto replayed=cd::elmcan::correlate_maintenance(parsed,inspected);
            assert(replayed.configured&&replayed.diagnostic_rows==(clear?16u:9u));
            auto forged=parsed;forged.rows.push_back(parsed.rows[clear?25:23]);
            rejected([&]{cd::elmcan::replay_trace(forged);});
            forged=parsed;forged.rows.back().duration_ms=4000;
            rejected([&]{cd::elmcan::replay_trace(forged);});
            forged=parsed;forged.rows.pop_back();
            rejected([&]{cd::elmcan::correlate_maintenance(forged,inspected);});
            forged=parsed;forged.operation=cd::elmcan::TraceOperation::scan;
            rejected([&]{cd::elmcan::replay_trace(forged);});
            const auto text=report(r,std::string(64,'a'),cd::cm1::report(inspected,std::string(64,'a')));
            assert(text.find("\"local_serial_closed\":true")!=std::string::npos);
            assert(text.find("\"physical_transceiver_disabled_verified\":false")!=std::string::npos);
            assert(text.find("\"transport\":\"ELM_SERIAL_CLASSIC_ISOTP\"")!=std::string::npos);
            ElmFixture other(d.path);
            rejected([&]{run_elm(p,target,other,[]{return true;},d.path.c_str(),"next.cm",true,true);});
            assert(other.writes==0);
        }
    }
}
void elm_failures(){
    const cd::elmcan::Scope target{6,0x7e0,0x7e8};auto p=scope();p.operation=Scope::Operation::Evap;
    const std::string header="7E8 ";
    {
        Directory d;ElmFixture fake(d.path);
        rejected([&]{run_elm(p,target,fake,[]{return true;},d.path.c_str(),"session.cm",false,true);});
        assert(fake.writes==0&&std::filesystem::is_empty(d.path));
        fake.setup(6);fake.admission(header,false,true);
        auto r=run_elm(p,target,fake,[]{return true;},d.path.c_str(),"session.cm",true,true);
        assert(!r.result.evidence.summary.attempted&&fake.mutations==0&&fake.closed);
    }
    {
        Directory d;ElmFixture fake(d.path);fake.setup(6);fake.admission(header,false);fake.recheck(header,false);
        fake.add("020801",header+"02 48 01");fake.disconnect=true;
        const auto r=run_elm(p,target,fake,[]{return true;},d.path.c_str(),"session.cm",true,true);
        assert(r.result.evidence.summary.attempted&&!r.result.evidence.summary.acknowledged&&r.result.recovery_latched);
        assert(fake.closed&&fake.mutations==1);
    }
    {
        Directory d;ElmFixture fake(d.path);fake.setup(6);fake.admission(header,false);fake.recheck(header,false);
        fake.add("020801",header+"02 48 01");fake.erase_intent=true;
        auto r=run_elm(p,target,fake,[]{return true;},d.path.c_str(),"session.cm",true,true);
        assert(!r.result.evidence.summary.attempted&&fake.mutations==0&&r.result.recovery_latched);
    }
    {
        Directory d;ElmFixture fake(d.path);fake.setup(6);fake.admission(header,false);fake.recheck(header,false);
        fake.add("020801",header+"02 48 01");fake.close_ok=false;
        auto r=run_elm(p,target,fake,[]{return true;},d.path.c_str(),"session.cm",true,true);
        assert(r.result.evidence.summary.acknowledged&&!r.result.evidence.terminalPersisted&&!r.result.local_serial_closed);
        assert(r.result.recovery_latched&&fake.mutations==1);
    }
}
void approval_and_report(){
    Directory d;auto target=scope();
    volatile sig_atomic_t cancelled=1;
    rejected([&]{run_socket(target,"can0",d.path.c_str(),"session.cm",true,true,&cancelled);});
    rejected([&]{run_socket(target,"can0",d.path.c_str(),"session.cm",false,true,nullptr);});
    assert(std::filesystem::is_empty(d.path));
    Result r{};r.evidence.scope=target;r.local_socket_closed=true;r.journal_created=true;
    const auto text=report(r,"",{});
    assert(text.find("\"physical_stop_independently_verified\":false")!=std::string::npos);
    assert(text.find("\"physical_transceiver_disabled_verified\":false")!=std::string::npos);
    assert(text.find("\"permits_automatic_retry\":false")!=std::string::npos);
    assert(text.find("\"recovery_marker_requires_operator_review\":true")!=std::string::npos);
}
}
int main(){durable_open();unsafe_paths();changed_storage();approval_and_report();elm_maintenance();elm_failures();}
