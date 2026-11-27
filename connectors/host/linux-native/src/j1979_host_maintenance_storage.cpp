#include "j1979_host_maintenance.hpp"
#include <cerrno>
#include <fcntl.h>
#include <sys/file.h>
#include <sys/stat.h>
#include <unistd.h>
#include <stdexcept>
#include <algorithm>
namespace cd::hostmaint {
namespace {
constexpr const char* lock_name=".maintenance.lock";
constexpr const char* recovery_name="RECOVERY_REQUIRED";
bool safe_name(std::string_view s){
    return !s.empty()&&s.size()<=96&&s!="."&&s!=".."&&s!=lock_name&&s!=recovery_name&&
        std::all_of(s.begin(),s.end(),[](char c){
            return (c>='a'&&c<='z')||(c>='A'&&c<='Z')||(c>='0'&&c<='9')||c=='-'||c=='_'||c=='.';
        });
}
bool write_all(int fd,const uint8_t* data,size_t n){
    while(n){
        const auto count=write(fd,data,n);
        if(count<0&&errno==EINTR)continue;
        if(count<=0)return false;
        data+=count;n-=static_cast<size_t>(count);
    }
    return true;
}
}
FileStorage::FileStorage(const char* directory,const char* name){
    if(!directory||!name||!safe_name(name))throw std::invalid_argument("Explicit private maintenance directory and simple journal filename required");
    journal_name_=name;
    dir_=open(directory,O_RDONLY|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC);
    if(dir_<0)throw std::runtime_error("MAINTENANCE_DIRECTORY_UNAVAILABLE");
    try{
        struct stat s{};
        if(fstat(dir_,&s)||s.st_uid!=geteuid()||(s.st_mode&077))
            throw std::runtime_error("MAINTENANCE_DIRECTORY_NOT_PRIVATE");
        if(flock(dir_,LOCK_EX|LOCK_NB))throw std::runtime_error("MAINTENANCE_DIRECTORY_BUSY");
        if(fstatat(dir_,recovery_name,&s,AT_SYMLINK_NOFOLLOW)==0||errno!=ENOENT)
            throw std::runtime_error("UNRESOLVED_MAINTENANCE_RECOVERY");
        // One outstanding journal per private directory. Never silently
        // reuse a directory with a previous intent or terminal record.
        fd_=openat(dir_,journal_name_.c_str(),O_RDWR|O_APPEND|O_CREAT|O_EXCL|O_NOFOLLOW|O_CLOEXEC,0600);
        if(fd_<0)throw std::runtime_error("JOURNAL_ALREADY_EXISTS_OR_UNAVAILABLE");
        if(fsync(fd_)||fsync(dir_))throw std::runtime_error("JOURNAL_RESERVATION_NOT_DURABLE");
        // A durable marker exists before vehicle access and is never
        // automatically removed. Operator recovery is explicitly external.
        const int marker=openat(dir_,recovery_name,O_WRONLY|O_CREAT|O_EXCL|O_NOFOLLOW|O_CLOEXEC,0600);
        if(marker<0)throw std::runtime_error("RECOVERY_MARKER_UNAVAILABLE");
        const std::string message="Maintenance reserved; inspect the journal before any further operation.\n"+journal_name_+"\n";
        const bool written=write_all(marker,reinterpret_cast<const uint8_t*>(message.data()),message.size());
        const bool synced=fsync(marker)==0;
        const bool closed=close(marker)==0;
        if(!written||!synced||!closed||fsync(dir_))throw std::runtime_error("RECOVERY_MARKER_NOT_DURABLE");
    }catch(...){
        if(fd_>=0)close(fd_);
        close(dir_);fd_=dir_=-1;throw;
    }
}
FileStorage::~FileStorage(){
    if(fd_>=0)close(fd_);
    if(dir_>=0)close(dir_);
}
bool FileStorage::same_file()const{
    struct stat opened{},named{};
    return fd_>=0&&dir_>=0&&!fstat(fd_,&opened)&&
        !fstatat(dir_,journal_name_.c_str(),&named,AT_SYMLINK_NOFOLLOW)&&S_ISREG(opened.st_mode)&&
        opened.st_dev==named.st_dev&&opened.st_ino==named.st_ino&&opened.st_nlink==1&&
        opened.st_uid==geteuid()&&!(opened.st_mode&077);
}
bool FileStorage::ready(){return !failed_&&same_file();}
bool FileStorage::size(size_t& n){
    struct stat s{};
    if(!ready()||fstat(fd_,&s)||s.st_size<0||static_cast<uint64_t>(s.st_size)>Journal::MaximumJournal)return false;
    n=static_cast<size_t>(s.st_size);return true;
}
bool FileStorage::append(const uint8_t* data,size_t n){
    size_t existing=0;
    if(!data||!n||!size(existing)||n>Journal::MaximumJournal-existing||!write_all(fd_,data,n)){
        latchRecovery();return false;
    }
    return true;
}
bool FileStorage::flush(){
    if(!ready()||fsync(fd_)||fsync(dir_)){latchRecovery();return false;}
    return true;
}
bool FileStorage::read(size_t offset,uint8_t* data,size_t n){
    size_t total=0;
    if(!data||!size(total)||offset>total||n>total-offset)return false;
    while(n){
        const auto count=pread(fd_,data,n,static_cast<off_t>(offset));
        if(count<0&&errno==EINTR)continue;
        if(count<=0)return false;
        offset+=static_cast<size_t>(count);data+=count;n-=static_cast<size_t>(count);
    }
    return same_file();
}
void FileStorage::latchRecovery(){failed_=true;latched_=true;}
}
