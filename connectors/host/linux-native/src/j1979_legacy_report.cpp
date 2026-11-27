#include "j1979_legacy_scan.hpp"
#include "j1979_policy.h"
#include "j1979_measurement.h"
#include "j1979_multi.hpp"
#include "j1979_status.hpp"
#include <iomanip>
#include <locale>
#include <sstream>

namespace cd::legacy {
namespace {
void quote(std::ostream& o,std::string_view s){
    constexpr char digits[]="0123456789abcdef";
    o<<'"';
    for(unsigned char c:s){
        if(c=='"'||c=='\\')o<<'\\'<<char(c);
        else if(c<32||c>126)o<<"\\u00"<<digits[c>>4]<<digits[c&15];
        else o<<char(c);
    }
    o<<'"';
}
void nullable(std::ostream& o,std::string_view s){if(s.empty())o<<"null";else quote(o,s);}
void truth(std::ostream& o,bool b){o<<(b?"true":"false");}
std::string source_hex(unsigned n){const std::array<std::uint8_t,1> bytes{static_cast<std::uint8_t>(n)};return hex(bytes);}
void frame_json(std::ostream& o,const Frame& f){
    o<<"{\"source\":";quote(o,source_hex(f.source));o<<",\"raw_hex\":";quote(o,hex(f.raw));
    o<<",\"payload_hex\":";quote(o,hex(f.payload));o<<'}';
}
template<class T,class F> void array(std::ostream& o,const T& rows,F f){
    o<<'[';bool first=true;
    for(const auto& row:rows){if(!first)o<<',';first=false;f(row);}
    o<<']';
}
void measurement(std::ostream& o,const ScanStep& step,bool verified){
    if(step.selected.size()!=1||!verified){o<<"null";return;}
    const auto& r=step.selected.front();
    j1979_measurement m{};
    if(!j1979_measurement_decode(step.request.data(),step.request.size(),r.data(),r.size(),&m)){
        o<<"null";return;
    }
    o<<"{\"name\":";quote(o,m.name?m.name:"");o<<",\"unit\":";quote(o,m.unit?m.unit:"");
    o<<",\"mode\":"<<unsigned(m.mode)<<",\"pid\":"<<unsigned(m.pid)<<",\"number\":";
    if(m.interpreted&&!m.context_required)o<<m.number;else o<<"null";
    o<<",\"context_required\":";truth(o,m.context_required);
    o<<",\"upper_trim_unverified\":";truth(o,m.upper_trim_unverified);o<<'}';
}
void dtcs(std::ostream& o,const ScanStep& step){
    const auto mode=step.request[0];
    if(mode!=3&&mode!=7&&mode!=10){o<<"null";return;}
    std::set<std::string> codes;
    for(const auto& row:step.selected){
        if(row.size()!=7)continue;
        for(unsigned pos=1;pos<7;pos+=2){
            char code[6]{};
            if((row[pos]||row[pos+1])&&cd_j1979_native_dtc_code(row.data()+pos,code))codes.insert(code);
        }
    }
    array(o,codes,[&](const auto& code){quote(o,code);});
}
void monitors(std::ostream& o,const ScanStep& step){
    const auto mode=step.request[0];
    const auto pid=step.request.size()>1?step.request[1]:0;
    if((mode!=5&&mode!=6)||pid%32==0){o<<"null";return;}
    array(o,step.selected,[&](const Data& r){
        o<<"{\"mode\":"<<unsigned(mode)<<",\"test_id\":"<<unsigned(pid);
        if(mode==5&&r.size()>=4){
            o<<",\"sensor_mask\":"<<unsigned(r[2])<<",\"encoded_data_hex\":";quote(o,hex(Bytes(r).subspan(3)));
        }else if(mode==6&&r.size()==7){
            o<<",\"component_and_limit_encoded\":"<<unsigned(r[2])
                <<",\"value_encoded\":"<<(unsigned(r[3])*256+r[4])
                <<",\"limit_encoded\":"<<(unsigned(r[5])*256+r[6]);
        }
        o<<",\"scale_verified\":false,\"diagnostic_verdict\":null}";
    });
}
}
std::string scan_report(const Scan& s){
    std::ostringstream o;o.imbue(std::locale::classic());o<<std::setprecision(12);
    o<<"{\"schema\":\"camarodiag-j1979-legacy-selected-linux-v1\",\"protocol\":"<<static_cast<unsigned>(s.protocol)
     <<",\"protocol_name\":";quote(o,name(s.protocol));
    o<<",\"selected_response_source\":";quote(o,source_hex(s.source));
    o<<",\"expected_vin\":";quote(o,s.expected_vin);
    o<<",\"observed_initial_vin\":";nullable(o,s.initial_vin);
    o<<",\"observed_final_vin\":";nullable(o,s.final_vin);
    o<<",\"initial_protocol_probe\":";quote(o,s.initial_probe);
    o<<",\"final_protocol_probe\":";quote(o,s.final_probe);
    o<<",\"identity_rechecked\":";truth(o,s.identity_rechecked);
    o<<",\"completed\":";truth(o,s.completed);
    o<<",\"budget_expired\":";truth(o,s.budget_expired);
    o<<",\"error\":";nullable(o,s.error);
    o<<",\"whole_vehicle_coverage_verified\":false,\"physical_vehicle_verified\":false,\"permits_programming\":false";
    o<<",\"other_response_sources\":";array(o,s.other_sources,[&](unsigned n){quote(o,source_hex(n));});
    o<<",\"inventory\":";
    array(o,s.inventories,[&](const Inventory& i){
        o<<"{\"mode\":"<<i.mode<<",\"sensor_mask\":";if(i.mode==5)o<<i.sensor;else o<<"null";
        o<<",\"verified_pages\":"<<i.pages<<",\"complete\":";truth(o,i.complete);
        o<<",\"advertised_ids\":";array(o,i.advertised,[&](unsigned n){o<<n;});o<<'}';
    });
    o<<",\"steps\":";
    array(o,s.steps,[&](const ScanStep& step){
        o<<"{\"request_hex\":";quote(o,hex(step.request));
        o<<",\"raw_response\":";quote(o,step.raw);
        o<<",\"status\":";quote(o,step.status);o<<",\"detail\":";nullable(o,step.detail);
        o<<",\"duration_ms\":"<<step.duration_ms<<",\"frames\":";
        array(o,step.observations,[&](const Frame& f){frame_json(o,f);});
        o<<",\"selected_records\":";array(o,step.selected,[&](const Data& data){quote(o,hex(data));});
        o<<",\"measurement\":";measurement(o,step,s.identity_rechecked);
        o<<",\"dtc_codes\":";dtcs(o,step);
        o<<",\"monitor_records\":";monitors(o,step);
        o<<'}';
    });
    std::vector<cd::j1979::Observation> rows;
    if(s.identity_rechecked){
        for(const auto& step:s.steps)if(step.selected.size()==1)
            rows.push_back({step.request,step.selected.front(),step.status=="PASSED",true});
    }
    const auto context=cd::j1979::derive_context(rows);
    o<<",\"multi_signal_records\":";cd::j1979::write_multi_json(o,rows,&context.scaling);
    o<<",\"status_records\":";cd::j1979::write_status_json(o,rows);
    o<<",\"not_executed\":[\"MODE_04_CLEAR\",\"MODE_08_CONTROL\"]}\n";
    return o.str();
}
}
