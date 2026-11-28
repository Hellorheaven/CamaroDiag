#include <algorithm>
#include "j1979_host_maintenance.hpp"
#include <iomanip>
#include <locale>
#include <sstream>
namespace cd::hostmaint {
namespace {
void quote(std::ostream& o,std::string_view s){
    constexpr char hex[]="0123456789abcdef";
    o<<'"';
    for(unsigned char c:s){
        if(c=='"'||c=='\\')o<<'\\'<<char(c);
        else if(c<32||c>126)o<<"\\u00"<<hex[c>>4]<<hex[c&15];
        else o<<char(c);
    }
    o<<'"';
}
const char* tf(bool b){return b?"true":"false";}
std::string_view outcome(Campaign::Outcome value){
    switch(value){
    case Campaign::Outcome::Running:return "RUNNING";
    case Campaign::Outcome::NotTested:return "NOT_TESTED";
    case Campaign::Outcome::Refused:return "REFUSED";
    case Campaign::Outcome::Uncertain:return "UNCERTAIN";
    case Campaign::Outcome::AcknowledgedPartial:return "ACKNOWLEDGED_PARTIAL";
    case Campaign::Outcome::AcknowledgedReadback:return "ACKNOWLEDGED_READBACK";
    case Campaign::Outcome::AcknowledgedEvap:return "ACKNOWLEDGED_EVAP";
    }
    return "INVALID";
}
void bytes(std::ostream& o,const uint8_t* p,size_t n){
    constexpr char digits[]="0123456789ABCDEF";
    o<<'"';for(size_t i=0;i<n;++i)o<<digits[p[i]>>4]<<digits[p[i]&15];o<<'"';
}
}
std::string report(const Result& r,std::string_view digest,std::string_view replay){
    const auto& e=r.evidence;const auto& s=e.summary;
    std::ostringstream o;o.imbue(std::locale::classic());
    o<<"{\"schema\":";
    quote(o,r.elm_transport?"camarodiag-j1979-elm-maintenance-v1":"camarodiag-j1979-socketcan-maintenance-v1");
    o<<",\"protocol\":\"SAE-J1979\",\"transport\":";
    quote(o,r.elm_transport?"ELM_SERIAL_CLASSIC_ISOTP":"SOCKETCAN_ISOTP");
    o<<",\"scope\":\"PHYSICAL_PRIMARY_ECM\",\"operation\":";
    quote(o,e.scope.operation==Scope::Operation::Clear?"CLEAR":"EVAP");
    o<<",\"expected_vin\":";quote(o,{reinterpret_cast<const char*>(e.scope.vin),17});
    o<<",\"tx_can_id\":\""<<std::uppercase<<std::hex<<e.scope.tx<<"\",\"rx_can_id\":\""<<e.scope.rx
      <<"\""<<std::dec<<",\"extended\":"<<tf(e.scope.extended)<<",\"budget_ms\":"<<e.scope.budgetMs;
    o<<",\"outcome\":";quote(o,outcome(s.outcome));
    o<<",\"reason_code\":"<<unsigned(s.reason)<<",\"negative_response_code\":"<<unsigned(s.nrc)
      <<",\"duration_ms\":"<<s.durationMs
      <<",\"initial_vin_verified\":"<<tf(s.initialVin)<<",\"rechecked_vin_verified\":"<<tf(s.recheckedVin)
      <<",\"final_vin_verified\":"<<tf(s.finalVin)
      <<",\"baseline_persisted\":"<<tf(s.baselinePersisted)<<",\"intent_persisted\":"<<tf(s.intentPersisted)
      <<",\"command_attempted\":"<<tf(s.attempted)<<",\"acknowledged\":"<<tf(s.acknowledged)
      <<",\"after_confirmed_verified\":"<<tf(s.afterConfirmed)<<",\"after_pending_verified\":"<<tf(s.afterPending)
      <<",\"after_confirmed_empty\":"<<tf(s.afterConfirmedEmpty)<<",\"after_pending_empty\":"<<tf(s.afterPendingEmpty)
      <<",\"local_socket_closed\":"<<tf(r.local_socket_closed)<<",\"local_serial_closed\":"<<tf(r.local_serial_closed)
      <<",\"physical_stop_independently_verified\":false,\"physical_vehicle_verified\":false,"
        "\"physical_transceiver_disabled_verified\":false,\"monitor_completion_verified\":false,"
        "\"whole_vehicle_coverage_verified\":false,\"permits_automatic_retry\":false,\"permits_programming\":false,"
        "\"recovery_marker_requires_operator_review\":"<<tf(r.journal_created)
      <<",\"recovery_required\":"<<tf(r.recovery_latched)
      <<",\"journal_created\":"<<tf(r.journal_created)<<",\"terminal_persisted\":"<<tf(e.terminalPersisted)
      <<",\"independent_replay_verified\":"<<tf(!replay.empty())
      <<",\"journal_sha256\":";
    if(digest.empty())o<<"null";else quote(o,digest);
    o<<",\"error\":";if(r.error.empty())o<<"null";else quote(o,r.error);
    o<<",\"observations\":[";
    for(size_t i=0;i<e.count&&i<Campaign::MaxRows;++i){
        if(i)o<<',';
        const auto& row=e.rows[i];
        o<<"{\"stage\":"<<unsigned(row.stage)<<",\"status\":"<<unsigned(row.status)
          <<",\"request_hex\":";bytes(o,row.request,std::min<size_t>(row.requestSize,2));
        o<<",\"response_hex\":";bytes(o,row.response,std::min<size_t>(row.responseSize,Campaign::MaxReply));
        o<<",\"start_ms\":"<<row.startMs<<",\"end_ms\":"<<row.endMs<<",\"timeout_ms\":"<<row.timeoutMs<<'}';
    }
    o<<"],\"independent_journal\":";
    if(replay.empty())o<<"null";else o<<replay;
    o<<"}\n";return o.str();
}
}
