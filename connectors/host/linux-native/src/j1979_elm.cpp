#define _POSIX_C_SOURCE 200809L
#include "j1979_elm.hpp"
#include <algorithm>
#include <array>
#include <cerrno>
#include <fcntl.h>
#include <poll.h>
#include <sys/ioctl.h>
#include <termios.h>
#include <time.h>
#include <unistd.h>
#include <utility>

namespace cd::legacy {
namespace {
bool space(char c){return c==' '||c=='\t'||c=='\r'||c=='\n';}
std::string uppercase(std::string_view s){
    std::string result;
    for(char c:s)result+=c>='a'&&c<='z'?char(c-'a'+'A'):c;
    return result;
}
bool acknowledged(std::string_view response,std::string_view request){
    std::vector<std::string> tokens;
    std::size_t begin=0;
    for(std::size_t i=0;i<=response.size();++i){
        if(i<response.size()&&response[i]!='\r'&&response[i]!='\n')continue;
        auto part=response.substr(begin,i-begin);begin=i+1;
        while(!part.empty()&&space(part.front()))part.remove_prefix(1);
        while(!part.empty()&&space(part.back()))part.remove_suffix(1);
        const auto text=uppercase(part);
        if(!text.empty()&&text!=request)tokens.push_back(text);
    }
    return tokens==std::vector<std::string>{"OK"};
}
bool port_name(std::string_view name){
    for(const std::string_view prefix:{"/dev/ttyACM","/dev/ttyUSB","/dev/rfcomm"}){
        if(!name.starts_with(prefix))continue;
        name.remove_prefix(prefix.size());
        return !name.empty()&&name.size()<=3&&std::all_of(name.begin(),name.end(),[](char c){return c>='0'&&c<='9';});
    }
    return false;
}
speed_t speed(unsigned baud){
    switch(baud){
    case 9600:return B9600;
    case 19200:return B19200;
    case 38400:return B38400;
    case 57600:return B57600;
    case 115200:return B115200;
    case 230400:return B230400;
    default:throw std::invalid_argument("Unsupported ELM baud");
    }
}
class Serial final:public Link{
    int fd_{-1};
    termios saved_{};
    bool configured_{},retired_{};
    Current current_;
    void require_current()const{
        if(!current_||!current_())throw Cancelled();
    }
    void release()noexcept{
        if(fd_>=0){if(configured_)tcsetattr(fd_,TCSANOW,&saved_);close(fd_);fd_=-1;}
    }
    void ready(short events,std::uint64_t deadline){
        for(;;){
            require_current();
            const auto now=monotonic_ms();
            if(!now||now>=deadline)throw TransportError("ELM deadline exceeded");
            pollfd p{fd_,events,0};
            const int result=poll(&p,1,static_cast<int>(std::min<std::uint64_t>(100,deadline-now)));
            if(result<0&&errno==EINTR)continue;
            if(result<0||(result>0&&(p.revents&(POLLERR|POLLHUP|POLLNVAL))))
                throw TransportError("ELM serial disconnected");
            if(result>0&&(p.revents&events))return;
        }
    }
public:
    Serial(std::string_view port,unsigned baud,Current current):current_(std::move(current)){
        if(!port_name(port)||!current_)throw std::invalid_argument("Explicit local ELM port required");
        const auto rate=speed(baud);
        require_current();
        const std::string path(port);
        fd_=open(path.c_str(),O_RDWR|O_NOCTTY|O_NONBLOCK|O_NOFOLLOW|O_CLOEXEC);
        if(fd_<0)throw TransportError("Cannot open selected ELM serial port");
        try{
            if(ioctl(fd_,TIOCEXCL)<0||!isatty(fd_)||tcgetattr(fd_,&saved_)!=0)
                throw TransportError("Cannot exclusively claim ELM port");
            auto settings=saved_;
            settings.c_iflag=IGNPAR;settings.c_oflag=0;settings.c_lflag=0;
            tcflag_t flow=0;
#ifdef CRTSCTS
            flow=CRTSCTS;
#endif
            settings.c_cflag=(settings.c_cflag&~(CSIZE|PARENB|CSTOPB|flow))|CS8|CREAD|CLOCAL;
            settings.c_cc[VMIN]=0;settings.c_cc[VTIME]=0;
            if(cfsetispeed(&settings,rate)||cfsetospeed(&settings,rate)||tcsetattr(fd_,TCSANOW,&settings))
                throw TransportError("Cannot configure ELM serial");
            configured_=true;
            if(tcflush(fd_,TCIOFLUSH))throw TransportError("Cannot synchronize dedicated ELM port");
        }catch(...){release();throw;}
    }
    ~Serial()override{release();}
    std::string command(std::string_view command,unsigned timeout)override{
        if(retired_||command.empty()||command.size()>32||timeout<1||timeout>4000)
            throw TransportError("Retired or invalid ELM session");
        if(!std::all_of(command.begin(),command.end(),[](char c){return (c>='0'&&c<='9')||(c>='A'&&c<='Z');}))
            throw std::invalid_argument("Unexpected ELM command bytes");
        try{
            require_current();
            const auto start=monotonic_ms();
            if(!start)throw TransportError("Monotonic clock unavailable");
            const auto deadline=start+timeout;
            const std::string wire=std::string(command)+"\r";
            std::size_t sent=0;
            while(sent<wire.size()){
                ready(POLLOUT,deadline);
                const auto n=write(fd_,wire.data()+sent,wire.size()-sent);
                if(n<0&&(errno==EAGAIN||errno==EINTR))continue;
                if(n<=0)throw TransportError("ELM write interrupted; no retry");
                sent+=static_cast<std::size_t>(n);
            }
            std::string received;received.reserve(1024);
            std::array<char,256> chunk{};
            for(;;){
                ready(POLLIN,deadline);
                const auto n=read(fd_,chunk.data(),chunk.size());
                if(n<0&&(errno==EAGAIN||errno==EINTR))continue;
                if(n<0)throw TransportError("ELM read interrupted");
                for(std::size_t i=0;i<static_cast<std::size_t>(n);++i){
                    const auto c=static_cast<unsigned char>(chunk[i]);
                    if(c=='>'){
                        if(i+1!=static_cast<std::size_t>(n))throw TransportError("Unexpected bytes after ELM prompt");
                        require_current();
                        const auto end=monotonic_ms();
                        if(!end||end>=deadline)throw TransportError("ELM prompt arrived late");
                        return received;
                    }
                    if((c<32&&c!='\r'&&c!='\n'&&c!='\t')||c>126)
                        throw TransportError("Non-ASCII ELM response");
                    if(received.size()==32768)throw TransportError("ELM response byte limit exceeded");
                    received+=static_cast<char>(c);
                }
            }
        }catch(...){retired_=true;throw;}
    }
};
}
std::uint64_t monotonic_ms(){
    timespec now{};
    if(clock_gettime(CLOCK_MONOTONIC,&now))return 0;
    return std::uint64_t(now.tv_sec)*1000u+std::uint64_t(now.tv_nsec)/1000000u;
}
std::unique_ptr<Link> serial_link(std::string_view port,unsigned baud,Current current){
    return std::make_unique<Serial>(port,baud,std::move(current));
}
Elm::Elm(Link& link,Protocol protocol,bool headered,Current current):
    link_(link),protocol_(protocol),headered_(headered),current_(std::move(current)){
    if(!valid_protocol(protocol_)||!current_)throw std::invalid_argument("Fixed legacy protocol and context required");
}
std::string Elm::command(std::string_view request,unsigned timeout){
    if(!current_()){ready_=false;throw Cancelled();}
    try{
        auto response=link_.command(request,timeout);
        if(!current_())throw Cancelled();
        if(response.size()>32768)throw TransportError("ELM reply exceeds configured bound");
        return response;
    }catch(...){ready_=false;throw;}
}
void Elm::configure(){
    if(used_)throw TransportError("ELM session cannot be reused");
    used_=true;
    const auto banner=uppercase(command("ATZ",4000));
    if(banner.find("ELM")==std::string::npos&&banner.find("STN")==std::string::npos&&banner.find("OBDLINK")==std::string::npos)
        throw TransportError("Unrecognized adapter identity");
    std::vector<std::string> config{"ATE0","ATL0","ATS1",headered_?"ATH1":"ATH0",
        "ATSP"+std::to_string(static_cast<unsigned>(protocol_))};
    if(headered_)config.push_back(protocol_==Protocol::pwm?"ATSH616AF1":
        protocol_==Protocol::kwp_slow||protocol_==Protocol::kwp_fast?"ATSHC033F1":"ATSH686AF1");
    for(const auto& cmd:config)
        if(!acknowledged(command(cmd,2000),cmd))throw TransportError("Adapter rejected fixed protocol configuration");
    ready_=true;
}
std::string Elm::exchange(Bytes request,unsigned timeout){
    if(!ready_||!read_allowed(request)||timeout<1||timeout>4000)
        throw std::invalid_argument("Read request outside legacy scope");
    return command(hex(request),timeout);
}
std::string Elm::probe(unsigned timeout){
    if(!ready_||timeout<1||timeout>2000)throw std::invalid_argument("Invalid fixed protocol probe");
    const auto response=command("ATDPN",timeout);
    if(fixed_protocol(response)!=protocol_)ready_=false;
    return response;
}
}
