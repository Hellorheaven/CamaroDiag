#include "j1979_host_freeze.hpp"
#include "j1979_freeze_evidence.h"
#include <algorithm>
#include <cerrno>
#include <stdexcept>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>
namespace cd::hostfreeze {
namespace {
using Campaign=J1979FreezeCampaign;
using State=J1979CanReadExchange::State;
uint64_t now(){
    timespec t{};
    if(clock_gettime(CLOCK_MONOTONIC,&t))return 0;
    return uint64_t(t.tv_sec)*1000+uint64_t(t.tv_nsec)/1000000;
}
class Writer final:public J1979FreezeEvidence::Writer{
    int fd_;
    size_t bytes_{};
    bool failed_{};
public:
    explicit Writer(int fd):fd_(fd){
        struct stat s{};
        if(fd_<0||fstat(fd_,&s)||!S_ISREG(s.st_mode)||s.st_size||s.st_nlink!=1||
            s.st_uid!=geteuid()||(s.st_mode&077))
            throw std::invalid_argument("New private CF/1 destination required");
    }
    bool write(const uint8_t* data,size_t n)override{
        if(failed_||!data||n>CF1_MAX_BYTES-bytes_)return false;
        while(n){
            const auto result=::write(fd_,data,n);
            if(result<0&&errno==EINTR)continue;
            if(result<=0){failed_=true;return false;}
            n-=static_cast<size_t>(result);bytes_+=static_cast<size_t>(result);data+=result;
        }
        // Preserve verified prefixes on abrupt termination, including a
        // torn last record which the independent prefix inspector handles.
        if(fsync(fd_)){failed_=true;return false;}
        return true;
    }
};
class Application final:public Campaign::Link{
    j1979_isotp_link link_{};
    const J1979FreezeScope scope_;
    const uint32_t tx_,rx_;
    const bool extended_;
public:
    Application(const cf1_plan& p,const J1979FreezeScope& scope,const char* iface,const volatile sig_atomic_t* cancel)
        :scope_(scope),tx_(p.tx),rx_(p.rx),extended_(p.extended){
        link_.socket_fd=-1;
        if(!j1979_isotp_open(&link_,iface,tx_,rx_,cancel))throw std::runtime_error("SCOPED_SOCKETCAN_UNAVAILABLE");
    }
    ~Application()override{j1979_isotp_close(&link_);}
    bool active()override{return j1979_isotp_current(&link_)&&now()!=0;}
    bool matches(uint32_t tx,uint32_t rx,bool ext)const override{return tx==tx_&&rx==rx_&&ext==extended_;}
    bool matchesFreeze(const J1979FreezeScope& scope)const override{return scope_.same(scope);}
    uint32_t monotonicMs()override{return static_cast<uint32_t>(now());}
    void exchange(const uint8_t* q,size_t n,uint32_t timeout,J1979CanReadExchange::Result& r)override{
        r.state=State::Fault;r.responseSize=0;r.elapsedMs=0;
        if(!active()||!scope_.allows(q,n))return;
        const auto start=now();
        size_t capacity=sizeof(r.response);
        const int rc=hostfreeze::exchange(link_,scope_,q,n,r.response,&capacity,timeout);
        const auto end=now();
        if(end<start||end-start>timeout||!active())return;
        r.elapsedMs=static_cast<uint32_t>(end-start);
        if(rc==1){r.state=State::Timeout;return;}
        if(rc!=0||capacity==0||capacity>sizeof(r.response))return;
        using R=J1979ReadEvidence::Result;
        switch(J1979ReadEvidence::classifyFreeze(scope_,q,n,r.response,capacity)){
        case R::Positive:r.state=State::Positive;break;
        case R::Raw:r.state=State::Raw;break;
        case R::Negative:r.state=State::Negative;break;
        default:return;
        }
        r.responseSize=capacity;
    }
    bool close_local(){
        const auto fd=link_.socket_fd;link_.socket_fd=-1;
        return fd>=0&&close(fd)==0;
    }
};
}
Result run_socket(const cf1_plan& p,const char* interface,int fd,const volatile sig_atomic_t* cancel){
    if(!cf1_valid_plan(&p)||!j1979_isotp_valid_interface(interface)||(cancel&&*cancel))
        throw std::invalid_argument("Explicit approved SocketCAN freeze plan required");
    J1979FreezeScope scope{};
    std::copy_n(reinterpret_cast<const uint8_t*>(p.vin),17,scope.vin);
    scope.frameCount=p.frame_count;scope.pidCount=p.pid_count;
    std::copy_n(p.frames,p.frame_count,scope.frames);std::copy_n(p.pids,p.pid_count,scope.pids);
    if(!scope.valid())throw std::invalid_argument("Noncanonical freeze plan");
    Writer writer(fd);J1979FreezeEvidence evidence(writer);
    Result result{};
    if(!evidence.begin(p.tx,p.rx,p.extended,p.budget_ms,scope))return result;
    result.capture_started=true;
    try{
        Application app(p,scope,interface,cancel);
        J1979CanReadExchange::Result scratch{};
        result.summary=Campaign::runApplication(app,p.tx,p.rx,p.extended,scope,p.budget_ms,evidence,scratch);
        result.local_socket_closed=app.close_local();
        // A terminal describes the host recipe only. A local descriptor
        // close is not an attestation of physical bus shutdown.
        if(result.local_socket_closed&&result.summary.steps&&result.summary.stop!=Campaign::Stop::SinkStopped)
            result.terminal_written=evidence.finish(result.summary);
    }catch(const std::exception&){
        // A plan-only or partially written stream is retained for offline
        // inspection; no invented observation or terminal.
        result.summary.stop=Campaign::Stop::ContextChanged;
    }
    return result;
}
}
