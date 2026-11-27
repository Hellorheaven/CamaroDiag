#include "j1979_legacy_mutation.hpp"
#include <array>
#include <locale>
#include <sstream>
namespace cd::legacy {
namespace {
void quote(std::ostream& out,std::string_view value){
    constexpr char digits[]="0123456789abcdef";
    out<<'"';
    for(unsigned char c:value){
        if(c=='"'||c=='\\')out<<'\\'<<char(c);
        else if(c<32||c>126)out<<"\\u00"<<digits[c>>4]<<digits[c&15];
        else out<<char(c);
    }
    out<<'"';
}
void nullable(std::ostream& out,std::string_view s){if(s.empty())out<<"null";else quote(out,s);}
const char* tf(bool value){return value?"true":"false";}
std::string source_hex(unsigned n){return hex(std::array<std::uint8_t,1>{static_cast<std::uint8_t>(n)});}
template<class R,class F>void array(std::ostream& out,const R& rows,F emit){
    out<<'[';bool first=true;
    for(const auto& row:rows){if(!first)out<<',';first=false;emit(row);}
    out<<']';
}
void snapshot(std::ostream& out,const MutationPlan& p,const DtcSnapshot& d){
    out<<'{';bool first=true;
    for(auto s:p.sources){
        if(!first)out<<',';
        first=false;quote(out,source_hex(s));out<<":{";
        bool first_mode=true;
        for(auto mode:{3u,7u,10u}){
            if(!first_mode)out<<',';
            first_mode=false;quote(out,source_hex(mode));out<<':';
            auto a=d.find(s);
            if(a==d.end()){out<<"null";continue;}
            auto b=a->second.find(mode);
            if(b==a->second.end()||!b->second){out<<"null";continue;}
            array(out,*b->second,[&](const auto& code){quote(out,code);});
        }
        out<<'}';
    }
    out<<'}';
}
}
std::string mutation_report(const MutationResult& r){
    std::ostringstream out;out.imbue(std::locale::classic());
    out<<"{\"schema\":\"camarodiag-j1979-legacy-maintenance-linux-v1\",\"protocol\":\"SAE-J1979\","
      "\"scope\":\"FUNCTIONAL_VEHICLE_WIDE\",\"transport\":\"ELM_SERIAL_LEGACY_HEADERED\","
      "\"physically_addressed\":false,\"expected_protocol\":"<<static_cast<unsigned>(r.plan.protocol)
      <<",\"operation\":";
    quote(out,r.plan.operation==MutationOperation::clear?"CLEAR":"EVAP");
    out<<",\"expected_vin\":";quote(out,r.plan.vin);
    out<<",\"observed_vin\":";nullable(out,r.vin);
    out<<",\"engine_source\":";quote(out,source_hex(r.plan.engine));
    out<<",\"expected_sources\":";array(out,r.plan.sources,[&](auto s){quote(out,source_hex(s));});
    out<<",\"unexpected_sources\":";array(out,r.unexpected_sources,[&](auto s){quote(out,source_hex(s));});
    out<<",\"outcome\":";quote(out,r.outcome);
    out<<",\"reason\":";nullable(out,r.reason);
    out<<",\"baseline_saved\":"<<tf(r.baseline_saved)<<",\"intent_saved\":"<<tf(r.intent_saved)
        <<",\"command_attempted\":"<<tf(r.attempted)<<",\"complete_acknowledgment\":"<<tf(r.complete_ack)
        <<",\"budget_ms\":"<<r.plan.budget_ms<<",\"duration_ms\":"<<r.elapsed_ms
        <<",\"physical_vehicle_verified\":false,\"whole_vehicle_coverage_verified\":false,"
          "\"leak_test_performed\":false,\"permits_automatic_retry\":false,\"permits_programming\":false,"
          "\"acknowledgments\":{";
    bool first=true;
    for(const auto& [source,ack]:r.acknowledgments){
        if(!first)out<<',';
        first=false;quote(out,source_hex(source));out<<':';quote(out,ack);
    }
    out<<"},\"before_dtcs\":";snapshot(out,r.plan,r.before);
    out<<",\"after_dtcs\":";snapshot(out,r.plan,r.after);
    out<<",\"observations\":";
    array(out,r.observations,[&](const MutationObservation& row){
        out<<"{\"stage\":";quote(out,row.stage);out<<",\"request_hex\":";quote(out,row.command);
        out<<",\"raw_response\":";nullable(out,row.raw);out<<",\"error\":";nullable(out,row.error);
        out<<",\"started_ms\":"<<row.started_ms<<",\"ended_ms\":"<<row.ended_ms
            <<",\"transmission_attempted\":"<<tf(row.attempted)<<",\"frames\":";
        array(out,row.frames,[&](const Frame& frame){
            out<<"{\"source\":";quote(out,source_hex(frame.source));
            out<<",\"raw_hex\":";quote(out,hex(frame.raw));
            out<<",\"payload_hex\":";quote(out,hex(frame.payload));out<<'}';
        });
        out<<'}';
    });
    out<<"}\n";return out.str();
}
}
