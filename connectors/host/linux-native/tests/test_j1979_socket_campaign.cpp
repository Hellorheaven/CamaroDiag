#include "j1979_socket_campaign.hpp"
#include "j1979_live_export.hpp"
#include "j1979_native_live_inspection.hpp"
#include <filesystem>
#include <algorithm>
#include <array>
#include <cassert>
#include <cstdlib>
#include <stdexcept>
#include <sys/stat.h>
#include <unistd.h>
using namespace cd::socketcampaign;
namespace {
constexpr std::string_view vin="1G1FA1RXXR0123456";
struct File{
    int fd{-1};
    File(){
        char named[]="/tmp/camarodiag-scj-XXXXXX";
        fd=mkstemp(named);assert(fd>=0);name=named;
        assert(fchmod(fd,0600)==0);
    }
    std::string name;
    ~File(){if(fd>=0)close(fd);if(!name.empty())unlink(name.c_str());}
    Data read()const{
        struct stat s{};assert(fstat(fd,&s)==0&&s.st_size>=0);
        Data out(static_cast<std::size_t>(s.st_size));
        std::size_t at=0;
        while(at<out.size()){
            auto n=pread(fd,out.data()+at,out.size()-at,static_cast<off_t>(at));
            assert(n>0);at+=static_cast<std::size_t>(n);
        }
        return out;
    }
};
struct Fake{
    std::uint64_t time{1000};
    unsigned calls{},vin_calls{},clock_calls{},stall_at{},stall_ms{};
    bool revoked{},timeout_speed{},bad_final{},oversize{},fail_persistence{};
    int fd{-1};
};
std::uint64_t clock(void* p){auto& f=*static_cast<Fake*>(p);if(++f.clock_calls==f.stall_at)f.time+=f.stall_ms;return f.time;}
bool current(void* p){return !static_cast<Fake*>(p)->revoked;}
bool wait(void* p,unsigned n){static_cast<Fake*>(p)->time+=n;return true;}
int exchange(void* context,const uint8_t* q,std::size_t n,uint8_t* r,std::size_t* size,unsigned timeout){
    auto& f=*static_cast<Fake*>(context);
    assert(j1979_can_allowed_read(q,n)&&timeout&&timeout<=2500);
    ++f.calls;f.time+=5;
    if(f.fail_persistence){assert(fchmod(f.fd,0644)==0);return -1;}
    Data out;
    if(n==2&&q[0]==9&&q[1]==2){
        ++f.vin_calls;out={0x49,2,1};
        const std::string_view identity=f.bad_final&&f.vin_calls>1?"2G1FA1RXXR0123456":vin;
        out.insert(out.end(),identity.begin(),identity.end());
    }else if(n==1)out={static_cast<uint8_t>(q[0]+0x40),0};
    else if(n==3&&q[0]==2&&q[1]==2)out={0x42,2,0,0,0};
    else if(q[1]==0)out={static_cast<uint8_t>(q[0]+0x40),0,0,static_cast<uint8_t>(q[0]==1?0x18:0),0,0};
    else if(q[0]==1&&q[1]==12){
        if(f.oversize){*size=4096;return 0;}
        out={0x41,12,0x1a,0xf8};
    }else if(q[0]==1&&q[1]==13){
        if(f.timeout_speed){*size=0;return 1;}
        out={0x41,13,42};
    }else assert(false);
    assert(out.size()<=*size);std::copy(out.begin(),out.end(),r);*size=out.size();return 0;
}
Plan plan(bool live){
    Plan p{};p.operation=live?Operation::live:Operation::scan;p.tx=0x7e0;p.rx=0x7e8;
    p.vin=vin;p.interface="vcan0";p.budget_ms=90000;
    if(live){p.pids={12,13};p.cycles=2;p.interval_ms=500;}
    assert(p.valid());return p;
}
Link link(Fake& f){return {exchange,clock,current,wait,&f};}
template<class F>void rejects(F f){
    bool rejected=false;try{f();}catch(const std::exception&){rejected=true;}assert(rejected);
}
Data rebuilt(const Journal& j){
    auto result=encode_header(j.plan);
    for(std::size_t i=0;i<j.events.size();++i){
        auto frame=encode_event(j.events[i],i);result.insert(result.end(),frame.begin(),frame.end());
    }
    return result;
}
void complete(bool live,Fake f={}){
    File file;
    Writer w(file.fd,plan(live));
    const auto r=record(plan(live),link(f),w);
    const auto prefix=decode(file.read(),true);
    assert(!prefix.terminal);
    const auto interrupted=replay(prefix);
    assert(interrupted.prefix&&!interrupted.completed&&interrupted.report.empty());
    rejects([&]{(void)decode(file.read(),false);});
    finish(w,r);
    const auto data=file.read();
    const auto j=decode(data,false);
    assert(j.terminal&&!j.torn&&j.plan==plan(live)&&j.verified_bytes==data.size());
    assert(rebuilt(j)==data);
    const auto same=replay(j);
    assert(same.report==r.report&&same.completed==r.completed&&same.stop==r.stop&&same.exchanges==f.calls);
    assert(same.dispatch_calls==f.calls&&same.response_records==f.calls);
    assert(r.completed==(!f.timeout_speed&&!f.bad_final&&!f.oversize));
    const auto checked=proof(j,same,sha256(data));
    assert(checked.at("report_sha256").string()==sha256(std::string_view(r.report)));
    assert(!checked.at("physical_vehicle_verified").boolean()&&!checked.at("permits_automatic_retry").boolean());
    auto corrupt=data;corrupt.back()^=1;rejects([&]{(void)decode(corrupt,true);});
    auto extra=data;extra.push_back('E');rejects([&]{(void)decode(extra,true);});
    auto short_tail=data;short_tail.pop_back();
    const auto torn=decode(short_tail,true);
    assert(torn.torn&&!torn.terminal&&replay(torn).prefix);
    auto wrong=j;wrong.events.back().bytes[0]^=1;
    rejects([&]{(void)replay(decode(rebuilt(wrong),false));});
    auto changed=j;
    auto intent=std::find_if(changed.events.begin(),changed.events.end(),[](const Event& e){return e.kind==Kind::intent;});
    assert(intent!=changed.events.end());
    intent->bytes={1,0};
    rejects([&]{(void)replay(decode(rebuilt(changed),false));});
    // Cut exactly after intent: the outcome is unknown, not a timeout.
    const auto first=std::find_if(j.events.begin(),j.events.end(),[](const Event& e){return e.kind==Kind::intent;});
    auto pending=j;pending.events.resize(static_cast<std::size_t>(first-j.events.begin())+1);
    const auto prefix_check=replay(decode(rebuilt(pending),true));
    assert(prefix_check.prefix&&prefix_check.pending_exchange&&!prefix_check.completed&&prefix_check.exchanges==1);
    rejects([&]{w.append({Kind::clock,1,0,{}});});
}
}
int main(){
    complete(false);complete(true);
    Fake f{};f.timeout_speed=true;complete(true,f);
    f={};f.bad_final=true;complete(true,f);complete(false,f);
    f={};f.oversize=true;complete(true,f);complete(false,f);
    // Numeric exports are based on durable raw bytes, never on a second
    // acquisition or a caller-supplied numeric summary.
    {
        File file,raw;
        Writer w(file.fd,plan(true));Fake transport{};
        const auto result=record(plan(true),link(transport),w);
        assert(write(raw.fd,result.report.data(),result.report.size())==static_cast<ssize_t>(result.report.size()));
        finish(w,result);
        const auto json=raw.name+".json",csv=raw.name+".csv";
        {
            cd::liveexport::Destination projection(json.c_str()),table(csv.c_str());
            rejects([&]{cd::liveexport::Destination collision(json.c_str());});
            rejects([&]{(void)cd::liveexport::publish(raw.fd,raw.name.c_str(),result.report+" ",
                {0x7e0,0x7e8},vin,projection,table);});
            rejects([&]{(void)cd::liveexport::publish(raw.fd,raw.name.c_str(),result.report,
                {0x7e1,0x7e9},vin,projection,table);});
            projection.check_empty();table.check_empty();
            const auto exported=cd::liveexport::publish(raw.fd,raw.name.c_str(),result.report,
                {0x7e0,0x7e8},vin,projection,table);
            assert(exported.complete&&exported.samples==4&&exported.source_sha256==sha256(result.report));
            assert(std::filesystem::file_size(json)>0&&std::filesystem::file_size(csv)>0);
            rejects([&]{(void)cd::liveexport::publish(raw.fd,raw.name.c_str(),result.report,
                {0x7e0,0x7e8},vin,projection,table);});
        }
        assert(unlink(json.c_str())==0&&unlink(csv.c_str())==0);
        assert(symlink(raw.name.c_str(),json.c_str())==0);
        rejects([&]{cd::liveexport::Destination forbidden(json.c_str());});
        assert(unlink(json.c_str())==0);
    }
    for(bool bad_identity:{false,true}){
        File file,raw;
        Writer w(file.fd,plan(true));Fake transport{};
        transport.bad_final=bad_identity;transport.timeout_speed=!bad_identity;
        const auto result=record(plan(true),link(transport),w);
        assert(!result.completed);
        assert(write(raw.fd,result.report.data(),result.report.size())==static_cast<ssize_t>(result.report.size()));
        finish(w,result);
        const auto json=raw.name+".json";
        {
            cd::liveexport::Destination projection(json.c_str()),none(nullptr);
            const auto exported=cd::liveexport::publish(raw.fd,raw.name.c_str(),result.report,
                {0x7e0,0x7e8},vin,projection,none);
            assert(!exported.complete&&exported.samples==4);
            const auto root=cd::network::history::project_native_live(cd::json::parse_report(result.report),
                {0x7e0,0x7e8},vin).report;
            assert(root.at("identity_rechecked").boolean()==!bad_identity);
            assert(!root.at("all_requested_samples_interpreted").boolean());
            for(const auto& summary:root.at("summary").array()){
                assert(summary.at("requested_samples").unsigned_integer()==2);
                if(bad_identity)assert(summary.at("engineering_samples").unsigned_integer()==0);
            }
            if(bad_identity)for(const auto& sample:root.at("samples").array())
                assert(sample.at("signals").array().empty());
        }
        assert(unlink(json.c_str())==0);
    }
    auto p=plan(true);p.pids={12,12};assert(!p.valid());
    p=plan(true);p.tx=0x7df;assert(!p.valid());
    p=plan(true);p.vin="INVALID";assert(!p.valid());
    rejects([]{(void)encode_event({Kind::intent,100,0,{4}},0);});
    rejects([]{(void)encode_event({Kind::response,1,0,{0x41,0}},0);});
    rejects([]{(void)encode_event({Kind::response,0,0,{0x41,0}},0);});
    // The third clock read in the live engine is the pre-dispatch
    // recheck after intent fsync. A stall there must prevent dispatch.
    {
        File file;Writer w(file.fd,plan(true));Fake transport{};
        transport.stall_at=3;transport.stall_ms=3000;
        const auto r=record(plan(true),link(transport),w);
        assert(transport.calls==0&&!r.completed&&r.exchanges==1&&r.response_records==1&&r.dispatch_calls==0);
        finish(w,r);
        const auto j=decode(file.read(),false);
        const auto checked=replay(j);
        assert(checked.report==r.report&&!checked.completed&&checked.dispatch_calls==0);
    }
    // A writer for a different plan is rejected before any ECU exchange.
    {
        File file;Writer w(file.fd,plan(true));Fake transport{};
        rejects([&]{(void)record(plan(false),link(transport),w);});
        assert(transport.calls==0);
    }
    // A same-length corruption in a previously persisted frame is
    // detected by the full replay before the terminal can be sealed.
    {
        File file;Writer w(file.fd,plan(true));Fake transport{};
        const auto result=record(plan(true),link(transport),w);
        const auto header=encode_header(plan(true));
        unsigned char byte{};
        assert(pread(file.fd,&byte,1,static_cast<off_t>(header.size()+15))==1);
        byte^=1;
        assert(pwrite(file.fd,&byte,1,static_cast<off_t>(header.size()+15))==1);
        rejects([&]{finish(w,result);});
        assert(!w.healthy());
    }
    {
        File file;Writer w(file.fd,plan(true));Fake transport{};
        auto result=record(plan(true),link(transport),w);
        result.report+=" ";
        rejects([&]{finish(w,result);});
        assert(!decode(file.read(),true).terminal);
    }
    // Failure before first intent does not dispatch an ECU request.
    {
        File file;Writer w(file.fd,plan(true));Fake transport{};
        assert(fchmod(file.fd,0644)==0);
        rejects([&]{(void)record(plan(true),link(transport),w);});
        assert(transport.calls==0);
    }
    // Failure after dispatch leaves a recoverable intent with no reply.
    {
        File file;Writer w(file.fd,plan(true));Fake transport{};
        transport.fail_persistence=true;transport.fd=file.fd;
        rejects([&]{(void)record(plan(true),link(transport),w);});
        assert(transport.calls==1);
        const auto j=decode(file.read(),true);
        const auto r=replay(j);
        assert(r.pending_exchange&&r.prefix&&!r.completed);
    }
}
