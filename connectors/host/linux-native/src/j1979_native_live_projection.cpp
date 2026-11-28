#include "j1979_native_live_inspection.hpp"
#include "j1979_native_report_detail.hpp"
#include "j1979_context.hpp"
#include "j1979_multi.hpp"
#include "j1979_measurement.h"
#include "j1979_status.hpp"
#include <cmath>
#include <iomanip>
#include <locale>
#include <map>
#include <set>
#include <sstream>
#include <tuple>

namespace cd::network::history {
namespace {
using namespace detail;
using Object=Value::Object;
using Array=Value::Array;
Value obj(Object o){return Value{std::move(o)};}
Value arr(Array a){return Value{std::move(a)};}
Value text(std::string_view s){return Value{std::string(s)};}
Value integer(std::uint64_t n){return Value{json::Integer{std::to_string(n)}};}
Value flag(bool b){return Value{b};}
Value number(double n){
    require(std::isfinite(n),"Nonfinite native engineering value");
    std::ostringstream s;s.imbue(std::locale::classic());s<<std::scientific<<std::setprecision(16)<<n;
    return Value{json::Decimal{s.str()}};
}
unsigned small(const Value& v,unsigned bound){
    const auto n=v.unsigned_integer();require(n<=bound,"Native projection field exceeds bound");return static_cast<unsigned>(n);
}
struct Row {
    Bytes request,response;
    const Value* source{};
    unsigned cycle{},pid{},end{};
    std::string phase,status;
    bool accepted()const{return status=="PASSED"||status=="RAW";}
    cd::j1979::Observation observation()const{
        return {request,response,accepted(),phase=="INVENTORY"||phase=="CONTEXT"};
    }
};
struct Channel {
    std::string name,unit,scale;
    std::optional<double> value;
    std::optional<std::uint64_t> raw;
};
Value channel(const Channel& c){
    return obj({{"name",text(c.name)},{"unit",text(c.unit)},{"number",c.value?number(*c.value):Value{}},
        {"raw_encoded",c.raw?integer(*c.raw):Value{}},{"scale_source",text(c.scale)}});
}
std::vector<Channel> engineering(const Row& r,const cd::j1979::Context& context,bool identity){
    std::vector<Channel> out;
    if(!r.accepted()||r.phase!="SAMPLE")return out;
    auto observed=r.observation();
    // If identity is not rechecked, retain bytes, but never attribute
    // engineering values or a configuration from a possibly changed ECU.
    if(!identity)return out;
    if(cd::j1979::trim_pid(r.pid)){
        const auto decoded=cd::j1979::trims(observed,context.layout);
        if(!decoded)return out;
        for(const auto& c:decoded->signals)
            out.push_back({c.name,"%",c.number?"SAE_TRIM":"UPPER_BANK_UNVERIFIED",c.number,c.raw});
        return out;
    }
    if(cd::j1979::dependency(r.pid)){
        const auto decoded=cd::j1979::scale(observed,context.scaling);
        if(!decoded)return out;
        for(const auto& c:decoded->signals)
            out.push_back({c.name,c.unit,std::string(c.scale_source),c.number,c.raw});
        return out;
    }
    if(cd::j1979::multi_width(r.pid)){
        const auto decoded=cd::j1979::multi_signal(observed,&context.scaling);
        if(!decoded)return out;
        if(cd::j1979::status_width(r.pid)&&!cd::j1979::status_record(observed))return out;
        for(const auto& c:decoded->signals)
            out.push_back({std::string(c.name),std::string(c.unit),"DOCUMENTED_DEFINITION",c.number,c.raw});
        return out;
    }
    j1979_measurement m{};
    if(j1979_measurement_decode(r.request.data(),r.request.size(),r.response.data(),r.response.size(),&m)
        &&m.interpreted&&m.name&&m.unit)
        out.push_back({m.name,m.unit,"DOCUMENTED_DEFINITION",m.number,{}});
    return out;
}
struct Stats {
    unsigned count{},last_ms{},observed{};
    double minimum{},maximum{},last{};
    long double sum{};
    void add(const Channel& s,unsigned end){
        ++observed;if(!s.value)return;
        const auto n=*s.value;
        require(std::isfinite(n),"Nonfinite live projection");
        minimum=count?std::min(minimum,n):n;maximum=count?std::max(maximum,n):n;
        ++count;sum+=n;last=n;last_ms=end;
    }
    Object fields(unsigned attempts)const{
        require(attempts>=observed&&observed>=count,"Inconsistent channel accounting");
        return {{"attempted",integer(attempts)},{"observed",integer(observed)},
            {"count",integer(count)},{"missing",integer(attempts-count)},
            {"minimum",count?number(minimum):Value{}},{"maximum",count?number(maximum):Value{}},
            {"mean",count?number(static_cast<double>(sum/count)):Value{}},
            {"last_verified_value",count?number(last):Value{}},
            {"last_verified_elapsed_ms",count?integer(last_ms):Value{}}};
    }
};
std::string csv_cell(const Value& v){
    if(v.null())return {};
    if(const auto* s=std::get_if<std::string>(&v.data)){
        std::string out="\"";
        for(char c:*s){if(c=='"')out+='"';out+=c;}
        return out+"\"";
    }
    if(const auto* n=std::get_if<json::Integer>(&v.data))return n->text;
    if(const auto* n=std::get_if<json::Decimal>(&v.data))return n->text;
    throw std::invalid_argument("Unexpected live CSV cell type");
}
void csv_line(std::ostringstream& csv,const Value& row,const Value* signal){
    bool first=true;
    const auto append=[&](const Value& v){if(!first)csv<<',';first=false;csv<<csv_cell(v);};
    for(auto key:{"cycle","pid_hex","started_elapsed_ms","elapsed_ms","duration_ms","status"})append(row.at(key));
    for(auto key:{"number","unit"})append(signal?signal->at(key):Value{});
    for(auto key:{"request_hex","response_hex","detail"})append(row.at(key));
    for(auto key:{"name","raw_encoded","scale_source"})append(signal?signal->at(key):Value{});
    csv<<'\n';
}
}
NativeLiveProjection project_native_live(const json::Value& source,Pair pair,std::string_view vin){
    const auto verified=inspect_native_live(source,pair,vin);
    const auto root=json::parse_report(verified.canonical,64u*1024u*1024u);
    const auto& steps=root.at("steps").array();
    const bool identity=verified.initial_identity&&verified.final_identity;
    std::vector<Row> rows;rows.reserve(steps.size());
    for(const auto& saved:steps){
        Row row{};
        row.request=bytes(saved.at("request_hex"),2);
        if(!saved.at("response_hex").null())row.response=bytes(saved.at("response_hex"),4095);
        row.source=&saved;row.cycle=small(saved.at("cycle"),1000);row.pid=row.request.at(1);
        row.end=small(saved.at("elapsed_ms"),3600000);row.phase=saved.at("phase").string();row.status=saved.at("status").string();
        rows.push_back(std::move(row));
    }
    std::vector<cd::j1979::Observation> observations;
    observations.reserve(rows.size());
    for(const auto& row:rows)if(row.phase=="INVENTORY"||row.phase=="CONTEXT")observations.push_back(row.observation());
    const auto context=identity?cd::j1979::derive_context(observations):cd::j1979::Context{};
    std::map<unsigned,std::map<std::string,unsigned>> statuses;
    std::map<unsigned,unsigned> attempts,interpreted;
    using Key=std::tuple<unsigned,std::string,std::string>;
    std::map<Key,Stats> stats;
    Array samples,summary,channels;
    for(const auto& r:rows){
        if(r.phase!="SAMPLE")continue;
        ++attempts[r.pid];++statuses[r.pid][r.status];
        const auto decoded=engineering(r,context,identity);
        Array signals;bool any=false;
        for(const auto& c:decoded){
            signals.push_back(channel(c));any=any||c.value.has_value();
            auto& stat=stats[{r.pid,c.name,c.unit}];stat.add(c,r.end);
        }
        if(any)++interpreted[r.pid];
        auto fields=r.source->object();
        // Preserve authoritative raw bytes and response status, replace only
        // the non-authoritative display projection in this separate report.
        fields.erase("measurement");fields.erase("phase");
        fields.emplace("pid",integer(r.pid));
        constexpr char digits[]="0123456789ABCDEF";
        std::string pid_hex{digits[r.pid>>4],digits[r.pid&15]};
        fields.emplace("pid_hex",text(pid_hex));fields.emplace("signals",arr(std::move(signals)));
        fields.emplace("engineering_verified",flag(any));samples.push_back(obj(std::move(fields)));
    }
    bool all_selected=true;
    for(const auto& p:root.at("selected_pids").array()){
        const auto pid=small(p,255);Object status;
        for(const auto& [k,n]:statuses[pid])status.emplace(k,integer(n));
        const auto seen=attempts[pid],values=interpreted[pid];
        all_selected=all_selected&&values!=0;
        summary.push_back(obj({{"pid",integer(pid)},{"attempted",integer(seen)},{"engineering_samples",integer(values)},
            {"uninterpreted_or_missing",integer(seen-values)},{"status_counts",obj(std::move(status))}}));
    }
    for(const auto& [key,stat]:stats){
        const auto& [pid,name,unit]=key;auto fields=stat.fields(attempts.at(pid));
        fields.emplace("pid",integer(pid));fields.emplace("name",text(name));fields.emplace("unit",text(unit));
        channels.push_back(obj(std::move(fields)));
    }
    const bool complete=verified.completed&&identity&&all_selected;
    auto projection=obj({{"schema",text("camarodiag-j1979-native-live-projection-v1")},
        {"source_transport",text(verified.transport)},{"vin",text(vin)},{"tx_can_id",root.at("tx_can_id")},
        {"rx_can_id",root.at("rx_can_id")},{"source_completed",flag(verified.completed)},{"identity_rechecked",flag(identity)},
        {"numeric_projection_complete",flag(complete)},{"all_selected_pids_interpreted",flag(all_selected)},
        {"source_stop_reason",root.at("stop_reason")},{"completed_cycles",integer(verified.cycles)},
        {"requested_cycles",root.at("requested_cycles")},{"summary",arr(std::move(summary))},
        {"channel_summary",arr(std::move(channels))},{"samples",arr(std::move(samples))},
        {"physical_vehicle_verified",flag(false)},{"authenticated",flag(false)},{"permits_programming",flag(false)},
        {"time_aligned",flag(false)},{"diagnostic_verdict",Value{}}});
    return {std::move(projection),complete};
}
std::string native_live_csv(const NativeLiveProjection& p){
    std::ostringstream csv;csv.imbue(std::locale::classic());
    csv<<"cycle,pid_hex,started_elapsed_ms,elapsed_ms,duration_ms,status,value,unit,request_hex,response_hex,detail,signal_name,raw_encoded,scale_source\n";
    for(const auto& row:p.report.at("samples").array()){
        const auto& signals=row.at("signals").array();
        if(signals.empty())csv_line(csv,row,nullptr);
        else for(const auto& signal:signals)csv_line(csv,row,&signal);
    }
    auto result=csv.str();require(result.size()<=64u*1024u*1024u,"Native live CSV size limit");
    return result;
}
}
