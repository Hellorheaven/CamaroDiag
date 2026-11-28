#include "j1979_socket_campaign.hpp"
#include "j1979_native_report_detail.hpp"
#include <algorithm>
#include <array>
#include <cerrno>
#include <stdexcept>
#include <sys/stat.h>
#include <unistd.h>

namespace cd::socketcampaign {
namespace {
bool private_regular(int fd,std::size_t expected){
    struct stat s{};
    return fd>=0&&!fstat(fd,&s)&&S_ISREG(s.st_mode)&&s.st_uid==geteuid()&&s.st_nlink==1&&
        !(s.st_mode&077)&&s.st_size>=0&&static_cast<std::uint64_t>(s.st_size)==expected;
}
}
Writer::Writer(int fd,const Plan& p):fd_(fd){
    if(!private_regular(fd_,0)||lseek(fd_,0,SEEK_CUR)!=0)
        throw std::invalid_argument("SCJ requires a newly created private regular file");
    persist(encode_header(p));
}
void Writer::persist(Bytes b){
    if(failed_||finished_||b.size()>max_journal-bytes_||
       !private_regular(fd_,bytes_)||lseek(fd_,0,SEEK_CUR)!=static_cast<off_t>(bytes_)){
        failed_=true;throw std::runtime_error("SCJ destination changed or exhausted");
    }
    std::size_t offset=0;
    while(offset<b.size()){
        const auto n=write(fd_,b.data()+offset,b.size()-offset);
        if(n<0&&errno==EINTR)continue;
        if(n<=0){failed_=true;throw std::runtime_error("SCJ write failed");}
        offset+=static_cast<std::size_t>(n);
    }
    if(fsync(fd_)){failed_=true;throw std::runtime_error("SCJ fsync failed");}
    std::array<std::uint8_t,4096> check{};
    offset=0;
    while(offset<b.size()){
        const auto n=pread(fd_,check.data(),std::min(check.size(),b.size()-offset),
            static_cast<off_t>(bytes_+offset));
        if(n<0&&errno==EINTR)continue;
        if(n<=0||!std::equal(check.begin(),check.begin()+n,b.begin()+offset)){
            failed_=true;throw std::runtime_error("SCJ readback differs from persisted evidence");
        }
        offset+=static_cast<std::size_t>(n);
    }
    if(!private_regular(fd_,bytes_+b.size())){
        failed_=true;throw std::runtime_error("SCJ destination changed during persistence");
    }
    bytes_+=b.size();
}
void Writer::append(const Event& event){
    append_batch(std::span<const Event>(&event,1));
}
void Writer::append_batch(std::span<const Event> events){
    if(events.empty())return;
    if(failed_||finished_||events.size()>max_events-sequence_){
        failed_=true;throw std::runtime_error("SCJ event limit or terminal reached");
    }
    Data wire;
    for(std::size_t i=0;i<events.size();++i){
        if(events[i].kind==Kind::terminal&&i+1!=events.size()){
            failed_=true;throw std::runtime_error("SCJ terminal must be last");
        }
        const auto frame=encode_event(events[i],sequence_+i);
        if(frame.size()>max_journal-bytes_-wire.size()){
            failed_=true;throw std::runtime_error("SCJ batch exceeds journal bound");
        }
        wire.insert(wire.end(),frame.begin(),frame.end());
    }
    // One fsync/readback per group: all callback observations and intent
    // are durable before the vehicle request, without a sync per clock tick.
    persist(wire);
    sequence_+=events.size();finished_=events.back().kind==Kind::terminal;
}
}
