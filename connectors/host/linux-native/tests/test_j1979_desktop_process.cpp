#include "j1979_desktop_process.hpp"
#include <cassert>
#include <cstdio>
#include <chrono>
#include <filesystem>
#include <stdexcept>
#include <thread>
#include <unistd.h>
using namespace cd::desktop;
int main(int argc,char** argv){
    // The self-executable is the synthetic CLI. It never opens a vehicle.
    if(argc>1){
        if(std::string_view(argv[1])=="--j1979-can"){
            assert(isatty(STDIN_FILENO));
            fputs("Confirm synthetic channel. Type yes: ",stderr);fflush(stderr);
            char answer[20]{};
            return fgets(answer,sizeof(answer),stdin)&&std::string_view(answer)=="yes\n"?0:2;
        }
        if(std::string_view(argv[1])=="verify-live"){puts("offline complete");return 0;}
        return 2;
    }
    const auto self=std::filesystem::canonical("/proc/self/exe").string();
    Process p;
    Invocation cmd{"--j1979-can",{"--j1979-can"},true,"--confirm-physical-channel"};
    p.start(self,cmd);
    assert(!p.approve());
    std::string output;
    for(unsigned i=0;i<200&&!p.awaiting_consent();++i){output+=p.drain();std::this_thread::sleep_for(std::chrono::milliseconds(5));}
    assert(p.awaiting_consent()&&output.find("Type yes")!=std::string::npos);
    assert(p.approve()&&!p.approve());
    int code=-1;
    for(unsigned i=0;i<200&&p.running();++i){(void)p.drain();(void)p.poll(code);std::this_thread::sleep_for(std::chrono::milliseconds(5));}
    assert(!p.running()&&code==0);
    (void)p.drain();
    Process offline;offline.start(self,{"verify-live",{"verify-live"},false,{}});
    output.clear();code=-1;
    for(unsigned i=0;i<200&&offline.running();++i){output+=offline.drain();(void)offline.poll(code);std::this_thread::sleep_for(std::chrono::milliseconds(5));}
    output+=offline.drain();
    assert(code==0&&output.find("offline complete")!=std::string::npos&&!offline.approve());
    // The same GUI process controller can perform successive independent
    // operations without inheriting the previous terminal or consent state.
    assert(offline.descriptor()==-1);
    offline.start(self,{"verify-live",{"verify-live"},false,{}});
    code=-1;
    for(unsigned i=0;i<200&&offline.running();++i){(void)offline.poll(code);std::this_thread::sleep_for(std::chrono::milliseconds(5));}
    assert(code==0&&!offline.running());
    // Even without an explicit final drain a new session is independent.
    offline.start(self,{"verify-live",{"verify-live"},false,{}});
    code=-1;
    for(unsigned i=0;i<200&&offline.running();++i){(void)offline.drain();(void)offline.poll(code);std::this_thread::sleep_for(std::chrono::milliseconds(5));}
    (void)offline.drain();
    assert(code==0&&offline.descriptor()==-1&&!offline.approve());
    Process denied;denied.start(self,cmd);denied.cancel();code=-1;
    for(unsigned i=0;i<200&&denied.running();++i){(void)denied.drain();(void)denied.poll(code);std::this_thread::sleep_for(std::chrono::milliseconds(5));}
    assert(!denied.running()&&code!=0&&!denied.approve());
    bool failed=false;
    try{Process invalid;invalid.start(self,{"--j1979-maintenance-can",{"--j1979-maintenance-can"},true,{}});}
    catch(const std::runtime_error&){failed=true;}assert(failed);
}
