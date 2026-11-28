#include "j1979_legacy_discovery_binding.hpp"
#include <algorithm>
#include <sstream>
#include <stdexcept>

namespace cd::legacy::history {
namespace {
void require(bool b,const char* reason){if(!b)throw std::invalid_argument(reason);}
const char* tf(bool b){return b?"true":"false";}
}
ScanBinding bind_capture(const DiscoveryInspection& d,Bytes capture,Protocol protocol,unsigned source,std::string_view vin){
    require(valid_protocol(protocol)&&valid_source(source),"Invalid independent target");
    require(d.completed&&d.protocol==protocol,"Discovery incomplete or protocol mismatch");
    require(std::count(d.eligible.begin(),d.eligible.end(),source)==1,"Source not eligible in historical discovery");
    const auto it=std::find_if(d.responders.begin(),d.responders.end(),[&](const auto& row){return row.source==source;});
    require(it!=d.responders.end()&&it->status=="PASSED"&&it->bitmap.has_value(),"Discovery source lacks unambiguous PID00");
    // Replays the captured exchange sequence against the explicit target; a
    // serialized report's claimed VIN/bitmap is not trusted as input.
    const auto scan=inspect_scan_capture(capture,protocol,source,vin);
    ScanBinding out{};out.protocol=protocol;out.source=source;
    out.discovery_bitmap=*it->bitmap;
    if(!scan.steps.empty()){
        const auto& first=scan.steps.front();
        if(first.request==Data{1,0}&&first.status=="PASSED"&&first.selected.size()==1){
            const auto& p=first.selected.front();
            if(p.size()==6&&p[0]==0x41&&p[1]==0){
                std::array<std::uint8_t,4> bitmap{};std::copy_n(p.begin()+2,4,bitmap.begin());out.scan_bitmap=bitmap;
            }
        }
    }
    out.scan_completed=scan.completed;out.identity_rechecked=scan.identity_rechecked;
    if(scan.identity_rechecked&&scan.initial_vin==scan.final_vin&&scan.final_vin==vin)out.observed_vin=scan.final_vin;
    return out;
}
std::string binding_report(const ScanBinding& r,std::string_view dd,std::string_view cdigest){
    std::ostringstream out;
    out<<"{\"schema\":\"camarodiag-j1979-legacy-discovery-lsr-binding-linux-v1\",\"consistent\":true,"
        "\"protocol\":"<<static_cast<unsigned>(r.protocol)<<",\"selected_response_source\":"
        <<json::quote(hex(Data{static_cast<std::uint8_t>(r.source)}))
        <<",\"discovery_bitmap\":"<<json::quote(hex(r.discovery_bitmap))
        <<",\"scan_bitmap\":"<<(r.scan_bitmap?json::quote(hex(*r.scan_bitmap)):"null")
        <<",\"bitmap_unchanged\":"<<tf(r.scan_bitmap&&*r.scan_bitmap==r.discovery_bitmap)
        <<",\"scan_completed\":"<<tf(r.scan_completed)<<",\"identity_rechecked\":"<<tf(r.identity_rechecked)
        <<",\"observed_vin\":"<<(r.observed_vin.empty()?"null":json::quote(r.observed_vin))
        <<",\"discovery_sha256\":"<<json::quote(dd)<<",\"capture_sha256\":"<<json::quote(cdigest)
        <<",\"physical_vehicle_verified\":false,\"whole_vehicle_coverage_verified\":false,"
          "\"current_authorization_verified\":false,\"automatic_selection_permitted\":false,"
          "\"evidence_authenticated\":false,\"permits_programming\":false}\n";
    return out.str();
}
}
