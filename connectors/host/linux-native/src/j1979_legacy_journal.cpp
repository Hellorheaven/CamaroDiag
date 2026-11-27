#include "j1979_legacy_journal.hpp"
#include <algorithm>
#include <cerrno>
#include <fcntl.h>
#include <stdexcept>
#include <sys/stat.h>
#include <unistd.h>
namespace cd::legacy {
namespace {
struct Layout {size_t header,row,command,raw,count,vin;unsigned maximum;size_t bytes;};
Layout layout(JournalKind k){
    switch(k){
    case JournalKind::scan:return {40,20,6,16,12,16,scan_max_exchanges,scan_max_capture};
    case JournalKind::freeze:return {112,16,6,12,12,16,freeze_max_trace,freeze_max_capture};
    case JournalKind::live:return {80,24,7,20,20,24,live_max_trace,live_max_capture};
    }
    throw std::invalid_argument("Unknown legacy journal kind");
}
void require(bool ok,const char* why){if(!ok)throw std::invalid_argument(why);}
uint32_t get(Bytes b,size_t at){
    return uint32_t(b[at])|(uint32_t(b[at+1])<<8)|(uint32_t(b[at+2])<<16)|(uint32_t(b[at+3])<<24);
}
void put(Data& b,size_t at,uint32_t value){for(unsigned i=0;i<4;++i)b.at(at+i)=uint8_t(value>>(8*i));}
uint32_t crc(Bytes b){
    uint32_t v=0xffffffffu;
    for(auto x:b){v^=x;for(unsigned i=0;i<8;++i)v=(v>>1)^((v&1)?0xedb88320u:0);}
    return v^0xffffffffu;
}
void fix_header(Data& b,const Layout& l,unsigned count){
    put(b,l.count,count);put(b,l.header-4,crc(Bytes(b).first(l.header-4)));
}
Data prototype(ScanCapture scope){
    require(scope.trace.empty(),"Journal scope must not contain observations");
    scope.trace.push_back({"0100","",ScanOutcome::cancelled,0,0,false});
    return encode_scan_capture(scope);
}
Data prototype(FreezeCapture scope){
    require(scope.trace.empty(),"Journal scope must not contain observations");
    scope.trace.push_back({"0100","",FreezeOutcome::cancelled,0,false});
    return encode_freeze_capture(scope);
}
Data prototype(LiveCapture scope){
    require(scope.trace.empty(),"Journal scope must not contain observations");
    scope.trace.push_back({{"0100","",FreezeOutcome::cancelled,0,false},LivePhase::initial,0,0,0});
    return encode_live_capture(scope);
}
void validate_capture(JournalKind kind,Bytes bytes,Protocol p,unsigned source,std::string_view vin,bool replay){
    switch(kind){
    case JournalKind::scan:
        if(replay)(void)inspect_scan_capture(bytes,p,source,vin);
        else (void)decode_scan_capture(bytes);
        break;
    case JournalKind::freeze:
        if(replay)(void)inspect_freeze_capture(bytes,p,source,vin);
        else (void)decode_freeze_capture(bytes);
        break;
    case JournalKind::live:
        if(replay)(void)inspect_live_capture(bytes,p,source,vin);
        else (void)decode_live_capture(bytes);
        break;
    }
}
constexpr std::array<uint8_t,4> terminal{'L','J','E',1};
}
CaptureJournal::CaptureJournal(const char* path,const ScanCapture& scope):kind_(JournalKind::scan),scan_(scope){begin(path,prototype(scope));}
CaptureJournal::CaptureJournal(const char* path,const FreezeCapture& scope):kind_(JournalKind::freeze),freeze_(scope){begin(path,prototype(scope));}
CaptureJournal::CaptureJournal(const char* path,const LiveCapture& scope):kind_(JournalKind::live),live_(scope){begin(path,prototype(scope));}
CaptureJournal::~CaptureJournal(){if(fd_>=0)close(fd_);}
void CaptureJournal::begin(const char* path,Data sample){
    const auto l=layout(kind_);
    header_.assign(sample.begin(),sample.begin()+l.header);
    fd_=open(path,O_WRONLY|O_CREAT|O_EXCL|O_NOFOLLOW|O_CLOEXEC,0600);
    if(fd_<0)throw std::runtime_error("Legacy journal destination unavailable");
    try{
        Data opening{'L','J',1,static_cast<uint8_t>(kind_)};
        opening.insert(opening.end(),header_.begin(),header_.end());
        persist(opening);
        const std::string p(path);const auto slash=p.find_last_of('/');
        const auto parent=slash==std::string::npos?std::string("."):slash==0?std::string("/"):p.substr(0,slash);
        const int dir=open(parent.c_str(),O_RDONLY|O_DIRECTORY|O_CLOEXEC);
        if(dir<0)throw std::runtime_error("Legacy journal parent unavailable");
        const bool synced=fsync(dir)==0;const bool closed=close(dir)==0;
        if(!synced||!closed)throw std::runtime_error("Legacy journal parent sync failed");
    }catch(...){close(fd_);fd_=-1;throw;}
}
void CaptureJournal::persist(Bytes b){
    if(failed_||finished_||b.size()>journal_max_bytes-bytes_)throw std::runtime_error("Legacy journal unavailable or full");
    while(!b.empty()){
        const auto n=write(fd_,b.data(),b.size());
        if(n<0&&errno==EINTR)continue;
        if(n<=0){failed_=true;throw std::runtime_error("Legacy journal write failed");}
        bytes_+=static_cast<size_t>(n);b=b.subspan(static_cast<size_t>(n));
    }
    if(fsync(fd_)){failed_=true;throw std::runtime_error("Legacy journal sync failed");}
}
void CaptureJournal::append_single(Data single){
    const auto l=layout(kind_);
    if(count_>=l.maximum||single.size()<l.header+l.row+4||
       !std::equal(header_.begin(),header_.end(),single.begin())||
       bytes_+single.size()-l.header>l.bytes+4)
        throw std::runtime_error("Legacy journal scope or bounds changed");
    Data row(single.begin()+l.header,single.end());
    put(row,0,count_);put(row,row.size()-4,crc(Bytes(row).first(row.size()-4)));
    persist(row);++count_;
}
void CaptureJournal::append(const ScanExchange& x){
    if(kind_!=JournalKind::scan)throw std::logic_error("Wrong journal exchange kind");
    try{auto v=scan_;v.trace.push_back(x);append_single(encode_scan_capture(v));}
    catch(const std::exception&){failed_=true;throw std::runtime_error("Legacy journal rejected observation");}
}
void CaptureJournal::append(const FreezeExchange& x){
    if(kind_!=JournalKind::freeze)throw std::logic_error("Wrong journal exchange kind");
    try{auto v=freeze_;v.trace.push_back(x);append_single(encode_freeze_capture(v));}
    catch(const std::exception&){failed_=true;throw std::runtime_error("Legacy journal rejected observation");}
}
void CaptureJournal::append(const LiveExchange& x){
    if(kind_!=JournalKind::live)throw std::logic_error("Wrong journal exchange kind");
    try{auto v=live_;v.trace.push_back(x);append_single(encode_live_capture(v));}
    catch(const std::exception&){failed_=true;throw std::runtime_error("Legacy journal rejected observation");}
}
void CaptureJournal::finish(){
    if(!count_||finished_||failed_)throw std::runtime_error("No completed journal recording");
    Data end(terminal.begin(),terminal.end());end.resize(12);
    put(end,4,count_);put(end,8,crc(Bytes(end).first(8)));persist(end);
    finished_=true;
    const int rc=close(fd_);fd_=-1;
    if(rc)throw std::runtime_error("Legacy journal close failed");
}
JournalEvidence inspect_journal(Bytes raw,Protocol p,unsigned source,std::string_view vin){
    require(raw.size()>=4&&raw.size()<=journal_max_bytes&&raw[0]=='L'&&raw[1]=='J'&&raw[2]==1,
        "Invalid LJ/1 journal");
    JournalEvidence out{};out.kind=static_cast<JournalKind>(raw[3]);const auto l=layout(out.kind);
    require(raw.size()>=4+l.header&&vin.size()==17&&valid_protocol(p)&&valid_source(source),"Truncated journal scope");
    auto header=raw.subspan(4,l.header);
    require(header[4]==static_cast<uint8_t>(p)&&header[5]==source&&
        std::equal(vin.begin(),vin.end(),header.begin()+l.vin)&&get(header,l.count)==1&&
        crc(header.first(l.header-4))==get(header,l.header-4),"Journal independent scope mismatch");
    // Validate even a plan-only prefix using a synthetic cancellation solely
    // in memory; it is never counted as recorded evidence.
    Data sample(header.begin(),header.end());
    Data row(l.row,0);row[4]=out.kind==JournalKind::scan?3:out.kind==JournalKind::freeze?5:1;
    if(out.kind==JournalKind::live){row[5]=5;row[7]=4;}else row[6]=4;
    row.insert(row.end(),{'0','1','0','0'});row.resize(row.size()+4);
    put(row,row.size()-4,crc(Bytes(row).first(row.size()-4)));
    sample.insert(sample.end(),row.begin(),row.end());validate_capture(out.kind,sample,p,source,vin,false);
    out.capture.assign(header.begin(),header.end());
    size_t pos=4+l.header;
    while(pos<raw.size()){
        const auto remaining=raw.subspan(pos);
        const auto prefix=std::min<size_t>(remaining.size(),terminal.size());
        if(std::equal(remaining.begin(),remaining.begin()+prefix,terminal.begin())){
            if(remaining.size()<12)break;
            require(remaining.size()==12&&get(remaining,4)==out.observations&&out.observations&&
                get(remaining,8)==crc(remaining.first(8)),"Invalid journal terminal");
            out.terminal=true;pos+=12;break;
        }
        if(remaining.size()<l.row)break;
        require(out.observations<l.maximum&&get(remaining,0)==out.observations,"Invalid journal sequence");
        const size_t command=remaining[l.command],payload=get(remaining,l.raw);
        require(command>=2&&command<=(out.kind==JournalKind::scan?14:6)&&payload<=32768,"Invalid journal record size");
        const size_t size=l.row+command+payload+4;
        if(size>remaining.size())break;
        require(get(remaining,size-4)==crc(remaining.first(size-4))&&out.capture.size()+size<=l.bytes,"Journal record CRC or bound");
        out.capture.insert(out.capture.end(),remaining.begin(),remaining.begin()+size);
        ++out.observations;pos+=size;
    }
    out.verified_bytes=pos;out.torn_bytes=raw.size()-pos;
    if(out.observations){
        fix_header(out.capture,l,out.observations);
        validate_capture(out.kind,out.capture,p,source,vin,out.terminal);
    }else out.capture.clear();
    return out;
}
}
