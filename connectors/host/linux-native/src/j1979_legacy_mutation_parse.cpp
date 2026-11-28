#include "j1979_legacy_mutation_inspection.hpp"
#include "native_json.hpp"
#include <algorithm>
#include <limits>
namespace cd::legacy {
namespace {
using Value=cd::json::Value;
void require(bool condition,const char* why){if(!condition)throw std::invalid_argument(why);}
unsigned number(const Value& v,unsigned maximum){
    const auto n=v.unsigned_integer();require(n<=maximum,"Mutation integer exceeds bound");return static_cast<unsigned>(n);
}
std::string text(const Value& v,size_t limit,bool nullable=false){
    if(v.null()&&nullable)return {};
    const auto& s=v.string();
    require(!s.empty()&&s.size()<=limit&&std::all_of(s.begin(),s.end(),[](unsigned char c){
        return (c>=32&&c<=126)||c=='\r'||c=='\n'||c=='\t';
    }),"Invalid mutation text");
    return s;
}
Data hex_data(const Value& v,size_t maximum){
    const auto s=text(v,maximum*2);
    require(s.size()%2==0,"Odd hex evidence");
    const auto b=unhex(s);require(hex(b)==s,"Noncanonical hex evidence");return b;
}
unsigned source(const Value& v){
    auto bytes=hex_data(v,1);
    require(bytes.size()==1&&valid_source(bytes[0]),"Invalid legacy source");return bytes[0];
}
unsigned source_key(std::string_view key){
    return source(Value{std::string(key)});
}
std::set<unsigned> source_set(const Value& v){
    const auto& a=v.array();require(a.size()<=128,"Too many sources");
    std::set<unsigned> result;
    for(const auto& item:a)require(result.insert(source(item)).second,"Duplicate source");
    return result;
}
DtcSnapshot read_snapshot(const Value& v,const MutationPlan& p){
    DtcSnapshot result;
    require(v.object().size()==p.sources.size(),"DTC snapshot source count");
    for(const auto& [key,obj]:v.object()){
        const auto s=source_key(key);require(p.sources.contains(s),"Unexpected DTC snapshot source");
        cd::json::fields(obj,{"03","07","0A"});
        for(unsigned mode:{3u,7u,10u}){
            const auto key=hex(std::array<uint8_t,1>{static_cast<uint8_t>(mode)});
            const auto& codes=obj.at(key);
            if(codes.null()){result[s][mode]={};continue;}
            require(codes.array().size()<=384,"Too many DTCs");
            std::vector<std::string> list;
            for(const auto& entry:codes.array()){
                auto code=text(entry,5);
                require(code.size()==5&&std::string_view("PCBU").find(code[0])!=std::string_view::npos&&
                    code[1]>='0'&&code[1]<='3'&&std::all_of(code.begin()+2,code.end(),[](char c){
                        return (c>='0'&&c<='9')||(c>='A'&&c<='F');
                    })&&std::find(list.begin(),list.end(),code)==list.end(),"Invalid DTC list");
                list.push_back(std::move(code));
            }
            result[s][mode]=std::move(list);
        }
    }
    return result;
}
MutationObservation read_row(const Value& v){
    cd::json::fields(v,{"stage","request_hex","raw_response","error","started_ms","ended_ms","transmission_attempted","frames"});
    MutationObservation row{};
    row.stage=text(v.at("stage"),24);row.command=text(v.at("request_hex"),14);
    row.raw=text(v.at("raw_response"),32768,true);row.error=text(v.at("error"),512,true);
    row.started_ms=number(v.at("started_ms"),3600000);row.ended_ms=number(v.at("ended_ms"),3600000);
    row.attempted=v.at("transmission_attempted").boolean();
    require(row.ended_ms>=row.started_ms&&row.attempted,"Invalid mutation timing or attempt");
    const auto& rows=v.at("frames").array();require(rows.size()<=128,"Too many legacy frames");
    for(const auto& f:rows){
        cd::json::fields(f,{"source","raw_hex","payload_hex"});
        row.frames.push_back({source(f.at("source")),hex_data(f.at("raw_hex"),11),hex_data(f.at("payload_hex"),7)});
    }
    return row;
}
}
MutationResult parse_mutation_report(std::string_view raw){
    const auto v=cd::json::parse(raw,mutation_report_max);
    cd::json::fields(v,{"schema","protocol","scope","transport","physically_addressed","expected_protocol","operation",
        "expected_vin","observed_vin","engine_source","expected_sources","unexpected_sources","outcome","reason",
        "baseline_saved","intent_saved","command_attempted","complete_acknowledgment","budget_ms","duration_ms",
        "physical_vehicle_verified","whole_vehicle_coverage_verified","leak_test_performed","permits_automatic_retry",
        "permits_programming","acknowledgments","before_dtcs","after_dtcs","observations"});
    require(v.at("schema").string()=="camarodiag-j1979-legacy-maintenance-linux-v1"&&
        v.at("protocol").string()=="SAE-J1979"&&v.at("scope").string()=="FUNCTIONAL_VEHICLE_WIDE"&&
        v.at("transport").string()=="ELM_SERIAL_LEGACY_HEADERED","Unsupported mutation report schema");
    for(auto flag:{"physically_addressed","physical_vehicle_verified","whole_vehicle_coverage_verified","leak_test_performed",
                   "permits_automatic_retry","permits_programming"})require(!v.at(flag).boolean(),"Unsupported mutation claim");
    MutationResult r{};
    auto& p=r.plan;
    p.protocol=static_cast<Protocol>(number(v.at("expected_protocol"),5));
    const auto operation=v.at("operation").string();
    require(operation=="CLEAR"||operation=="EVAP","Invalid mutation operation");
    p.operation=operation=="CLEAR"?MutationOperation::clear:MutationOperation::evap;
    p.vin=text(v.at("expected_vin"),17);p.engine=source(v.at("engine_source"));p.sources=source_set(v.at("expected_sources"));
    p.budget_ms=number(v.at("budget_ms"),60000);require(p.valid(),"Invalid legacy mutation scope");
    r.vin=text(v.at("observed_vin"),17,true);r.outcome=text(v.at("outcome"),32);r.reason=text(v.at("reason"),512,true);
    r.unexpected_sources=source_set(v.at("unexpected_sources"));
    r.baseline_saved=v.at("baseline_saved").boolean();r.intent_saved=v.at("intent_saved").boolean();
    r.attempted=v.at("command_attempted").boolean();r.complete_ack=v.at("complete_acknowledgment").boolean();
    r.elapsed_ms=number(v.at("duration_ms"),3600000);
    for(const auto& [key,value]:v.at("acknowledgments").object()){
        const auto s=source_key(key);require(p.sources.contains(s),"Unexpected acknowledged source");
        r.acknowledgments.emplace(s,text(value,32));
    }
    r.before=read_snapshot(v.at("before_dtcs"),p);r.after=read_snapshot(v.at("after_dtcs"),p);
    const auto& rows=v.at("observations").array();require(rows.size()<=40,"Mutation observation limit");
    size_t retained=0;
    for(const auto& value:rows){
        auto row=read_row(value);
        require(row.raw.size()<=1024*1024-retained&&row.ended_ms<=r.elapsed_ms&&
            (r.observations.empty()||row.started_ms>=r.observations.back().ended_ms),"Mutation observation time or aggregate limit");
        retained+=row.raw.size();r.observations.push_back(std::move(row));
    }
    return r;
}
}
