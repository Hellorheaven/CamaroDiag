#include "j1979_elm_can_trace.hpp"
#include <algorithm>
#include <array>
#include <stdexcept>
#include <utility>
namespace cd::elmcan {
namespace {
constexpr size_t head=40,record=28;
constexpr std::array<uint8_t,4> magic{'E','C','R',1};
void require(bool b,const char* message){if(!b)throw std::invalid_argument(message);}
uint32_t get(Bytes b,size_t at){
    return uint32_t(b[at])|(uint32_t(b[at+1])<<8)|(uint32_t(b[at+2])<<16)|(uint32_t(b[at+3])<<24);
}
void put(Data& b,size_t at,uint32_t n){for(unsigned i=0;i<4;++i)b.at(at+i)=uint8_t(n>>(8*i));}
uint32_t crc(Bytes b){
    uint32_t v=0xffffffffu;
    for(auto byte:b){v^=byte;for(unsigned i=0;i<8;++i)v=(v>>1)^((v&1u)?0xedb88320u:0);}
    return v^0xffffffffu;
}
template<class Container>void append(Data& out,const Container& bytes){out.insert(out.end(),bytes.begin(),bytes.end());}
}
Data encode_trace(const Trace& t){
    (void)replay_trace(t);
    Data bytes(head,0);
    std::copy(magic.begin(),magic.end(),bytes.begin());
    bytes[4]=static_cast<uint8_t>(t.operation);bytes[5]=static_cast<uint8_t>(t.target.profile);
    put(bytes,8,t.target.tx);put(bytes,12,t.target.rx);put(bytes,16,static_cast<uint32_t>(t.rows.size()));
    std::copy(t.vin.begin(),t.vin.end(),bytes.begin()+20);
    // Header fields occupy 37 bytes, three reserved bytes, and CRC32.
    bytes.resize(head+4);put(bytes,head,crc(Bytes(bytes).first(head)));
    for(size_t seq=0;seq<t.rows.size();++seq){
        const auto& row=t.rows[seq];
        const auto at=bytes.size();bytes.resize(at+record,0);
        put(bytes,at,static_cast<uint32_t>(seq));
        bytes[at+4]=static_cast<uint8_t>(row.status+1);
        bytes[at+5]=static_cast<uint8_t>(row.command.size());
        bytes[at+6]=static_cast<uint8_t>(row.request.size());
        put(bytes,at+8,row.duration_ms);put(bytes,at+12,row.timeout_ms);
        put(bytes,at+16,static_cast<uint32_t>(row.raw.size()));
        put(bytes,at+20,static_cast<uint32_t>(row.response.size()));
        put(bytes,at+24,static_cast<uint32_t>(row.error.size()));
        append(bytes,row.command);append(bytes,row.request);append(bytes,row.raw);append(bytes,row.response);append(bytes,row.error);
        const auto sum=crc(Bytes(bytes).subspan(at));const auto end=bytes.size();bytes.resize(end+4);put(bytes,end,sum);
        require(bytes.size()<=trace_max_bytes,"ELM trace byte limit");
    }
    return bytes;
}
Trace decode_trace(Bytes b){
    require(b.size()>=head+4&&b.size()<=trace_max_bytes&&std::equal(magic.begin(),magic.end(),b.begin())&&
        !b[6]&&!b[7]&&!b[37]&&!b[38]&&!b[39]&&get(b,head)==crc(b.first(head)),"Invalid ECR/1 header");
    Trace t{};
    t.operation=static_cast<TraceOperation>(b[4]);t.target={b[5],get(b,8),get(b,12)};
    const auto count=get(b,16);require(count<=trace_max_rows,"ECR/1 record count limit");
    t.vin.assign(b.begin()+20,b.begin()+37);
    size_t pos=head+4,raw_total=0;
    for(uint32_t seq=0;seq<count;++seq){
        require(b.size()-pos>=record+4,"Truncated ECR/1 record");
        const auto h=b.subspan(pos,record);
        require(get(h,0)==seq&&h[4]<=2&&h[5]>=2&&h[5]<=32&&h[6]<=7&&!h[7],"Invalid ECR/1 flags");
        const auto raw=get(h,16),reply=get(h,20),error=get(h,24);
        require(raw<=32768&&raw<=trace_max_raw-raw_total&&reply<=4095&&error<=512,"ECR/1 row bounds");
        const size_t full=record+h[5]+h[6]+raw+reply+error+4;
        require(full<=b.size()-pos&&get(b,pos+full-4)==crc(b.subspan(pos,full-4)),"ECR/1 size or CRC mismatch");
        Observation row{};
        row.status=int(h[4])-1;row.duration_ms=get(h,8);row.timeout_ms=get(h,12);
        auto cursor=pos+record;
        auto field=[&](size_t n){const auto part=b.subspan(cursor,n);cursor+=n;return part;};
        const auto command=field(h[5]),request=field(h[6]),raw_bytes=field(raw),response=field(reply),err=field(error);
        row.command.assign(command.begin(),command.end());row.request.assign(request.begin(),request.end());
        row.raw.assign(raw_bytes.begin(),raw_bytes.end());row.response.assign(response.begin(),response.end());
        row.error.assign(err.begin(),err.end());
        t.rows.push_back(std::move(row));pos+=full;raw_total+=raw;
    }
    require(pos==b.size(),"Trailing ECR/1 bytes");
    (void)replay_trace(t);
    return t;
}
}
