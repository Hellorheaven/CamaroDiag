#include "j1979_legacy_scan_history.hpp"
#include <algorithm>
#include <cassert>
#include <sstream>
#include <stdexcept>
using namespace cd::legacy;
using namespace cd::legacy::history;
namespace {
constexpr std::string_view vin="1G1FA1RXXR0123456";
struct Reply {unsigned source;Data payload;};
Data wire(Protocol p,const Reply& r,bool driver){
    Data out=p==Protocol::kwp_slow||p==Protocol::kwp_fast?
        Data{static_cast<std::uint8_t>(0x80u|r.payload.size()),0xf1,static_cast<std::uint8_t>(r.source)}:
        Data{static_cast<std::uint8_t>(p==Protocol::pwm?0x41:0x48),0x6b,static_cast<std::uint8_t>(r.source)};
    out.insert(out.end(),r.payload.begin(),r.payload.end());
    if(!driver)out.push_back(checksum(p,out));
    return out;
}
std::string address(unsigned n){return hex(Data{static_cast<std::uint8_t>(n)});}
std::string row(Protocol p,std::string_view command,std::vector<Reply> replies,bool driver,std::string_view status="PASSED"){
    std::ostringstream o;const auto q=unhex(command);
    o<<"{\"request_hex\":"<<cd::json::quote(command)<<",\"duration_ms\":1,\"status\":"<<cd::json::quote(status);
    if(driver){
        Data tx=p==Protocol::kwp_slow||p==Protocol::kwp_fast?
            Data{static_cast<std::uint8_t>(0xc0u|q.size()),0x33,0xf1}:
            Data{static_cast<std::uint8_t>(p==Protocol::pwm?0x61:0x68),0x6a,0xf1};
        tx.insert(tx.end(),q.begin(),q.end());
        o<<",\"tx_hex\":"<<cd::json::quote(hex(tx))<<",\"responses\":[";
        bool first=true;
        for(const auto& r:replies){
            if(r.source!=0x10)continue;
            if(!first)o<<',';
            first=false;
            o<<"{\"source\":\"10\",\"raw_hex\":"<<cd::json::quote(hex(wire(p,r,true)))
                <<",\"payload_hex\":"<<cd::json::quote(hex(r.payload))<<",\"timestamp\":1}";
        }
        o<<']';
    }else{
        std::string raw;bool first=true;
        o<<",\"frames\":[";
        for(const auto& r:replies){
            if(!first){o<<',';raw+='\r';}
            first=false;
            const auto frame=hex(wire(p,r,false));raw+=frame;
            o<<"{\"source\":"<<cd::json::quote(address(r.source))<<",\"raw_hex\":"<<cd::json::quote(frame)
                <<",\"payload_hex\":"<<cd::json::quote(hex(r.payload))<<'}';
        }
        o<<"],\"raw_response\":"<<cd::json::quote(raw.empty()?"NO DATA":raw)<<",\"records_hex\":[";
        first=true;
        for(const auto& r:replies)if(r.source==0x10&&(status=="PASSED"||status=="RAW")){
            if(!first)o<<',';
            first=false;
            o<<cd::json::quote(hex(r.payload));
        }
        o<<']';
    }
    o<<'}';return o.str();
}
std::string fixture(Protocol p,bool driver=false,bool mismatch=false){
    std::ostringstream o;
    const auto selected=mismatch?"1FA6P8TH0L5100000":vin;
    o<<"{\"schema\":\"camarodiag-functional-j1979-legacy-v1\",";
    if(driver){
        constexpr std::array<std::string_view,5> variants{"Pwm","Vpw","Iso9141","KwpSlow","KwpFast"};
        o<<"\"variant\":"<<cd::json::quote(variants[static_cast<unsigned>(p)-1])<<",\"baud\":"<<(p==Protocol::pwm?41600:10400)
            <<",\"selected_response_source\":\"10\",\"initialization_evidence\":"
            <<(p==Protocol::kwp_fast?"\"83F110C1E98F\"":"\"0808\"")<<",\"result\":{";
    }else{
        o<<"\"expected_protocol\":"<<static_cast<unsigned>(p)<<",\"observed_protocol\":"<<static_cast<unsigned>(p)
            <<",\"raw_protocol_response\":"<<cd::json::quote("ATDPN\r"+std::to_string(static_cast<unsigned>(p))+"\r>")
            <<",\"other_response_sources\":[\"11\"],";
    }
    o<<"\"transport_observed\":true,\"selected_response_source\":\"10\",\"declared_vin\":"<<cd::json::quote(selected)
        <<",\"observed_vin\":"<<cd::json::quote(vin)<<",\"completed\":"<<(mismatch?"false":"true")
        <<",\"stop_reason\":"<<(mismatch?"\"VIN_MISMATCH\"":"null")<<",\"duration_ms\":100,\"steps\":[";
    bool first=true;
    auto emit=[&](std::string_view q,std::vector<Reply> replies,std::string_view status="PASSED"){
        if(!first)o<<',';
        first=false;o<<row(p,q,std::move(replies),driver,status);
    };
    emit("0100",{{0x10,unhex("410088100000")},{0x11,unhex("410000080000")}});
    emit("0900",{{0x10,unhex("490001C0000000")}});
    emit("0901",{{0x10,unhex("490105")}});
    Data data{0,0,0};data.insert(data.end(),vin.begin(),vin.end());
    std::vector<Reply> vin_rows;
    for(unsigned i=0;i<5;++i){
        Data part{0x49,2,static_cast<std::uint8_t>(i+1)};
        part.insert(part.end(),data.begin()+4*i,data.begin()+4*i+4);
        vin_rows.push_back({0x10,std::move(part)});
    }
    vin_rows.push_back({0x11,unhex("49020100000041")});emit("0902",vin_rows);
    if(!mismatch){
        emit("03",{{0x10,unhex("43012300000000")},{0x11,unhex("43045600000000")}});
        emit("07",{{0x10,unhex("47000000000000")}});
        emit("0A",{{0x10,unhex("7F0A11")}},"NOT_TESTED");
        emit("0101",{{0x10,unhex("410100000000")}});
        emit("0105",{{0x10,unhex("41055A")}});
        emit("010C",{{0x10,unhex("410C0FA0")}});
        emit("020200",{{0x10,unhex("4202000000")}});
        emit("0600",{{0x10,unhex("4600FF80000000")}});
        emit("0601",{{0x10,unhex("46018100010002")}});
        emit("08000000000000",{{0x10,unhex("48000080000000")}});
    }
    o<<']';
    if(!mismatch)o<<R"(,"measurements":[{"mode":1,"pid":5,"name":"coolant-temperature","number":50.0,"unit":"degC","source":"10"},{"mode":1,"pid":12,"name":"engine-rpm","number":1000.0,"unit":"rpm","source":"10"}],"dtc_modes":[{"mode":3,"codes":["P0123"]},{"mode":7,"codes":[]},{"mode":10,"codes":null}],"inventory":[{"mode":1,"sensor_mask":null,"state":"COMPLETE","verified_pages":1,"advertised_ids":[1,5,12]},{"mode":2,"sensor_mask":null,"state":"NOT_APPLICABLE","verified_pages":0,"advertised_ids":[]},{"mode":6,"sensor_mask":null,"state":"COMPLETE","verified_pages":1,"advertised_ids":[1]},{"mode":8,"sensor_mask":null,"state":"COMPLETE","verified_pages":1,"advertised_ids":[1]},{"mode":9,"sensor_mask":null,"state":"COMPLETE","verified_pages":1,"advertised_ids":[1,2]}],"freeze_triggers":[{"present":false,"dtc":null}],"advertised_control_tests":[1],"readiness":{"mil_on":false,"confirmed_dtc_count":0,"compression_ignition":false,"byte_b":0,"byte_c":0,"byte_d":0,"supported_monitors":{}},"legacy_monitor_results":[{"mode":6,"test_id":1,"sensor_mask":null,"encoded_data_hex":"8100010002","component_and_limit_encoded":129,"component_id":1,"limit_type":"MINIMUM","value_encoded":1,"limit_encoded":2,"encoded_relation":"BELOW","scaled":null,"monitor_verdict":null,"request_hex":"0601","record_index":0}])";
    o<<'}';if(driver)o<<'}';return o.str();
}
std::string change(std::string s,std::string_view from,std::string_view to){
    const auto pos=s.find(from);assert(pos!=std::string::npos);s.replace(pos,from.size(),to);return s;
}
void rejects(const std::string& text,std::optional<std::string> expected=std::nullopt){
    bool failed=false;
    try{(void)inspect_historical_scan(cd::json::parse_report(text),expected);}
    catch(const std::exception&){failed=true;}
    assert(failed);
}
}
int main(){
    for(unsigned n=1;n<=5;++n)for(bool driver:{false,true}){
        const auto p=static_cast<Protocol>(n);
        const auto raw=fixture(p,driver);
        const auto inspected=inspect_historical_scan(cd::json::parse_report(raw),std::string(vin));
        assert(inspected.completed&&inspected.observed_vin==vin&&inspected.driver==driver);
        assert(inspected.dtcs.at(3)==std::set<std::string>{"P0123"});
        assert(inspected.dtcs.at(7).empty()&&!inspected.dtcs.contains(10));
        assert(inspected.checked_summary_fields.size()==7);
        DiscoveryInspection discovery{};discovery.protocol=p;discovery.completed=true;discovery.eligible={0x10};
        Responder responder{};responder.source=0x10;responder.status="PASSED";
        responder.bitmap=std::array<std::uint8_t,4>{0x88,0x10,0,0};discovery.responders={responder};
        const auto binding=compare_historical_discovery_scan(discovery,inspected,"discovery-digest","scan-digest");
        assert(binding.at("bitmap_unchanged").boolean()&&binding.at("scan_completed").boolean());
        assert(!binding.at("current_authorization_verified").boolean());
        assert(binding.at("scan_sha256").string()=="scan-digest");
        discovery.responders[0].bitmap=std::array<std::uint8_t,4>{0x80,0,0,0};
        assert(!compare_historical_discovery_scan(discovery,inspected).at("bitmap_unchanged").boolean());
        discovery.eligible.clear();bool denied=false;
        try{(void)compare_historical_discovery_scan(discovery,inspected);}catch(const std::invalid_argument&){denied=true;}
        assert(denied);
        if(!driver)assert(inspected.other_sources==std::set<unsigned>{0x11});
        const auto proof=cd::json::parse_report(historical_scan_report(inspected));
        assert(proof.at("link_checksum_verified").boolean()!=driver&&!proof.at("physical_vehicle_verified").boolean());
        const auto& derived=proof.at("derived");
        assert(derived.at("identification").array().at(0).at("values").array().at(0).string()==vin);
        assert(derived.at("dtc_modes").array().at(0).at("codes").array().at(0).string()=="P0123");
        assert(derived.at("monitor_records").array().at(0).at("value_encoded").unsigned_integer()==1);
        assert(derived.at("measurements").array().size()==2);
        rejects(change(raw,"\"number\":50.0","\"number\":51.0"));
        rejects(change(raw,"\"number\":1000.0","\"number\":999.0"));
        rejects(change(raw,"\"codes\":[\"P0123\"]","\"codes\":[\"P0456\"]"));
        rejects(change(raw,"\"advertised_ids\":[1,5,12]","\"advertised_ids\":[1,5,13]"));
        rejects(change(raw,"\"value_encoded\":1","\"value_encoded\":2"));
        rejects(change(raw,"\"mil_on\":false","\"mil_on\":true"));
        rejects(change(raw,"\"request_hex\":\"0105\"","\"request_hex\":\"04\""));
        rejects(change(raw,"\"request_hex\":\"0105\"","\"request_hex\":\"010D\""));
        rejects(change(raw,"\"selected_response_source\":\"10\"","\"selected_response_source\":\"11\""));
        rejects(change(raw,"\"completed\":true","\"completed\":false"));
        if(driver){
            const auto alternate=change(raw,"\"advertised_ids\":[1,5,12]","\"advertised_ids\":\"AQUM\"");
            assert(inspect_historical_scan(cd::json::parse_report(alternate)).completed);
            rejects(change(raw,"\"payload_hex\":\"41055A\"","\"payload_hex\":\"41055B\""));
        }else{
            const auto f=hex(wire(p,{0x10,unhex("41055A")},false));
            auto corrupt=f;corrupt.back()=corrupt.back()=='0'?'1':'0';
            rejects(change(raw,f,corrupt));
            rejects(change(raw,"\"records_hex\":[\"41055A\"]","\"records_hex\":[\"41055B\"]"));
        }
    }
    const auto mismatch=fixture(Protocol::vpw,false,true);
    const auto partial=inspect_historical_scan(cd::json::parse_report(mismatch));
    assert(!partial.completed&&partial.stop=="VIN_MISMATCH"&&partial.steps.size()==4);
    rejects(mismatch,std::string(vin));
    rejects(change(mismatch,"\"stop_reason\":\"VIN_MISMATCH\"","\"stop_reason\":null"));
}
