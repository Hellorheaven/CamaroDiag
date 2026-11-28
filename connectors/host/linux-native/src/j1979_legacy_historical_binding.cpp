#include "j1979_legacy_scan_history.hpp"
#include <algorithm>
#include <stdexcept>

namespace cd::legacy::history {
json::Value compare_historical_discovery_scan(const DiscoveryInspection& d,const HistoricalScan& s,
    std::string_view discovery_digest,std::string_view scan_digest){
    using V=json::Value;
    const auto str=[](std::string_view v){return V{std::string(v)};};
    const auto optional=[&](std::string_view v){return v.empty()?V{}:str(v);};
    if(!d.completed||d.protocol!=s.protocol||!valid_source(s.source)||
        std::count(d.eligible.begin(),d.eligible.end(),s.source)!=1)
        throw std::invalid_argument("Historical scan target not eligible in discovery");
    const auto responder=std::find_if(d.responders.begin(),d.responders.end(),[&](const auto& r){return r.source==s.source;});
    if(responder==d.responders.end()||responder->status!="PASSED"||!responder->bitmap)
        throw std::invalid_argument("Discovery has no verified target bitmap");
    std::optional<std::array<std::uint8_t,4>> bitmap;
    if(!s.steps.empty()){
        const auto& first=s.steps.front();
        if(first.request==Data{1,0}&&first.status=="PASSED"&&first.accepted.size()==1){
            const auto& p=first.accepted.front();
            if(p.size()==6&&p[0]==0x41&&p[1]==0){
                std::array<std::uint8_t,4> b{};
                std::copy_n(p.begin()+2,4,b.begin());bitmap=b;
            }
        }
    }
    return V{V::Object{
        {"schema",str("camarodiag-j1979-legacy-discovery-scan-inspection-v1")},
        {"protocol",V{json::Integer{std::to_string(static_cast<unsigned>(s.protocol))}}},
        {"selected_response_source",str(hex(Data{static_cast<std::uint8_t>(s.source)}))},
        {"discovery_bitmap",str(hex(*responder->bitmap))},{"scan_bitmap",bitmap?str(hex(*bitmap)):V{}},
        {"bitmap_unchanged",V{bitmap&&*bitmap==*responder->bitmap}},
        {"scan_completed",V{s.completed}},{"observed_vin",s.observed_vin?str(*s.observed_vin):V{}},
        {"discovery_sha256",optional(discovery_digest)},{"scan_sha256",optional(scan_digest)},
        {"physical_vehicle_verified",V{false}},{"whole_vehicle_coverage_verified",V{false}},
        {"current_authorization_verified",V{false}},{"automatic_selection_permitted",V{false}},
        {"evidence_authenticated",V{false}},{"permits_programming",V{false}}
    }};
}
}
