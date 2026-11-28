#include "j1979_network_journal.hpp"
#include "j1979_native_report_detail.hpp"
#include <algorithm>
#include <cerrno>
#include <dirent.h>
#include <fcntl.h>
#include <memory>
#include <set>
#include <stdexcept>
#include <sys/stat.h>
#include <unistd.h>

namespace cd::network::history {
namespace {
using detail::require;
struct Fd{
    int fd{-1};
    ~Fd(){if(fd>=0)close(fd);}
};
bool private_directory(const struct stat& s){return S_ISDIR(s.st_mode)&&s.st_uid==geteuid()&&!(s.st_mode&077);}
bool private_file(const struct stat& s){return S_ISREG(s.st_mode)&&s.st_uid==geteuid()&&s.st_nlink==1&&!(s.st_mode&077)&&s.st_size>0;}
bool same(const struct stat& a,const struct stat& b){
    return a.st_dev==b.st_dev&&a.st_ino==b.st_ino&&a.st_size==b.st_size&&a.st_mode==b.st_mode&&
        a.st_nlink==b.st_nlink&&a.st_uid==b.st_uid&&a.st_mtim.tv_sec==b.st_mtim.tv_sec&&a.st_mtim.tv_nsec==b.st_mtim.tv_nsec&&
        a.st_ctim.tv_sec==b.st_ctim.tv_sec&&a.st_ctim.tv_nsec==b.st_ctim.tv_nsec;
}
std::set<std::string> names(int fd){
    // openat creates an independent directory stream offset.
    const int copy=openat(fd,".",O_RDONLY|O_DIRECTORY|O_CLOEXEC|O_NOFOLLOW);
    if(copy<0)throw std::runtime_error("Network journal directory stream unavailable");
    DIR* raw=fdopendir(copy);
    if(!raw){close(copy);throw std::runtime_error("Network journal directory stream unavailable");}
    const auto close_directory=[](DIR* stream){if(stream)closedir(stream);};
    const std::unique_ptr<DIR,decltype(close_directory)> directory(raw,close_directory);
    std::set<std::string> found;
    for(;;){
        errno=0;const auto* entry=readdir(directory.get());
        if(!entry){if(errno)throw std::runtime_error("Network journal directory read failed");break;}
        std::string name(entry->d_name);
        if(name=="."||name=="..")continue;
        require(found.size()<max_targets+1&&found.insert(std::move(name)).second,"Network journal directory entry limit");
    }
    return found;
}
struct Snapshot{
    std::string name;
    struct stat identity{};
    socketcampaign::Data bytes;
};
Snapshot read(int fd,const std::string& name,std::size_t max,std::size_t remaining){
    Fd file{openat(fd,name.c_str(),O_RDONLY|O_CLOEXEC|O_NOFOLLOW|O_NONBLOCK)};
    Snapshot out{};out.name=name;
    require(file.fd>=0&&!fstat(file.fd,&out.identity)&&private_file(out.identity)&&
        static_cast<std::uint64_t>(out.identity.st_size)<=std::min(max,remaining),"Unsafe or oversized network journal file");
    out.bytes.resize(static_cast<std::size_t>(out.identity.st_size));
    std::size_t at=0;
    while(at<out.bytes.size()){
        const auto n=pread(file.fd,out.bytes.data()+at,out.bytes.size()-at,static_cast<off_t>(at));
        if(n<0&&errno==EINTR)continue;
        require(n>0,"Network journal read failed");at+=static_cast<std::size_t>(n);
    }
    struct stat last{},named{};
    require(!fstat(file.fd,&last)&&!fstatat(fd,name.c_str(),&named,AT_SYMLINK_NOFOLLOW)&&
        same(out.identity,last)&&same(out.identity,named),"Network journal file changed during read");
    return out;
}
}
NetworkJournalInput load_network_journals(const char* path,std::span<const std::uint32_t> selected){
    require(path&&*path&&!selected.empty()&&selected.size()<=max_targets,"Explicit network journal directory and selected targets required");
    Fd fd{open(path,O_RDONLY|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC)};
    struct stat initial{};
    require(fd.fd>=0&&!fstat(fd.fd,&initial)&&private_directory(initial),"Private network journal directory required");
    auto listed=names(fd.fd);
    require(listed.contains("discovery.json"),"Missing durable network discovery");
    std::map<std::string,std::uint32_t> expected;
    for(auto id:selected)require(expected.emplace(journal_name(id),id).second,"Duplicate network journal target");
    constexpr std::size_t total_limit=128u*1024u*1024u;
    std::size_t total=0;
    std::vector<Snapshot> snapshots;
    for(const auto& name:listed){
        const bool discovery=name=="discovery.json";
        require(discovery||expected.contains(name),"Unexpected network journal directory entry");
        auto snapshot=read(fd.fd,name,discovery?4u*1024u*1024u:socketcampaign::max_journal,total_limit-total);
        total+=snapshot.bytes.size();snapshots.push_back(std::move(snapshot));
    }
    // Read all bytes again before returning any proof inputs.
    for(const auto& snapshot:snapshots){
        const auto fresh=read(fd.fd,snapshot.name,snapshot.bytes.size(),snapshot.bytes.size());
        require(same(snapshot.identity,fresh.identity)&&snapshot.bytes==fresh.bytes,"Network journal changed during verification");
    }
    struct stat final{},named{};
    require(!fstat(fd.fd,&final)&&!lstat(path,&named)&&same(initial,final)&&same(initial,named)&&listed==names(fd.fd),
        "Network journal directory changed during verification");
    NetworkJournalInput result{};
    for(auto& snapshot:snapshots){
        if(snapshot.name=="discovery.json")result.discovery.assign(reinterpret_cast<const char*>(snapshot.bytes.data()),snapshot.bytes.size());
        else result.journals.emplace(expected.at(snapshot.name),std::move(snapshot.bytes));
    }
    return result;
}
}
