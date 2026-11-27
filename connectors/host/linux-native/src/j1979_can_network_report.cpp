#include "j1979_can_network.hpp"
#include <iomanip>
#include <locale>
#include <sstream>
namespace cd::network {
namespace {
const char* tf(bool b){return b?"true":"false";}
std::string hex(std::uint32_t n){std::ostringstream o;o.imbue(std::locale::classic());o<<std::uppercase<<std::hex<<n;return o.str();}
void bytes(std::ostream& o,std::span<const std::uint8_t> b){
    constexpr char digits[]="0123456789ABCDEF";
    o<<'"';for(auto x:b)o<<digits[x>>4]<<digits[x&15];o<<'"';
}
void frame(std::ostream& o,const Frame& f){
    o<<"{\"can_id\":\""<<hex(f.id)<<"\",\"extended\":"<<tf(f.extended)<<",\"rtr\":"<<tf(f.rtr)
      <<",\"error\":"<<tf(f.error)<<",\"data_hex\":";
    bytes(o,f.data);o<<'}';
}
template<class R,class F>void array(std::ostream& o,const R& rows,F emit){
    o<<'[';bool first=true;
    for(const auto& x:rows){if(!first)o<<',';first=false;emit(x);}
    o<<']';
}
}
std::string report(const Discovery& d){
    std::ostringstream o;o.imbue(std::locale::classic());
    o<<"{\"schema\":\"camarodiag-j1979-can-discovery-linux-v1\",\"protocol\":\"SAE-J1979\","
      "\"scope\":\"FUNCTIONAL_PID00_DISCOVERY\",\"addressing\":\""<<(d.extended?"29BIT":"11BIT")
      <<"\",\"request\":";
    frame(o,functional_request(d.extended));
    o<<",\"request_sent\":"<<tf(d.sent)<<",\"completed\":"<<tf(d.completed)
      <<",\"duration_ms\":"<<d.duration_ms<<",\"ignored_frame_count\":"<<d.ignored
      <<",\"stop_reason\":";
    if(d.stop.empty())o<<"null";else o<<'"'<<d.stop<<'"';
    o<<",\"cancelled\":"<<tf(d.stop=="CANCELLED")
      <<",\"physical_vehicle_verified\":false,\"whole_vehicle_coverage_verified\":false,"
        "\"permits_programming\":false,\"not_executed\":[\"MODE_04_CLEAR\",\"MODE_08_CONTROL\"],"
        "\"responders\":";
    array(o,d.responders,[&](const Responder& r){
        const auto& c=r.classification;
        o<<"{\"tx_can_id\":\""<<hex(c.pair.tx)<<"\",\"rx_can_id\":\""<<hex(c.pair.rx)
          <<"\",\"status\":\""<<(c.state==State::positive?"PASSED":
              c.state==State::negative||c.state==State::pending?"NOT_TESTED":"FAILED")<<"\",\"detail\":";
        switch(c.state){
        case State::positive:o<<"null";break;
        case State::negative:o<<"\"NRC_"<<(c.nrc<16?"0":"")<<hex(c.nrc)<<'"';break;
        case State::pending:o<<"\"PENDING_WITHOUT_FINAL_RESPONSE\"";break;
        case State::invalid:o<<"\"INVALID_PID00_FRAME\"";break;
        case State::conflicting:o<<"\"CONFLICTING_RESPONSES\"";break;
        }
        o<<",\"supported_pids\":";
        if(c.state==State::positive){
            std::vector<unsigned> ids;
            for(unsigned bit=0;bit<31;++bit)if(c.bitmap[bit/8]&(0x80u>>(bit%8)))ids.push_back(bit+1);
            array(o,ids,[&](auto id){o<<id;});
        }else o<<"null";
        o<<",\"next_page\":";
        if(c.state==State::positive)o<<tf((c.bitmap[3]&1)!=0);else o<<"null";
        o<<",\"negative_response_code\":";
        if(c.state==State::negative)o<<unsigned(c.nrc);else o<<"null";
        o<<",\"frames\":";array(o,r.frames,[&](const Frame& f){frame(o,f);});o<<'}';
    });
    o<<",\"eligible_response_ids\":";array(o,d.eligible,[&](auto id){o<<'"'<<hex(id)<<'"';});
    o<<",\"raw_frames\":";array(o,d.frames,[&](const Frame& f){frame(o,f);});
    o<<"}\n";return o.str();
}
std::string report(const Scan& s){
    std::ostringstream o;o.imbue(std::locale::classic());
    auto quoted=[&](std::string_view value){
        o<<'"';for(unsigned char c:value){
            if(c=='"'||c=='\\')o<<'\\'<<char(c);
            else if(c>=32&&c<=126)o<<char(c);
            else o<<"?";
        }o<<'"';
    };
    o<<"{\"schema\":\"camarodiag-j1979-can-network-linux-v1\",\"protocol\":\"SAE-J1979\","
       "\"scope\":\"EXPLICIT_PHYSICAL_RESPONDER_SET\",\"declared_vin\":";
    quoted(s.vin);o<<",\"completed\":"<<tf(s.completed)<<",\"duration_ms\":"<<s.duration_ms<<",\"stop_reason\":";
    if(s.stop.empty())o<<"null";else quoted(s.stop);
    o<<",\"physical_vehicle_verified\":false,\"whole_vehicle_coverage_verified\":false,"
        "\"permits_programming\":false,\"selected_response_ids\":";
    array(o,s.selected,[&](auto id){quoted(hex(id));});
    o<<",\"discovery\":"<<report(s.discovery)<<",\"ecu_reports\":";
    array(o,s.ecus,[&](const EcuReport& row){
        o<<"{\"tx_can_id\":";quoted(hex(row.pair.tx));o<<",\"rx_can_id\":";quoted(hex(row.pair.rx));
        o<<",\"status\":";quoted(row.status);o<<",\"error\":";
        if(row.error.empty())o<<"null";else quoted(row.error);
        o<<",\"report\":";if(row.json.empty())o<<"null";else o<<row.json;
        o<<'}';
    });
    o<<"}\n";return o.str();
}
}
