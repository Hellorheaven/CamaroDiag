#include "j1979_elm_can_journal.hpp"
#include <algorithm>
#include <array>
#include <cerrno>
#include <stdexcept>
#include <sys/stat.h>
#include <unistd.h>
namespace cd::elmcan {
namespace {
constexpr size_t header=44,record=8,trailer=16;
constexpr std::array<uint8_t,4> magic{'E','J','R',1};
void require(bool ok,const char* message){if(!ok)throw std::invalid_argument(message);}
uint32_t get(Bytes b,size_t at){
    return uint32_t(b[at])|(uint32_t(b[at+1])<<8)|(uint32_t(b[at+2])<<16)|(uint32_t(b[at+3])<<24);
}
void put(Data& b,size_t at,uint32_t n){for(unsigned i=0;i<4;++i)b.at(at+i)=uint8_t(n>>(8*i));}
uint32_t update(uint32_t v,Bytes b){
    for(auto byte:b){v^=byte;for(unsigned i=0;i<8;++i)v=(v>>1)^((v&1u)?0xedb88320u:0);}
    return v;
}
uint32_t crc(Bytes b){return update(0xffffffffu,b)^0xffffffffu;}
bool private_regular(int fd){
    struct stat s{};
    return fd>=0&&!fstat(fd,&s)&&S_ISREG(s.st_mode)&&s.st_uid==geteuid()&&
        !(s.st_mode&077)&&s.st_nlink==1;
}
Data header_bytes(const Trace& t){
    auto out=encode_trace(t);
    require(out.size()==header,"Invalid EJR scope");
    std::copy(magic.begin(),magic.end(),out.begin());
    put(out,40,crc(Bytes(out).first(40)));
    return out;
}
Trace read_header(Bytes b){
    require(b.size()>=header&&std::equal(magic.begin(),magic.end(),b.begin())&&get(b,16)==0&&
        get(b,40)==crc(b.first(40)),"Invalid EJR/1 header");
    Data ecr(b.begin(),b.begin()+header);
    ecr[1]='C';put(ecr,40,crc(Bytes(ecr).first(40)));
    return decode_trace(ecr);
}
}
EvidenceJournal::EvidenceJournal(int fd,Scope scope,std::string_view vin,TraceOperation op)
    :fd_(fd),scope_{scope,std::string(vin),op,{}}{
    struct stat s{};
    if(!private_regular(fd_)||fstat(fd_,&s)||s.st_size!=0||lseek(fd_,0,SEEK_CUR)!=0)
        throw std::invalid_argument("New private EJR/1 output required");
    persist(header_bytes(scope_));
}
void EvidenceJournal::persist(Bytes b){
    if(failed_||finished_||b.size()>journal_max_bytes-bytes_||!private_regular(fd_)){
        failed_=true;throw legacy::TransportError("EJR/1 persistence unavailable");
    }
    struct stat s{};
    if(fstat(fd_,&s)||s.st_size<0||static_cast<size_t>(s.st_size)!=bytes_||
       lseek(fd_,0,SEEK_CUR)!=static_cast<off_t>(bytes_)){
        failed_=true;throw legacy::TransportError("EJR/1 destination changed");
    }
    size_t offset=0;
    while(offset<b.size()){
        const auto n=write(fd_,b.data()+offset,b.size()-offset);
        if(n<0&&errno==EINTR)continue;
        if(n<=0){failed_=true;throw legacy::TransportError("EJR/1 write failed");}
        offset+=static_cast<size_t>(n);
    }
    if(fsync(fd_)){failed_=true;throw legacy::TransportError("EJR/1 sync failed");}
    // Independently read back the new record from the owned descriptor.
    std::array<uint8_t,4096> check{};
    offset=0;
    while(offset<b.size()){
        const auto want=std::min(check.size(),b.size()-offset);
        const auto n=pread(fd_,check.data(),want,static_cast<off_t>(bytes_+offset));
        if(n<0&&errno==EINTR)continue;
        if(n<=0||!std::equal(check.begin(),check.begin()+n,b.begin()+offset)){
            failed_=true;throw legacy::TransportError("EJR/1 readback mismatch");
        }
        offset+=static_cast<size_t>(n);
    }
    bytes_+=b.size();rolling_=update(rolling_,b);
}
void EvidenceJournal::append(const Observation& row){
    if(sequence_>=trace_max_rows||row.raw.size()>trace_max_raw-raw_){
        failed_=true;throw legacy::TransportError("EJR/1 observation limit");
    }
    auto body=encode_trace_row(row,sequence_);
    Data wire(record,0);wire[0]='R';wire[1]='O';wire[2]='W';wire[3]=1;
    put(wire,4,static_cast<uint32_t>(body.size()));
    wire.insert(wire.end(),body.begin(),body.end());
    persist(wire);++sequence_;raw_+=row.raw.size();
}
void EvidenceJournal::finish(){
    Data wire(trailer,0);wire[0]='E';wire[1]='N';wire[2]='D';wire[3]=1;
    put(wire,4,sequence_);put(wire,8,rolling_^0xffffffffu);put(wire,12,crc(Bytes(wire).first(12)));
    persist(wire);finished_=true;
}
JournalInspection inspect_journal(Bytes b,bool allow_prefix){
    require(b.size()<=journal_max_bytes,"EJR/1 byte limit");
    JournalInspection result{};result.trace=read_header(b);result.total_bytes=b.size();
    size_t pos=header,raw=0;
    uint32_t rolling=update(0xffffffffu,b.first(header));
    constexpr std::array<uint8_t,4> row_magic{'R','O','W',1},end_magic{'E','N','D',1};
    while(pos<b.size()){
        const auto remaining=b.subspan(pos);
        const auto prefix=remaining.first(std::min<size_t>(4,remaining.size()));
        const bool row=std::equal(prefix.begin(),prefix.end(),row_magic.begin());
        const bool end=std::equal(prefix.begin(),prefix.end(),end_magic.begin());
        require(row||end,"Invalid EJR/1 record marker");
        if(remaining.size()<4){result.torn=true;break;}
        if(end){
            if(remaining.size()<trailer){result.torn=true;break;}
            require(remaining.size()==trailer&&get(remaining,4)==result.trace.rows.size()&&
                get(remaining,8)==(rolling^0xffffffffu)&&get(remaining,12)==crc(remaining.first(12)),
                "Invalid EJR/1 terminal");
            pos+=trailer;result.terminal=true;break;
        }
        require(result.trace.rows.size()<trace_max_rows,"EJR/1 record limit");
        if(remaining.size()<record){result.torn=true;break;}
        const size_t n=get(remaining,4);
        require(n>=34&&n<=28+32+7+32768+4095+512+4,"Invalid EJR/1 record length");
        if(remaining.size()-record<n){result.torn=true;break;}
        size_t consumed=0;
        auto x=decode_trace_row(remaining.subspan(record,n),static_cast<uint32_t>(result.trace.rows.size()),consumed);
        require(consumed==n&&x.raw.size()<=trace_max_raw-raw,"Invalid EJR/1 aggregate length");
        raw+=x.raw.size();
        result.trace.rows.push_back(std::move(x));
        rolling=update(rolling,remaining.first(record+n));pos+=record+n;
    }
    require(allow_prefix||result.terminal,"EJR/1 terminal missing");
    result.verified_bytes=pos;result.replay=replay_trace(result.trace);return result;
}
Data recover_trace(Bytes b){
    const auto result=inspect_journal(b,false);
    return encode_trace(result.trace);
}
}
