#include "j1979_native_live_inspection.hpp"
#include "j1979_native_report_detail.hpp"
#include <algorithm>
#include <cmath>
#include <iomanip>
#include <locale>
#include <iterator>
#include <map>
#include <set>
#include <sstream>
#include <tuple>
namespace cd::network::history {
namespace {
using namespace detail;
using Object=Value::Object;
using Array=Value::Array;
using Key=std::tuple<unsigned,std::string,std::string>;
Value obj(Object o){return Value{std::move(o)};}
Value arr(Array a){return Value{std::move(a)};}
Value str(std::string_view s){return Value{std::string(s)};}
Value num(std::size_t n){return Value{json::Integer{std::to_string(n)}};}
Value flag(bool b){return Value{b};}
Value real(double n){
    require(std::isfinite(n),"Nonfinite native comparison");
    std::ostringstream o;o.imbue(std::locale::classic());o<<std::scientific<<std::setprecision(16)<<n;
    return Value{json::Decimal{o.str()}};
}
bool digest_ok(std::string_view s){
    return s.size()==64&&std::all_of(s.begin(),s.end(),[](char c){return (c>='0'&&c<='9')||(c>='a'&&c<='f');});
}
unsigned pid(const Value& row){
    auto n=row.at("pid").unsigned_integer();
    require(n>0&&n<256&&n%32!=0,"Invalid native comparison PID");
    return static_cast<unsigned>(n);
}
std::string scale_key(const Value& signal){
    const auto& source=signal.at("scale_source").string();
    const auto& config=signal.at("scale_configuration");
    if(source=="ECU_CONFIGURATION"){
        require(!config.null()&&bytes(config,4).size()==4,"Missing verified ECU scaling configuration");
        return source+":"+config.string();
    }
    require(config.null(),"Unexpected configuration on context-free scale");
    return source;
}
struct Channel {
    const Value* summary{};
    std::set<std::string> scales;
    unsigned usable{};
};
struct Index {
    std::map<unsigned,const Value*> pids;
    std::map<Key,Channel> channels;
    bool identity{},complete{};
};
Index index(const NativeLiveProjection& r){
    const auto& body=r.report;
    require(body.at("schema").string()=="camarodiag-j1979-native-live-projection-v1","Unexpected native live projection schema");
    no_claims(body);
    Index out;
    out.identity=body.at("identity_rechecked").boolean();
    out.complete=r.complete&&body.at("numeric_projection_complete").boolean();
    require(r.complete==body.at("numeric_projection_complete").boolean(),"Projection completion differs");
    const auto& pids=body.at("summary").array();
    require(!pids.empty()&&pids.size()<=32,"Native projection PID limit");
    for(const auto& row:pids){
        require(out.pids.emplace(pid(row),&row).second,"Duplicate native PID summary");
        const auto wanted=row.at("requested_samples").unsigned_integer();
        const auto tried=row.at("attempted").unsigned_integer();
        const auto usable=row.at("engineering_samples").unsigned_integer();
        const auto full=row.at("fully_interpreted_samples").unsigned_integer();
        require(wanted>=tried&&tried>=usable&&usable>=full&&
            row.at("unattempted_samples").unsigned_integer()==wanted-tried&&
            row.at("partially_interpreted_samples").unsigned_integer()==usable-full&&
            row.at("uninterpreted_or_missing").unsigned_integer()==tried-usable,
            "Invalid native projection coverage");
        if(out.complete)require(wanted==full,"Complete projection has missing numeric samples");
    }
    const auto& channels=body.at("channel_summary").array();
    require(channels.size()<=320,"Native projection channel limit");
    for(const auto& row:channels){
        auto p=pid(row);
        require(out.pids.contains(p),"Channel outside selected PIDs");
        Key k{p,row.at("name").string(),row.at("unit").string()};
        require(out.channels.emplace(std::move(k),Channel{&row,{},0}).second,"Duplicate native channel summary");
    }
    const auto& samples=body.at("samples").array();
    require(samples.size()<=10000,"Native comparison sample bound");
    for(const auto& row:samples){
        const auto p=pid(row);
        require(out.pids.contains(p),"Native sample outside selected PIDs");
        const auto& signals=row.at("signals").array();
        require(signals.size()<=32,"Native sample channel bound");
        std::set<Key> unique;
        for(const auto& s:signals){
            Key k{p,s.at("name").string(),s.at("unit").string()};
            require(unique.insert(k).second,"Duplicate signal in native sample");
            const auto it=out.channels.find(k);
            require(it!=out.channels.end(),"Native channel summary omitted sample");
            if(!s.at("number").null()){
                require(out.identity,"Unverified identity cannot carry engineering measurements");
                (void)s.at("number").finite_number();
                it->second.scales.insert(scale_key(s));++it->second.usable;
            }
        }
    }
    for(const auto& [k,c]:out.channels){
        (void)k;
        require(c.summary->at("count").unsigned_integer()==c.usable,"Native channel count differs");
        require(c.summary->at("mean").null()==(c.usable==0),"Native mean presence differs");
    }
    return out;
}
Value scales(const Channel& c){
    Array a;for(const auto& s:c.scales)a.push_back(str(s));return arr(std::move(a));
}
}
json::Value compare_native_live(std::span<const NativeLiveProjection> runs,std::span<const std::string> digests){
    require(runs.size()>=2&&runs.size()<=16&&runs.size()==digests.size(),"Select two to sixteen native recordings");
    const auto& first=runs.front().report;
    const auto& vin=first.at("vin");
    const auto& tx=first.at("tx_can_id");
    const auto& rx=first.at("rx_can_id");
    const Pair expected{address(tx),address(rx)};
    std::set<std::string> hashes;
    std::vector<Index> indices;
    std::set<Key> keys;
    std::set<unsigned> shared;
    Array sources;
    bool all_identity=true,all_complete=true;
    for(std::size_t i=0;i<runs.size();++i){
        const auto& root=runs[i].report;
        require(compare(root.at("vin"),vin)&&address(root.at("tx_can_id"))==expected.tx&&address(root.at("rx_can_id"))==expected.rx,
            "Native live recordings differ in VIN or physical CAN pair");
        require(digest_ok(digests[i])&&hashes.insert(digests[i]).second,"Invalid or duplicate source SHA-256");
        auto idx=index(runs[i]);
        std::set<unsigned> selected;
        for(const auto& [p,row]:idx.pids){(void)row;selected.insert(p);}
        if(i==0)shared=selected;
        else{
            std::set<unsigned> intersection;
            std::set_intersection(shared.begin(),shared.end(),selected.begin(),selected.end(),std::inserter(intersection,intersection.end()));
            shared=std::move(intersection);
        }
        for(const auto& [k,c]:idx.channels){(void)c;keys.insert(k);}
        all_identity=all_identity&&idx.identity;all_complete=all_complete&&idx.complete;
        sources.push_back(obj({{"source_sha256",str(digests[i])},{"transport",root.at("source_transport")},
            {"source_completed",root.at("source_completed")},{"identity_rechecked",flag(idx.identity)},
            {"numeric_projection_complete",flag(idx.complete)},{"completed_cycles",root.at("completed_cycles")},
            {"requested_cycles",root.at("requested_cycles")}}));
        indices.push_back(std::move(idx));
    }
    Array shared_pids,coverage,comparisons;
    for(auto p:shared){
        shared_pids.push_back(num(p));
        Array rows;
        for(const auto& idx:indices)rows.push_back(*idx.pids.at(p));
        coverage.push_back(obj({{"pid",num(p)},{"recordings",arr(std::move(rows))}}));
    }
    for(const auto& k:keys){
        const auto& [p,name,unit]=k;
        Array values;
        std::set<std::string> scale_sources;
        std::vector<double> means;
        unsigned present=0,usable=0;
        for(const auto& idx:indices){
            const auto it=idx.channels.find(k);
            if(it==idx.channels.end()){values.emplace_back();continue;}
            const auto& c=it->second;++present;
            auto summary=c.summary->object();
            summary.emplace("scale_sources",scales(c));
            values.push_back(obj(std::move(summary)));
            if(c.usable&&idx.identity){
                ++usable;means.push_back(c.summary->at("mean").finite_number());
                scale_sources.insert(c.scales.begin(),c.scales.end());
            }
        }
        // Even equal engineering units do not establish equivalent
        // scaling provenance. Never compare a default and configured scale.
        const bool comparable=all_identity&&usable==runs.size()&&scale_sources.size()==1;
        Value range;
        if(comparable){
            const auto [lo,hi]=std::minmax_element(means.begin(),means.end());
            range=real(*hi-*lo);
        }
        comparisons.push_back(obj({{"pid",num(p)},{"name",str(name)},{"unit",str(unit)},
            {"recordings",arr(std::move(values))},{"recordings_with_channel",num(present)},
            {"recordings_with_numeric_values",num(usable)},{"comparable",flag(comparable)},
            {"mean_range",std::move(range)},{"all_recordings_complete",flag(all_complete)}}));
    }
    return obj({{"schema",str("camarodiag-j1979-native-live-comparison-v1")},{"vin",vin},
        {"tx_can_id",tx},{"rx_can_id",rx},{"recordings",arr(std::move(sources))},
        {"shared_pids",arr(std::move(shared_pids))},{"pid_coverage",arr(std::move(coverage))},
        {"channel_comparison",arr(std::move(comparisons))},{"all_identities_rechecked",flag(all_identity)},
        {"all_recordings_complete",flag(all_complete)},{"time_aligned",flag(false)},
        {"diagnostic_verdict",Value{}},{"authenticated",flag(false)},{"physical_vehicle_verified",flag(false)},
        {"permits_programming",flag(false)}});
}
}
