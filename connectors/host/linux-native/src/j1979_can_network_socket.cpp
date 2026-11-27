#include "j1979_can_network.hpp"
#include "j1979_isotp.h"
#include <algorithm>
#include <cerrno>
#include <linux/can.h>
#include <linux/can/raw.h>
#include <net/if.h>
#include <poll.h>
#include <stdexcept>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>
namespace cd::network {
namespace {
std::uint64_t now(){
    timespec t{};
    if(clock_gettime(CLOCK_MONOTONIC,&t)!=0)throw std::runtime_error("CLOCK_UNAVAILABLE");
    return std::uint64_t(t.tv_sec)*1000+std::uint64_t(t.tv_nsec)/1000000;
}
struct Socket{
    int fd{-1};
    ~Socket(){if(fd>=0)close(fd);}
};
Frame convert(const can_frame& f){
    if(f.len>CAN_MAX_DLEN)throw std::runtime_error("INVALID_CLASSIC_FRAME");
    const bool ext=(f.can_id&CAN_EFF_FLAG)!=0;
    return {f.can_id&(ext?CAN_EFF_MASK:CAN_SFF_MASK),ext,
        (f.can_id&CAN_RTR_FLAG)!=0,(f.can_id&CAN_ERR_FLAG)!=0,{f.data,f.data+f.len}};
}
}
Discovery discover_socket(const char* interface,bool ext,unsigned window,std::function<bool()> current){
    if(!interface||!j1979_isotp_valid_interface(interface)||window<1||window>30000||!current)
        throw std::invalid_argument("Explicit classic SocketCAN discovery scope required");
    const auto start=now(),deadline=start+window;
    std::vector<Frame> frames;frames.reserve(max_frames);
    bool sent=false;std::string stop;
    try{
        if(!current())return replay_discovery(ext,false,{},"CANCELLED",0);
        Socket socket{::socket(AF_CAN,SOCK_RAW|SOCK_NONBLOCK|SOCK_CLOEXEC,CAN_RAW)};
        if(socket.fd<0)throw std::runtime_error("CAN_RAW_OPEN");
        const can_filter filter{
            ext?CAN_EFF_FLAG|0x18daf100u:0x7e8u,
            CAN_EFF_FLAG|CAN_RTR_FLAG|CAN_ERR_FLAG|(ext?0x1fffff00u:0x7f8u)
        };
        if(setsockopt(socket.fd,SOL_CAN_RAW,CAN_RAW_FILTER,&filter,sizeof(filter))!=0)
            throw std::runtime_error("CAN_FILTER");
        const unsigned index=if_nametoindex(interface);
        if(!index)throw std::runtime_error("CAN_INTERFACE");
        sockaddr_can addr{};addr.can_family=AF_CAN;addr.can_ifindex=static_cast<int>(index);
        if(bind(socket.fd,reinterpret_cast<sockaddr*>(&addr),sizeof(addr)))throw std::runtime_error("CAN_BIND");
        bool drained=false;
        for(unsigned i=0;i<64;++i){
            if(!current()||now()>=deadline)break;
            can_frame stale{};
            const auto n=recv(socket.fd,&stale,sizeof(stale),MSG_DONTWAIT);
            if(n<0&&(errno==EAGAIN||errno==EWOULDBLOCK)){drained=true;break;}
            if(n<0&&errno==EINTR)continue;
            if(n!=static_cast<ssize_t>(sizeof(stale)))throw std::runtime_error("CAN_STALE_FRAME");
        }
        if(!current())return replay_discovery(ext,false,{},"CANCELLED",static_cast<unsigned>(now()-start));
        if(!drained||now()>=deadline)throw std::runtime_error("CAN_STALE_QUEUE");
        const auto request=functional_request(ext);
        can_frame wire{};wire.can_id=request.id|(ext?CAN_EFF_FLAG:0);wire.len=8;
        std::copy(request.data.begin(),request.data.end(),wire.data);
        // No retry: the one functional discovery request is the only write.
        if(send(socket.fd,&wire,sizeof(wire),MSG_DONTWAIT)!=static_cast<ssize_t>(sizeof(wire)))throw std::runtime_error("CAN_DISCOVERY_SEND");
        sent=true;
        while(frames.size()<max_frames){
            if(!current()){stop="CANCELLED";break;}
            const auto t=now();if(t>=deadline)break;
            pollfd p{socket.fd,POLLIN,0};
            const auto rc=poll(&p,1,static_cast<int>(std::min<std::uint64_t>(deadline-t,100)));
            if(rc<0&&errno==EINTR)continue;
            if(rc<0||(rc>0&&(p.revents&(POLLERR|POLLHUP|POLLNVAL))))throw std::runtime_error("CAN_POLL");
            if(!rc||!(p.revents&POLLIN))continue;
            can_frame incoming{};
            const auto n=recv(socket.fd,&incoming,sizeof(incoming),MSG_DONTWAIT|MSG_TRUNC);
            if(n<0&&(errno==EAGAIN||errno==EINTR))continue;
            if(n!=static_cast<ssize_t>(sizeof(incoming)))throw std::runtime_error("CAN_FRAME_SIZE");
            if(now()>=deadline)break;
            frames.push_back(convert(incoming));
        }
        if(frames.size()==max_frames&&stop.empty())stop="FRAME_LIMIT";
    }catch(const std::exception&){stop=current()?"TRANSPORT_ERROR":"CANCELLED";}
    if(!current())stop="CANCELLED";
    return replay_discovery(ext,sent,frames,stop,static_cast<unsigned>(now()-start));
}
}
