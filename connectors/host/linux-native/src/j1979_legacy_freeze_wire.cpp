#include "j1979_legacy_freeze_wire.hpp"
#include <algorithm>
#include <array>
#include <stdexcept>

namespace cd::legacy {
namespace {
constexpr std::size_t header_size=112,record_head=16,record_crc=4;
constexpr std::array<std::uint8_t,4> magic{'L','F','R',1};
void require(bool condition,const char* message){if(!condition)throw std::invalid_argument(message);}
void put32(Data& b,std::size_t at,std::uint32_t n){
    for(unsigned i=0;i<4;++i)b.at(at+i)=std::uint8_t(n>>(i*8));
}
std::uint32_t get32(Bytes b,std::size_t at){
    return std::uint32_t(b[at])|(std::uint32_t(b[at+1])<<8)|
        (std::uint32_t(b[at+2])<<16)|(std::uint32_t(b[at+3])<<24);
}
std::uint32_t crc(Bytes data){
    std::uint32_t v=0xffffffffu;
    for(auto b:data){v^=b;for(unsigned i=0;i<8;++i)v=(v>>1)^((v&1)?0xedb88320u:0u);}
    return v^0xffffffffu;
}
bool zero(Bytes b){return std::all_of(b.begin(),b.end(),[](auto x){return x==0;});}
bool ascii(std::string_view s){
    return std::all_of(s.begin(),s.end(),[](unsigned char c){return (c>=32&&c<=126)||c=='\r'||c=='\n'||c=='\t';});
}
bool command_allowed(const FreezePlan& plan,std::string_view command){
    if(command=="ATDPN")return true;
    try{
        const auto q=unhex(command);
        return hex(q)==command&&plan.allows(q);
    }catch(const std::invalid_argument&){return false;}
}
void verify_exchange(const FreezePlan& plan,const FreezeExchange& x){
    const auto outcome=static_cast<unsigned>(x.outcome);
    require(command_allowed(plan,x.command)&&x.command.size()<=6&&x.raw.size()<=32768&&ascii(x.raw)&&
        outcome>=1&&outcome<=6&&x.duration_ms<=3600000,"Invalid LFR/1 exchange");
    require((x.outcome!=FreezeOutcome::received&&x.outcome!=FreezeOutcome::late)||x.attempted,
        "LFR/1 response without transmission");
    require(x.outcome!=FreezeOutcome::budget||!x.attempted,"LFR/1 budget contradiction");
    require((x.outcome!=FreezeOutcome::timeout&&x.outcome!=FreezeOutcome::transport)||x.attempted,
        "LFR/1 transport failure without transmission");
    require(x.outcome==FreezeOutcome::received||x.outcome==FreezeOutcome::late||x.raw.empty(),
        "LFR/1 invalid raw response");
}
}
Data encode_freeze_capture(const FreezeCapture& capture){
    require(valid_protocol(capture.protocol)&&valid_source(capture.source)&&capture.plan.valid()&&
        !capture.trace.empty()&&capture.trace.size()<=freeze_max_trace,"Invalid LFR/1 capture scope");
    Data out(header_size,0);
    std::copy(magic.begin(),magic.end(),out.begin());
    out[4]=static_cast<std::uint8_t>(capture.protocol);out[5]=static_cast<std::uint8_t>(capture.source);
    out[6]=static_cast<std::uint8_t>(capture.plan.frames.size());out[7]=static_cast<std::uint8_t>(capture.plan.pids.size());
    put32(out,8,capture.plan.budget_ms);put32(out,12,static_cast<std::uint32_t>(capture.trace.size()));
    std::copy(capture.plan.vin.begin(),capture.plan.vin.end(),out.begin()+16);
    std::copy(capture.plan.frames.begin(),capture.plan.frames.end(),out.begin()+33);
    std::copy(capture.plan.pids.begin(),capture.plan.pids.end(),out.begin()+41);
    // Header length includes the fixed arrays, reserved padding and checksum.
    put32(out,108,crc(Bytes(out).first(108)));
    std::size_t raw_bytes=0;
    for(std::size_t i=0;i<capture.trace.size();++i){
        const auto& x=capture.trace[i];verify_exchange(capture.plan,x);
        require(x.raw.size()<=freeze_max_raw-raw_bytes,"LFR/1 total raw limit");
        raw_bytes+=x.raw.size();
        const auto offset=out.size();
        out.resize(offset+record_head,0);
        put32(out,offset,static_cast<std::uint32_t>(i));
        out[offset+4]=static_cast<std::uint8_t>(x.outcome);
        out[offset+5]=x.attempted?1:0;
        out[offset+6]=static_cast<std::uint8_t>(x.command.size());
        put32(out,offset+8,x.duration_ms);put32(out,offset+12,static_cast<std::uint32_t>(x.raw.size()));
        out.insert(out.end(),x.command.begin(),x.command.end());out.insert(out.end(),x.raw.begin(),x.raw.end());
        const auto checksum=crc(Bytes(out).subspan(offset));
        const auto at=out.size();out.resize(at+record_crc);put32(out,at,checksum);
    }
    require(out.size()<=freeze_max_capture,"LFR/1 capture limit");
    return out;
}
FreezeCapture decode_freeze_capture(Bytes raw){
    require(raw.size()>=112&&raw.size()<=freeze_max_capture&&std::equal(magic.begin(),magic.end(),raw.begin()),
        "Invalid LFR/1 input");
    require(zero(raw.subspan(105,3))&&crc(raw.first(108))==get32(raw,108),"LFR/1 header CRC or reserved bytes");
    FreezeCapture capture{};capture.protocol=static_cast<Protocol>(raw[4]);capture.source=raw[5];
    require(valid_protocol(capture.protocol)&&valid_source(capture.source)&&raw[6]>=1&&raw[6]<=8&&raw[7]<=64,
        "Invalid LFR/1 plan counts");
    capture.plan.budget_ms=get32(raw,8);
    const auto count=get32(raw,12);require(count>=1&&count<=freeze_max_trace,"LFR/1 record count");
    capture.plan.vin.assign(raw.begin()+16,raw.begin()+33);
    capture.plan.frames.assign(raw.begin()+33,raw.begin()+33+raw[6]);
    capture.plan.pids.assign(raw.begin()+41,raw.begin()+41+raw[7]);
    require(capture.plan.valid()&&zero(raw.subspan(33+raw[6],8-raw[6]))&&zero(raw.subspan(41+raw[7],64-raw[7])),
        "Noncanonical LFR/1 plan");
    std::size_t pos=112,raw_bytes=0;
    capture.trace.reserve(count);
    for(std::uint32_t i=0;i<count;++i){
        require(raw.size()-pos>=record_head+record_crc,"Truncated LFR/1 exchange");
        const auto head=raw.subspan(pos,record_head);
        require(get32(head,0)==i&&head[4]>=1&&head[4]<=6&&head[5]<=1&&head[6]>=2&&head[6]<=6&&!head[7],
            "Invalid LFR/1 sequence or flags");
        const auto size=get32(head,12);const auto duration=get32(head,8);
        require(size<=32768&&size<=freeze_max_raw-raw_bytes,"LFR/1 response exceeds bound");
        const auto length=record_head+head[6]+size+record_crc;
        require(length<=raw.size()-pos,"Truncated LFR/1 response");
        require(crc(raw.subspan(pos,length-record_crc))==get32(raw,pos+length-record_crc),"LFR/1 exchange CRC mismatch");
        FreezeExchange x{};x.outcome=static_cast<FreezeOutcome>(head[4]);x.attempted=head[5]!=0;x.duration_ms=duration;
        x.command.assign(raw.begin()+pos+record_head,raw.begin()+pos+record_head+head[6]);
        x.raw.assign(raw.begin()+pos+record_head+head[6],raw.begin()+pos+length-record_crc);
        verify_exchange(capture.plan,x);
        capture.trace.push_back(std::move(x));pos+=length;raw_bytes+=size;
    }
    require(pos==raw.size(),"Trailing LFR/1 data");
    return capture;
}
FreezeResult inspect_freeze_capture(Bytes raw,Protocol protocol,unsigned source,std::string_view vin){
    const auto capture=decode_freeze_capture(raw);
    require(capture.protocol==protocol&&capture.source==source&&capture.plan.vin==vin,
        "LFR/1 differs from independently specified protocol, ECU or VIN");
    return replay_freeze(capture.protocol,capture.source,capture.plan,capture.trace);
}
}
