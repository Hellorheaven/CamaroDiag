#include "j1979_can_freeze_history.hpp"
#include "j1979_legacy_freeze_history.hpp"
#include <algorithm>
#include <cassert>
#include <sstream>
#include <stdexcept>
namespace {
using namespace cd::legacy;
using cd::json::quote;
constexpr std::string_view vin="1G1FA1RXXR0123456";
const std::string vin_reply=[](){
    Data p{0x49,2,1};p.insert(p.end(),vin.begin(),vin.end());return hex(p);
}();
std::string observation(std::string_view q,std::string_view r,std::string_view evidence,std::string_view status="PASSED",std::string_view detail="null"){
    return "{\"request_hex\":"+quote(q)+",\"response_hex\":"+quote(r)+
        ",\"transport_outcome\":\"RECEIVED\",\"duration_ms\":1,\"transmission_attempted\":true,\"status\":"+
        quote(status)+",\"detail\":"+std::string(detail)+",\"evidence\":"+std::string(evidence)+"}";
}
std::string result(){
    const std::string identity="{\"vin\":"+quote(vin)+"}";
    const auto i=observation("0902",vin_reply,identity);
    const std::string page=R"({"base":0,"bitmap_hex":"48000000","advertised_pids":[2,5],"has_next_page":false})";
    const auto p=observation("020000","42000048000000","{\"frame\":0,\"pid\":0,\"data_hex\":\"48000000\",\"page\":"+page+"}");
    const auto trigger=observation("020200","4202000123",R"({"frame":0,"pid":2,"data_hex":"0123","trigger_present":true,"trigger_dtc":"P0123"})");
    const auto sample=observation("020500","42050064",R"({"frame":0,"pid":5,"data_hex":"64","measurement":{"mode":2,"pid":5,"frame":0,"name":"coolant-temperature","unit":"degC","number":60.0}})");
    return "{\"plan\":{\"expected_vin\":"+quote(vin)+",\"frames\":[0],\"selected_pids\":[5],\"budget_ms\":10000},"
        "\"initial_vin\":"+quote(vin)+",\"final_vin\":"+quote(vin)+
        ",\"completed\":true,\"stop_reason\":null,\"identity_rechecked\":true,\"frames\":[{\"frame\":0,\"required_frame\":true,"
        "\"status\":\"COMPLETE\",\"trigger_dtc\":\"P0123\",\"final_trigger_dtc\":\"P0123\",\"trigger_stable\":true,"
        "\"inventory_complete\":true,\"verified_pages\":["+page+"],\"advertised_pids\":[2,5],\"read_pids\":[5],"
        "\"unadvertised_selected_pids\":[],\"not_read_selected_pids\":[],\"measurement_steps\":[3],\"detail\":null}],"
        "\"steps\":["+i+","+p+","+trigger+","+sample+","+trigger+","+i+"],\"unvisited_frames\":[],\"all_frames_stable\":true,"
        "\"physical_vehicle_verified\":false,\"authenticated\":false,\"whole_vehicle_coverage_verified\":false,"
        "\"permits_programming\":false,\"duration_ms\":6}";
}
std::string can_lines(const Data& p){
    const auto line=[](const Data& data){
        std::string out="7E8";for(auto b:data)out+=" "+hex(Data{b});return out;
    };
    if(p.size()<=7){Data sf{static_cast<std::uint8_t>(p.size())};sf.insert(sf.end(),p.begin(),p.end());return line(sf);}
    Data ff{static_cast<std::uint8_t>(0x10|(p.size()>>8)),static_cast<std::uint8_t>(p.size())};
    ff.insert(ff.end(),p.begin(),p.begin()+6);
    std::string out=line(ff);unsigned seq=1;
    for(std::size_t offset=6;offset<p.size();offset+=7){
        Data cf{static_cast<std::uint8_t>(0x20|(seq++&15))};
        cf.insert(cf.end(),p.begin()+static_cast<std::ptrdiff_t>(offset),
            p.begin()+static_cast<std::ptrdiff_t>(std::min(offset+7,p.size())));
        out+="\r"+line(cf);
    }
    return out+">";
}
std::string common(std::string_view schema,std::string_view transport){
    return "{\"schema\":"+quote(schema)+",\"protocol\":\"SAE-J1979\",\"transport\":"+quote(transport)+
        ",\"version\":\"test\",\"started_at_utc\":\"2026-11-28T01:00:00Z\",\"finished_at_utc\":\"2026-11-28T01:00:01+00:00\","
        "\"transport_problem\":null,\"execution_completed\":true,";
}
const std::vector<std::string> commands{"0902","020000","020200","020500","020200","0902"};
const std::vector<Data> responses=[](){
    Data id{0x49,2,1};id.insert(id.end(),vin.begin(),vin.end());
    return std::vector<Data>{id,unhex("42000048000000"),unhex("4202000123"),unhex("42050064"),unhex("4202000123"),id};
}();
std::string can_report(std::string_view transport){
    std::ostringstream o;
    o<<common("camarodiag-functional-j1979-freeze-v1",transport)
        <<"\"tx_can_id\":\"7E0\",\"rx_can_id\":\"7E8\",\"physical_channel_confirmed_by_operator\":true,";
    if(transport=="LINUX_SOCKETCAN_ISOTP")o<<"\"interface\":\"vcan0\",";
    else if(transport=="WINDOWS_J2534_ISOTP")o<<"\"adapter\":\"driver\",\"profile\":33,";
    else{
        o<<"\"adapter\":\"ELM\",\"profile\":6,\"observed_protocol\":6,\"raw_protocol_response\":\"6>\",\"adapter_exchanges\":[";
        for(std::size_t i=0;i<commands.size();++i){
            if(i)o<<',';
            o<<"{\"step\":"<<i<<",\"request_hex\":"<<quote(commands[i])<<",\"raw_response\":"<<quote(can_lines(responses[i]))<<'}';
        }
        o<<"],";
    }
    o<<"\"result\":"<<result()<<'}';return o.str();
}
std::string legacy_report(bool driver){
    std::ostringstream o;
    o<<common("camarodiag-functional-j1979-legacy-freeze-v1",driver?"WINDOWS_J2534_LEGACY":"ELM_SERIAL_LEGACY_HEADERED")
        <<"\"scope\":\"FUNCTIONAL_SELECTED_RESPONSE_FREEZE\",\"physically_addressed\":false,"
          "\"functional_scope_confirmed_by_operator\":true,\"expected_protocol\":2,\"selected_response_source\":\"10\","
          "\"response_hex_representation\":\"NORMALIZED_APPLICATION_PAYLOAD\",\"response_attribution\":"
        <<quote(driver?"J2534_DRIVER_MANAGED_SELECTED_HEADER":"CHECKSUM_VERIFIED_LEGACY_HEADER")<<',';
    if(driver)o<<"\"adapter\":\"J2534\",\"j2534_protocol_id\":1,\"baud\":10400,\"initialization_evidence\":null,"
        "\"physical_vehicle_verified\":false,\"whole_vehicle_coverage_verified\":false,";
    else o<<"\"port\":\"/dev/ttyUSB0\",\"baud\":38400,\"dedicated_adapter_confirmed_by_operator\":true,"
        "\"observed_protocol\":2,\"other_response_sources\":[],";
    o<<"\"transport_exchanges\":[";
    bool first=true;
    auto emit=[&](unsigned index,std::string_view q,std::string_view status,const std::vector<Data>& rows){
        if(!first)o<<',';
        first=false;
        o<<"{\"logical_query\":"<<index<<",\"request_hex\":"<<quote(q)<<",\"status\":"<<quote(status)
            <<",\"detail\":null,\"frames\":[";
        std::string transcript;bool first_frame=true;
        for(const auto& payload:rows){
            Data wire{0x48,0x6b,0x10};wire.insert(wire.end(),payload.begin(),payload.end());
            if(!driver)wire.push_back(checksum(Protocol::vpw,wire));
            if(!first_frame){o<<',';transcript+='\r';}
            first_frame=false;transcript+=hex(wire);
            o<<"{\"source\":\"10\",\"raw_hex\":"<<quote(hex(wire))<<",\"payload_hex\":"<<quote(hex(payload));
            if(driver)o<<",\"timestamp\":1";
            o<<'}';
        }
        o<<']';
        if(driver)o<<",\"tx_hex\":"<<quote("686AF1"+std::string(q));
        else{
            o<<",\"raw_response\":"<<quote(q=="ATDPN"?"ATDPN\r2>":transcript)<<",\"selected_records_hex\":[";
            for(std::size_t j=0;j<rows.size();++j){if(j)o<<',';o<<quote(hex(rows[j]));}
            o<<']';if(q=="ATDPN")o<<",\"observed_protocol\":2";
        }
        o<<'}';
    };
    for(unsigned i=0;i<commands.size();++i){
        if(commands[i]!="0902"){emit(i,commands[i],"CORRELATED",{responses[i]});continue;}
        emit(i,"0100","PASSED",{unhex("410080000000")});
        if(!driver)emit(i,"ATDPN","PASSED",{});
        emit(i,"0900","PASSED",{unhex("490001C0000000")});
        emit(i,"0901","PASSED",{unhex("490105")});
        Data v{0,0,0};v.insert(v.end(),vin.begin(),vin.end());
        std::vector<Data> rows;
        for(unsigned j=0;j<5;++j){
            Data p{0x49,2,static_cast<std::uint8_t>(j+1)};
            p.insert(p.end(),v.begin()+4*j,v.begin()+4*j+4);rows.push_back(std::move(p));
        }
        emit(i,"0902","PASSED",rows);
    }
    o<<"],\"result\":"<<result()<<'}';return o.str();
}
std::string replace(std::string s,std::string_view a,std::string_view b){
    const auto pos=s.find(a);assert(pos!=std::string::npos);s.replace(pos,a.size(),b);return s;
}
template<class Inspector>void rejects(const std::string& raw,Inspector inspect){
    bool failed=false;
    try{inspect(cd::json::parse_report(raw));}catch(const std::exception&){failed=true;}
    assert(failed);
}
}
int main(){
    namespace net=cd::network::history;
    namespace old=cd::legacy::history;
    const auto inspect_can=[](const cd::json::Value& v){return net::inspect_historical_can_freeze(v,std::string(vin));};
    for(auto transport:{"LINUX_SOCKETCAN_ISOTP","WINDOWS_J2534_ISOTP","ANDROID_ELM_ISOTP"}){
        const auto raw=can_report(transport);
        auto r=inspect_can(cd::json::parse_report(raw));
        assert(r.execution_completed&&r.all_frames_stable);
        assert(!cd::json::parse_report(net::historical_can_freeze_report(r)).at("physical_vehicle_verified").boolean());
        rejects(replace(raw,"\"number\":60.0","\"number\":61.0"),inspect_can);
        rejects(replace(raw,"\"trigger_stable\":true","\"trigger_stable\":false"),inspect_can);
        rejects(replace(raw,"\"read_pids\":[5]","\"read_pids\":[6]"),inspect_can);
        rejects(replace(raw,"\"rx_can_id\":\"7E8\"","\"rx_can_id\":\"7E9\""),inspect_can);
        rejects(replace(raw,"2026-11-28T01:00:00Z","2026-02-30T01:00:00Z"),inspect_can);
        if(std::string_view(transport)=="ANDROID_ELM_ISOTP"){
            rejects(replace(raw,"7E8 10 14","7E9 10 14"),inspect_can);
            rejects(replace(raw,"\"observed_protocol\":6","\"observed_protocol\":8"),inspect_can);
        }
    }
    const auto inspect_legacy=[](const cd::json::Value& v){return old::inspect_historical_freeze(v,std::string(vin));};
    for(bool driver:{false,true}){
        const auto raw=legacy_report(driver);
        auto r=inspect_legacy(cd::json::parse_report(raw));
        assert(r.execution_completed&&r.all_frames_stable&&r.driver==driver);
        rejects(replace(raw,"\"number\":60.0","\"number\":61.0"),inspect_legacy);
        rejects(replace(raw,"\"payload_hex\":\"42050064\"","\"payload_hex\":\"42050065\""),inspect_legacy);
        rejects(replace(raw,"\"logical_query\":3","\"logical_query\":2"),inspect_legacy);
        rejects(replace(raw,"\"selected_response_source\":\"10\"","\"selected_response_source\":\"11\""),inspect_legacy);
        if(!driver)rejects(replace(raw,"ATDPN\\r2>","ATDPN\\r3>"),inspect_legacy);
    }
}
