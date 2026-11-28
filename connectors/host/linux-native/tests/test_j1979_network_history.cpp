#include "j1979_network_history.hpp"
#include <cassert>
#include <stdexcept>
using namespace cd::network;
namespace {
std::string replace(std::string s,std::string_view old,std::string_view value){
    auto p=s.find(old);assert(p!=std::string::npos);s.replace(p,old.size(),value);return s;
}
void rejects(const std::string& s){
    bool failed=false;
    try{(void)history::inspect_discovery(cd::json::parse(s));}catch(const std::exception&){failed=true;}
    assert(failed);
}
}
int main(){
    const Frame positive{0x7e8,false,false,false,{6,0x41,0,8,0,0,1,0xcc}};
    const Frame pending{0x7e8,false,false,false,{3,0x7f,1,0x78,0,0,0,0}};
    const Frame negative{0x7e9,false,false,false,{3,0x7f,1,0x11,0,0,0,0}};
    auto d=replay_discovery(false,true,std::array{pending,positive,negative},"",100);
    auto native=report(d);
    auto inspected=history::inspect_discovery(cd::json::parse(native));
    assert(inspected.discovery.completed&&inspected.discovery.eligible==std::vector<std::uint32_t>{0x7e8});
    assert(inspected.discovery.responders[0].frames.size()==2);
    rejects(replace(native,"\"supported_pids\":[5]","\"supported_pids\":[6]"));
    rejects(replace(native,"\"negative_response_code\":17","\"negative_response_code\":18"));
    rejects(replace(native,"\"request_sent\":true","\"request_sent\":false"));
    rejects(replace(native,"\"physical_vehicle_verified\":false","\"physical_vehicle_verified\":true"));
    rejects(replace(native,"\"next_page\":true","\"next_page\":false"));
    rejects(replace(native,"\"eligible_response_ids\":[\"7E8\"]","\"eligible_response_ids\":[\"7E9\"]"));
    const Frame ignored{0x123,false,false,false,{1,2,3}};
    auto ignored_report=report(replay_discovery(false,true,std::array{positive,ignored},"",100));
    auto with_ignored=history::inspect_discovery(cd::json::parse(ignored_report));
    assert(with_ignored.discovery.ignored==1&&with_ignored.discovery.frames.size()==2&&with_ignored.discovery.completed);
    rejects(replace(ignored_report,"\"ignored_frame_count\":1","\"ignored_frame_count\":0"));
    rejects(replace(ignored_report,"\"stop_reason\":null","\"stop_reason\":\"BOGUS\""));
    auto historical=replace(native,"camarodiag-j1979-can-discovery-linux-v1","camarodiag-functional-j1979-can-discovery-v1");
    assert(history::inspect_discovery(cd::json::parse(replace(historical,"\"supported_pids\":[5]","\"supported_pids\":[5,32]"))).discovery.completed);
    Frame conflict=positive;conflict.data[3]=4;
    const auto bad=replay_discovery(false,true,std::array{positive,conflict},"",100);
    auto b=history::inspect_discovery(cd::json::parse(report(bad)));
    assert(!b.discovery.completed&&b.discovery.eligible.empty());
    assert(b.discovery.responders[0].classification.state==State::conflicting);
    Frame ext=positive;ext.id=0x18daf110;ext.extended=true;
    auto e=history::inspect_discovery(cd::json::parse(report(replay_discovery(true,true,std::array{ext},"",10))));
    assert(e.discovery.extended&&e.discovery.eligible==std::vector<std::uint32_t>{0x18daf110});
    const std::string android=R"({"schema":"camarodiag-j1979-network-v1","protocol":6,"completed":true,"selectedResponseIds":[],"ecus":[],"discovery":{"completed":true,"requestSent":true,"stopReason":null,"observedProtocol":6,"rawProtocol":"ATDPN\r6>","frames":[{"responseId":2024,"dataHex":"06410008000000"}],"responders":[{"requestId":2016,"responseId":2024,"status":"PASSED","detail":null,"supportedPids":[5],"nextPage":false,"negativeResponseCode":null,"frames":["06410008000000"]}]}})";
    auto a=history::inspect_discovery(cd::json::parse(android));
    assert(a.probe_verified&&a.discovery.completed&&a.discovery.frames[0].data.size()==7);
    rejects(replace(android,"ATDPN\\r6>","A6"));
    rejects(replace(android,"\"requestSent\":true","\"requestSent\":true,\"request_sent\":true"));
    rejects(replace(android,"\"selectedResponseIds\":[]","\"selectedResponseIds\":[2024]"));
    rejects(replace(android,"\"ecus\":[]","\"ecus\":[{}]"));
    auto windows=std::string("{\"schema\":\"camarodiag-functional-j1979-can-discovery-v1\",\"profile\":33,\"completed\":true,\"discovery\":")+native+"}";
    assert(history::inspect_discovery(cd::json::parse(windows)).platform=="J2534");
    rejects(replace(windows,"\"profile\":33","\"profile\":34"));
    auto proof=cd::json::parse(history::report(a,"digest"));
    assert(proof.at("verification_scope").string()=="DISCOVERY_ONLY"&&!proof.at("authenticated").boolean());
}
