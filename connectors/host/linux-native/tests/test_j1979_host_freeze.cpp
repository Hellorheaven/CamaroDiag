#include "j1979_can.h"
#include "j1979_host_freeze.hpp"
#include "j1979_elm_can_trace.hpp"
#include <algorithm>
#include <cassert>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <fstream>
#include <iterator>
#include <sys/stat.h>
#include <unistd.h>
#include <vector>
#include <deque>
#include <string>
namespace {
struct Temporary{
    char path[48]="/tmp/camarodiag-freeze-XXXXXX";
    int fd{-1};
    Temporary(){fd=mkstemp(path);assert(fd>=0);}
    ~Temporary(){if(fd>=0)close(fd);unlink(path);}
    std::vector<uint8_t> data()const{
        std::ifstream input(path,std::ios::binary);assert(input);
        return {std::istreambuf_iterator<char>(input),std::istreambuf_iterator<char>()};
    }
};
template<class F>void rejects(F f){bool rejected=false;try{f();}catch(const std::exception&){rejected=true;}assert(rejected);}
cf1_plan plan(){
    cf1_plan p{};p.tx=0x7e0;p.rx=0x7e8;p.budget_ms=60000;
    std::memcpy(p.vin,"1G1FA1RXXR0123456",18);
    p.frame_count=2;p.frames[0]=0;p.frames[1]=7;p.pid_count=1;p.pids[0]=12;
    return p;
}
void no_hardware(){
    Temporary file;
    const auto p=plan();
    volatile sig_atomic_t cancelled=1;
    rejects([&]{cd::hostfreeze::run_socket(p,"can0",file.fd,&cancelled);});
    assert(file.data().empty());
    auto invalid=p;invalid.tx=0x7df;
    rejects([&]{cd::hostfreeze::run_socket(invalid,"can0",file.fd,nullptr);});
    assert(file.data().empty());
    rejects([&]{cd::hostfreeze::run_socket(p,"../bad",file.fd,nullptr);});
    assert(file.data().empty());
    // Invalid interface name is rejected before socket access. A legal
    // interface name deliberately absent from this fixture retains the
    // plan-only prefix, never a terminal or synthesized vehicle response.
    const auto missing="cdnoif987654321";
    const auto r=cd::hostfreeze::run_socket(p,missing,file.fd,nullptr);
    assert(r.capture_started&&!r.terminal_written&&!r.local_socket_closed);
    const auto bytes=file.data();
    assert(bytes.size()==CF1_PLAN_SIZE+12);
    cf1_evidence evidence{};
    char err[256]{};
    assert(cf1_inspect_prefix(bytes.data(),bytes.size(),p.vin,&evidence,err,sizeof(err))==0);
    assert(evidence.observation_count==0&&evidence.replay_missing_observation&&!evidence.execution_completed);
    assert(evidence.plan.frame_count==2&&evidence.plan.frames[1]==7);
    cf1_evidence_free(&evidence);
    rejects([&]{cd::hostfreeze::run_socket(p,missing,file.fd,nullptr);});
    assert(file.data()==bytes);
}
struct ElmFixture:cd::legacy::Link{
    std::deque<std::pair<std::string,std::string>> rows;
    unsigned count{};
    void add(std::string q,std::string r){rows.emplace_back(std::move(q),std::move(r));}
    std::string command(std::string_view q,unsigned timeout)override{
        assert(timeout&&timeout<=4000&&!rows.empty()&&rows.front().first==q);
        ++count;auto r=std::move(rows.front().second);rows.pop_front();return r;
    }
    void configure(unsigned profile){
        const bool extended=profile==7||profile==9;
        add("ATZ","ELM327");
        for(auto cmd:{"ATE0","ATL0","ATH1","ATS1","ATD0"})add(cmd,"OK");
        add("ATSP"+std::to_string(profile),"OK");add("ATCAF0","OK");
        add(extended?"ATSH18DA10F1":"ATSH7E0","OK");
        add(extended?"ATCRA18DAF110":"ATCRA7E8","OK");
        add(extended?"ATFCSH18DA10F1":"ATFCSH7E0","OK");
        for(auto cmd:{"ATFCSD300000","ATFCSM1","ATCFC1"})add(cmd,"OK");
        add("ATDPN",std::to_string(profile));
    }
    void identity(std::string header){
        add("020902",header+"10 14 49 02 01 31 47 31\r"+
            header+"21 46 41 31 52 58 58 52\r"+
            header+"22 30 31 32 33 34 35 36");
    }
    void capture(std::string header,bool changed=false){
        identity(header);
        add("03020000",header+"07 42 00 00 40 10 00 00");
        add("03020200",header+"05 42 02 00 01 33");
        add("03020C00",header+"05 42 0C 00 1F 40");
        add("03020200",header+(changed?"05 42 02 00 01 34":"05 42 02 00 01 33"));
        add("03020007",header+"07 42 00 07 40 00 00 00");
        add("03020207",header+"05 42 02 07 00 00");
        identity(header);
    }
};
void elm_capture(){
    for(unsigned profile=6;profile<=9;++profile){
        const bool ext=profile==7||profile==9;
        auto p=plan();
        if(ext){p.tx=0x18da10f1;p.rx=0x18daf110;p.extended=true;}
        const cd::elmcan::Scope target{profile,p.tx,p.rx};
        const std::string header=ext?"18 DA F1 10 ":"7E8 ";
        for(bool changed:{false,true}){
            Temporary out;ElmFixture fake;fake.configure(profile);fake.capture(header,changed);
            auto result=cd::hostfreeze::run_elm(p,target,fake,[]{return true;},out.fd);
            assert(result.capture.capture_started&&result.capture.terminal_written&&result.capture.summary.completed);
            assert(result.capture.summary.allFramesStable==!changed&&fake.rows.empty());
            assert(result.observations.size()==fake.count);
            assert(!result.capture.local_socket_closed);
            const auto raw=out.data();cf1_evidence evidence{};char failure[256]{};
            assert(cf1_inspect_complete(raw.data(),raw.size(),p.vin,&evidence,failure,sizeof(failure))==0);
            assert(evidence.execution_completed&&evidence.observation_count==8);
            const cd::elmcan::Trace trace{target,p.vin,cd::elmcan::TraceOperation::freeze,result.observations};
            const auto encoded=cd::elmcan::encode_trace(trace);
            const auto parsed=cd::elmcan::decode_trace(encoded);
            const auto verified=cd::elmcan::correlate_freeze(parsed,evidence);
            assert(verified.configured&&verified.setup_rows==15&&verified.diagnostic_rows==8&&verified.positive==8);
            auto bad=encoded;bad.back()^=1;
            rejects([&]{cd::elmcan::decode_trace(bad);});
            bad=encoded;bad.pop_back();
            rejects([&]{cd::elmcan::decode_trace(bad);});
            bad=encoded;bad.push_back(0);
            rejects([&]{cd::elmcan::decode_trace(bad);});
            auto forged=parsed;forged.rows.back().response.back()^=1;
            rejects([&]{cd::elmcan::replay_trace(forged);});
            forged=parsed;forged.rows[8].command="ATSH7DF";
            rejects([&]{cd::elmcan::replay_trace(forged);});
            forged=parsed;forged.rows.pop_back();
            rejects([&]{cd::elmcan::correlate_freeze(forged,evidence);});
            forged=parsed;forged.vin.back()='7';
            rejects([&]{cd::elmcan::correlate_freeze(forged,evidence);});
            cf1_evidence_free(&evidence);
        }
        Temporary out;ElmFixture wrong;wrong.configure(profile);
        wrong.rows.back().second="A"+std::to_string(profile);
        auto result=cd::hostfreeze::run_elm(p,target,wrong,[]{return true;},out.fd);
        assert(result.capture.capture_started&&!result.capture.terminal_written&&!result.error.empty());
        assert(out.data().size()==CF1_PLAN_SIZE+12);
        Temporary refused;ElmFixture unused;
        rejects([&]{cd::hostfreeze::run_elm(p,target,unused,[]{return false;},refused.fd);});
        assert(refused.data().empty()&&unused.count==0);
    }
}
void scoped_requests(){
    cd::J1979FreezeScope scope{};
    const auto p=plan();
    std::copy_n(reinterpret_cast<const uint8_t*>(p.vin),17,scope.vin);
    scope.frameCount=2;scope.frames[1]=7;scope.pidCount=1;scope.pids[0]=12;
    assert(scope.valid());
    uint8_t allowed[]={2,12,7},blocked[]={2,12,8},mutating[]={4};
    assert(scope.allows(allowed,3)&&!scope.allows(blocked,3)&&!scope.allows(mutating,1));
    assert(!j1979_can_allowed_read(allowed,3));
    j1979_isotp_link link{};link.socket_fd=-1;
    uint8_t response[32]{};
    for(const auto* q:{allowed,blocked,mutating}){
        size_t size=sizeof(response);
        assert(cd::hostfreeze::exchange(link,scope,q,q==mutating?1:3,response,&size,100)<0&&size==0);
    }
    size_t size=sizeof(response);
    assert(j1979_isotp_exchange(&link,blocked,3,response,&size,100)<0&&size==0);
}
}
int main(){no_hardware();scoped_requests();elm_capture();}
