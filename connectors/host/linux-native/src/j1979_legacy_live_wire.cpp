#include "j1979_legacy_live_wire.hpp"
#include <algorithm>
#include <array>
#include <stdexcept>

namespace cd::legacy {
namespace {
constexpr std::size_t header_size=80,record_size=24;
constexpr std::array<std::uint8_t,4> magic{'L','L','R',1};
void require(bool value,const char* why){if(!value)throw std::invalid_argument(why);}
void put32(Data& b,std::size_t at,std::uint32_t n){for(unsigned i=0;i<4;++i)b.at(at+i)=std::uint8_t(n>>(8*i));}
std::uint32_t get32(Bytes b,std::size_t at){
    return std::uint32_t(b[at])|(std::uint32_t(b[at+1])<<8)|(std::uint32_t(b[at+2])<<16)|(std::uint32_t(b[at+3])<<24);
}
std::uint32_t crc(Bytes b){
    std::uint32_t value=0xffffffffu;
    for(auto v:b){value^=v;for(unsigned bit=0;bit<8;++bit)value=(value>>1)^((value&1)?0xedb88320u:0u);}
    return value^0xffffffffu;
}
bool zero(Bytes b){return std::all_of(b.begin(),b.end(),[](auto n){return n==0;});}
bool command_allowed(const LivePlan& plan,std::string_view command){
    if(command=="WAIT"||command=="ATDPN")return true;
    try{
        const auto q=unhex(command);
        return hex(q)==command&&plan.allows(q);
    }catch(const std::invalid_argument&){return false;}
}
void verify(const LivePlan& plan,const LiveExchange& x){
    const auto phase=static_cast<unsigned>(x.phase),state=static_cast<unsigned>(x.exchange.outcome);
    require(phase>=1&&phase<=6&&state>=1&&state<=6&&x.cycle<=plan.cycles&&x.started_ms<=x.ended_ms&&
        x.ended_ms<=3600000&&x.exchange.duration_ms==x.ended_ms-x.started_ms&&
        command_allowed(plan,x.exchange.command)&&x.exchange.command.size()<=6&&x.exchange.raw.size()<=32768,
        "Invalid LLR/1 exchange");
    require(std::all_of(x.exchange.raw.begin(),x.exchange.raw.end(),[](unsigned char c){
        return (c>=32&&c<=126)||c=='\r'||c=='\n'||c=='\t';
    }),"Non-ASCII LLR/1 raw evidence");
}
}
Data encode_live_capture(const LiveCapture& v){
    require(valid_protocol(v.protocol)&&valid_source(v.source)&&v.plan.valid()&&!v.trace.empty()&&v.trace.size()<=live_max_trace,
        "Invalid LLR/1 scope");
    Data out(header_size,0);
    std::copy(magic.begin(),magic.end(),out.begin());
    out[4]=static_cast<std::uint8_t>(v.protocol);out[5]=static_cast<std::uint8_t>(v.source);
    out[6]=v.plan.raw_only?1:0;out[7]=static_cast<std::uint8_t>(v.plan.pids.size());
    put32(out,8,v.plan.cycles);put32(out,12,v.plan.interval_ms);put32(out,16,v.plan.budget_ms);
    put32(out,20,static_cast<std::uint32_t>(v.trace.size()));
    std::copy(v.plan.vin.begin(),v.plan.vin.end(),out.begin()+24);
    std::copy(v.plan.pids.begin(),v.plan.pids.end(),out.begin()+41);
    put32(out,76,crc(Bytes(out).first(76)));
    std::size_t total=0;
    for(std::size_t seq=0;seq<v.trace.size();++seq){
        const auto& x=v.trace[seq];verify(v.plan,x);
        const auto& e=x.exchange;
        require(e.raw.size()<=live_max_raw-total,"LLR/1 raw byte limit");total+=e.raw.size();
        const auto at=out.size();out.resize(at+record_size,0);
        put32(out,at,static_cast<std::uint32_t>(seq));
        out[at+4]=static_cast<std::uint8_t>(x.phase);out[at+5]=static_cast<std::uint8_t>(e.outcome);
        out[at+6]=e.attempted?1:0;out[at+7]=static_cast<std::uint8_t>(e.command.size());
        put32(out,at+8,x.cycle);put32(out,at+12,x.started_ms);put32(out,at+16,x.ended_ms);
        put32(out,at+20,static_cast<std::uint32_t>(e.raw.size()));
        out.insert(out.end(),e.command.begin(),e.command.end());
        out.insert(out.end(),e.raw.begin(),e.raw.end());
        const auto check=crc(Bytes(out).subspan(at));const auto end=out.size();out.resize(end+4);put32(out,end,check);
    }
    require(out.size()<=live_max_capture,"LLR/1 capture size limit");
    return out;
}
LiveCapture decode_live_capture(Bytes bytes){
    require(bytes.size()>=header_size&&bytes.size()<=live_max_capture&&std::equal(magic.begin(),magic.end(),bytes.begin())&&
        crc(bytes.first(76))==get32(bytes,76)&&zero(bytes.subspan(73,3)),"Invalid LLR/1 header");
    LiveCapture v{};v.protocol=static_cast<Protocol>(bytes[4]);v.source=bytes[5];
    require(valid_protocol(v.protocol)&&valid_source(v.source)&&bytes[6]<=1&&bytes[7]>=1&&bytes[7]<=32&&
        zero(bytes.subspan(41+bytes[7],32-bytes[7])),"Invalid LLR/1 scope");
    v.plan.raw_only=bytes[6]!=0;v.plan.cycles=get32(bytes,8);v.plan.interval_ms=get32(bytes,12);
    v.plan.budget_ms=get32(bytes,16);const auto count=get32(bytes,20);
    v.plan.vin.assign(bytes.begin()+24,bytes.begin()+41);
    v.plan.pids.assign(bytes.begin()+41,bytes.begin()+41+bytes[7]);
    require(v.plan.valid()&&count>=1&&count<=live_max_trace,"Invalid LLR/1 plan or record count");
    std::size_t pos=header_size,total=0;
    v.trace.reserve(count);
    for(std::uint32_t seq=0;seq<count;++seq){
        require(bytes.size()-pos>=record_size+4,"Truncated LLR/1 record");
        const auto header=bytes.subspan(pos,record_size);
        require(get32(header,0)==seq&&header[4]>=1&&header[4]<=6&&header[5]>=1&&header[5]<=6&&
            header[6]<=1&&header[7]>=2&&header[7]<=6,"Invalid LLR/1 sequence or flags");
        const auto length=get32(header,20);
        require(length<=32768&&length<=live_max_raw-total,"LLR/1 record byte limit");
        const auto full=record_size+header[7]+length+4;
        require(full<=bytes.size()-pos&&crc(bytes.subspan(pos,full-4))==get32(bytes,pos+full-4),
            "Truncated or damaged LLR/1 record");
        LiveExchange x{};x.phase=static_cast<LivePhase>(header[4]);x.cycle=get32(header,8);
        x.started_ms=get32(header,12);x.ended_ms=get32(header,16);
        require(x.started_ms<=x.ended_ms,"Invalid LLR/1 timing");
        x.exchange.duration_ms=x.ended_ms-x.started_ms;x.exchange.outcome=static_cast<FreezeOutcome>(header[5]);
        x.exchange.attempted=header[6]!=0;
        x.exchange.command.assign(bytes.begin()+pos+record_size,bytes.begin()+pos+record_size+header[7]);
        x.exchange.raw.assign(bytes.begin()+pos+record_size+header[7],bytes.begin()+pos+full-4);
        verify(v.plan,x);v.trace.push_back(std::move(x));total+=length;pos+=full;
    }
    require(pos==bytes.size(),"Trailing LLR/1 bytes");
    return v;
}
LiveResult inspect_live_capture(Bytes bytes,Protocol p,unsigned source,std::string_view vin){
    const auto v=decode_live_capture(bytes);
    require(v.protocol==p&&v.source==source&&v.plan.vin==vin,"LLR/1 scope differs from independent selection");
    return replay_live(p,source,v.plan,v.trace);
}
}
