#include "j1979_can.h"
#include "j1979_host_freeze.hpp"
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
int main(){no_hardware();scoped_requests();}
