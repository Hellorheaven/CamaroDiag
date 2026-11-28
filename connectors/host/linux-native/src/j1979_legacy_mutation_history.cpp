#include "j1979_legacy_mutation_history.hpp"
#include "j1979_legacy_scan_history.hpp"
#include "j1979_legacy_identity.hpp"
#include "j1979_native_report_detail.hpp"
#include "j1979_policy.h"
#include <algorithm>
#include <array>
#include <map>
#include <set>

namespace cd::legacy::history {
namespace {
using json::Value;
using Object=Value::Object;
using Array=Value::Array;
using cd::network::history::detail::require;
using cd::network::history::detail::bytes;
using cd::network::history::detail::compare;
Value obj(Object fields){return Value{std::move(fields)};}
Value arr(Array rows){return Value{std::move(rows)};}
Value str(std::string_view s){return Value{std::string(s)};}
Value num(std::uint64_t n){return Value{json::Integer{std::to_string(n)}};}
Value flag(bool b){return Value{b};}
Value nullable(std::string_view s){return s.empty()?Value{}:str(s);}
const Value* property(const Value& v,std::string_view key){
    std::string camel;bool upper=false;
    for(char c:key){
        if(c=='_'){upper=true;continue;}
        if(upper&&c>='a'&&c<='z')c=static_cast<char>(c-'a'+'A');
        upper=false;camel+=c;
    }
    const auto* a=v.find(key);const auto* b=camel==key?nullptr:v.find(camel);
    require(!a||!b,"Ambiguous historical maintenance field");return a?a:b;
}
const Value& optional(const Value& v,std::string_view key){
    static const Value nil{};const auto* p=property(v,key);return p?*p:nil;
}
const Value& field(const Value& v,std::string_view key){
    const auto* p=property(v,key);require(p,"Missing historical maintenance field");return *p;
}
std::string text(const Value& v,std::size_t maximum){
    if(v.null())return {};
    const auto& s=v.string();require(!s.empty()&&s.size()<=maximum,"Historical maintenance text limit");return s;
}
std::string addr(unsigned n){return hex(Data{static_cast<std::uint8_t>(n)});}
unsigned source(const Value& v){
    const auto b=bytes(v,1);require(b.size()==1&&valid_source(b[0]),"Invalid historical responder");return b[0];
}
std::set<unsigned> sources(const Value& v){
    std::set<unsigned> out;
    require(v.array().size()>=1&&v.array().size()<=32,"Historical responder scope limit");
    for(const auto& s:v.array())require(out.insert(source(s)).second,"Duplicate historical responder");
    return out;
}
Array source_rows(const std::set<unsigned>& values){
    Array out;for(auto s:values)out.push_back(str(addr(s)));return out;
}
Protocol protocol(const Value& v,bool driver){
    if(driver){
        const auto& s=v.string();
        if(s=="Pwm")return Protocol::pwm;
        if(s=="Vpw")return Protocol::vpw;
        if(s=="Iso9141")return Protocol::iso9141;
        if(s=="KwpSlow")return Protocol::kwp_slow;
        if(s=="KwpFast")return Protocol::kwp_fast;
        throw std::invalid_argument("Unknown historical J2534 variant");
    }
    const auto n=v.unsigned_integer();require(n>=1&&n<=5,"Invalid historical legacy variant");
    return static_cast<Protocol>(n);
}
MutationOperation operation(const Value& v){
    const auto& s=v.string();
    if(s=="clear"||s=="CLEAR"||s=="Clear")return MutationOperation::clear;
    if(s=="evap"||s=="EVAP_PREPARATION"||s=="EvapPreparation")return MutationOperation::evap;
    throw std::invalid_argument("Invalid historical legacy operation");
}
enum class Platform {elm,android,j2534};
std::string_view platform_name(Platform p){
    return p==Platform::elm?"ELM":p==Platform::android?"ANDROID":"J2534";
}
struct Scope {
    MutationPlan plan;
    Platform platform{};
    const Value* body{};
};
void no_claims(const Value& v){
    for(auto key:{"whole_vehicle_coverage_verified","physical_vehicle_verified","leak_test_performed",
        "evidence_authenticated","permits_automatic_retry","physically_addressed","permits_programming"})
        if(const auto* p=property(v,key))require(!p->boolean(),"Historical report claims unsupported authority");
}
Scope scope(const Value& root,bool baseline){
    require(root.at("schema").string()==(baseline?"camarodiag-j1979-legacy-baseline-v1":
        "camarodiag-functional-j1979-legacy-mutation-v1"),"Unsupported historical legacy maintenance schema");
    const auto* android_body=root.find("legacyMutation");const auto* driver_body=root.find("result");
    const bool android=android_body||property(root,"elm_protocol"),driver=driver_body!=nullptr;
    require(!android||!driver,"Ambiguous historical maintenance envelope");
    Scope out{};out.platform=android?Platform::android:driver?Platform::j2534:Platform::elm;
    require(!android||baseline||android_body,"Missing Android legacy maintenance result");
    out.body=android&&!baseline?android_body:driver?driver_body:&root;
    const auto& body=*out.body;const auto& meta=android?body:root;
    no_claims(root);no_claims(body);
    auto& p=out.plan;
    p.protocol=protocol(android?field(body,"elm_protocol"):field(root,"variant"),driver);
    const auto& primary=optional(root,"expected_vin");
    p.vin=(primary.null()&&android?field(root,"selected_vin"):primary).string();
    p.operation=operation(field(meta,"operation"));
    p.engine=source(field(meta,"engine_source"));p.sources=sources(field(meta,"documented_sources"));
    p.budget_ms=60000;
    require(p.valid(),"Invalid historical maintenance scope");
    const auto& declared=optional(body,"expected_sources");
    if(!declared.null())require(sources(declared)==p.sources,"Conflicting historical responder scope");
    if(android&&!baseline){
        require(protocol(field(root,"legacy_protocol"),false)==p.protocol&&
            operation(field(root,"legacy_operation"))==p.operation&&
            source(field(root,"legacy_engine_source"))==p.engine&&
            sources(field(root,"legacy_documented_sources"))==p.sources,"Android outer maintenance scope differs");
    }
    const auto& scoped=optional(meta,"scope");
    require(!scoped.null()&&scoped.string()=="FUNCTIONAL_VEHICLE_WIDE","Historical legacy operation scope is not functional");
    return out;
}
using Groups=std::map<unsigned,std::vector<Data>>;
struct Observation{
    std::string stage,command,raw,tx,error;
    bool has_raw{},has_tx{};
    std::vector<Frame> frames;
    Groups groups;
    bool operator==(const Observation&)const=default;
};
bool positive(const std::vector<Data>& rows,const Data& wanted){return rows.size()==1&&rows.front()==wanted;}
const std::vector<Data>& at(const Groups& groups,unsigned n){
    static const std::vector<Data> empty;const auto i=groups.find(n);return i==groups.end()?empty:i->second;
}
std::vector<unsigned> ordered_sources(const std::vector<Frame>& frames){
    std::vector<unsigned> out;
    for(const auto& f:frames)if(std::find(out.begin(),out.end(),f.source)==out.end())out.push_back(f.source);
    return out;
}
Observation parse(const Value& v,const Scope& s,std::size_t& total){
    Observation o{};o.stage=field(v,"stage").string();o.command=field(v,"request_hex").string();
    const auto request=bytes(field(v,"request_hex"),7);
    require(!request.empty()&&hex(request)==o.command,"Noncanonical legacy maintenance request");
    const auto mutation=s.plan.operation==MutationOperation::clear?Data{4}:Data{8,1,0,0,0,0,0};
    require(o.stage=="MUTATION"?request==mutation:read_allowed(request),"Historical maintenance request outside recipe");
    o.error=text(optional(v,"error"),256);
    const auto& raw=optional(v,"raw_response");o.has_raw=!raw.null();
    const auto& tx=optional(v,"tx_hex");o.has_tx=!tx.null();
    if(o.has_raw){
        o.raw=raw.string();require(o.raw.size()<=32768&&total<=1024u*1024u-o.raw.size(),"Historical legacy transcript bound");
        total+=o.raw.size();
    }
    if(o.has_tx){const auto b=bytes(tx,10);require(b==driver_request_frame(s.plan.protocol,request),"Driver legacy transmitted bytes differ");o.tx=hex(b);}
    if(s.platform==Platform::j2534){
        const auto& rows=field(v,"replies").array();require(rows.size()<=128&&(o.has_tx||rows.empty()),"Driver replies without request");
        for(const auto& f:rows)o.frames.push_back(inspect_driver_frame(f,s.plan.protocol,source(field(f,"source"))));
    }else{
        if(o.has_raw){
            try{o.frames=o.stage=="MUTATION"?mutation_response_frames(s.plan,request,o.raw):frames(s.plan.protocol,o.command,o.raw);}
            catch(const std::invalid_argument&){
                require(!o.error.empty()||o.stage=="MUTATION","Unrecorded malformed legacy reply");
                if(o.error.empty())o.error="INVALID_MUTATION_RESPONSE";
            }
        }
        if(s.platform==Platform::android){
            const auto& rows=field(v,"sources").array();require(rows.size()<=128,"Android historical source count");
            std::vector<unsigned> saved;for(const auto& f:rows)saved.push_back(source(f));
            require(saved==ordered_sources(o.frames),"Android legacy source order differs from frames");
        }else{
            const auto& rows=field(v,"frames").array();
            require(rows.size()==o.frames.size(),"Legacy maintenance frame count differs");
            for(std::size_t i=0;i<rows.size();++i){
                const auto& f=rows[i];
                require(source(field(f,"source"))==o.frames[i].source&&bytes(field(f,"raw_hex"),11)==o.frames[i].raw&&
                    bytes(field(f,"payload_hex"),7)==o.frames[i].payload,"Legacy maintenance frame differs from transcript");
            }
        }
    }
    for(const auto& f:o.frames)o.groups[f.source].push_back(f.payload);
    return o;
}
using Request=std::pair<std::string_view,std::string_view>;
struct Recipe{std::vector<Request> rows;std::size_t boundary{};};
Recipe recipe(MutationOperation operation,bool fresh){
    Recipe p{{{"SCOPE","0100"},{"IDENTITY","0901"},{"IDENTITY","0902"}},0};
    if(operation==MutationOperation::clear)p.rows.insert(p.rows.end(),{{"BEFORE","03"},{"BEFORE","07"},{"BEFORE","0A"},{"BEFORE","0101"}});
    else p.rows.push_back({"SUPPORT","08000000000000"});
    p.rows.insert(p.rows.end(),{{"SCOPE","0100"},{"STATIONARY","010D"},{"STATIONARY","010C"}});
    p.boundary=p.rows.size();
    if(fresh)p.rows.push_back({"SCOPE","0100"});
    p.rows.insert(p.rows.end(),{{"STATIONARY","010D"},{"STATIONARY","010C"},
        {"MUTATION",operation==MutationOperation::clear?"04":"08010000000000"}});
    if(operation==MutationOperation::clear)p.rows.insert(p.rows.end(),{{"AFTER","03"},{"AFTER","07"},{"AFTER","0A"},{"AFTER","0101"}});
    return p;
}
std::optional<std::vector<std::string>> dtcs(unsigned mode,const std::vector<Data>& rows){
    if(rows.empty())return {};
    std::vector<std::string> out;
    for(const auto& r:rows){
        if(r.size()!=7||r[0]!=mode+64)return {};
        for(unsigned i=1;i<7;i+=2)if(r[i]||r[i+1]){
            char code[6]{};require(cd_j1979_native_dtc_code(r.data()+i,code),"Invalid historical legacy DTC");
            if(std::find(out.begin(),out.end(),code)==out.end())out.emplace_back(code);
        }
    }
    return out;
}
DtcSnapshot empty_snapshot(const MutationPlan& p){
    DtcSnapshot out;
    for(auto s:p.sources)for(auto mode:{3u,7u,10u})out[s][mode]=std::nullopt;
    return out;
}
Value snapshot_json(const DtcSnapshot& rows){
    Object out;
    for(const auto& [s,modes]:rows){
        Object mode_rows;
        for(const auto& [m,codes]:modes){
            Array a;if(codes)for(const auto& code:*codes)a.push_back(str(code));
            mode_rows.emplace(addr(m),codes?arr(std::move(a)):Value{});
        }
        out.emplace(addr(s),obj(std::move(mode_rows)));
    }
    return obj(std::move(out));
}
void readback(DtcSnapshot& dst,const Groups& g,const MutationPlan& p,std::string_view command){
    if(command!="03"&&command!="07"&&command!="0A")return;
    const auto mode=unhex(command).front();
    for(auto s:p.sources)dst.at(s).at(mode)=dtcs(mode,at(g,s));
}
bool scope_observed(const Groups& g,const MutationPlan& p){
    if(g.size()!=p.sources.size())return false;
    for(auto source:p.sources){
        const auto& r=at(g,source);
        if(r.size()!=1||r.front().size()!=6||r.front()[0]!=0x41||r.front()[1]!=0)return false;
    }
    return true;
}
bool known_sources(const Groups& g,const MutationPlan& p){
    return std::all_of(g.begin(),g.end(),[&](const auto& a){return p.sources.contains(a.first);});
}
bool stationary(const Groups& g,unsigned engine,std::string_view command){
    const auto expected=command=="010D"?Data{0x41,0x0d,0}:Data{0x41,0x0c,0,0};
    if(!positive(at(g,engine),expected))return false;
    for(const auto& [s,rows]:g)for(const auto& r:rows){
        if(r==expected)continue;
        if(s==engine||r.size()!=3||r[0]!=0x7f||r[1]!=1||(r[2]!=0x11&&r[2]!=0x12&&r[2]!=0x31))return false;
    }
    return true;
}
bool identity(const Groups& counts,const Groups& parts,const MutationPlan& p,std::string& observed){
    observed.clear();
    for(auto s:p.sources){
        const auto& c=at(counts,s);const auto& rows=at(parts,s);
        if(s!=p.engine&&c.empty()&&rows.empty())continue;
        if(c.size()!=1||!vin_fragment_count(c.front()))return false;
        const auto decoded=normalized_vin(rows);
        if(!decoded||*decoded!=p.vin)return false;
        if(s==p.engine)observed=*decoded;
    }
    return observed==p.vin;
}
bool stored_verified(const DtcSnapshot& snapshot,unsigned mode){
    return std::all_of(snapshot.begin(),snapshot.end(),[&](const auto& row){return row.second.at(mode).has_value();});
}
bool residual(const DtcSnapshot& snapshot){
    for(const auto& [s,modes]:snapshot){
        (void)s;
        for(auto mode:{3u,7u})if(const auto& codes=modes.at(mode);codes&&!codes->empty())return true;
    }
    return false;
}
Value observations_json(const std::vector<Observation>& rows){
    Array out;
    for(const auto& o:rows){
        Array frames;
        for(const auto& f:o.frames)frames.push_back(obj({{"source",str(addr(f.source))},{"raw_hex",str(hex(f.raw))},{"payload_hex",str(hex(f.payload))}}));
        out.push_back(obj({{"stage",str(o.stage)},{"request_hex",str(o.command)},
            {"raw_response",o.has_raw?str(o.raw):Value{}},{"tx_hex",o.has_tx?str(o.tx):Value{}},
            {"error",nullable(o.error)},{"frames",arr(std::move(frames))}}));
    }
    return arr(std::move(out));
}
struct Replay{
    std::vector<Observation> rows;
    std::size_t boundary{};
    bool attempted{},acknowledged{},fresh{};
    std::string observed,outcome,reason;
    DtcSnapshot before,after;
    std::map<unsigned,std::string> acks;
    Value json()const{
        Object a;
        for(const auto& [s,v]:acks)a.emplace(addr(s),str(v));
        return obj({{"observations",observations_json(rows)},{"boundary",num(boundary)},{"command_attempted",flag(attempted)},
            {"complete_acknowledgment",flag(acknowledged)},{"observed_vin",nullable(observed)},
            {"before_dtcs",snapshot_json(before)},{"after_dtcs",snapshot_json(after)},{"acknowledgments",obj(std::move(a))},
            {"outcome",str(outcome)},{"reason",nullable(reason)},{"fresh_scope_recorded",flag(fresh)}});
    }
};
Replay replay(const Scope& s,bool baseline){
    const auto& body=*s.body;const auto& p=s.plan;
    Replay r{};
    r.before=empty_snapshot(p);r.after=empty_snapshot(p);
    for(auto source:p.sources)r.acks[source]="NOT_TESTED";
    const auto& raw=field(body,"observations").array();require(raw.size()<=40,"Historical legacy observation count");
    std::size_t retained=0;
    for(const auto& row:raw)r.rows.push_back(parse(row,s,retained));
    const auto initial=recipe(p.operation,false);
    r.fresh=r.rows.size()>initial.boundary&&r.rows[initial.boundary].stage=="SCOPE";
    const auto plan=recipe(p.operation,r.fresh||s.platform==Platform::android);
    r.boundary=plan.boundary;
    const auto count=baseline?plan.boundary:plan.rows.size();
    require(r.rows.size()<=count,"Historical legacy operation contains retry or extra request");
    Groups vin_counts;
    auto ack_sources=p.sources;
    bool blocked=false;
    for(std::size_t i=0;i<r.rows.size();++i){
        const auto& o=r.rows[i];const auto& [stage,command]=plan.rows[i];
        require(!blocked&&o.stage==stage&&o.command==command,"Unexpected historical legacy operation sequence");
        if(stage!="MUTATION"&&(!o.error.empty()||!known_sources(o.groups,p))){blocked=true;continue;}
        if(stage=="SCOPE")blocked=!scope_observed(o.groups,p);
        else if(stage=="IDENTITY"){
            if(command=="0901")vin_counts=o.groups;
            else blocked=!identity(vin_counts,o.groups,p,r.observed);
        }else if(stage=="BEFORE"){
            readback(r.before,o.groups,p,command);
            if(command=="0101")blocked=!stored_verified(r.before,3);
        }else if(stage=="SUPPORT"){
            ack_sources.clear();
            for(const auto& [source,rows]:o.groups){
                if(rows.size()!=1||rows[0].size()!=7||rows[0][0]!=0x48||rows[0][1]||rows[0][2])blocked=true;
                else if(rows[0][3]&0x80)ack_sources.insert(source);
            }
            blocked=blocked||ack_sources!=std::set<unsigned>{p.engine};
        }else if(stage=="STATIONARY")blocked=!stationary(o.groups,p.engine,command);
        else if(stage=="MUTATION"){
            r.attempted=true;
            const bool clear=p.operation==MutationOperation::clear;
            const auto wanted=clear?Data{0x44}:Data{0x48,1,0,0,0,0,0};
            const auto mode=clear?4u:8u;
            r.acknowledged=o.error.empty();
            for(const auto& [source,rows]:o.groups){
                if(!ack_sources.contains(source))r.acknowledged=false;
                if(positive(rows,wanted))r.acks[source]="ACKNOWLEDGED";
                else if(rows.size()==1&&rows[0].size()==3&&rows[0][0]==0x7f&&rows[0][1]==mode&&rows[0][2]&&rows[0][2]!=0x78)
                    r.acks[source]="REFUSED_"+addr(rows[0][2]);
                else r.acks[source]="UNCERTAIN";
            }
            for(auto source:ack_sources)if(r.acks.at(source)!="ACKNOWLEDGED")r.acknowledged=false;
        }else if(stage=="AFTER")readback(r.after,o.groups,p,command);
    }
    r.outcome=field(body,"outcome").string();r.reason=text(optional(body,"reason"),512);
    const auto* attempted=property(body,"command_attempted");
    require((attempted?*attempted:field(body,"attempted")).boolean()==r.attempted,"Historical legacy attempted flag differs");
    require(compare(optional(body,"observed_vin"),nullable(r.observed)),"Historical legacy VIN claim differs");
    const auto proof=r.json();
    require(compare(field(body,"acknowledgments"),proof.at("acknowledgments"))&&
        compare(field(body,"before_dtcs"),proof.at("before_dtcs"))&&compare(field(body,"after_dtcs"),proof.at("after_dtcs")),
        "Historical legacy acknowledgment or DTC claim differs");
    if(baseline)require(r.outcome=="BASELINE_SAVED"&&r.reason.empty()&&!r.attempted&&r.rows.size()==plan.boundary&&!blocked&&r.observed==p.vin,
        "Historical baseline does not match complete admission");
    else if(!r.attempted)require(r.outcome=="NOT_TESTED","Unattempted historical maintenance claims success");
    else if(!r.acknowledged)require(r.outcome=="UNCERTAIN","Missing historical acknowledgment claims success");
    else if(p.operation==MutationOperation::evap){
        require(r.outcome=="ACKNOWLEDGED_PARTIAL"||(r.outcome=="ACKNOWLEDGED"&&r.reason=="LEAK_TEST_NOT_PERFORMED"),
            "EVAP preparation is not a leak test");
    }else if(r.outcome=="ACKNOWLEDGED_READBACK"){
        require(r.rows.size()==plan.rows.size()&&!blocked&&stored_verified(r.after,3)&&
            (residual(r.after)?r.reason=="DTCS_STILL_PRESENT":r.reason.empty()&&stored_verified(r.after,7)),
            "Historical legacy DTC readback exceeds evidence");
    }else require(r.outcome=="ACKNOWLEDGED_PARTIAL","Invalid historical acknowledged outcome");
    return r;
}
}
LegacyMaintenanceInspection inspect_historical_maintenance(const Value& final,const Value* baseline,
    std::optional<std::string> independent,std::string_view report_digest,std::string_view baseline_digest){
    const auto s=scope(final,false);
    require(!independent||*independent==s.plan.vin,"Independent maintenance VIN differs");
    const auto r=replay(s,false);
    bool baseline_consistent=false;
    std::string origin;
    const auto correlate=[&](const Value& source){
        const auto b=scope(source,true);
        require(b.plan==s.plan&&b.platform==s.platform,"Historical legacy baseline scope differs");
        const auto initial=replay(b,true);
        require(initial.rows.size()==r.boundary&&r.rows.size()>=initial.rows.size()&&
            std::equal(initial.rows.begin(),initial.rows.end(),r.rows.begin()),"Historical legacy baseline prefix differs");
        baseline_consistent=true;
    };
    if(baseline){correlate(*baseline);origin="SEPARATE_FILE";}
    else if(s.platform==Platform::android){
        if(const auto* b=final.find("legacyBaseline");b&&!b->null()){
            auto fields=b->object();fields["schema"]=str("camarodiag-j1979-legacy-baseline-v1");
            fields["expectedVin"]=str(s.plan.vin);correlate(obj(std::move(fields)));origin="EMBEDDED_COPY";
        }
    }
    Object proof=r.json().object();
    const auto add=[&](std::string_view key,Value value){proof.emplace(key,std::move(value));};
    add("schema",str("camarodiag-j1979-legacy-mutation-inspection-v1"));
    add("consistent",flag(true));add("platform",str(platform_name(s.platform)));
    add("variant",num(static_cast<unsigned>(s.plan.protocol)));
    add("operation",str(s.plan.operation==MutationOperation::clear?"clear":"evap"));
    add("documented_vin",str(s.plan.vin));add("engine_source",str(addr(s.plan.engine)));
    add("documented_sources",arr(source_rows(s.plan.sources)));
    add("baseline_consistent",flag(baseline_consistent));add("baseline_origin",nullable(origin));
    add("baseline_required_missing",flag(r.attempted&&!baseline_consistent));
    add("report_sha256",nullable(report_digest));add("baseline_sha256",nullable(baseline_digest));
    for(auto k:{"physical_vehicle_verified","whole_vehicle_coverage_verified","operator_consent_verified","durable_before_tx_verified",
        "evidence_authenticated","protocol_probe_independently_verified","leak_test_performed","permits_automatic_retry","permits_programming"})
        add(k,flag(false));
    return {obj(std::move(proof)),r.attempted,r.acknowledged,baseline_consistent};
}
}
