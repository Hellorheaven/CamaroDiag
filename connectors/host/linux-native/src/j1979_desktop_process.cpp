#include "j1979_desktop_process.hpp"
#include <algorithm>
#include <array>
#include <cerrno>
#include <csignal>
#include <cstring>
#include <fcntl.h>
#include <filesystem>
#include <pty.h>
#include <spawn.h>
#include <stdexcept>
#include <sys/stat.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>
extern char** environ;
namespace cd::desktop {
namespace {
struct Fd {
    int value{-1};
    ~Fd(){if(value>=0)close(value);}
    int release()noexcept{int f=value;value=-1;return f;}
};
void checked(int code,const char* message){
    if(code)throw std::runtime_error(std::string(message)+": "+strerror(code));
}
struct Actions{
    posix_spawn_file_actions_t value{};
    Actions(){checked(posix_spawn_file_actions_init(&value),"Cannot prepare local process");}
    ~Actions(){posix_spawn_file_actions_destroy(&value);}
};
struct Attributes{
    posix_spawnattr_t value{};
    Attributes(){checked(posix_spawnattr_init(&value),"Cannot configure local process");}
    ~Attributes(){posix_spawnattr_destroy(&value);}
};
void require(bool yes,const char* message){if(!yes)throw std::runtime_error(message);}
bool executable_regular(const std::string& name){
    struct stat s{};
    return std::filesystem::path(name).is_absolute()&&!lstat(name.c_str(),&s)&&S_ISREG(s.st_mode)&&
        !(s.st_mode&(S_ISUID|S_ISGID|S_IWOTH|S_IWGRP))&&access(name.c_str(),X_OK)==0;
}
}
void Process::close_master()noexcept{if(master_>=0)close(master_);master_=-1;eof_=true;}
Process::~Process(){
    // UI closes only when the child has exited. Exceptional destruction
    // still withdraws the process and closes its pseudo-terminal.
    if(child_>0){cancel();close_master();int ignored{};(void)waitpid(child_,&ignored,WNOHANG);}
    else close_master();
}
void Process::start(const std::string& executable,const Invocation& inv){
    require(!running()&&master_<0,"An operation is already running");
    require(executable_regular(executable),"Native executable must be a trusted local regular file");
    const auto& allowed=operations();
    const auto op=std::find_if(allowed.begin(),allowed.end(),[&](const Operation& candidate){return candidate.command==inv.command;});
    require(op!=allowed.end()&&op->physical==inv.physical&&!inv.args.empty()&&inv.args.front()==op->command,
        "Operation is not in the native desktop catalogue");
    require(inv.args.size()<=256,"Too many command arguments");
    std::vector<std::string> strings;strings.reserve(inv.args.size()+1);strings.push_back(executable);
    for(const auto& arg:inv.args){
        require(arg.size()<=4096&&arg.find('\0')==std::string::npos,"Invalid native argument");strings.push_back(arg);
    }
    std::vector<char*> argv;
    for(auto& s:strings)argv.push_back(s.data());
    argv.push_back(nullptr);
    Fd master,slave;
    if(openpty(&master.value,&slave.value,nullptr,nullptr,nullptr))throw std::runtime_error("Local pseudo-terminal unavailable");
    require(master.value>2&&slave.value>2,"Standard process descriptors unavailable");
    if(fcntl(master.value,F_SETFD,FD_CLOEXEC)||fcntl(slave.value,F_SETFD,FD_CLOEXEC))
        throw std::runtime_error("Local process descriptor configuration failed");
    const int flags=fcntl(master.value,F_GETFL);
    if(flags<0||fcntl(master.value,F_SETFL,flags|O_NONBLOCK))
        throw std::runtime_error("Local process nonblocking mode failed");
    termios terminal{};
    if(tcgetattr(slave.value,&terminal))throw std::runtime_error("Local consent terminal configuration failed");
    terminal.c_lflag|=ICANON;terminal.c_lflag&=~ECHO;
    terminal.c_iflag|=ICRNL;
    if(tcsetattr(slave.value,TCSANOW,&terminal))throw std::runtime_error("Local consent terminal configuration failed");
    Actions actions;
    for(int fd=0;fd<3;++fd)checked(posix_spawn_file_actions_adddup2(&actions.value,slave.value,fd),"Cannot connect local terminal");
    checked(posix_spawn_file_actions_addclose(&actions.value,master.value),"Cannot isolate local terminal");
    checked(posix_spawn_file_actions_addclose(&actions.value,slave.value),"Cannot isolate local terminal");
    Attributes attr;
    sigset_t mask{},defaults{};sigemptyset(&mask);sigemptyset(&defaults);
    for(int sig:{SIGINT,SIGTERM,SIGPIPE,SIGHUP})sigaddset(&defaults,sig);
    checked(posix_spawnattr_setsigmask(&attr.value,&mask),"Cannot set local signal mask");
    checked(posix_spawnattr_setsigdefault(&attr.value,&defaults),"Cannot set local signals");
    checked(posix_spawnattr_setpgroup(&attr.value,0),"Cannot isolate local process group");
    checked(posix_spawnattr_setflags(&attr.value,POSIX_SPAWN_SETPGROUP|POSIX_SPAWN_SETSIGMASK|POSIX_SPAWN_SETSIGDEF),"Cannot set local process attributes");
    pid_t child{};
    checked(posix_spawn(&child,executable.c_str(),&actions.value,&attr.value,argv.data(),environ),"Native command could not start");
    child_=child;master_=master.release();physical_=inv.physical;
    prompt_seen_=approved_=cancelled_=eof_=false;prompt_tail_.clear();
}
std::string Process::drain(){
    std::string out;
    if(master_<0)return out;
    std::array<char,8192> buffer{};
    // Bound each UI iteration even if a subprocess is noisy.
    while(out.size()<65536){
        const auto n=read(master_,buffer.data(),buffer.size());
        if(n<0&&errno==EINTR)continue;
        if(n<0&&(errno==EAGAIN||errno==EWOULDBLOCK))break;
        if(n<=0){close_master();break;}
        out.append(buffer.data(),static_cast<std::size_t>(n));
    }
    if(physical_&&!approved_&&!cancelled_){
        prompt_tail_+=out;
        if(prompt_tail_.find("Type yes: ")!=std::string::npos||prompt_tail_.find("Type yes:")!=std::string::npos)
            prompt_seen_=true;
        if(prompt_tail_.size()>512)prompt_tail_.erase(0,prompt_tail_.size()-512);
    }
    return out;
}
bool Process::approve(){
    if(!awaiting_consent()||master_<0)return false;
    constexpr char yes[]="yes\n";
    // A small canonical-terminal write is a single explicit operator
    // action. If it fails, never retry automatically.
    const auto n=write(master_,yes,sizeof(yes)-1);
    if(n!=static_cast<ssize_t>(sizeof(yes)-1)){cancel();return false;}
    approved_=true;return true;
}
void Process::cancel()noexcept{
    if(child_<=0||cancelled_)return;
    cancelled_=true;
    (void)kill(-child_,SIGTERM);
}
bool Process::poll(int& status){
    if(child_<=0)return false;
    int state{};
    const auto child=waitpid(child_,&state,WNOHANG);
    if(child==0||(child<0&&errno==EINTR))return false;
    if(child<0){
        if(errno!=ECHILD)throw std::runtime_error("Cannot observe native process exit");
        status=255;
    }else if(WIFEXITED(state))status=WEXITSTATUS(state);
    else if(WIFSIGNALED(state))status=128+WTERMSIG(state);
    else return false;
    child_=-1;return true;
}
}
