#include "j1979_cu45_session.hpp"
#include <algorithm>
#include <cerrno>
#include <fcntl.h>
#include <openssl/rand.h>
#include <poll.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <termios.h>
#include <time.h>
#include <unistd.h>
#include <utility>

namespace cd::cu45 {
namespace {
std::uint64_t now(){
    timespec t{};
    if(clock_gettime(CLOCK_MONOTONIC,&t)!=0)throw TransportError("MONOTONIC_CLOCK_UNAVAILABLE");
    return std::uint64_t(t.tv_sec)*1000+std::uint64_t(t.tv_nsec)/1000000;
}
bool tty_name(std::string_view path){
    for(const auto prefix:{std::string_view("/dev/ttyACM"),std::string_view("/dev/ttyUSB")}){
        if(!path.starts_with(prefix))continue;
        path.remove_prefix(prefix.size());
        return !path.empty()&&path.size()<=3&&std::all_of(path.begin(),path.end(),[](char c){return c>='0'&&c<='9';});
    }
    return false;
}
class Serial final:public Link{
    int fd_{-1};
    termios original_{};
    bool configured_{};
    Current current_;
    void check(std::uint64_t end){
        if(!current_())throw TransportError("CONTEXT_REVOKED");
        if(now()>=end)throw TransportError("SERIAL_DEADLINE");
    }
    void ready(short events,std::uint64_t end){
        while(true){
            check(end);
            const auto time=now();
            if(time>=end)throw TransportError("SERIAL_DEADLINE");
            pollfd p{fd_,events,0};
            const auto n=poll(&p,1,static_cast<int>(std::min<std::uint64_t>(end-time,100)));
            if(n<0&&errno==EINTR)continue;
            if(n<0||(n>0&&(p.revents&(POLLERR|POLLHUP|POLLNVAL))))throw TransportError("SERIAL_DISCONNECTED");
            if(n>0&&(p.revents&events))return;
        }
    }
    bool close_port()noexcept{
        if(fd_<0)return true;
        bool ok=!configured_||tcsetattr(fd_,TCSANOW,&original_)==0;
        if(close(fd_)!=0)ok=false;
        fd_=-1;return ok;
    }
public:
    Serial(const char* port,Current current):current_(std::move(current)){
        if(!port||!tty_name(port)||!current_||!current_())throw TransportError("EXPLICIT_CDC_PORT_REQUIRED");
        fd_=open(port,O_RDWR|O_NOCTTY|O_NONBLOCK|O_NOFOLLOW|O_CLOEXEC);
        if(fd_<0)throw TransportError("CDC_OPEN_FAILED");
        try{
            if(!isatty(fd_)||ioctl(fd_,TIOCEXCL)<0||tcgetattr(fd_,&original_)!=0)
                throw TransportError("CDC_EXCLUSIVE_CLAIM_FAILED");
            auto t=original_;
            t.c_iflag=IGNPAR;t.c_oflag=0;t.c_lflag=0;
            tcflag_t mask=CSIZE|PARENB|CSTOPB;
#ifdef CRTSCTS
            mask|=CRTSCTS;
#endif
            t.c_cflag=(t.c_cflag&~mask)|CS8|CREAD|CLOCAL;
            t.c_cc[VMIN]=0;t.c_cc[VTIME]=0;
            if(cfsetispeed(&t,B115200)||cfsetospeed(&t,B115200)||tcsetattr(fd_,TCSANOW,&t))
                throw TransportError("CDC_CONFIGURATION_FAILED");
            configured_=true;
            if(tcflush(fd_,TCIOFLUSH))throw TransportError("CDC_SYNCHRONIZATION_FAILED");
        }catch(...){close_port();throw;}
    }
    ~Serial()override{close_port();}
    void write(cm1::Bytes bytes,std::uint64_t end)override{
        while(!bytes.empty()){
            ready(POLLOUT,end);
            const auto n=::write(fd_,bytes.data(),bytes.size());
            if(n<0&&(errno==EINTR||errno==EAGAIN))continue;
            if(n<=0)throw TransportError("SERIAL_WRITE_UNCERTAIN");
            bytes=bytes.subspan(static_cast<std::size_t>(n));
        }
        check(end);
    }
    std::size_t read(std::span<std::uint8_t> bytes,std::uint64_t end)override{
        for(;;){
            ready(POLLIN,end);
            const auto n=::read(fd_,bytes.data(),bytes.size());
            if(n<0&&(errno==EINTR||errno==EAGAIN))continue;
            if(n<0)throw TransportError("SERIAL_READ_FAILED");
            if(n>0)return static_cast<std::size_t>(n);
        }
    }
    void quiet(std::uint64_t end)override{
        check(end);
        const auto begin=now();
        if(begin>=end||end-begin<=50)throw TransportError("NO_TRAILING_CHECK_BUDGET");
        while(now()-begin<50){
            check(end);
            const auto elapsed=now()-begin;
            if(elapsed>=50)break;
            pollfd p{fd_,POLLIN,0};
            const int n=poll(&p,1,static_cast<int>(50-elapsed));
            if(n<0&&errno==EINTR)continue;
            if(n!=0)throw TransportError("UNEXPECTED_CDC_DATA_OR_DISCONNECT");
        }
        check(end);
    }
    void finish()override{if(!close_port())throw TransportError("CDC_RESTORE_OR_CLOSE_FAILED");}
};
class Journal {
    int fd_{-1};
public:
    explicit Journal(const char* path){
        if(!path||!*path)throw TransportError("JOURNAL_PATH_REQUIRED");
        fd_=open(path,O_WRONLY|O_CREAT|O_EXCL|O_NOFOLLOW|O_CLOEXEC,0600);
        if(fd_<0)throw TransportError("JOURNAL_DESTINATION_UNAVAILABLE");
    }
    ~Journal(){if(fd_>=0){fsync(fd_);close(fd_);}}
    void append(cm1::Bytes bytes){
        while(!bytes.empty()){
            const auto n=write(fd_,bytes.data(),bytes.size());
            if(n<0&&errno==EINTR)continue;
            if(n<=0)throw TransportError("JOURNAL_WRITE_FAILED");
            bytes=bytes.subspan(static_cast<std::size_t>(n));
        }
    }
    void finish(){
        const bool synced=fsync(fd_)==0;
        const bool closed=close(fd_)==0;
        fd_=-1;
        if(!synced||!closed)throw TransportError("JOURNAL_PERSISTENCE_FAILED");
    }
};
}
Result capture_serial(Mode mode,const cm1::Scope& scope,std::span<const std::uint8_t,32> key,
    const char* port,const char* path,bool authorized,Current current){
    Result result{};result.mode=mode;result.scope=scope;
    try{
        if(!authorized||!valid_scope(scope)||!current||!current()||
            (mode!=Mode::archive&&mode!=Mode::maintenance))
            throw TransportError("EXPLICIT_AUTHORIZED_SCOPE_REQUIRED");
        Nonce nonce{};
        if(RAND_bytes(nonce.data(),static_cast<int>(nonce.size()))!=1)
            throw TransportError("SECURE_NONCE_UNAVAILABLE");
        Journal journal(path);result.file_created=true;
        try{
            Serial link(port,current);
            Session session(link,now,current);
            result=session.run(mode,scope,key,nonce,[&](cm1::Bytes bytes){journal.append(bytes);},authorized);
            result.file_created=true;
        }catch(const std::exception& e){result.error=e.what();}
        try{journal.finish();}
        catch(const std::exception& e){if(result.error.empty())result.error=e.what();}
    }catch(const std::exception& e){result.error=e.what();}
    if(current&&!current()){result.cancelled=true;if(result.error.empty())result.error="CONTEXT_REVOKED";}
    if(!result.error.empty())result.inspection.reset();
    return result;
}
}
