#include "j1979_live_export.hpp"
#include "j1979_native_live_inspection.hpp"
#include "j1979_socket_campaign.hpp"
#include <algorithm>
#include <cerrno>
#include <fcntl.h>
#include <stdexcept>
#include <sys/stat.h>
#include <unistd.h>

namespace cd::liveexport {
namespace {
constexpr std::size_t limit=64u*1024u*1024u;
void require(bool ok,const char* reason){if(!ok)throw std::runtime_error(reason);}
bool same(const struct stat& a,const struct stat& b){
    return a.st_dev==b.st_dev&&a.st_ino==b.st_ino;
}
struct stat private_file(int fd,const char* path){
    struct stat a{},b{};
    require(fd>=0&&path&&*path&&fstat(fd,&a)==0&&lstat(path,&b)==0&&same(a,b)&&
        S_ISREG(a.st_mode)&&S_ISREG(b.st_mode)&&a.st_uid==geteuid()&&a.st_nlink==1&&
        !(a.st_mode&0077)&&a.st_size>=0&&static_cast<std::uint64_t>(a.st_size)<=limit,
        "Live evidence path is not the reserved private regular file");
    return a;
}
void read_exact(int fd,std::string_view expected){
    char block[16384];
    std::size_t offset=0;
    while(offset<expected.size()){
        const auto size=std::min(sizeof(block),expected.size()-offset);
        const auto n=pread(fd,block,size,static_cast<off_t>(offset));
        if(n<0&&errno==EINTR)continue;
        require(n>0&&std::string_view(block,static_cast<std::size_t>(n))==
            expected.substr(offset,static_cast<std::size_t>(n)),"Live evidence differs from the recorded bytes");
        offset+=static_cast<std::size_t>(n);
    }
}
void check_source(int fd,const char* path,std::string_view expected){
    require(!expected.empty()&&expected.size()<=limit,"Invalid bounded source report");
    const auto before=private_file(fd,path);
    require(static_cast<std::uint64_t>(before.st_size)==expected.size(),"Live source report size changed");
    read_exact(fd,expected);
    const auto after=private_file(fd,path);
    require(same(before,after)&&before.st_size==after.st_size&&
        before.st_mtim.tv_sec==after.st_mtim.tv_sec&&before.st_mtim.tv_nsec==after.st_mtim.tv_nsec&&
        before.st_ctim.tv_sec==after.st_ctim.tv_sec&&before.st_ctim.tv_nsec==after.st_ctim.tv_nsec,
        "Live source report changed during readback");
}
}
Destination::Destination(const char* path){
    if(!path)return;
    path_=path;
    require(!path_.empty(),"Empty live export destination");
    fd_=open(path_.c_str(),O_RDWR|O_CREAT|O_EXCL|O_NOFOLLOW|O_CLOEXEC,0600);
    require(fd_>=0,"Live export destination already exists or cannot be reserved");
    try{check_empty();}catch(...){close(fd_);fd_=-1;throw;}
}
Destination::~Destination(){if(fd_>=0)close(fd_);}
void Destination::verify()const{
    if(requested())(void)private_file(fd_,path_.c_str());
}
void Destination::check_empty()const{
    if(!requested())return;
    const auto st=private_file(fd_,path_.c_str());
    require(st.st_size==0,"Reserved live export destination is no longer empty");
}
void Destination::persist(std::string_view bytes){
    if(!requested())return;
    require(!bytes.empty()&&bytes.size()<=limit,"Invalid bounded live export");
    check_empty();
    std::size_t offset=0;
    while(offset<bytes.size()){
        const auto n=pwrite(fd_,bytes.data()+offset,bytes.size()-offset,static_cast<off_t>(offset));
        if(n<0&&errno==EINTR)continue;
        require(n>0,"Live export write failed");
        offset+=static_cast<std::size_t>(n);
    }
    require(fsync(fd_)==0,"Live export synchronization failed");
    verify();
    check_source(fd_,path_.c_str(),bytes);
}
Result publish(int source_fd,const char* source_path,std::string_view expected_report,
    cd::network::Pair pair,std::string_view vin,Destination& projection,Destination& csv){
    require(projection.requested()||csv.requested(),"No live export requested");
    projection.check_empty();csv.check_empty();
    require(fsync(source_fd)==0,"Raw live report synchronization failed");
    check_source(source_fd,source_path,expected_report);
    const auto digest=cd::socketcampaign::sha256(expected_report);
    const auto raw=cd::json::parse_report(expected_report,limit);
    const auto rebuilt=cd::network::history::bind_native_live_projection(
        cd::network::history::project_native_live(raw,pair,vin),digest);
    // Generate all requested representations before persisting either.
    const auto json=projection.requested()?cd::json::serialize(rebuilt.report)+"\n":std::string{};
    const auto table=csv.requested()?cd::network::history::native_live_csv(rebuilt):std::string{};
    check_source(source_fd,source_path,expected_report);
    projection.check_empty();csv.check_empty();
    projection.persist(json);csv.persist(table);
    check_source(source_fd,source_path,expected_report);
    return {rebuilt.complete,digest,rebuilt.report.at("samples").array().size()};
}
}
