#include "j1979_can_history.hpp"
#include <iomanip>
#include <locale>
#include <sstream>
namespace cd::canhistory {
namespace {
void nullable(std::ostream& out,std::string_view value){if(value.empty())out<<"null";else out<<json::quote(value);}
const char* tf(bool b){return b?"true":"false";}
void dtcs(std::ostream& out,const Dtc& d){
    constexpr std::array<std::string_view,3> modes{"03","07","0A"};
    out<<'{';
    for(size_t i=0;i<modes.size();++i){
        if(i)out<<',';
        out<<json::quote(modes[i])<<':';
        if(!d[i]){out<<"null";continue;}
        out<<'[';
        for(size_t j=0;j<d[i]->size();++j){
            if(j)out<<',';
            out<<json::quote(d[i]->at(j));
        }
        out<<']';
    }
    out<<'}';
}
}
std::string report(const Inspection& r,std::string_view digest,std::string_view baseline_digest){
    std::ostringstream out;out.imbue(std::locale::classic());
    out<<"{\"schema\":\"camarodiag-j1979-can-mutation-inspection-v1\",\"consistent\":true,\"operation\":"
        <<json::quote(r.scope.evap?"evap":"clear")<<",\"platform\":"<<json::quote(r.platform)
        <<",\"expected_vin\":"<<json::quote(r.scope.vin)
        <<",\"request_can_id\":\""<<std::uppercase<<std::hex<<r.scope.tx
        <<"\",\"response_can_id\":\""<<r.scope.rx<<"\""<<std::dec
        <<",\"baseline_consistent\":"<<tf(r.baseline_consistent)<<",\"baseline_origin\":";
    nullable(out,r.baseline_origin);
    out<<",\"baseline_required_missing\":"<<tf(!r.scope.evap&&r.attempted&&!r.baseline_consistent)
        <<",\"physical_vehicle_verified\":false,\"monitor_completion_verified\":false,"
          "\"operator_consent_verified\":false,\"durable_before_tx_verified\":false,\"evidence_authenticated\":false,"
          "\"permits_automatic_retry\":false,\"observations\":[";
    for(size_t i=0;i<r.rows.size();++i){
        if(i)out<<',';
        const auto& row=r.rows[i];
        out<<"{\"stage\":";nullable(out,row.stage);
        out<<",\"request_hex\":"<<json::quote(row.command)<<",\"response_hex\":";
        if(row.response)out<<json::quote(legacy::hex(*row.response));else out<<"null";
        out<<",\"error\":";nullable(out,row.error);out<<'}';
    }
    out<<"],\"command_attempted\":"<<tf(r.attempted)<<",\"acknowledged\":"<<tf(r.acknowledged)
        <<",\"observed_vin\":";nullable(out,r.observed);
    out<<",\"outcome\":"<<json::quote(r.outcome)<<",\"reason\":";nullable(out,r.reason);
    if(!r.scope.evap){
        out<<",\"boundary\":7,\"before_dtcs\":";dtcs(out,r.before);
        out<<",\"after_dtcs\":";dtcs(out,r.after);
        out<<",\"readiness\":{";
        for(size_t i=0;i<2;++i){
            if(i)out<<',';
            out<<json::quote(i?"AFTER":"BEFORE")<<':';
            if(!r.readiness[i])out<<"null";
            else{
                const auto& value=*r.readiness[i];
                out<<"{\"mil_on\":"<<tf(value.mil)<<",\"confirmed_dtc_count\":"<<value.count
                    <<",\"readiness_hex\":"<<json::quote(value.bits)<<'}';
            }
        }
        out<<"},\"fresh_identity_and_stationary_recorded\":"<<tf(r.fresh);
    }
    out<<",\"report_sha256\":";nullable(out,digest);
    out<<",\"baseline_sha256\":";nullable(out,baseline_digest);out<<"}\n";return out.str();
}
}
