#include "j1979_native_live_inspection.hpp"
#include "j1979_native_report_detail.hpp"
#include "j1979_measurement.h"
#include <algorithm>
#include <cmath>
#include <iomanip>
#include <locale>
#include <map>
#include <sstream>

namespace cd::network::history {
namespace {
using namespace detail;
using Object=Value::Object;
using Array=Value::Array;
Value obj(Object v){return Value{std::move(v)};}
Value arr(Array v){return Value{std::move(v)};}
Value str(std::string_view v){return Value{std::string(v)};}
Value integer(std::uint64_t n){return Value{json::Integer{std::to_string(n)}};}
Value flag(bool n){return Value{n};}
Value real(double n){
    require(std::isfinite(n),"Nonfinite CL/1 scalar");
    std::ostringstream s;s.imbue(std::locale::classic());
    s<<std::scientific<<std::setprecision(16)<<n;
    return Value{json::Decimal{s.str()}};
}
bool contextual(unsigned pid){
    return pid==0x0b||pid==0x10||pid==0x44||pid==0x4f||pid==0x50||
        (pid>=6&&pid<=9)||(pid>=0x24&&pid<=0x2b)||(pid>=0x34&&pid<=0x3b)||
        (pid>=0x55&&pid<=0x58);
}
std::string hex(unsigned n,unsigned width=1){
    constexpr char digits[]="0123456789ABCDEF";
    std::string out;
    do{out.insert(out.begin(),digits[n&15]);n>>=4;}while(n||out.size()<width);
    return out;
}
struct Channel{
    unsigned requested{},attempted{},count{};
    std::string name,unit;
    double minimum{},maximum{},mean{},last{};
    unsigned last_ms{};
    std::map<std::string,unsigned> outcomes;
    void add(std::string_view outcome,const j1979_measurement* measured,unsigned elapsed){
        require(attempted<requested,"Scalar sample count exceeds selected plan");
        ++attempted;++outcomes[std::string(outcome)];
        if(!measured)return;
        const auto n=measured->number;
        require(std::isfinite(n)&&measured->name&&measured->unit,"Invalid scalar result");
        require(!count||(name==measured->name&&unit==measured->unit),"Scalar definition changed");
        name=measured->name;unit=measured->unit;
        if(!count)minimum=maximum=mean=n;
        else{minimum=std::min(minimum,n);maximum=std::max(maximum,n);mean+=(n-mean)/(count+1);}
        ++count;last=n;last_ms=elapsed;
    }
    Value report(unsigned pid)const{
        Object states;
        for(const auto& [k,v]:outcomes)states.emplace(k,integer(v));
        return obj({{"pid",integer(pid)},{"name",count?str(name):Value{}},{"unit",count?str(unit):Value{}},
            {"requested",integer(requested)},{"attempted",integer(attempted)},{"count",integer(count)},
            {"missing",integer(requested-count)},{"minimum",count?real(minimum):Value{}},
            {"maximum",count?real(maximum):Value{}},{"mean",count?real(mean):Value{}},
            {"last_verified_value",count?real(last):Value{}},
            {"last_verified_elapsed_ms",count?integer(last_ms):Value{}},{"outcomes",obj(std::move(states))}});
    }
};
bool valid_digest(std::string_view s){
    return s.size()==64&&std::all_of(s.begin(),s.end(),[](char c){return (c>='0'&&c<='9')||(c>='a'&&c<='f');});
}
std::string cell(const Value& v,bool quote=false){
    if(v.null())return {};
    if(const auto* n=std::get_if<json::Integer>(&v.data))return n->text;
    if(const auto* n=std::get_if<json::Decimal>(&v.data))return n->text;
    const auto& s=v.string();
    if(!quote)return s;
    std::string out="\"";
    for(char c:s){if(c=='"')out+='"';out+=c;}
    return out+"\"";
}
}
NativeLiveProjection project_cl1_scalar(std::span<const std::uint8_t> capture,Pair pair,
    std::string_view vin,std::string_view digest){
    require(valid_digest(digest),"Independent capture digest required");
    const auto native=project_cl1_live(capture,pair,vin);
    const auto& root=native.report;
    const bool identity=root.at("identity_rechecked").boolean();
    const bool completed=root.at("source_completed").boolean();
    const auto requested=static_cast<unsigned>(root.at("requested_cycles").unsigned_integer());
    std::map<unsigned,Channel> channels;
    std::vector<unsigned> order;
    for(const auto& s:root.at("summary").array()){
        const auto pid=static_cast<unsigned>(s.at("pid").unsigned_integer());
        require(channels.try_emplace(pid).second,"Duplicate scalar PID");
        channels.at(pid).requested=requested;order.push_back(pid);
    }
    require(!order.empty(),"Missing scalar PID selection");
    Array rows,summary;
    for(const auto& s:root.at("samples").array()){
        const auto& status=s.at("status").string();
        // A rejected event is retained in the richer raw projection, but is
        // not a completed application observation in the portable schema.
        if(status=="FAILED")continue;
        require(status=="RAW"||status=="NEGATIVE"||status=="TIMEOUT","Unexpected scalar source status");
        const auto pid=static_cast<unsigned>(s.at("pid").unsigned_integer());
        const auto elapsed=static_cast<unsigned>(s.at("elapsed_ms").unsigned_integer());
        const auto cycle=s.at("cycle").unsigned_integer();
        require(pid==order[rows.size()%order.size()]&&cycle==rows.size()/order.size()+1,
            "Scalar samples differ from selected order");
        const auto& response=s.at("response_hex");
        j1979_measurement measurement{};
        bool decoded=false;
        if(identity&&status=="RAW"&&!contextual(pid)){
            const auto q=bytes(s.at("request_hex"),2);
            const auto r=bytes(response,4095);
            decoded=j1979_measurement_decode(q.data(),q.size(),r.data(),r.size(),&measurement)&&
                measurement.interpreted&&!measurement.context_required;
        }
        const std::string meaning=status!="RAW"?status:!identity?"IDENTITY_UNVERIFIED":
            contextual(pid)?"CONTEXT_REQUIRED":!decoded?"RAW_UNINTERPRETED":"DOCUMENTED_SCALAR";
        channels.at(pid).add(meaning,decoded?&measurement:nullptr,elapsed);
        rows.push_back(obj({{"cycle",integer(cycle)},{"pid",integer(pid)},{"elapsed_ms",integer(elapsed)},
            {"source_status",str(status)},{"response_hex",response},{"interpretation",str(meaning)},
            {"name",decoded?str(measurement.name):Value{}},{"unit",decoded?str(measurement.unit):Value{}},
            {"number",decoded?real(measurement.number):Value{}}}));
    }
    bool complete=identity&&completed;
    for(auto pid:order){
        const auto& c=channels.at(pid);
        complete=complete&&c.count==requested;
        summary.push_back(c.report(pid));
    }
    return {obj({{"schema",str("camarodiag-j1979-cl1-scalar-projection-v1")},
        {"vin",str(vin)},{"tx",integer(pair.tx)},{"rx",integer(pair.rx)},
        {"identity_rechecked",flag(identity)},{"source_completed",flag(completed)},
        {"numeric_projection_complete",flag(complete)},{"physical_vehicle_verified",flag(false)},
        {"authenticated",flag(false)},{"permits_programming",flag(false)},
        {"capture_sha256",str(digest)},{"expected_vin_independently_supplied",flag(true)},
        {"samples",arr(std::move(rows))},{"channels",arr(std::move(summary))}}),complete};
}
std::string cl1_scalar_csv(const NativeLiveProjection& p){
    const auto& root=p.report;
    require(root.at("schema").string()=="camarodiag-j1979-cl1-scalar-projection-v1","Invalid scalar schema");
    std::string out="cycle,pid_hex,elapsed_ms,source_status,interpretation,name,number,unit,response_hex,vin,tx_can_id,rx_can_id,capture_sha256\r\n";
    for(const auto& row:root.at("samples").array()){
        out+=cell(row.at("cycle"))+","+hex(static_cast<unsigned>(row.at("pid").unsigned_integer()),2)+","+
            cell(row.at("elapsed_ms"))+","+cell(row.at("source_status"))+","+cell(row.at("interpretation"))+","+
            cell(row.at("name"),true)+","+cell(row.at("number"))+","+cell(row.at("unit"),true)+","+
            cell(row.at("response_hex"))+","+root.at("vin").string()+","+
            hex(static_cast<unsigned>(root.at("tx").unsigned_integer()))+","+
            hex(static_cast<unsigned>(root.at("rx").unsigned_integer()))+","+root.at("capture_sha256").string()+"\r\n";
        require(out.size()<=40u*1024u*1024u,"Scalar CSV byte limit");
    }
    return out;
}
bool verify_cl1_scalar(const NativeLiveProjection& expected,const json::Value& saved){
    // Exact schema, field set, counts, flags, bytes and scope. Only finite
    // engineering numbers admit the existing cross-host numeric tolerance.
    no_claims(saved);
    return compare(saved,expected.report);
}
}
