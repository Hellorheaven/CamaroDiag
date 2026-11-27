#include "j1979_cu45_session.hpp"
#include <locale>
#include <sstream>
namespace cd::cu45 {
namespace {
void quoted(std::ostream& o,std::string_view s){
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
}
std::string report(const Result& r,std::string_view digest){
    std::ostringstream o;o.imbue(std::locale::classic());
    const bool archive=r.mode==Mode::archive;
    o<<"{\"schema\":";
    quoted(o,archive?"camarodiag-j1979-native-maintenance-archive-linux-v1":
        "camarodiag-functional-j1979-native-maintenance-linux-v1");
    o<<",\"protocol\":\"SAE-J1979\",\"source\":";
    quoted(o,archive?"READ_ONLY_USB_CU5":"LIVE_USB_CU4");
    o<<",\"scope\":{\"operation\":";
    quoted(o,r.scope.operation==1?"CLEAR":"EVAP");
    o<<",\"extended\":"<<tf(r.scope.extended)<<",\"request_can_id\":\""<<std::uppercase<<std::hex<<r.scope.tx
        <<"\",\"response_can_id\":\""<<r.scope.rx<<"\""<<std::dec<<",\"budget_ms\":"<<r.scope.budget
        <<",\"expected_vin\":";quoted(o,r.scope.vin);o<<'}';
    o<<",\"device_credential_authenticated\":"<<tf(r.authenticated)
     <<",\"proof_transmission_attempted\":"<<tf(r.proof_attempted)
     <<",\"client_proof_sent\":"<<tf(r.proof_sent)
     <<",\"cancelled\":"<<tf(r.cancelled)
     <<",\"journal_file_created\":"<<tf(r.file_created)
     <<",\"journal_bytes_received\":"<<r.journal.size()
     <<",\"recovery_required\":"<<tf(r.recovery_required());
    if(archive){
        o<<",\"maintenance_command_sent\":false,\"archive_transfer_verified\":"<<tf(r.verified())
         <<",\"archive_close_receipt_verified\":"<<tf(r.verified()&&r.receipt_verified)
         <<",\"declared_archive\":";
        if(r.declared){
            const auto& d=*r.declared;
            o<<"{\"bytes\":"<<d.length<<",\"records\":"<<d.records<<",\"terminal_present\":"<<tf(d.terminal)
             <<",\"damaged_tail\":"<<tf(d.damaged)<<",\"recovery_required\":"<<tf(d.recovery)<<'}';
        }else o<<"null";
    }else{
        const bool terminal=r.verified()&&r.inspection->terminal.has_value();
        o<<",\"journal_transfer_verified\":"<<tf(r.verified())<<",\"terminal_present\":"<<tf(terminal)
         <<",\"command_outcome_unknown\":"<<tf(r.proof_attempted&&(!terminal||r.inspection->terminal->outcome==3))
         <<",\"declared_recovery_in_envelope\":";
        if(r.declared)o<<tf(r.declared->recovery);else o<<"null";
    }
    o<<",\"physical_vehicle_verified\":false,\"physical_stop_independently_verified\":false,"
        "\"evidence_authenticated\":false,\"operator_consent_independently_verified\":false,"
        "\"permits_automatic_retry\":false,\"permits_programming\":false,\"host_elapsed_ms\":"<<r.elapsed_ms
        <<",\"journal_sha256\":";
    if(digest.empty())o<<"null";else quoted(o,digest);
    o<<",\"inspection\":";
    if(r.verified()&&!digest.empty()){
        auto json=cm1::report(*r.inspection,digest);
        if(!json.empty()&&json.back()=='\n')json.pop_back();
        o<<json;
    }else o<<"null";
    o<<",\"error\":";
    if(r.error.empty())o<<"null";else quoted(o,r.error);
    o<<"}\n";return o.str();
}
}
