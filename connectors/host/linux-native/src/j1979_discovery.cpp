#include "j1979_discovery.hpp"
#include <algorithm>
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
void frame_json(std::ostream& o,const Frame& frame){
    const std::array<std::uint8_t,1> source{static_cast<std::uint8_t>(frame.source)};
    o<<"{\"source\":";quote(o,hex(source));
    o<<",\"raw_hex\":";quote(o,hex(frame.raw));
    o<<",\"payload_hex\":";quote(o,hex(frame.payload));o<<'}';
}
void frames_json(std::ostream& o,std::span<const Frame> frames){
    o<<'[';
    bool first=true;
    for(const auto& frame:frames){if(!first)o<<',';first=false;frame_json(o,frame);}
    o<<']';
}
}
Discovery replay_discovery(Protocol protocol,std::string_view response,std::string_view probe){
    if(!valid_protocol(protocol))throw std::invalid_argument("Explicit legacy protocol required");
    Discovery d{};d.protocol=protocol;d.response=response;d.probe=probe;d.queried=true;d.probed=true;
    try{
        d.observations=frames(protocol,"0100",response);
        d.responders=classify(d.observations);
        if(fixed_protocol(probe)!=protocol)d.error="FIXED_PROTOCOL_NOT_VERIFIED";
        else if(d.observations.empty())d.error="NO_RESPONSE";
        else if(std::none_of(d.responders.begin(),d.responders.end(),[](const Responder& r){return r.status=="PASSED";}))
            d.error="NO_UNAMBIGUOUS_POSITIVE_SOURCE";
        else d.complete=true;
    }catch(const std::invalid_argument&){d.error="MALFORMED_PID00_EVIDENCE";}
    return d;
}
Discovery discover(Elm& elm,unsigned budget,Clock clock,Current current){
    if(!elm.headered()||!elm.ready()||!clock||!current||budget==0||budget>60000)
        throw std::invalid_argument("Discovery requires a configured headered adapter and explicit budget");
    Discovery d{};d.protocol=elm.protocol();const auto begin=clock();
    const auto remaining=[&](){
        if(!current())throw Cancelled();
        const auto now=clock();
        if(now<begin||now-begin>=budget)throw TransportError("Discovery budget expired");
        return static_cast<unsigned>(budget-(now-begin));
    };
    try{
        const Data request{1,0};
        const auto first_timeout=remaining();
        d.queried=true;d.response=elm.exchange(request,first_timeout);
        const auto probe_timeout=remaining();
        d.probed=true;d.probe=elm.probe(probe_timeout);
        (void)remaining();
        return replay_discovery(d.protocol,d.response,d.probe);
    }catch(const Cancelled&){d.error="CANCELLED";}
    catch(const TransportError&){d.error="TRANSPORT_OR_BUDGET";}
    try{if(d.queried){d.observations=frames(d.protocol,"0100",d.response);d.responders=classify(d.observations);}}catch(const std::invalid_argument&){}
    return d;
}
std::string discovery_report(const Discovery& d){
    std::ostringstream o;
    o<<"{\"schema\":\"camarodiag-j1979-legacy-discovery-linux-v1\",\"protocol\":"
      <<static_cast<unsigned>(d.protocol)<<",\"protocol_name\":";
    quote(o,name(d.protocol));o<<",\"complete\":"<<(d.complete?"true":"false")
      <<",\"physical_vehicle_verified\":false,\"permits_programming\":false,\"automatic_selection\":false,\"queried\":"
      <<(d.queried?"true":"false")<<",\"probed\":"<<(d.probed?"true":"false")<<",\"raw_response\":";
    quote(o,d.response);o<<",\"raw_protocol_probe\":";quote(o,d.probe);
    o<<",\"error\":";if(d.error.empty())o<<"null";else quote(o,d.error);
    o<<",\"observations\":";frames_json(o,d.observations);o<<",\"responders\":[";
    bool first=true;
    for(const auto& r:d.responders){
        if(!first)o<<',';
        first=false;
        const std::array<std::uint8_t,1> source{static_cast<std::uint8_t>(r.source)};
        o<<"{\"source\":";quote(o,hex(source));o<<",\"status\":";quote(o,r.status);
        o<<",\"detail\":";if(r.detail.empty())o<<"null";else quote(o,r.detail);
        o<<",\"bitmap\":";if(r.bitmap)quote(o,hex(*r.bitmap));else o<<"null";
        o<<",\"next_page\":";if(r.next)o<<(*r.next?"true":"false");else o<<"null";
        o<<",\"supported_pids\":[";
        bool first_pid=true;
        for(auto pid:r.supported){if(!first_pid)o<<',';first_pid=false;o<<pid;}
        o<<"],\"observations\":";frames_json(o,r.observations);o<<'}';
    }
    o<<"]}\n";return o.str();
}
}
