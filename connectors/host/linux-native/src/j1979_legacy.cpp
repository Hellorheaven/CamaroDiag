#include "j1979_legacy.hpp"
#include "j1979_legacy_freeze_plan.hpp"
#include <algorithm>
#include <map>
#include <stdexcept>

namespace cd::legacy {
namespace {
constexpr std::size_t max_response=32768,max_frames=128;
bool whitespace(char c){return c==' '||c=='\t'||c=='\r'||c=='\n'||c=='\v'||c=='\f';}
char upper(char c){return c>='a'&&c<='z'?char(c-'a'+'A'):c;}
std::string compact(std::string_view line) {
    std::string result;
    for(char c:line){
        if(!whitespace(c)){
            if(static_cast<unsigned char>(c)<32||static_cast<unsigned char>(c)>126)
                throw std::invalid_argument("Non-ASCII ELM response");
            result+=upper(c);
        }
    }
    return result;
}
std::vector<std::string> lines(std::string_view response,std::string_view command) {
    if(response.size()>max_response)throw std::invalid_argument("ELM response byte limit");
    std::vector<std::string> result;
    std::size_t begin=0;
    for(std::size_t i=0;i<=response.size();++i){
        if(i<response.size()&&response[i]!='\r'&&response[i]!='\n'&&response[i]!='>')continue;
        auto token=compact(response.substr(begin,i-begin));begin=i+1;
        if(token.empty()||token==command||token=="SEARCHING...")continue;
        if(result.size()==max_frames)throw std::invalid_argument("Too many ELM response lines");
        result.push_back(std::move(token));
    }
    return result;
}
unsigned nibble(char c){
    c=upper(c);
    if(c>='0'&&c<='9')return unsigned(c-'0');
    if(c>='A'&&c<='F')return unsigned(c-'A'+10);
    throw std::invalid_argument("Invalid hexadecimal response");
}
}
bool valid_protocol(Protocol p){const auto n=static_cast<unsigned>(p);return n>=1&&n<=5;}
bool valid_source(unsigned n){return n>0&&n<255&&n!=0xf1;}
std::string_view name(Protocol p){
    switch(p){
    case Protocol::pwm:return "SAE J1850 PWM";
    case Protocol::vpw:return "SAE J1850 VPW";
    case Protocol::iso9141:return "ISO 9141-2";
    case Protocol::kwp_slow:return "ISO 14230-4 slow init";
    case Protocol::kwp_fast:return "ISO 14230-4 fast init";
    }
    throw std::invalid_argument("Invalid legacy protocol");
}
std::uint8_t checksum(Protocol protocol,Bytes data){
    if(!valid_protocol(protocol))throw std::invalid_argument("Invalid legacy checksum protocol");
    unsigned value=0;
    if(protocol==Protocol::pwm||protocol==Protocol::vpw){
        value=255;
        for(auto b:data){
            value^=b;
            for(unsigned i=0;i<8;++i)value=((value<<1)^((value&128)?0x1d:0))&255;
        }
        value^=255;
    }else for(auto b:data)value=(value+b)&255;
    return static_cast<std::uint8_t>(value);
}
std::string hex(Bytes bytes) {
    constexpr std::string_view digits="0123456789ABCDEF";
    std::string result;result.reserve(bytes.size()*2);
    for(auto b:bytes){result+=digits[b>>4];result+=digits[b&15];}
    return result;
}
Data unhex(std::string_view s){
    if(s.empty()||s.size()%2||s.size()>22)throw std::invalid_argument("Invalid legacy hex size");
    Data bytes;bytes.reserve(s.size()/2);
    for(std::size_t i=0;i<s.size();i+=2)bytes.push_back(std::uint8_t(nibble(s[i])*16+nibble(s[i+1])));
    return bytes;
}
bool read_allowed(Bytes q){
    if(q.empty()||q.size()>7)return false;
    const unsigned mode=q[0];
    if(q.size()==2&&(mode==1||mode==6||mode==9))return true;
    if(q.size()==3&&mode==2&&q[2]==0)return true;
    if(q.size()==3&&mode==5&&q[2]&&(q[2]&(q[2]-1))==0)return true;
    if(q.size()==7&&mode==8&&q[1]%32==0&&std::all_of(q.begin()+2,q.end(),[](auto b){return b==0;}))return true;
    return q.size()==1&&(mode==3||mode==7||mode==10);
}
std::optional<Protocol> fixed_protocol(std::string_view response){
    if(response.size()>4096)return {};
    try{
        std::vector<std::string> tokens;
        std::size_t start=0;
        for(std::size_t i=0;i<=response.size();++i){
            if(i<response.size()&&response[i]!='\r'&&response[i]!='\n'&&response[i]!='>')continue;
            auto line=response.substr(start,i-start);start=i+1;
            while(!line.empty()&&whitespace(line.front()))line.remove_prefix(1);
            while(!line.empty()&&whitespace(line.back()))line.remove_suffix(1);
            if(line.empty()||line=="ATDPN")continue;
            if(line.size()!=1||line[0]<'1'||line[0]>'5')return {};
            tokens.emplace_back(line);
            if(tokens.size()>1)return {};
        }
        if(tokens.size()==1&&tokens[0].size()==1&&tokens[0][0]>='1'&&tokens[0][0]<='5')
            return static_cast<Protocol>(tokens[0][0]-'0');
    }catch(const std::invalid_argument&){}
    return {};
}
static std::vector<Frame> parse_frames(Protocol protocol,std::string_view command,std::string_view response){
    if(!valid_protocol(protocol))throw std::invalid_argument("Invalid legacy protocol");
    const auto tokens=lines(response,command);
    if(tokens.empty()||(tokens.size()==1&&tokens[0]=="NODATA"))return {};
    std::vector<Frame> result;result.reserve(tokens.size());
    for(const auto& token:tokens){
        auto raw=unhex(token);
        if(raw.size()<5||raw.size()>11)throw std::invalid_argument("Unattributed legacy response");
        if(!valid_source(raw[2]))throw std::invalid_argument("Invalid legacy source address");
        if(protocol==Protocol::kwp_fast||protocol==Protocol::kwp_slow){
            if((raw[0]&0xc0)!=0x80||raw[1]!=0xf1||(raw[0]&0x3f)!=raw.size()-4)
                throw std::invalid_argument("Invalid addressed KWP response");
        }else if(raw[0]!=(protocol==Protocol::pwm?0x41:0x48)||raw[1]!=0x6b){
            throw std::invalid_argument("Unexpected legacy response header");
        }
        if(checksum(protocol,Bytes(raw).first(raw.size()-1))!=raw.back())
            throw std::invalid_argument("Legacy checksum mismatch");
        const unsigned source=raw[2];
        Data payload(raw.begin()+3,raw.end()-1);
        result.push_back({source,std::move(raw),std::move(payload)});
    }
    return result;
}
std::vector<Frame> frames(Protocol protocol,std::string_view command,std::string_view response){
    if(!read_allowed(unhex(command)))throw std::invalid_argument("Invalid legacy frame scope");
    return parse_frames(protocol,command,response);
}
std::vector<Frame> freeze_frames(Protocol protocol,const FreezePlan& plan,Bytes request,std::string_view raw){
    if(!plan.allows(request))throw std::invalid_argument("Invalid legacy freeze frame scope");
    return parse_frames(protocol,hex(request),raw);
}
std::vector<Data> anonymous_records(std::string_view command,std::string_view response){
    if(!read_allowed(unhex(command)))throw std::invalid_argument("Invalid anonymous scope");
    const auto tokens=lines(response,command);
    if(tokens.empty()||(tokens.size()==1&&tokens[0]=="NODATA"))return {};
    std::vector<Data> result;
    for(const auto& token:tokens){
        auto data=unhex(token);
        if(data.size()>7)throw std::invalid_argument("Invalid anonymous legacy response width");
        result.push_back(std::move(data));
    }
    return result;
}
std::vector<Responder> classify(std::span<const Frame> observations){
    if(observations.size()>max_frames)throw std::invalid_argument("Too many legacy observations");
    std::map<unsigned,std::vector<Frame>> groups;
    for(const auto& f:observations){
        if(!valid_source(f.source))throw std::invalid_argument("Invalid response source");
        groups[f.source].push_back(f);
    }
    std::vector<Responder> result;
    for(auto& [source,rows]:groups){
        Responder r{};
        r.source=source;r.status="FAILED";r.detail="AMBIGUOUS_OR_INVALID_PID00";
        if(rows.size()==1){
            const auto& data=rows.front().payload;
            if(data.size()==6&&data[0]==0x41&&data[1]==0){
                r.status="PASSED";r.detail.clear();
                std::array<std::uint8_t,4> bitmap{};
                std::copy_n(data.begin()+2,4,bitmap.begin());
                r.bitmap=bitmap;
                for(unsigned bit=0;bit<31;++bit)if(bitmap[bit/8]&(0x80u>>(bit%8)))r.supported.push_back(bit+1);
                r.next=(bitmap[3]&1)!=0;
            }else if(data.size()==3&&data[0]==0x7f&&data[1]==1&&data[2]&&data[2]!=0x78){
                r.status="NOT_TESTED";r.detail="NRC_"+hex(Bytes(data).subspan(2));
            }
        }
        r.observations=std::move(rows);
        result.push_back(std::move(r));
    }
    return result;
}
}
