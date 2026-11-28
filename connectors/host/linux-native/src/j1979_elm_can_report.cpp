#include "j1979_elm_can.hpp"
#include <iomanip>
#include <locale>
#include <sstream>
namespace cd::elmcan {
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
const char* tf(bool b){return b?"true":"false";}
void nullable(std::ostream& o,std::string_view s){if(s.empty())o<<"null";else quote(o,s);}
}
std::string report(const Scope& scope,std::string_view vin,std::string_view operation,std::string_view native_report,
                   std::span<const Observation> observations,std::string_view error,bool completed){
    std::ostringstream o;o.imbue(std::locale::classic());
    o<<"{\"schema\":\"camarodiag-j1979-elm-can-linux-v1\",\"protocol\":\"SAE-J1979\","
      "\"transport\":\"ELM_SERIAL_CLASSIC_ISOTP\",\"scope\":\"PHYSICAL_SELECTED_ECU\","
      "\"profile\":"<<scope.profile<<",\"extended\":"<<tf(scope.extended())
      <<",\"tx_can_id\":\""<<std::uppercase<<std::hex<<scope.tx<<"\",\"rx_can_id\":\""<<scope.rx
      <<"\""<<std::dec<<",\"expected_vin\":";quote(o,vin);
    o<<",\"operation\":";quote(o,operation);
    o<<",\"completed\":"<<tf(completed)<<",\"error\":";nullable(o,error);
    o<<",\"physical_vehicle_verified\":false,\"whole_vehicle_coverage_verified\":false,"
      "\"adapter_configuration_independently_verified\":false,\"permits_programming\":false,"
      "\"adapter_exchanges\":[";
    bool first=true;
    for(const auto& row:observations){
        if(!first)o<<',';
        first=false;
        o<<"{\"command\":";quote(o,row.command);
        o<<",\"request_hex\":";nullable(o,legacy::hex(row.request));
        o<<",\"response_hex\":";nullable(o,legacy::hex(row.response));
        o<<",\"raw_response\":";nullable(o,row.raw);
        o<<",\"duration_ms\":"<<row.duration_ms<<",\"timeout_ms\":"<<row.timeout_ms<<",\"transport_status\":"<<row.status<<",\"error\":";nullable(o,row.error);
        o<<'}';
    }
    o<<"],\"native_report\":";
    if(native_report.empty())o<<"null";else o<<native_report;
    o<<"}\n";return o.str();
}
}
