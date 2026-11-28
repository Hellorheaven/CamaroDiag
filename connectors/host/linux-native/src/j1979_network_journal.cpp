#include "j1979_network_journal.hpp"
#include "j1979_isotp.h"
#include "j1979_native_report_detail.hpp"
#include <algorithm>
#include <cerrno>
#include <fcntl.h>
#include <iomanip>
#include <locale>
#include <sstream>
#include <stdexcept>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

namespace cd::network {
namespace {
using history::detail::require;
struct File {
    int fd{-1};
    ~File(){if(fd>=0)close(fd);}
};
bool safe_dir(int fd,struct stat& s){
    return fd>=0&&fstat(fd,&s)==0&&S_ISDIR(s.st_mode)&&s.st_uid==geteuid()&&!(s.st_mode&077);
}
bool same(const struct stat& a,const struct stat& b){return a.st_dev==b.st_dev&&a.st_ino==b.st_ino;}
void save(int fd,std::string_view data){
    std::size_t at=0;
    while(at<data.size()){
        const auto n=write(fd,data.data()+at,data.size()-at);
        if(n<0&&errno==EINTR)continue;
        if(n<=0)throw std::runtime_error("Network discovery journal write failed");
        at+=static_cast<std::size_t>(n);
    }
    if(fsync(fd))throw std::runtime_error("Network discovery journal sync failed");
    std::string readback(data.size(),'\0');at=0;
    while(at<readback.size()){
        const auto n=pread(fd,readback.data()+at,readback.size()-at,static_cast<off_t>(at));
        if(n<0&&errno==EINTR)continue;
        if(n<=0)throw std::runtime_error("Network discovery journal readback failed");
        at+=static_cast<std::size_t>(n);
    }
    require(readback==data,"Network discovery journal differs from acquisition");
}
std::uint64_t monotonic(void*){
    timespec t{};
    if(clock_gettime(CLOCK_MONOTONIC,&t))return 0;
    return std::uint64_t(t.tv_sec)*1000+std::uint64_t(t.tv_nsec)/1000000;
}
bool wait(void* context,unsigned ms){
    if(!ms||ms>100||!j1979_isotp_current(context))return false;
    timespec t{static_cast<time_t>(ms/1000),static_cast<long>(ms%1000)*1000000};
    while(nanosleep(&t,&t)){
        if(errno!=EINTR||!j1979_isotp_current(context))return false;
    }
    return j1979_isotp_current(context);
}
struct Link {
    j1979_isotp_link value{};
    Link(){value.socket_fd=-1;}
    ~Link(){j1979_isotp_close(&value);}
};
}
std::string journal_name(std::uint32_t rx){
    require(rx<=0x1fffffffu,"Invalid journal response identifier");
    std::ostringstream s;s.imbue(std::locale::classic());s<<"ecu-"<<std::uppercase<<std::hex<<rx<<".scj";
    return s.str();
}
JournalDirectory::JournalDirectory(const char* path):path_(path?path:""){
    require(!path_.empty(),"New network journal directory required");
    // A previous campaign must never be overwritten or resumed.
    if(mkdir(path_.c_str(),0700))throw std::runtime_error("Network journal directory must not exist");
    fd_=open(path_.c_str(),O_RDONLY|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC);
    try{
        verify_directory();
        const auto separator=path_.find_last_of('/');
        const auto parent=separator==std::string::npos?".":separator==0?"/":path_.substr(0,separator);
        File parent_fd{open(parent.c_str(),O_RDONLY|O_DIRECTORY|O_CLOEXEC)};
        if(parent_fd.fd<0||fsync(parent_fd.fd))throw std::runtime_error("Network journal parent sync failed");
    }catch(...){if(fd_>=0)close(fd_);fd_=-1;throw;}
}
JournalDirectory::~JournalDirectory(){if(fd_>=0)close(fd_);}
void JournalDirectory::verify_directory()const{
    struct stat anchored{},named{};
    if(!safe_dir(fd_,anchored)||lstat(path_.c_str(),&named)||!same(anchored,named))
        throw std::runtime_error("Network journal directory changed");
}
void JournalDirectory::save_discovery(const Discovery& d){
    verify_directory();
    require(!discovery_saved_,"Network discovery already persisted");
    const auto content=report(d);
    File f{openat(fd_,"discovery.json",O_RDWR|O_CREAT|O_EXCL|O_NOFOLLOW|O_CLOEXEC,0600)};
    if(f.fd<0)throw std::runtime_error("Network discovery journal destination unavailable");
    save(f.fd,content);
    if(fsync(fd_))throw std::runtime_error("Network discovery directory sync failed");
    verify_directory();discovery_saved_=true;
}
EcuReport JournalDirectory::scan(Pair pair,unsigned budget,const char* interface,const char* vin,const volatile sig_atomic_t* cancel){
    EcuReport row{};row.pair=pair;
    verify_directory();
    require(discovery_saved_,"Persist functional discovery before physical acquisition");
    require(attempted_.insert(pair.rx).second,"Network journal target already attempted");
    if(budget<1000){row.error="BUDGET_EXHAUSTED";return row;}
    socketcampaign::Plan plan{};
    plan.operation=socketcampaign::Operation::scan;plan.tx=pair.tx;plan.rx=pair.rx;
    plan.vin=vin?vin:"";plan.interface=interface?interface:"";plan.budget_ms=budget;
    require(plan.valid(),"Invalid journaled network physical scope");
    const auto name=journal_name(pair.rx);
    File file{openat(fd_,name.c_str(),O_RDWR|O_CREAT|O_EXCL|O_NOFOLLOW|O_CLOEXEC,0600)};
    if(file.fd<0)throw std::runtime_error("Network ECU journal destination unavailable");
    // A durable header is required even when opening the transport fails.
    socketcampaign::Writer writer(file.fd,plan);
    if(fsync(fd_))throw std::runtime_error("Network ECU journal directory sync failed");
    verify_directory();
    Link link;
    if(!j1979_isotp_open(&link.value,interface,pair.tx,pair.rx,cancel)){
        row.error="TRANSPORT_UNAVAILABLE";return row;
    }
    const socketcampaign::Link callbacks{j1979_isotp_exchange,monotonic,j1979_isotp_current,wait,&link.value};
    const auto observed=socketcampaign::record(plan,callbacks,writer);
    j1979_isotp_close(&link.value);
    verify_directory();
    socketcampaign::finish(writer,observed);
    verify_directory();
    row.json=observed.report;
    if(observed.completed)row.status="PASSED";
    else row.error=observed.stop.empty()?"CAMPAIGN_FAILED":observed.stop;
    return row;
}
}
