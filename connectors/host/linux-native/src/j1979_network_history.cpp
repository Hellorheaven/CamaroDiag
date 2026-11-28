#include "j1979_network_history.hpp"
#include "j1979_policy.h"
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
static Inspection inspect_admission(const Value& root,bool campaign){
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
        if(!campaign)require(array(required(root,"ecus"),max_targets).empty()&&
            array(required(root,"selected_response_ids"),max_targets).empty(),"Use discovery-only Android report");
        d=&required(root,"discovery");
    }else if(contains_schema(root,campaign?"camarodiag-functional-j1979-can-network-v1":
        "camarodiag-functional-j1979-can-discovery-v1")&&root.find("profile")){
        result.platform="J2534";const auto p=bounded_int(required(root,"profile"),36);
        require(p>=33,"Invalid J2534 CAN profile");result.profile=p;extended=p==34||p==36;
        d=&required(root,"discovery");
        if(!campaign)require(!root.find("network")||root.at("network").null(),"Use discovery-only J2534 report");
        if(const auto* baud=root.find("baud"))
            require(baud->unsigned_integer()==(p<=34?500000u:250000u),"J2534 baud differs from profile");
    }else{
        require(contains_schema(root,campaign?"camarodiag-functional-j1979-can-network-v1":
            "camarodiag-functional-j1979-can-discovery-v1")||
            (!campaign&&contains_schema(root,"camarodiag-j1979-can-discovery-linux-v1")),"Unsupported CAN schema");
        result.platform="SOCKETCAN";extended=addressing(required(root,"addressing"));
        if(campaign)d=&required(root,"discovery");
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
    if(!campaign&&d!=&root)require(required(root,"completed").boolean()==out.completed,"Outer discovery completion differs");
    if(android&&out.completed){
        const auto p=bounded_int(required(*d,"observed_protocol"),9);
        require(result.profile==p&&probe(required(*d,"raw_protocol").string())==p,"Android fixed CAN probe differs");
        result.probe_verified=true;
    }
    if(const auto* q=d->find("request"))(void)frame(*q,extended,false,true);
    if(const auto* p=property(*d,"duration_ms"))out.duration_ms=bounded_int(*p,3600000);
    if(const auto* p=property(*d,"ignored_frame_count"))out.ignored=bounded_int(*p,1000000);
    else if(const auto* ignored=property(*d,"ignored_frames"))out.ignored=bounded_int(*ignored,1000000);
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
Inspection inspect_discovery(const Value& root){return inspect_admission(root,false);}
namespace {
bool equal_value(const Value& a,const Value& b){
    if(a.data.index()!=b.data.index())return false;
    if(a.null())return true;
    if(const auto* p=std::get_if<bool>(&a.data))return *p==b.boolean();
    if(const auto* p=std::get_if<json::Integer>(&a.data))return p->text==std::get<json::Integer>(b.data).text;
    if(const auto* p=std::get_if<json::Decimal>(&a.data))return p->text==std::get<json::Decimal>(b.data).text;
    if(const auto* p=std::get_if<std::string>(&a.data))return *p==b.string();
    if(const auto* p=std::get_if<Value::Array>(&a.data)){
        const auto& q=b.array();if(p->size()!=q.size())return false;
        for(std::size_t i=0;i<p->size();++i)if(!equal_value(p->at(i),q[i]))return false;
        return true;
    }
    const auto& x=a.object();const auto& y=b.object();
    if(x.size()!=y.size())return false;
    for(const auto& [k,v]:x){
        const auto j=y.find(k);if(j==y.end()||!equal_value(v,j->second))return false;
    }
    return true;
}
std::string optional_text(const Value& v,std::string_view key){
    const auto& p=optional(v,key);
    if(p.null())return {};
    const auto& s=p.string();require(s.size()<=256,"Physical error exceeds bound");return s;
}
std::optional<std::string> vin_value(const Value& v){
    if(v.null())return {};
    const auto& s=v.string();require(cd_j1979_native_vin(s.data(),s.size()),"Invalid network VIN");return s;
}
bool terminal_error(std::string_view error){
    return error=="CANCELLED"||error=="BUDGET_EXHAUSTED"||error=="VIN_MISMATCH"||
        error=="VIN_UNVERIFIED"||error=="TRANSPORT_ERROR"||error=="VIN_CHANGED"||
        error=="FINAL_VIN_UNVERIFIED"||error=="OSError"||error=="ConnectionError"||
        error=="BrokenPipeError"||error=="ConnectionResetError";
}
}
NetworkInspection inspect_network(const Value& root,std::optional<std::string> expected_vin){
    NetworkInspection out{};
    out.admission=inspect_admission(root,true);
    const bool android=out.admission.platform=="ANDROID",windows=out.admission.platform=="J2534";
    const auto& d=out.admission.discovery;
    const Value* body=&root;
    if(windows){
        body=&required(root,"network");
        require(!body->null(),"Missing Windows physical network body");
        require(equal_value(required(root,"discovery"),required(*body,"discovery")),"Duplicated discovery differs");
    }
    no_claims(*body);
    out.vin=vin_value(optional(*body,"declared_vin"));
    if(expected_vin)require(out.vin==expected_vin,"Network VIN differs from independent target");
    if(android)require(out.vin.has_value(),"Android network VIN missing");
    out.completed=required(root,"completed").boolean();
    require(required(*body,"completed").boolean()==out.completed,"Outer network completion differs");
    out.stop=optional_text(*body,"stop_reason");
    require(!out.completed||out.stop.empty(),"Completed network has stop reason");
    const auto& ids=array(required(*body,"selected_response_ids"),max_targets);
    std::set<std::uint32_t> seen;
    for(const auto& id:ids){
        const auto n=address(id);
        require(response_pair(n,d.extended)&&seen.insert(n).second,"Invalid or duplicate network selection");
        out.selected.push_back(n);
    }
    require(!out.selected.empty(),"Use discovery inspection for an empty selection");
    const bool all_eligible=d.completed&&std::all_of(out.selected.begin(),out.selected.end(),[&](auto rx){
        return std::find(d.eligible.begin(),d.eligible.end(),rx)!=d.eligible.end();
    });
    const auto& rows=array(required(*body,android?"ecus":"ecu_reports"),max_targets);
    require(rows.size()<=out.selected.size(),"Unselected physical ECU");
    if(out.completed)require(all_eligible&&rows.size()==out.selected.size(),"Completed network lacks selected ECU");
    bool terminal=false;
    for(std::size_t i=0;i<rows.size();++i){
        const auto& v=rows[i];no_claims(v);
        PhysicalRow row{};
        row.pair={address(required(v,android?"request_id":"tx_can_id")),
            address(required(v,android?"response_id":"rx_can_id"))};
        require(row.pair.rx==out.selected[i]&&response_pair(row.pair.rx,d.extended)==row.pair,
            "Physical CAN pair differs from ordered selection");
        row.status=required(v,"status").string();
        require(row.status=="PASSED"||row.status=="FAILED"||row.status=="NOT_TESTED","Invalid ECU result status");
        row.error=optional_text(v,android?"stop_reason":"error");
        const auto& evidence=android?v:optional(v,"report");
        if(!evidence.null()){
            row.plan=inspect_plan(evidence,android?PlanOrder::android:PlanOrder::historical,out.vin);
            if(!row.plan->steps.empty())require(all_eligible&&!terminal,"Read attempted after unverified or terminal target");
            require(row.status!="PASSED"||row.plan->completed,"Successful ECU lacks complete physical plan");
            if(!row.plan->completed&&(android||windows||terminal_error(row.error)||terminal_error(row.plan->stop_reason)))terminal=true;
        }else{
            require(row.status!="PASSED","Successful ECU has no physical report");
            if(android||windows||terminal_error(row.error))terminal=true;
        }
        if(out.completed)require(row.status=="PASSED"&&row.plan&&row.plan->completed,"Completed network contains failed ECU");
        out.rows.push_back(std::move(row));
    }
    return out;
}
std::string report(const NetworkInspection& r,std::string_view digest){
    std::ostringstream o;
    o<<"{\"schema\":\"camarodiag-j1979-historical-can-network-inspection-linux-v1\","
        "\"verification_scope\":\"DISCOVERY_SELECTION_READ_PLAN_BASIC_SUMMARY\","
        "\"consistent\":true,\"completed\":"<<(r.completed?"true":"false")
        <<",\"declared_vin\":"<<(r.vin?json::quote(*r.vin):"null")
        <<",\"stop_reason\":"<<(r.stop.empty()?"null":json::quote(r.stop))
        <<",\"admission\":"<<report(r.admission)<<",\"selected_response_ids\":[";
    for(std::size_t i=0;i<r.selected.size();++i){if(i)o<<',';o<<json::quote(hex(r.selected[i]));}
    o<<"],\"ecu_reports\":[";
    for(std::size_t i=0;i<r.rows.size();++i){
        if(i)o<<',';
        const auto& row=r.rows[i];
        o<<"{\"request_id\":"<<json::quote(hex(row.pair.tx))<<",\"response_id\":"<<json::quote(hex(row.pair.rx))
            <<",\"status\":"<<json::quote(row.status)<<",\"error\":"<<(row.error.empty()?"null":json::quote(row.error))
            <<",\"plan\":"<<(row.plan?plan_report(*row.plan):"null")<<'}';
    }
    o<<"],\"source_sha256\":"<<(digest.empty()?"null":json::quote(digest))
        <<",\"derived_projections_verified\":false,\"authenticated\":false,\"physical_vehicle_verified\":false,"
          "\"whole_vehicle_coverage_verified\":false,\"current_authorization_verified\":false,"
          "\"permits_programming\":false}\n";return o.str();
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
