#include "j1979_legacy_discovery_history.hpp"
#include <cassert>
#include <stdexcept>
using namespace cd::legacy;
namespace {
std::string replace(std::string s,std::string_view from,std::string_view to){
    const auto pos=s.find(from);assert(pos!=std::string::npos);s.replace(pos,from.size(),to);return s;
}
void rejects(const std::string& s){
    bool rejected=false;
    try{(void)history::inspect(cd::json::parse(s));}catch(const std::invalid_argument&){rejected=true;}
    catch(const std::runtime_error&){rejected=true;}
    assert(rejected);
}
}
int main(){
    Data raw{0x48,0x6b,0x10,0x41,0,0x80,0,0,1};
    raw.push_back(checksum(Protocol::iso9141,raw));
    const auto wire=hex(raw);
    const auto row=std::string("{\"source\":\"10\",\"status\":\"PASSED\",\"supported_pid_bitmap\":\"80000001\",\"raw_frames\":[\"")+wire+"\"]}";
    const auto android=std::string("{\"legacySourceDiscovery\":{\"selected_protocol\":3,\"raw_protocol_response\":\"atdpn\\r3>\",\"observed_protocol\":3,\"raw_response\":\"")+wire+">\",\"error\":null,\"sources\":["+row+"],\"verified_sources\":[\"10\"]}}";
    auto a=history::inspect(cd::json::parse(android));
    assert(a.platform=="ANDROID"&&a.completed&&a.checksum_verified&&a.probe_verified&&a.eligible==std::vector<unsigned>{16});
    assert(a.responders[0].supported==std::vector<unsigned>{1}&&a.responders[0].next==true);
    rejects(replace(android,"80000001","00000001"));
    rejects(replace(android,"\"verified_sources\":[\"10\"]","\"verified_sources\":[]"));
    rejects(replace(android,"\"observed_protocol\":3","\"observed_protocol\":4"));
    rejects(replace(android,"\"selected_protocol\":3","\"selected_protocol\":4"));
    rejects(replace(android,"\"error\":null","\"error\":\"MALFORMED_LEGACY_FRAME\""));
    const auto twice=replace(replace(android,wire+">",wire+"\\r"+wire+">"),"\"raw_frames\":[\""+wire+"\"]","\"raw_frames\":[\""+wire+"\",\""+wire+"\"]");
    assert(history::inspect(cd::json::parse(twice)).eligible.size()==1);
    const auto frame=std::string("{\"source\":\"10\",\"raw_hex\":\"")+wire+"\",\"payload_hex\":\"410080000001\"}";
    const auto elm=std::string("{\"schema\":\"camarodiag-functional-j1979-legacy-discovery-v1\",\"expected_protocol\":3,\"observed_protocol\":3,\"completed\":true,\"stop_reason\":null,\"steps\":[{\"request_hex\":\"0100\",\"raw_response\":\"")+wire+">\",\"frames\":["+frame+"]}],\"responders\":[{\"source\":\"10\",\"status\":\"PASSED\",\"supported_pids\":[1],\"has_next_page\":true,\"detail\":null,\"frames\":["+frame+"]}]}";
    auto e=history::inspect(cd::json::parse(elm));
    assert(e.completed&&e.checksum_verified&&!e.probe_verified&&e.eligible.size()==1);
    rejects(replace(elm,"\"supported_pids\":[1]","\"supported_pids\":[2]"));
    rejects(replace(elm,"\"has_next_page\":true","\"has_next_page\":false"));
    rejects(replace(elm,"\"completed\":true","\"completed\":false"));
    rejects(replace(elm,"\"request_hex\":\"0100\"","\"request_hex\":\"010C\""));
    rejects(replace(elm,"\"payload_hex\":\"410080000001\"","\"payload_hex\":\"410080000000\""));
    const std::string driver=R"({"schema":"camarodiag-functional-j1979-legacy-discovery-v1","variant":"Iso9141","selected_response_source":null,"baud":10400,"error":null,"discovery":{"request_hex":"0100","tx_hex":"686AF10100","has_verified_source":true,"sources":[{"response_source":"10","status":"PASSED","detail":"80000001","records":[{"source":"10","raw_hex":"486B10410080000001","payload_hex":"410080000001","timestamp":0,"in_frame_response_hex":null}]}]}})";
    auto d=history::inspect(cd::json::parse(driver));
    assert(d.completed&&!d.checksum_verified&&!d.probe_verified&&d.eligible.size()==1);
    rejects(replace(driver,"10400","41600"));
    rejects(replace(driver,"486B10","486B11"));
    rejects(replace(driver,"\"timestamp\":0","\"timestamp\":4294967296"));
    rejects(replace(driver,"\"has_verified_source\":true","\"has_verified_source\":false"));
    rejects(replace(driver,"\"selected_response_source\":null","\"selected_response_source\":\"10\""));
    rejects(replace(driver,"\"error\":null","\"physical_vehicle_verified\":true,\"error\":null"));
    auto camel_android=android;
    for(const auto& [snake,camel]:std::vector<std::pair<std::string,std::string>>{
        {"selected_protocol","selectedProtocol"},{"observed_protocol","observedProtocol"},
        {"raw_protocol_response","rawProtocolResponse"},{"raw_response","rawResponse"},
        {"supported_pid_bitmap","supportedPidBitmap"},{"raw_frames","rawFrames"},{"verified_sources","verifiedSources"}})
        camel_android=replace(camel_android,snake,camel);
    assert(history::inspect(cd::json::parse(camel_android)).eligible==a.eligible);
    rejects(replace(camel_android,"\"selectedProtocol\":3","\"selectedProtocol\":3,\"selected_protocol\":3"));
    auto camel_driver=driver;
    for(const auto& [snake,camel]:std::vector<std::pair<std::string,std::string>>{
        {"selected_response_source","selectedResponseSource"},{"request_hex","requestHex"},{"tx_hex","txHex"},
        {"has_verified_source","hasVerifiedSource"},{"response_source","responseSource"},
        {"raw_hex","rawHex"},{"payload_hex","payloadHex"},{"in_frame_response_hex","inFrameResponseHex"}})
        camel_driver=replace(camel_driver,snake,camel);
    camel_driver=replace(camel_driver,",\"inFrameResponseHex\":null","");
    assert(history::inspect(cd::json::parse(camel_driver)).eligible==d.eligible);
    rejects(replace(camel_driver,"\"rawHex\":","\"raw_hex\":\"486B10410080000001\",\"rawHex\":"));
    rejects(replace(camel_driver,"\"error\":null","\"physicalVehicleVerified\":true,\"error\":null"));
    for(unsigned n=1;n<=5;++n){
        const auto p=static_cast<Protocol>(n);
        Data pos{static_cast<std::uint8_t>(n>=4?0x86:n==1?0x41:0x48),
            static_cast<std::uint8_t>(n>=4?0xf1:0x6b),0x10,0x41,0,0x80,0,0,1};
        pos.push_back(checksum(p,pos));
        auto variant=replace(replace(elm,"\"expected_protocol\":3","\"expected_protocol\":"+std::to_string(n)),
            "\"observed_protocol\":3","\"observed_protocol\":"+std::to_string(n));
        while(variant.find(wire)!=std::string::npos&&wire!=hex(pos))variant=replace(variant,wire,hex(pos));
        const auto inspected=history::inspect(cd::json::parse(variant));
        assert(inspected.protocol==p&&inspected.eligible==std::vector<unsigned>{16});
    }
    auto proof=cd::json::parse(history::report(d,"test-digest"));
    assert(!proof.at("physical_vehicle_verified").boolean()&&!proof.at("automatic_selection_permitted").boolean());
    assert(proof.at("source_sha256").string()=="test-digest");
}
