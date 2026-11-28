#include "j1979_live_history.hpp"
#include "j1979_historical_freeze.hpp"
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
Value object(Object fields){return Value{std::move(fields)};}
Value array(Array values){return Value{std::move(values)};}
Value text(std::string_view s){return Value{std::string(s)};}
Value count(std::size_t n){return Value{json::Integer{std::to_string(n)}};}
Value boolean(bool b){return Value{b};}
Value real(double n){
    require(std::isfinite(n),"Nonfinite comparison value");
    std::ostringstream s;s.imbue(std::locale::classic());s<<std::scientific<<std::setprecision(16)<<n;
    return Value{json::Decimal{s.str()}};
}
using SignalKey=std::tuple<unsigned,std::string,std::string>;
using SignalMap=std::map<SignalKey,const Value*>;
using PidMap=std::map<unsigned,const Value*>;
unsigned pid(const Value& row){
    const auto p=row.at("pid").unsigned_integer();
    require(p>=1&&p<=255&&p%32,"Invalid comparison PID");return static_cast<unsigned>(p);
}
bool digest_valid(std::string_view s){
    return s.size()==64&&std::all_of(s.begin(),s.end(),[](char c){
        return (c>='0'&&c<='9')||(c>='a'&&c<='f');
    });
}
Value range(const std::vector<double>& values){
    if(values.size()<2)return {};
    const auto [a,b]=std::minmax_element(values.begin(),values.end());return real(*b-*a);
}
Object scope(const HistoricalLive& r){
    auto fields=r.channel.object();fields.erase("link_checksum_verified");return fields;
}
PidMap scalar_index(const HistoricalLive& r){
    PidMap result;
    for(const auto& row:r.proof.at("summary").array()){
        require(result.emplace(pid(row),&row).second,"Duplicate PID in verified recording");
    }
    return result;
}
SignalMap signal_index(const HistoricalLive& r){
    SignalMap result;
    for(const auto& row:r.proof.at("multi_signal_summary").array()){
        auto k=SignalKey{pid(row),row.at("name").string(),row.at("unit").string()};
        require(result.emplace(std::move(k),&row).second,"Duplicate signal in verified recording");
    }
    return result;
}
}
json::Value compare_historical_live(std::span<const HistoricalLive> recordings,std::span<const std::string> digests){
    require(recordings.size()>=2&&recordings.size()<=16&&digests.size()==recordings.size(),
        "Select two to sixteen independently replayed recordings");
    const auto& first=recordings.front();
    const auto expected_scope=Value{scope(first)};
    std::set<std::string> unique;
    std::set<unsigned> shared(first.pids.begin(),first.pids.end());
    std::vector<PidMap> scalar;
    std::vector<SignalMap> signals;
    std::set<SignalKey> all_signals;
    Array sources;
    bool complete=true;
    for(std::size_t i=0;i<recordings.size();++i){
        const auto& r=recordings[i];
        require(r.vin==first.vin&&compare(Value{scope(r)},expected_scope)&&r.raw_only==first.raw_only,
            "Recordings differ in VIN, ECU scope or raw-only mode");
        require(digest_valid(digests[i])&&unique.insert(digests[i]).second,"Invalid or duplicate recording SHA-256");
        std::set<unsigned> selected(r.pids.begin(),r.pids.end()),intersection;
        std::set_intersection(shared.begin(),shared.end(),selected.begin(),selected.end(),std::inserter(intersection,intersection.end()));
        shared=std::move(intersection);
        scalar.push_back(scalar_index(r));signals.push_back(signal_index(r));
        for(const auto& [key,row]:signals.back()){(void)row;all_signals.insert(key);}
        complete=complete&&r.completed;
        sources.push_back(object({{"sha256",text(digests[i])},{"completed",boolean(r.completed)},
            {"completed_cycles",count(r.completed_cycles)}}));
    }
    Array scalar_rows,channel_rows,shared_ids;
    for(auto p:shared){
        shared_ids.push_back(count(p));
        Array rows;std::vector<double> means;Value unit;
        for(const auto& index:scalar){
            const auto it=index.find(p);require(it!=index.end(),"Missing verified PID summary");
            const auto& row=*it->second;const auto& u=row.at("unit");
            if(!u.null()){require(unit.null()||compare(unit,u),"Different engineering units for same PID");unit=u;}
            const auto& m=row.at("mean");if(!m.null())means.push_back(m.finite_number());
            rows.push_back(row);
        }
        scalar_rows.push_back(object({{"pid",count(p)},{"unit",unit},{"recordings",array(std::move(rows))},{"mean_range",range(means)}}));
    }
    for(const auto& key:all_signals){
        Array rows;std::vector<double> means;std::size_t present=0;
        for(const auto& index:signals){
            const auto it=index.find(key);
            if(it==index.end()){rows.emplace_back();continue;}
            const auto& row=*it->second;
            const auto& m=row.at("mean");require(!m.null(),"Verified signal lacks numeric mean");
            means.push_back(m.finite_number());rows.push_back(row);++present;
        }
        const bool all=present==recordings.size();
        const auto& [p,name,unit]=key;
        channel_rows.push_back(object({{"pid",count(p)},{"name",text(name)},{"unit",text(unit)},
            {"recordings",array(std::move(rows))},{"recordings_with_channel",count(present)},
            {"all_recordings_have_channel",boolean(all)},{"all_recordings_complete",boolean(complete)},
            {"mean_range",all?range(means):Value{}}}));
    }
    return object({{"schema",text("camarodiag-j1979-live-comparison-v1")},{"verified",boolean(true)},
        {"declared_vin",text(first.vin)},{"channel",first.channel},{"raw_only",boolean(first.raw_only)},
        {"shared_pids",array(std::move(shared_ids))},{"recordings",array(std::move(sources))},
        {"comparison",array(std::move(scalar_rows))},{"channel_comparison",array(std::move(channel_rows))},
        {"time_aligned",boolean(false)},{"diagnostic_verdict",Value{}},{"physical_vehicle_verified",boolean(false)},
        {"authenticated",boolean(false)}});
}
}
