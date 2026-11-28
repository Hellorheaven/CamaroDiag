#include "j1979_network_history.hpp"
#include <cassert>
#include <stdexcept>
using namespace cd::network;
namespace {
constexpr auto vin="1G1FF3D36C9104199";
std::string replace(std::string s,std::string_view a,std::string_view b){
    const auto pos=s.find(a);assert(pos!=std::string::npos);s.replace(pos,a.size(),b);return s;
}
std::string step(std::string_view q,std::string_view r){
    return "{\"request_hex\":\""+std::string(q)+"\",\"response_hex\":\""+std::string(r)+
        "\",\"status\":\"PASSED\",\"duration_ms\":5,\"detail\":null}";
}
std::string physical(){
    const std::vector<std::pair<std::string_view,std::string_view>> rows{
        {"0902","4902013147314646334433364339313034313939"},{"03","4300"},{"07","4700"},{"0A","4A00"},
        {"0100","410008000000"},{"0105","410564"},{"020200","4202000000"},{"0600","460000000000"},
        {"0800","480000000000"},{"0900","490040000000"}};
    std::string s="{\"completed\":true,\"declared_vin\":\""+std::string(vin)+"\",\"observed_vin\":\""+vin+
        "\",\"stop_reason\":null,\"steps\":[";
    for(std::size_t i=0;i<rows.size();++i){if(i)s+=',';s+=step(rows[i].first,rows[i].second);}
    return s+"]}";
}
std::string discovery(){
    Frame a{0x7e8,false,false,false,{6,0x41,0,8,0,0,0,0}};
    Frame b=a;b.id=0x7e9;
    return report(replay_discovery(false,true,std::array{a,b},"",20));
}
std::string root(){
    auto p=physical();
    return "{\"schema\":\"camarodiag-functional-j1979-can-network-v1\",\"addressing\":\"11BIT\","
        "\"completed\":true,\"stop_reason\":null,\"declared_vin\":\""+std::string(vin)+
        "\",\"selected_response_ids\":[\"7E8\",\"7E9\"],\"discovery\":"+discovery()+
        ",\"ecu_reports\":[{\"tx_can_id\":\"7E0\",\"rx_can_id\":\"7E8\",\"status\":\"PASSED\",\"report\":"+p+
        "},{\"tx_can_id\":\"7E1\",\"rx_can_id\":\"7E9\",\"status\":\"PASSED\",\"report\":"+p+"}]}";
}
void rejects(const std::string& s){
    bool failed=false;
    try{(void)history::inspect_network(cd::json::parse_report(s),std::string(vin));}catch(const std::exception&){failed=true;}
    assert(failed);
}
}
int main(){
    auto data=root();
    auto r=history::inspect_network(cd::json::parse_report(data),std::string(vin));
    assert(r.completed&&r.rows.size()==2&&r.rows[1].plan->measurements[0].number==60);
    rejects(replace(data,"\"selected_response_ids\":[\"7E8\",\"7E9\"]","\"selected_response_ids\":[\"7E9\",\"7E8\"]"));
    rejects(replace(data,"\"selected_response_ids\":[\"7E8\",\"7E9\"]","\"selected_response_ids\":[\"7E8\",\"7E8\"]"));
    rejects(replace(data,"\"selected_response_ids\":[\"7E8\",\"7E9\"]","\"selected_response_ids\":[\"7E8\",\"7EA\"]"));
    rejects(replace(data,"\"request_hex\":\"0105\"","\"request_hex\":\"04\""));
    rejects(replace(data,"\"declared_vin\":\"1G1FF3D36C9104199\"","\"declared_vin\":\"1G1FF3D36C9104198\""));
    auto partial="{\"schema\":\"camarodiag-functional-j1979-can-network-v1\",\"addressing\":\"11BIT\","
        "\"completed\":false,\"stop_reason\":\"SELECTED_RESPONDER_UNVERIFIED\",\"declared_vin\":\""+std::string(vin)+
        "\",\"selected_response_ids\":[\"7EA\"],\"discovery\":"+discovery()+
        ",\"ecu_reports\":[{\"tx_can_id\":\"7E2\",\"rx_can_id\":\"7EA\",\"status\":\"NOT_TESTED\",\"report\":null,\"error\":\"SELECTED_RESPONDER_UNVERIFIED\"}]}";
    auto q=history::inspect_network(cd::json::parse_report(partial),std::string(vin));
    assert(!q.completed&&!q.rows[0].plan);
    rejects(replace(partial,"\"report\":null","\"report\":"+physical()));
    auto windows="{\"schema\":\"camarodiag-functional-j1979-can-network-v1\",\"profile\":33,\"baud\":500000,"
        "\"completed\":true,\"discovery\":"+discovery()+",\"network\":"+data+"}";
    auto w=history::inspect_network(cd::json::parse_report(windows),std::string(vin));
    assert(w.completed&&w.admission.platform=="J2534");
    rejects(replace(windows,"\"ignored_frame_count\":0","\"ignored_frame_count\":1"));
    auto proof=cd::json::parse_report(history::report(r,"sha"));
    assert(!proof.at("derived_projections_verified").boolean()&&!proof.at("current_authorization_verified").boolean());
}
