#include "j1979_legacy_freeze.hpp"
#include "j1979_measurement.h"
#include "j1979_policy.h"
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
const char* tf(bool b){return b?"true":"false";}
template<class Range,class Emit> void array(std::ostream& o,const Range& rows,Emit emit){
    o<<'[';bool first=true;
    for(const auto& row:rows){if(!first)o<<',';first=false;emit(row);}
    o<<']';
}
template<class Range>void numbers(std::ostream& o,const Range& rows){array(o,rows,[&](auto value){o<<unsigned(value);});}
void trigger(std::ostream& o,std::optional<std::uint16_t> code){
    if(!code||!*code){o<<"null";return;}
    const std::array<std::uint8_t,2> data{std::uint8_t(*code>>8),std::uint8_t(*code)};
    char text[6]{};
    if(cd_j1979_native_dtc_code(data.data(),text))quote(o,text);else o<<"null";
}
std::string_view outcome(FreezeOutcome state){
    switch(state){
    case FreezeOutcome::received:return "RECEIVED";
    case FreezeOutcome::timeout:return "TIMEOUT";
    case FreezeOutcome::transport:return "TRANSPORT_ERROR";
    case FreezeOutcome::budget:return "BUDGET";
    case FreezeOutcome::cancelled:return "CONTEXT_CHANGED";
    case FreezeOutcome::late:return "LATE_RESPONSE";
    }
    return "INVALID";
}
void measurement(std::ostream& o,const FreezeStep& s,bool verified){
    if(!verified||s.status!="PASSED"||s.request.size()!=3||s.request[0]!=2||s.request[2]!=0){
        o<<"null";return;
    }
    j1979_measurement m{};
    if(!j1979_measurement_decode(s.request.data(),s.request.size(),s.response.data(),s.response.size(),&m)||
        !m.interpreted||m.context_required){o<<"null";return;}
    o<<"{\"mode\":2,\"frame\":0,\"pid\":"<<unsigned(s.request[1])<<",\"name\":";
    quote(o,m.name?m.name:"");o<<",\"unit\":";quote(o,m.unit?m.unit:"");o<<",\"number\":"<<m.number<<'}';
}
}
std::string freeze_report(const FreezeResult& r){
    std::ostringstream o;o.imbue(std::locale::classic());o<<std::setprecision(15);
    o<<"{\"schema\":\"camarodiag-j1979-legacy-freeze-linux-v1\",\"protocol\":\"SAE-J1979\","
        "\"transport\":\"ELM_SERIAL_LEGACY_HEADERED\",\"scope\":\"FUNCTIONAL_SELECTED_RESPONSE_FREEZE\","
        "\"physically_addressed\":false,\"expected_protocol\":"<<static_cast<unsigned>(r.protocol)
        <<",\"selected_response_source\":";
    const std::array<std::uint8_t,1> source{static_cast<std::uint8_t>(r.source)};
    quote(o,hex(source));
    o<<",\"plan\":{\"expected_vin\":";quote(o,r.plan.vin);
    o<<",\"budget_ms\":"<<r.plan.budget_ms<<",\"frames\":";numbers(o,r.plan.frames);
    o<<",\"selected_pids\":";numbers(o,r.plan.pids);o<<'}';
    o<<",\"initial_vin\":";nullable(o,r.initial_vin);
    o<<",\"final_vin\":";nullable(o,r.final_vin);
    o<<",\"stop_reason\":";nullable(o,r.error);
    o<<",\"completed\":"<<tf(r.completed)<<",\"identity_rechecked\":"<<tf(r.identity_rechecked)
        <<",\"all_frames_stable\":"<<tf(r.all_frames_stable)
        <<",\"physical_vehicle_verified\":false,\"authenticated\":false,"
          "\"whole_vehicle_coverage_verified\":false,\"permits_programming\":false,\"other_response_sources\":";
    array(o,r.other_sources,[&](unsigned n){
        const std::array<std::uint8_t,1> b{static_cast<std::uint8_t>(n)};quote(o,hex(b));
    });
    o<<",\"frames\":";
    array(o,r.frames,[&](const FrozenFrame& f){
        o<<"{\"frame\":"<<f.frame<<",\"status\":";quote(o,f.state);
        o<<",\"detail\":";nullable(o,f.detail);
        o<<",\"trigger_dtc\":";trigger(o,f.trigger);
        o<<",\"final_trigger_dtc\":";trigger(o,f.final_trigger);
        o<<",\"trigger_stable\":"<<tf(f.trigger_stable)<<",\"inventory_complete\":"<<tf(f.inventory_complete)
            <<",\"advertised_pids\":";numbers(o,f.advertised);
        o<<",\"read_pids\":";numbers(o,f.read);o<<",\"verified_pages\":";numbers(o,f.pages);
        o<<",\"measurement_steps\":";numbers(o,f.measurement_steps);
        o<<",\"unadvertised_selected_pids\":";numbers(o,f.unadvertised);
        o<<",\"not_read_selected_pids\":";numbers(o,f.unread);o<<'}';
    });
    o<<",\"unvisited_frames\":[";
    for(std::size_t i=r.frames.size();i<r.plan.frames.size();++i){
        if(i!=r.frames.size())o<<',';
        o<<unsigned(r.plan.frames[i]);
    }
    o<<"],\"steps\":";
    array(o,r.steps,[&](const FreezeStep& step){
        o<<"{\"request_hex\":";quote(o,hex(step.request));
        o<<",\"response_hex\":";if(step.response.empty())o<<"null";else quote(o,hex(step.response));
        o<<",\"status\":";quote(o,step.status);o<<",\"detail\":";nullable(o,step.detail);
        o<<",\"transport_outcome\":";quote(o,outcome(step.outcome));
        o<<",\"first_exchange\":"<<step.first_exchange<<",\"exchange_count\":"<<step.exchange_count
            <<",\"measurement\":";measurement(o,step,r.identity_rechecked);o<<'}';
    });
    o<<",\"transport_exchanges\":";
    array(o,r.trace,[&](const FreezeExchange& x){
        o<<"{\"request\":";quote(o,x.command);o<<",\"raw_response\":";quote(o,x.raw);
        o<<",\"transport_outcome\":";quote(o,outcome(x.outcome));
        o<<",\"duration_ms\":"<<x.duration_ms<<",\"transmission_attempted\":"<<tf(x.attempted)<<'}';
    });
    o<<"}\n";return o.str();
}
}
