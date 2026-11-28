#include "j1979_host_maintenance.hpp"
#include "j1979_isotp.h"
#include <algorithm>
#include <array>
#include <cerrno>
#include <cstring>
#include <linux/can.h>
#include <linux/can/isotp.h>
#include <net/if.h>
#include <poll.h>
#include <stdexcept>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>

namespace cd::hostmaint {
namespace {
using Scratch=J1979CanReadExchange::Result;
using State=J1979CanReadExchange::State;
std::uint64_t now(){
    timespec t{};
    if(clock_gettime(CLOCK_MONOTONIC,&t))return 0;
    return std::uint64_t(t.tv_sec)*1000+std::uint64_t(t.tv_nsec)/1000000;
}
class Socket final:public Campaign::Owner,public Campaign::Link{
    const Scope scope_;
    std::string interface_;
    Journal& journal_;
    const volatile sig_atomic_t* cancel_;
    unsigned index_{};
    int fd_{-1};
    bool authorized_{},used_{},sent_{},retired_{},latched_{};
    bool current()const{
        return authorized_&&!retired_&&(!cancel_||!*cancel_)&&index_&&
            if_nametoindex(interface_.c_str())==index_&&now()!=0;
    }
    int wait(short events,std::uint64_t deadline){
        while(active()){
            const auto t=now();if(!t||t>=deadline)return 0;
            pollfd p{fd_,events,0};
            const auto n=poll(&p,1,static_cast<int>(std::min<std::uint64_t>(deadline-t,50)));
            if(n<0&&errno==EINTR)continue;
            if(n<0||(n>0&&(p.revents&(POLLERR|POLLHUP|POLLNVAL))))return -1;
            if(n>0&&(p.revents&events))return 1;
        }
        return -1;
    }
    void exchange(const uint8_t* request,size_t n,uint32_t timeout,Scratch& result,bool command){
        result.state=State::Fault;result.responseSize=0;result.elapsedMs=0;
        const auto start=now();
        if(!active()||!start||!timeout||timeout>Campaign::Sequence::MaxExchangeMs||
            !(command?scope_.mutation(request,n):scope_.read(request,n)))return;
        const auto deadline=start+timeout;
        auto finish=[&](State state,size_t size=0){
            const auto end=now();
            result.elapsedMs=end>=start?static_cast<uint32_t>(std::min<std::uint64_t>(end-start,UINT32_MAX)):UINT32_MAX;
            if(!active()||!end||(end>=deadline&&state!=State::Timeout)){result.state=State::Fault;result.responseSize=0;}
            else{result.state=state;result.responseSize=size;}
            if(result.state==State::Fault)retired_=true;
        };
        std::array<uint8_t,4096> buffer{};
        bool drained=false;
        for(unsigned i=0;i<64;++i){
            const auto got=recv(fd_,buffer.data(),buffer.size(),MSG_DONTWAIT);
            if(got<0&&(errno==EAGAIN||errno==EWOULDBLOCK)){drained=true;break;}
            if(got<0&&errno==EINTR)continue;
            if(got<0||!active()||now()>=deadline)break;
        }
        if(!drained||wait(POLLOUT,deadline)!=1){finish(State::Fault);return;}
        if(command){
            if(sent_||!journal_.consumeIntent(scope_)||!active()||now()>=deadline){finish(State::Fault);return;}
            sent_=true;
        }
        // This is the only send site; it receives either the immutable
        // maintenance recipe read or its exact one-shot operation.
        if(send(fd_,request,n,MSG_DONTWAIT)!=static_cast<ssize_t>(n)){
            finish(State::Fault);return;
        }
        unsigned pending=0;
        while(active()){
            const int status=wait(POLLIN,deadline);
            if(status<=0){
                finish(status==0&&!pending&&!command?State::Timeout:State::Fault);return;
            }
            const auto got=recv(fd_,buffer.data(),buffer.size(),MSG_DONTWAIT|MSG_TRUNC);
            if(got<0&&(errno==EINTR||errno==EAGAIN||errno==EWOULDBLOCK))continue;
            if(got<=0||static_cast<size_t>(got)>Campaign::MaxReply){finish(State::Fault);return;}
            const auto size=static_cast<size_t>(got);
            const auto shape=scope_.classify(request,n,buffer.data(),size);
            if(shape==Scope::Shape::Pending){
                if(++pending>8){finish(State::Fault);return;}
                continue;
            }
            if(shape!=Scope::Shape::Positive&&shape!=Scope::Shape::Negative){finish(State::Fault);return;}
            std::copy_n(buffer.begin(),size,result.response);
            finish(shape==Scope::Shape::Positive?State::Positive:State::Negative,size);return;
        }
        finish(State::Fault);
    }
public:
    Socket(const Scope& scope,const char* interface,Journal& journal,bool authorized,const volatile sig_atomic_t* cancel)
        :scope_(scope),interface_(interface?interface:""),journal_(journal),cancel_(cancel),authorized_(authorized){
        if(!j1979_isotp_valid_interface(interface)||!scope.valid()||!authorized)
            throw std::invalid_argument("Dedicated authorized primary ECM SocketCAN scope required");
        index_=if_nametoindex(interface);
    }
    ~Socket()override{if(fd_>=0)close(fd_);}
    bool approved(const Scope& s)override{return scope_.same(s)&&current();}
    bool matches(const Scope& s)const override{return scope_.same(s);}
    bool start(const Scope& s)override{
        if(used_||!approved(s))return false;
        used_=true;
        fd_=socket(PF_CAN,SOCK_DGRAM|SOCK_NONBLOCK|SOCK_CLOEXEC,CAN_ISOTP);
        if(fd_<0)return false;
        can_isotp_options options{};
        options.flags=CAN_ISOTP_TX_PADDING|CAN_ISOTP_WAIT_TX_DONE;
        options.txpad_content=0xcc;
        if(setsockopt(fd_,SOL_CAN_ISOTP,CAN_ISOTP_OPTS,&options,sizeof(options)))return false;
        sockaddr_can address{};
        address.can_family=AF_CAN;address.can_ifindex=static_cast<int>(index_);
        const auto ext=scope_.extended?CAN_EFF_FLAG:0u;
        address.can_addr.tp.tx_id=scope_.tx|ext;
        address.can_addr.tp.rx_id=scope_.rx|ext;
        return bind(fd_,reinterpret_cast<sockaddr*>(&address),sizeof(address))==0;
    }
    bool active()override{return fd_>=0&&current();}
    uint32_t monotonicMs()override{return static_cast<uint32_t>(now());}
    bool stopAndVerify()override{
        retired_=true;
        if(fd_<0)return !used_;
        const auto fd=fd_;fd_=-1;
        // This verifies only release of the local socket, not physical
        // transceiver disable or whole-bus quiescence.
        return close(fd)==0;
    }
    void latchRecovery()override{latched_=true;retired_=true;}
    bool latched()const{return latched_;}
    void read(const uint8_t* req,size_t size,uint32_t timeout,Scratch& result)override{
        exchange(req,size,timeout,result,false);
    }
    void command(const Scope& s,uint32_t timeout,Scratch& result)override{
        if(!scope_.same(s)){result.state=State::Fault;result.responseSize=0;return;}
        const std::array<uint8_t,2> request=scope_.operation==Scope::Operation::Clear?
            std::array<uint8_t,2>{4,0}:std::array<uint8_t,2>{8,1};
        exchange(request.data(),scope_.operation==Scope::Operation::Clear?1:2,timeout,result,true);
    }
};
}
Result run_socket(const Scope& scope,const char* interface,const char* directory,const char* journal_name,
                  bool warning,bool approved,const volatile sig_atomic_t* cancel){
    if(!scope.valid()||!warning||!approved||!j1979_isotp_valid_interface(interface)||(cancel&&*cancel))
        throw std::invalid_argument("Explicit physical maintenance approval required");
    Result result{};result.evidence.scope=scope;
    FileStorage storage(directory,journal_name);result.journal_created=true;
    Journal::Scratch journal_scratch{};
    Journal journal(storage,journal_scratch);
    Socket socket(scope,interface,journal,approved,cancel);
    Scratch exchange_scratch{};
    Campaign::run(socket,journal,socket,scope,result.evidence,exchange_scratch);
    result.local_socket_closed=result.evidence.physicalStopVerified;
    result.recovery_latched=storage.latched()||socket.latched()||result.evidence.recoveryRequired;
    if(!result.evidence.terminalPersisted)result.error="TERMINAL_NOT_PERSISTED";
    return result;
}
}
