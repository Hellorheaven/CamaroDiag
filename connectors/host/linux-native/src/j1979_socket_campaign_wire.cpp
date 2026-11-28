#include "j1979_socket_campaign.hpp"
#include "j1979_isotp.h"
#include "j1979_native_report_detail.hpp"
#include <algorithm>
#include <array>
#include <limits>
#include <openssl/evp.h>
#include <stdexcept>

namespace cd::socketcampaign {
namespace {
using network::history::detail::require;
using json::Value;
constexpr std::array<std::uint8_t,4> magic{'S','C','J',1};
constexpr std::array<std::uint8_t,4> marker{'E','V','T',1};
constexpr std::size_t frame_header=12,max_frame=10000;
void append32(Data& d,std::uint32_t n){for(unsigned i=0;i<4;++i)d.push_back(static_cast<std::uint8_t>(n>>(8*i)));}
std::uint32_t get32(Bytes b,std::size_t at){
    require(at<=b.size()&&b.size()-at>=4,"Truncated SCJ integer");
    return std::uint32_t(b[at])|std::uint32_t(b[at+1])<<8|std::uint32_t(b[at+2])<<16|std::uint32_t(b[at+3])<<24;
}
std::uint32_t crc(Bytes b){
    std::uint32_t v=0xffffffffu;
    for(auto n:b){v^=n;for(unsigned i=0;i<8;++i)v=(v>>1)^((v&1u)?0xedb88320u:0);}
    return v^0xffffffffu;
}
Value str(std::string_view s){return Value{std::string(s)};}
Value num(std::uint64_t n){return Value{json::Integer{std::to_string(n)}};}
Value obj(Value::Object o){return Value{std::move(o)};}
std::string hex(Bytes b){
    constexpr char digits[]="0123456789ABCDEF";
    std::string s;s.reserve(b.size()*2);
    for(auto n:b){s+=digits[n>>4];s+=digits[n&15];}return s;
}
unsigned bounded(const Value& v,unsigned low,unsigned high){
    const auto n=v.unsigned_integer();require(n>=low&&n<=high,"SCJ integer outside range");return static_cast<unsigned>(n);
}
std::string_view name(Kind k){
    switch(k){
    case Kind::clock:return "clock";case Kind::current:return "current";case Kind::wait:return "wait";
    case Kind::intent:return "intent";case Kind::response:return "response";case Kind::terminal:return "terminal";
    }
    throw std::invalid_argument("Unknown SCJ event");
}
Kind kind(std::string_view s){
    for(auto k:{Kind::clock,Kind::current,Kind::wait,Kind::intent,Kind::response,Kind::terminal})if(name(k)==s)return k;
    throw std::invalid_argument("Unknown SCJ event name");
}
void validate(const Event& e){
    switch(e.kind){
    case Kind::clock:require(e.value<=3600001&&!e.aux&&e.bytes.empty(),"Invalid SCJ clock");break;
    case Kind::current:require(e.value<=1&&!e.aux&&e.bytes.empty(),"Invalid SCJ context");break;
    case Kind::wait:require(e.value>=1&&e.value<=100&&e.aux<=1&&e.bytes.empty(),"Invalid SCJ wait");break;
    case Kind::intent:require(e.value>=1&&e.value<=2500&&!e.aux&&j1979_can_allowed_read(e.bytes.data(),e.bytes.size()),"Invalid SCJ read intent");break;
    case Kind::response:
        // value: 0 success, 1 timeout, 2 transport failure, 3 successful oversized reply.
        // aux is the remaining timeout actually supplied to the transport.
        // Zero means the durable intent was not dispatched to its callback.
        require(e.value<=3&&e.aux<=2500&&e.bytes.size()<=J1979_CAN_MAX_PAYLOAD&&
            (e.value==0||e.bytes.empty())&&(e.aux||e.value==2),"Invalid SCJ exchange result");break;
    case Kind::terminal:require(e.value<=1&&!e.aux&&e.bytes.size()==32,"Invalid SCJ terminal digest");break;
    }
}
Data frame(const std::array<std::uint8_t,4>& type,std::size_t seq,std::string_view body){
    require(!body.empty()&&body.size()<=max_frame&&seq<=max_events,"SCJ frame bound");
    Data result(type.begin(),type.end());
    append32(result,static_cast<std::uint32_t>(seq));append32(result,static_cast<std::uint32_t>(body.size()));
    result.insert(result.end(),body.begin(),body.end());append32(result,crc(result));return result;
}
Value parse_body(Bytes bytes){
    const std::string_view raw(reinterpret_cast<const char*>(bytes.data()),bytes.size());
    auto v=json::parse(raw,max_frame);
    require(json::serialize(v,max_frame)==raw,"Noncanonical SCJ record");
    return v;
}
Plan parse_plan(const Value& v){
    json::fields(v,{"operation","tx","rx","vin","interface","budget_ms","cycles","interval_ms","pids"});
    Plan p{};
    const auto& op=v.at("operation").string();
    require(op=="scan"||op=="live","Invalid SCJ operation");
    p.operation=op=="scan"?Operation::scan:Operation::live;
    p.tx=bounded(v.at("tx"),0,0x1fffffff);p.rx=bounded(v.at("rx"),0,0x1fffffff);
    p.vin=v.at("vin").string();p.interface=v.at("interface").string();
    p.budget_ms=bounded(v.at("budget_ms"),1,600000);p.cycles=bounded(v.at("cycles"),0,1000);
    p.interval_ms=bounded(v.at("interval_ms"),0,60000);
    p.pids=network::history::detail::bytes(v.at("pids"),32);
    require(p.valid(),"Invalid SCJ campaign plan");return p;
}
Event parse_event(const Value& v){
    json::fields(v,{"kind","value","aux","bytes"});
    Event e{kind(v.at("kind").string()),v.at("value").unsigned_integer(),bounded(v.at("aux"),0,2500),
        network::history::detail::bytes(v.at("bytes"),J1979_CAN_MAX_PAYLOAD)};
    validate(e);return e;
}
}
bool Plan::valid()const{
    if(!j1979_can_physical_pair(tx,rx)||vin.size()!=17||!j1979_can_valid_vin(vin.c_str())||
        !j1979_isotp_valid_interface(interface.c_str())||interface.find('\0')!=std::string::npos||
        budget_ms<1000||budget_ms>600000)return false;
    if(operation==Operation::scan)return !cycles&&!interval_ms&&pids.empty();
    if(operation!=Operation::live||!cycles||cycles>1000||interval_ms<100||interval_ms>60000||
        pids.empty()||pids.size()>32||pids.size()*cycles>10000)return false;
    std::array<bool,256> seen{};
    for(auto p:pids){if(!p||p%32==0||seen[p])return false;seen[p]=true;}
    return true;
}
Data encode_header(const Plan& p){
    require(p.valid(),"Invalid SCJ campaign plan");
    return frame(magic,0,json::serialize(obj({{"operation",str(p.operation==Operation::scan?"scan":"live")},
        {"tx",num(p.tx)},{"rx",num(p.rx)},{"vin",str(p.vin)},{"interface",str(p.interface)},
        {"budget_ms",num(p.budget_ms)},{"cycles",num(p.cycles)},{"interval_ms",num(p.interval_ms)},
        {"pids",str(hex(p.pids))}}),max_frame));
}
Data encode_event(const Event& e,std::size_t seq){
    validate(e);
    return frame(marker,seq,json::serialize(obj({{"kind",str(name(e.kind))},{"value",num(e.value)},
        {"aux",num(e.aux)},{"bytes",str(hex(e.bytes))}}),max_frame));
}
Journal decode(Bytes input,bool allow_prefix){
    require(input.size()>=frame_header+4&&input.size()<=max_journal,"Invalid SCJ input size");
    Journal out{};out.total_bytes=input.size();
    std::size_t at=0;
    bool header=true;
    while(at<input.size()){
        const auto rest=input.subspan(at);
        const auto& expected=header?magic:marker;
        const auto prefix=rest.first(std::min<std::size_t>(4,rest.size()));
        require(std::equal(prefix.begin(),prefix.end(),expected.begin()),"Invalid SCJ frame marker");
        if(rest.size()<frame_header){out.torn=true;break;}
        const auto seq=get32(rest,4),length=get32(rest,8);
        require(seq==(header?0:out.events.size())&&length>0&&length<=max_frame,"Invalid SCJ sequence or length");
        const std::size_t size=frame_header+length+4;
        if(rest.size()<size){out.torn=true;break;}
        require(get32(rest,size-4)==crc(rest.first(size-4)),"SCJ frame CRC mismatch");
        const auto body=parse_body(rest.subspan(frame_header,length));
        if(header){out.plan=parse_plan(body);header=false;}
        else{
            require(out.events.size()<max_events&&!out.terminal,"SCJ events after terminal or limit");
            auto event=parse_event(body);
            out.terminal=event.kind==Kind::terminal;
            out.events.push_back(std::move(event));
            if(out.terminal)require(rest.size()==size,"Trailing data after SCJ terminal");
        }
        at+=size;
    }
    require(!header&&(allow_prefix||out.terminal),"SCJ header or terminal missing");
    out.verified_bytes=at;return out;
}
std::string sha256(Bytes b){
    std::array<unsigned char,EVP_MAX_MD_SIZE> hash{};
    unsigned size=0;
    require(EVP_Digest(b.data(),b.size(),hash.data(),&size,EVP_sha256(),nullptr)==1&&size==32,"SHA-256 unavailable");
    auto result=hex(Bytes(hash).first(size));
    for(char& c:result)if(c>='A'&&c<='F')c=static_cast<char>(c-'A'+'a');
    return result;
}
std::string sha256(std::string_view s){return sha256({reinterpret_cast<const std::uint8_t*>(s.data()),s.size()});}
}
