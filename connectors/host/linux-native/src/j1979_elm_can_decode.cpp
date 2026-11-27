#include "j1979_elm_can.hpp"
#include <algorithm>
#include <charconv>
#include <stdexcept>
namespace cd::elmcan {
namespace {
constexpr std::size_t max_response=32768;
void require(bool condition,const char* reason){if(!condition)throw std::invalid_argument(reason);}
bool space(char c){return c==' '||c=='\t'||c=='\r'||c=='\n';}
std::string upper(std::string_view value){
    std::string s(value);for(char& c:s)if(c>='a'&&c<='z')c=char(c-'a'+'A');return s;
}
std::string_view trim(std::string_view s){
    while(!s.empty()&&space(s.front()))s.remove_prefix(1);
    while(!s.empty()&&space(s.back()))s.remove_suffix(1);
    return s;
}
std::vector<std::string> lines(std::string_view raw){
    require(raw.size()<=max_response,"ELM CAN reply exceeds bound");
    std::vector<std::string> result;
    bool prompt=false;
    size_t pos=0;
    for(size_t i=0;i<=raw.size();++i){
        if(i<raw.size()&&raw[i]!='\r'&&raw[i]!='\n'&&raw[i]!='>')continue;
        const auto line=trim(raw.substr(pos,i-pos));pos=i+1;
        require(!prompt||line.empty(),"ELM data after prompt");
        if(!line.empty())result.push_back(upper(line));
        if(i<raw.size()&&raw[i]=='>'){require(!prompt,"Duplicate ELM prompt");prompt=true;}
    }
    return result;
}
std::vector<std::string_view> tokens(std::string_view s){
    std::vector<std::string_view> result;
    while(!(s=trim(s)).empty()){
        auto end=s.find_first_of(" \t");
        result.push_back(s.substr(0,end));
        if(end==std::string_view::npos)break;
        s.remove_prefix(end+1);
    }
    return result;
}
std::optional<unsigned> hex_number(std::string_view s){
    unsigned value=0;
    auto r=std::from_chars(s.data(),s.data()+s.size(),value,16);
    if(s.empty()||r.ec!=std::errc{}||r.ptr!=s.data()+s.size())return {};
    return value;
}
struct Frame{uint32_t id{};Data bytes;};
Frame frame(std::string_view line,bool extended){
    auto t=tokens(line);
    require(!t.empty(),"Empty ELM CAN frame");
    const auto width=extended?8u:3u;
    size_t count=1;
    std::string id(t[0]);
    if(extended&&t[0].size()==2){
        require(t.size()>=5,"Truncated extended CAN header");
        count=4;id.clear();
        for(size_t i=0;i<4;++i){require(t[i].size()==2&&hex_number(t[i]).has_value(),"Invalid extended CAN header");id+=t[i];}
    }
    const auto parsed=hex_number(id);
    require(id.size()==width&&parsed&&*parsed<=(extended?0x1fffffffu:0x7ffu),"Invalid ELM CAN identifier");
    require(t.size()>count&&t.size()-count<=8,"Invalid classical CAN frame length");
    Frame f{};f.id=*parsed;
    for(size_t i=count;i<t.size();++i){
        const auto v=hex_number(t[i]);require(t[i].size()==2&&v&&*v<=255,"Invalid ELM CAN data byte");
        f.bytes.push_back(static_cast<uint8_t>(*v));
    }
    return f;
}
bool negative_pending(Bytes p,Bytes request){return p.size()==3&&p[0]==0x7f&&p[1]==request[0]&&p[2]==0x78;}
}
bool Scope::valid()const{
    return profile>=6&&profile<=9&&extended()==(tx>0x7ffu)&&j1979_can_physical_pair(tx,rx);
}
std::optional<unsigned> fixed_profile(std::string_view raw){
    auto rows=lines(raw);
    std::erase(rows,std::string("ATDPN"));
    if(rows.size()!=1||rows[0].size()!=1||rows[0][0]<'6'||rows[0][0]>'9')return {};
    return static_cast<unsigned>(rows[0][0]-'0');
}
Decoded decode(std::string_view raw,const Scope& scope,Bytes request){
    require(scope.valid()&&!request.empty()&&request.size()<=7,"Invalid ELM CAN scope");
    auto rows=lines(raw);
    Data wire{static_cast<uint8_t>(request.size())};wire.insert(wire.end(),request.begin(),request.end());
    const auto echo=legacy::hex(wire);
    Decoded result{};
    if(rows==std::vector<std::string>{"NO DATA"}||rows==std::vector<std::string>{echo,"NO DATA"})return result;
    Data payload;
    unsigned expected=0,sequence=1;
    bool complete=false,echo_seen=false;
    for(const auto& row:rows){
        if(row==echo){require(!echo_seen&&result.selected_frames==0&&result.other_frames==0,"Unexpected request echo");echo_seen=true;continue;}
        if(row.starts_with("FC:"))continue; // adapter-generated flow-control transcript, never diagnostic payload
        const auto f=frame(row,scope.extended());
        if(f.id!=scope.rx){++result.other_frames;continue;}
        ++result.selected_frames;
        require(!complete,"Multiple terminal responses from selected ECU");
        const auto& b=f.bytes;
        switch(b[0]>>4){
        case 0:
            require(!expected&&(b[0]&15)>=1&&(b[0]&15)<=7&&b.size()>=size_t(1+(b[0]&15)),"Malformed selected single frame");
            expected=b[0]&15;payload.assign(b.begin()+1,b.begin()+1+expected);
            break;
        case 1:
            require(!expected&&b.size()==8,"Malformed selected first frame");
            expected=(unsigned(b[0]&15)<<8)|b[1];
            require(expected>=8&&expected<=4095,"Invalid selected ISO-TP length");
            payload.assign(b.begin()+2,b.end());break;
        case 2:{
            require(expected>7&&payload.size()<expected&&(b[0]&15)==sequence,"Unexpected selected ISO-TP sequence");
            const auto count=std::min<size_t>(7,expected-payload.size());
            require(b.size()>=1+count,"Truncated selected consecutive frame");
            payload.insert(payload.end(),b.begin()+1,b.begin()+1+count);sequence=(sequence+1)&15;break;
        }
        default:throw std::invalid_argument("Unexpected selected ISO-TP PCI");
        }
        if(expected&&payload.size()==expected){
            if(negative_pending(payload,request)){
                require(++result.pending_frames<=8,"Too many selected pending responses");
                expected=0;sequence=1;payload.clear();continue;
            }
            complete=true;
        }
    }
    require(complete,"No complete selected ISO-TP response");
    require((payload[0]==request[0]+0x40&&payload.size()>=request.size()&&
        std::equal(request.begin()+1,request.end(),payload.begin()+1))||
        (payload.size()==3&&payload[0]==0x7f&&payload[1]==request[0]&&payload[2]&&payload[2]!=0x78),
        "Uncorrelated diagnostic application response");
    result.status=DecodeStatus::received;result.payload=std::move(payload);return result;
}
}
