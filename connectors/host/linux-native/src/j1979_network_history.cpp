#include "j1979_network_history.hpp"
#include <algorithm>
#include <charconv>
#include <map>
#include <set>
#include <sstream>
#include <stdexcept>
namespace cd::network::history {
namespace {
using json::Value;
void require(bool b,const char* reason){if(!b)throw std::invalid_argument(reason);}
const Value* property(const Value& v,std::string_view key){
    std::string camel;bool upper=false;
    for(char c:key){
        if(c=='_'){upper=true;continue;}
        if(upper&&c>='a'&&c<='z')c=static_cast<char>(c-'a'+'A');
        upper=false;camel+=c;
    }
    const auto* a=v.find(key);const auto* b=camel==key?nullptr:v.find(camel);
    require(!a||!b,"Ambiguous historical CAN property");
    return a?a:b;
}
const Value& optional(const Value& v,std::string_view key){
    static const Value nil{};
    const auto* p=property(v,key);return p?*p:nil;
}
const Value& required(const Value& v,std::string_view key){
    const auto* p=property(v,key);require(p,"Missing historical CAN property");return *p;
}
bool flag(const Value& v,std::string_view key,bool fallback=false){
    const auto* p=property(v,key);return p?p->boolean():fallback;
}
void no_claims(const Value& v){
    for(auto k:{"authenticated","physical_vehicle_verified","whole_vehicle_coverage_verified",
        "permits_programming","permits_automatic_recovery","automatic_selection_permitted"})
        require(!flag(v,k),"Unsupported historical CAN assertion");
}
unsigned bounded_int(const Value& v,unsigned limit){
    const auto n=v.unsigned_integer();require(n<=limit,"Historical integer out of range");return static_cast<unsigned>(n);
}
const Value::Array& array(const Value& v,std::size_t max){
    const auto& a=v.array();require(a.size()<=max,"Historical array exceeds bound");return a;
}
std::uint32_t address(const Value& v){
    if(std::holds_alternative<json::Integer>(v.data))return bounded_int(v,0x1fffffffu);
    const auto& s=v.string();
    require(!s.empty()&&s.size()<=8,"Invalid CAN identifier width");
    std::uint32_t n{};
    const auto [end,ec]=std::from_chars(s.data(),s.data()+s.size(),n,16);
    require(ec==std::errc{}&&end==s.data()+s.size()&&n<=0x1fffffffu,"Invalid CAN identifier");
    return n;
}
std::vector<std::uint8_t> bytes(const Value& v,std::size_t max){
    const auto& s=v.string();require(s.size()%2==0&&s.size()<=max*2,"Invalid CAN data width");
    std::vector<std::uint8_t> out;out.reserve(s.size()/2);
    for(std::size_t i=0;i<s.size();i+=2){
        unsigned n{};
        const auto [end,ec]=std::from_chars(s.data()+i,s.data()+i+2,n,16);
        require(ec==std::errc{}&&end==s.data()+i+2&&n<=255,"Invalid CAN data hex");
        out.push_back(static_cast<std::uint8_t>(n));
    }
    return out;
}
Frame frame(const Value& v,bool extended,bool android,bool request=false,bool native_raw=false){
    Frame f{};f.id=address(required(v,android&&!request?"response_id":"can_id"));
    f.extended=flag(v,"extended",extended);f.rtr=flag(v,"rtr");f.error=flag(v,"error");
    f.data=bytes(required(v,"data_hex"),8);
    require(f.id<=(f.extended?0x1fffffffu:0x7ffu),"Invalid classic CAN identifier width");
    if(!native_raw){
        require(f.extended==extended&&!f.rtr&&!f.error,"Wrong classic CAN frame flags");
        if(request)require(f==functional_request(extended),"Unexpected functional PID00 request");
        else require(response_pair(f.id,extended).has_value(),"Unrelated recorded CAN response");
    }
    return f;
}
std::optional<unsigned> probe(std::string_view raw){
    if(raw.size()>4096)return {};
    std::optional<unsigned> result;
    std::size_t start=0;
    for(std::size_t i=0;i<=raw.size();++i){
        if(i<raw.size()&&raw[i]!='\r'&&raw[i]!='\n'&&raw[i]!='>')continue;
        auto line=raw.substr(start,i-start);start=i+1;
        while(!line.empty()&&(line.front()==' '||line.front()=='\t'))line.remove_prefix(1);
        while(!line.empty()&&(line.back()==' '||line.back()=='\t'))line.remove_suffix(1);
        std::string token(line);
        for(char& c:token)if(c>='a'&&c<='z')c=static_cast<char>(c-'a'+'A');
        if(token.empty()||token=="ATDPN")continue;
        if(token.size()!=1||token[0]<'6'||token[0]>'9'||result)return {};
        result=unsigned(token[0]-'0');
    }
    return result;
}
Classification classify_historical(const Frame& raw,bool extended,bool android){
    Frame f=raw;
    // Historical ELM ATH1 can omit padding, but may not truncate the
    // actual single-frame payload. Retain original bytes as evidence.
    if(android&&f.data.size()>=4&&f.data.size()<8){
        const auto& d=f.data;
        if((d.size()>=7&&d[0]==6&&d[1]==0x41&&d[2]==0)||
           (d[0]==3&&d[1]==0x7f&&d[2]==1))f.data.resize(8,0);
    }
    const auto result=classify(f,extended);
    require(result.has_value(),"Unclassifiable recorded CAN frame");return *result;
}
std::vector<Responder> group(const std::vector<Frame>& frames,bool extended,bool android){
    std::map<std::uint32_t,std::vector<Frame>> groups;
    for(const auto& f:frames)groups[f.id].push_back(f);
    std::vector<Responder> result;
    for(auto& [id,rows]:groups){
        std::optional<Classification> first,terminal;bool conflict=false;
        for(const auto& f:rows){
            const auto c=classify_historical(f,extended,android);
            if(!first)first=c;
            if(c.state==State::pending)continue;
            if(!terminal)terminal=c;
            else if(*terminal!=c)conflict=true;
        }
        auto verdict=terminal?*terminal:*first;
        if(conflict)verdict={*response_pair(id,extended),State::conflicting,{},0};
        result.push_back({verdict,std::move(rows)});
    }
    return result;
}
std::string hex(unsigned v){
    char b[16]{};const auto [p,e]=std::to_chars(b,b+sizeof(b),v,16);
    require(e==std::errc{},"CAN hexadecimal conversion");
    std::string out(b,p);
    for(char& c:out)if(c>='a'&&c<='f')c=static_cast<char>(c-'a'+'A');
    return out;
}
std::string status(State s){
    return s==State::positive?"PASSED":s==State::negative||s==State::pending?"NOT_TESTED":"FAILED";
}
std::string detail(const Classification& c){
    switch(c.state){
    case State::positive:return {};
    case State::negative:return std::string("NRC_")+(c.nrc<16?"0":"")+hex(c.nrc);
    case State::pending:return "PENDING_WITHOUT_FINAL_RESPONSE";
    case State::invalid:return "INVALID_PID00_FRAME";
    case State::conflicting:return "CONFLICTING_RESPONSES";
    }
    throw std::invalid_argument("Unknown discovery verdict");
}
std::vector<unsigned> pids(const Classification& c){
    std::vector<unsigned> out;
    for(unsigned i=0;i<31;++i)if(c.bitmap[i/8]&(0x80u>>(i%8)))out.push_back(i+1);
    return out;
}
void compare(const Value& row,const Responder& expected,bool extended,bool android){
    const auto& c=expected.classification;
    require(address(required(row,android?"response_id":"rx_can_id"))==c.pair.rx&&
        address(required(row,android?"request_id":"tx_can_id"))==c.pair.tx,"Historical responder address differs");
    require(required(row,"status").string()==status(c.state),"Historical responder status differs");
    const auto& d=optional(row,"detail");const auto why=detail(c);
    require(why.empty()?d.null():!d.null()&&d.string()==why,"Historical responder detail differs");
    const auto& claimed=optional(row,"supported_pids");const auto& next=optional(row,"next_page");
    const auto& nrc=optional(row,"negative_response_code");
    if(c.state==State::positive){
        const auto& a=array(claimed,32);const auto wanted=pids(c);
        const bool more=(c.bitmap[3]&1)!=0;
        const bool old_marker=!android&&!a.empty()&&a.back().unsigned_integer()==32;
        require(!old_marker||more,"Invalid historical continuation marker");
        require(a.size()==wanted.size()+(old_marker?1u:0u),"Historical PID count differs");
        for(std::size_t i=0;i<wanted.size();++i)require(a[i].unsigned_integer()==wanted[i],"Historical PID differs");
        require(next.boolean()==more,"Historical page flag differs");
    }else require(claimed.null()&&next.null(),"Unsupported PID bitmap claim");
    require(c.state==State::negative?!nrc.null()&&nrc.unsigned_integer()==c.nrc:nrc.null(),"Historical NRC differs");
    const auto& a=array(required(row,"frames"),max_frames);
    require(a.size()==expected.frames.size(),"Historical grouped frame count differs");
    for(std::size_t i=0;i<a.size();++i){
        if(android)require(bytes(a[i],8)==expected.frames[i].data,"Historical Android frame differs");
        else require(frame(a[i],extended,false)==expected.frames[i],"Historical grouped frame differs");
    }
}
bool addressing(const Value& v){
    const auto& s=v.string();require(s=="11BIT"||s=="29BIT","Invalid classic CAN addressing");return s=="29BIT";
}
bool contains_schema(const Value& v,std::string_view s){
    const auto* p=v.find("schema");return p&&p->string()==s;
}
}
Inspection inspect_discovery(const Value& root){
    no_claims(root);
    // A discovery-only inspection is deliberately not a physical campaign
    // verification; never silently accept or claim verified ECU scan rows.
    const Value* d=&root;
    Inspection result{};
    bool android=false,extended=false;
    if(contains_schema(root,"camarodiag-j1979-network-v1")){
        android=true;result.platform="ANDROID";
        const auto p=bounded_int(required(root,"protocol"),9);
        require(p>=6,"Invalid Android fixed CAN profile");result.profile=p;extended=p==7||p==9;
        require(array(required(root,"ecus"),max_targets).empty()&&
            array(required(root,"selected_response_ids"),max_targets).empty(),"Use discovery-only Android report");
        d=&required(root,"discovery");
    }else if(contains_schema(root,"camarodiag-functional-j1979-can-discovery-v1")&&root.find("profile")){
        result.platform="J2534";const auto p=bounded_int(required(root,"profile"),36);
        require(p>=33,"Invalid J2534 CAN profile");result.profile=p;extended=p==34||p==36;
        d=&required(root,"discovery");
        require(!root.find("network")||root.at("network").null(),"Use discovery-only J2534 report");
    }else{
        require(contains_schema(root,"camarodiag-functional-j1979-can-discovery-v1")||
            contains_schema(root,"camarodiag-j1979-can-discovery-linux-v1"),"Unsupported discovery-only CAN schema");
        result.platform="SOCKETCAN";extended=addressing(required(root,"addressing"));
    }
    no_claims(*d);
    if(const auto* p=property(*d,"extended"))require(p->boolean()==extended,"Discovery addressing flag differs");
    if(const auto* p=property(*d,"addressing"))require(addressing(*p)==extended,"Discovery addressing differs");
    auto& out=result.discovery;out.extended=extended;
    out.sent=required(*d,"request_sent").boolean();out.completed=required(*d,"completed").boolean();
    const auto& stop=optional(*d,"stop_reason");
    if(!stop.null()){
        out.stop=stop.string();
        require(!out.stop.empty()&&out.stop.size()<=80&&
            std::all_of(out.stop.begin(),out.stop.end(),[](unsigned char c){
                return (c>='A'&&c<='Z')||(c>='a'&&c<='z')||(c>='0'&&c<='9')||c=='_';
            }),"Invalid discovery stop reason");
    }
    require(out.completed==out.stop.empty()&&(!out.completed||out.sent),"Inconsistent discovery completion");
    if(d!=&root)require(required(root,"completed").boolean()==out.completed,"Outer discovery completion differs");
    if(android&&out.completed){
        const auto p=bounded_int(required(*d,"observed_protocol"),9);
        require(result.profile==p&&probe(required(*d,"raw_protocol").string())==p,"Android fixed CAN probe differs");
        result.probe_verified=true;
    }
    if(const auto* q=d->find("request"))(void)frame(*q,extended,false,true);
    if(const auto* p=property(*d,"duration_ms"))out.duration_ms=bounded_int(*p,3600000);
    if(const auto* p=property(*d,"ignored_frame_count"))out.ignored=bounded_int(*p,1000000);
    const auto* raw=property(*d,"raw_frames");
    if(!raw)raw=d->find("frames");
    require(raw,"Missing CAN discovery bytes");
    const bool native=contains_schema(root,"camarodiag-j1979-can-discovery-linux-v1");
    for(const auto& v:array(*raw,max_frames))out.frames.push_back(frame(v,extended,android,false,native));
    require(out.sent||out.frames.empty(),"Response recorded without discovery transmission");
    if(native){
        auto reason=out.stop;
        if(reason=="NO_VERIFIED_RESPONDER")reason.clear();
        const auto replay=replay_discovery(extended,out.sent,out.frames,reason,out.duration_ms);
        require(replay.completed==out.completed&&replay.stop==out.stop&&replay.ignored==out.ignored,
            "Native discovery stop or ignored-frame count differs");
        out.responders=replay.responders;
    }else out.responders=group(out.frames,extended,android);
    const auto& rows=array(required(*d,"responders"),max_frames);
    require(rows.size()==out.responders.size(),"Missing or additional responder");
    std::set<std::uint32_t> seen;
    for(const auto& v:rows){
        const auto id=address(required(v,android?"response_id":"rx_can_id"));
        const auto i=std::find_if(out.responders.begin(),out.responders.end(),[&](const auto& r){return r.classification.pair.rx==id;});
        require(i!=out.responders.end()&&seen.insert(id).second,"Duplicate or unrelated responder");
        compare(v,*i,extended,android);
    }
    if(out.completed)for(const auto& row:out.responders)
        if(row.classification.state==State::positive)out.eligible.push_back(row.classification.pair.rx);
    require(!out.completed||!out.eligible.empty(),"Completed discovery lacks eligible responder");
    if(const auto* p=property(*d,"eligible_response_ids");p&&!p->null()){
        const auto& ids=array(*p,max_frames);require(ids.size()==out.eligible.size(),"Eligible CAN count differs");
        for(std::size_t i=0;i<ids.size();++i)require(address(ids[i])==out.eligible[i],"Eligible CAN identifier differs");
    }
    return result;
}
std::string report(const Inspection& r,std::string_view digest){
    auto discovery=cd::network::report(r.discovery);
    std::ostringstream out;
    out<<"{\"schema\":\"camarodiag-j1979-can-discovery-inspection-linux-v1\",\"consistent\":true,"
        "\"verification_scope\":\"DISCOVERY_ONLY\",\"platform\":"<<json::quote(r.platform)<<",\"profile\":";
    if(r.profile)out<<*r.profile;else out<<"null";
    out<<",\"protocol_probe_independently_verified\":"<<(r.probe_verified?"true":"false")
        <<",\"discovery\":"<<discovery
        <<",\"source_sha256\":"<<(digest.empty()?"null":json::quote(digest))
        <<",\"physical_vehicle_verified\":false,\"whole_vehicle_coverage_verified\":false,"
          "\"authenticated\":false,\"automatic_selection_permitted\":false,\"permits_programming\":false}\n";
    return out.str();
}
}
