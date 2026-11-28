#include "j1979_legacy_scan.hpp"
#include "j1979_legacy_monitor.hpp"
#include "j1979_legacy_info.hpp"
#include <map>
#include <algorithm>
#include <utility>
#include "j1979_policy.h"
#include "j1979_measurement.h"
#include "j1979_multi.hpp"
#include "j1979_status.hpp"
#include "j1979_context.hpp"
#include <cstdlib>
#include <memory>
#include <stdexcept>
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
void context_json(std::ostream& o,std::span<const cd::j1979::Observation> rows,bool verified){
    char* buffer=nullptr;
    std::size_t size=0;
    auto* stream=open_memstream(&buffer,&size);
    if(!stream)throw std::runtime_error("Cannot allocate legacy context JSON");
    bool written=false;
    try {
        written=cd::j1979::write_context_json(stream,rows,verified);
    }catch(...){
        std::fclose(stream);std::free(buffer);throw;
    }
    const int closed=std::fclose(stream);
    const std::unique_ptr<char,decltype(&std::free)> owned(buffer,&std::free);
    if(!written||closed!=0||!owned||size==0||size>32*1024*1024)
        throw std::runtime_error("Cannot serialize bounded legacy context JSON");
    o.write(owned.get(),static_cast<std::streamsize>(size));
}
void measurement(std::ostream& o,const ScanStep& step,bool verified){
    if(step.selected.size()!=1||!verified||step.status!="PASSED"){o<<"null";return;}
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
void dtcs(std::ostream& o,const ScanStep& step,bool identity_verified){
    const auto mode=step.request[0];
    if((mode!=3&&mode!=7&&mode!=10)||!identity_verified||step.status!="PASSED"||step.selected.empty()){o<<"null";return;}
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
void monitors(std::ostream& o,const ScanStep& step,bool identity_verified){
    const auto mode=step.request[0];
    const auto pid=step.request.size()>1?step.request[1]:0;
    if((mode!=5&&mode!=6)||pid%32==0||step.status!="PASSED"||!identity_verified||step.selected.empty()){
        o<<"null";return;
    }
    std::vector<MonitorValue> decoded;
    for(const auto& r:step.selected){
        auto m=decode_monitor(step.request,r);
        if(!m){o<<"null";return;}
        decoded.push_back(std::move(*m));
    }
    array(o,decoded,[&](const auto& m){write_monitor(o,m);});
}
}
std::string scan_report(const Scan& s){
    std::ostringstream o;o.imbue(std::locale::classic());o<<std::setprecision(12);
    o<<"{\"schema\":\"camarodiag-j1979-legacy-selected-linux-v1\",\"protocol\":"<<static_cast<unsigned>(s.protocol)
     <<",\"protocol_name\":";quote(o,name(s.protocol));
    o<<",\"selected_response_source\":";quote(o,source_hex(s.source));
    o<<",\"expected_vin\":";quote(o,s.expected_vin);
    o<<",\"budget_ms\":"<<s.budget_ms;
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
    o<<",\"exchange_trace\":";
    array(o,s.trace,[&](const ScanExchange& e){
        o<<"{\"command\":";quote(o,e.command);
        o<<",\"raw_response\":";quote(o,e.raw);
        o<<",\"outcome\":"<<static_cast<unsigned>(e.outcome);
        o<<",\"started_ms\":"<<e.started_ms<<",\"ended_ms\":"<<e.ended_ms;
        o<<",\"attempted\":";truth(o,e.attempted);o<<'}';
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
        o<<",\"dtc_codes\":";dtcs(o,step,s.identity_rechecked);
        o<<",\"monitor_records\":";monitors(o,step,s.identity_rechecked);
        o<<'}';
    });
    std::vector<Identification> identities;
    if(s.identity_rechecked){
        std::map<unsigned,unsigned> counts;
        for(const auto& step:s.steps){
            if(step.request.size()!=2||step.request[0]!=9||step.status!="PASSED"||step.selected.empty())continue;
            const unsigned kind=step.request[1];
            if(count_kind(kind)){
                if(step.selected.size()==1&&step.selected[0].size()==3)counts[kind]=step.selected[0][2];
                continue;
            }
            const auto count=counts.find(matching_count_kind(kind));
            if(count==counts.end()||std::any_of(identities.begin(),identities.end(),[&](const auto& v){return v.kind==kind;}))continue;
            if(auto item=decode_identification(kind,count->second,step.selected);item&&item->interpreted)
                identities.push_back(std::move(*item));
        }
    }
    o<<",\"identification\":";
    array(o,identities,[&](const Identification& i){
        o<<"{\"info_type\":"<<i.kind<<",\"source\":";quote(o,source_hex(s.source));
        o<<",\"values\":";array(o,i.values,[&](const auto& value){quote(o,value);});
        o<<",\"raw_data_hex\":";quote(o,hex(i.encoded));
        o<<",\"counters\":";
        if(i.counters){
            const auto& c=*i.counters;
            o<<"{\"general_denominator\":"<<c.general_denominator<<",\"ignition_cycles\":"<<c.ignition_cycles
                <<",\"counter_count\":"<<c.counter_count<<",\"monitors\":[";
            for(unsigned n=0;n<c.monitor_count;++n){
                if(n)o<<',';
                const auto& monitor=c.monitors[n];
                o<<"{\"monitor\":";quote(o,monitor.name);
                o<<",\"numerator\":"<<monitor.numerator<<",\"denominator\":"<<monitor.denominator<<",\"ratio\":";
                if(monitor.ratio_valid)o<<monitor.ratio;else o<<"null";
                o<<'}';
            }
            o<<"],\"regulatory_verdict\":null}";
        }else o<<"null";
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
    // Context derives only from current Mode 01 records. Frozen data must not
    // inherit range configuration or sensor layout observed at a different time.
    std::vector<cd::j1979::Observation> current_rows;
    for(const auto& row:rows)
        if(row.request.size()==2&&row.request[0]==1)current_rows.push_back(row);
    o<<",\"operating_context\":";context_json(o,current_rows,s.identity_rechecked);
    o<<",\"not_executed\":[\"MODE_04_CLEAR\",\"MODE_08_CONTROL\"]}\n";
    return o.str();
}
}
