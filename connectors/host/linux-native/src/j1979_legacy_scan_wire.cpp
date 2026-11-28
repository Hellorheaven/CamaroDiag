#include "j1979_legacy_scan_wire.hpp"
#include "j1979_legacy_identity.hpp"
#include <algorithm>
#include <array>
#include <stdexcept>
namespace cd::legacy {
namespace {
constexpr std::size_t header=40,record=20;
constexpr std::array<std::uint8_t,4> magic{'L','S','R',1};
void require(bool b,const char* reason){if(!b)throw std::invalid_argument(reason);}
void put(Data& b,std::size_t at,std::uint32_t v){
    for(unsigned n=0;n<4;++n)b.at(at+n)=static_cast<std::uint8_t>(v>>(n*8));
}
std::uint32_t get(Bytes b,std::size_t at){
    return std::uint32_t(b[at])|(std::uint32_t(b[at+1])<<8)|
        (std::uint32_t(b[at+2])<<16)|(std::uint32_t(b[at+3])<<24);
}
std::uint32_t crc(Bytes b){
    std::uint32_t v=0xffffffffu;
    for(auto x:b){v^=x;for(unsigned n=0;n<8;++n)v=(v>>1)^((v&1)?0xedb88320u:0);}
    return v^0xffffffffu;
}
bool command_valid(std::string_view s){
    if(s=="ATDPN")return true;
    try{const auto q=unhex(s);return read_allowed(q)&&hex(q)==s;}
    catch(const std::invalid_argument&){return false;}
}
void validate(const ScanCapture& v){
    require(valid_protocol(v.protocol)&&valid_source(v.source)&&valid_vin(v.vin)&&
        v.budget_ms>=1&&v.budget_ms<=600000&&!v.trace.empty()&&v.trace.size()<=scan_max_exchanges,
        "Invalid LSR/1 capture scope");
}
void row_valid(const ScanExchange& row,unsigned prior,unsigned budget){
    const auto state=static_cast<unsigned>(row.outcome);
    require(command_valid(row.command)&&row.command.size()<=14&&state>=1&&state<=4&&
        row.started_ms>=prior&&row.ended_ms>=row.started_ms&&row.ended_ms<=3600000&&row.raw.size()<=32768,
        "Invalid LSR/1 exchange");
    require(std::all_of(row.raw.begin(),row.raw.end(),[](unsigned char c){
        return (c>=32&&c<=126)||c=='\r'||c=='\n'||c=='\t';
    }),"Invalid LSR/1 ASCII");
    if(row.outcome==ScanOutcome::received)
        require(row.attempted&&row.ended_ms<budget&&row.ended_ms-row.started_ms<=(row.command=="ATDPN"?2000u:4000u),
            "LSR/1 received response outside deadline");
    else require(row.raw.empty()&&(row.outcome!=ScanOutcome::transport||row.attempted),"Invalid LSR/1 failure");
}
}
Data encode_scan_capture(const ScanCapture& v){
    validate(v);
    Data b(header,0);
    std::copy(magic.begin(),magic.end(),b.begin());
    b[4]=static_cast<std::uint8_t>(v.protocol);b[5]=static_cast<std::uint8_t>(v.source);
    put(b,8,v.budget_ms);put(b,12,static_cast<std::uint32_t>(v.trace.size()));
    std::copy(v.vin.begin(),v.vin.end(),b.begin()+16);
    put(b,36,crc(Bytes(b).first(36)));
    unsigned prior=0;std::size_t total=0;
    for(std::size_t n=0;n<v.trace.size();++n){
        const auto& row=v.trace[n];row_valid(row,prior,v.budget_ms);prior=row.ended_ms;
        require(row.raw.size()<=scan_max_raw-total,"LSR/1 raw limit");total+=row.raw.size();
        auto at=b.size();b.resize(at+record,0);
        put(b,at,static_cast<std::uint32_t>(n));b[at+4]=static_cast<std::uint8_t>(row.outcome);
        b[at+5]=row.attempted?1:0;b[at+6]=static_cast<std::uint8_t>(row.command.size());
        put(b,at+8,row.started_ms);put(b,at+12,row.ended_ms);put(b,at+16,static_cast<std::uint32_t>(row.raw.size()));
        b.insert(b.end(),row.command.begin(),row.command.end());b.insert(b.end(),row.raw.begin(),row.raw.end());
        auto check=crc(Bytes(b).subspan(at));auto end=b.size();b.resize(end+4);put(b,end,check);
    }
    require(b.size()<=scan_max_capture,"LSR/1 capture limit");return b;
}
ScanCapture decode_scan_capture(Bytes b){
    require(b.size()>=header&&b.size()<=scan_max_capture&&std::equal(magic.begin(),magic.end(),b.begin())&&
        !b[6]&&!b[7]&&!b[33]&&!b[34]&&!b[35]&&crc(b.first(36))==get(b,36),"Invalid LSR/1 header");
    ScanCapture v{};v.protocol=static_cast<Protocol>(b[4]);v.source=b[5];v.budget_ms=get(b,8);
    v.vin.assign(b.begin()+16,b.begin()+33);
    const auto count=get(b,12);
    require(valid_protocol(v.protocol)&&valid_source(v.source)&&valid_vin(v.vin)&&v.budget_ms>=1&&
        v.budget_ms<=600000&&count>=1&&count<=scan_max_exchanges,"Invalid LSR/1 scope");
    unsigned prior=0;std::size_t pos=header,total=0;
    for(unsigned n=0;n<count;++n){
        require(b.size()-pos>=record+4,"Truncated LSR/1 exchange");
        const auto x=b.subspan(pos,record);
        require(get(x,0)==n&&x[4]>=1&&x[4]<=4&&x[5]<=1&&x[6]>=2&&x[6]<=14&&!x[7],"Invalid LSR/1 record header");
        const auto len=get(x,16);
        require(len<=32768&&len<=scan_max_raw-total,"Invalid LSR/1 payload size");
        const std::size_t size=record+x[6]+len+4;
        require(size<=b.size()-pos&&crc(b.subspan(pos,size-4))==get(b,pos+size-4),"LSR/1 CRC or size mismatch");
        ScanExchange row{};
        row.outcome=static_cast<ScanOutcome>(x[4]);row.attempted=x[5]!=0;
        row.started_ms=get(x,8);row.ended_ms=get(x,12);
        row.command.assign(b.begin()+pos+record,b.begin()+pos+record+x[6]);
        row.raw.assign(b.begin()+pos+record+x[6],b.begin()+pos+size-4);
        row_valid(row,prior,v.budget_ms);prior=row.ended_ms;
        v.trace.push_back(std::move(row));pos+=size;total+=len;
    }
    require(pos==b.size(),"Trailing LSR/1 bytes");return v;
}
Scan inspect_scan_capture(Bytes bytes,Protocol p,unsigned source,std::string_view vin){
    const auto v=decode_scan_capture(bytes);
    require(v.protocol==p&&v.source==source&&v.vin==vin,"LSR/1 differs from independent target");
    return replay_scan(p,source,vin,v.budget_ms,v.trace);
}
}
