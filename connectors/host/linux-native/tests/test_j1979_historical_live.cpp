#include "j1979_live_history.hpp"
#include "j1979_can_freeze_history.hpp"
#include "j1979_historical_freeze.hpp"
#include "j1979_native_report_detail.hpp"
#include <array>
#include <algorithm>
#include <cassert>
#include <stdexcept>

namespace {
using cd::json::Value;
using cd::json::quote;
using cd::legacy::Data;
using cd::legacy::hex;
using cd::network::history::inspect_historical_live;
constexpr std::string_view vin="1G1FA1RXXR0123456";
std::string identity(){
    Data bytes{0x49,2,1};bytes.insert(bytes.end(),vin.begin(),vin.end());return hex(bytes);
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
std::string row(std::string_view phase,std::string_view q,std::string_view reply,unsigned start,
    unsigned cycle=0,unsigned pid=0,bool raw=false,bool adapter=false){
    const bool sample=phase=="SAMPLE";
    std::string out="{\"phase\":"+quote(phase)+",\"request_hex\":"+quote(q)+",\"response_hex\":"+quote(reply)+
        ",\"status\":"+quote(sample&&raw?"RAW":"PASSED")+",\"detail\":"+(sample&&raw?"\"RAW_UNINTERPRETED\"":"null")+
        ",\"started_elapsed_ms\":"+std::to_string(start)+",\"elapsed_ms\":"+std::to_string(start+1)+",\"duration_ms\":1";
    if(sample){
        out+=",\"cycle\":"+std::to_string(cycle)+",\"pid\":"+std::to_string(pid);
        if(!raw)out+=",\"measurement\":"+std::string(pid==5?
            R"({"pid":5,"name":"coolant-temperature","unit":"degC","number":60})":
            R"({"pid":12,"name":"engine-rpm","unit":"rpm","number":2000})");
    }
    if(adapter)out+=",\"raw_adapter_response\":"+quote(can_lines(cd::network::history::detail::bytes(Value{std::string(reply)},4095)));
    return out+"}";
}
std::string stats(unsigned pid,bool raw){
    const auto name=pid==5?"coolant-temperature":"engine-rpm",unit=pid==5?"degC":"rpm";
    const auto n=std::to_string(pid==5?60:2000);
    return "{\"pid\":"+std::to_string(pid)+",\"attempted\":2,\"status_counts\":{"+
        quote(raw?"RAW":"PASSED")+":2},\"name\":"+(raw?"null":quote(name))+
        ",\"unit\":"+(raw?"null":quote(unit))+",\"minimum\":"+(raw?"null":n)+
        ",\"maximum\":"+(raw?"null":n)+",\"mean\":"+(raw?"null":n)+
        ",\"last_verified_value\":"+(raw?"null":n)+",\"last_verified_elapsed_ms\":"+
        (raw?"null":std::to_string(pid==5?103:104))+"}";
}
std::string report(bool raw=false,bool adapter=false){
    return "{\"schema\":\"camarodiag-j1979-live-v1\",\"tx_can_id\":\"7E0\",\"rx_can_id\":\"7E8\","
        "\"declared_vin\":"+quote(vin)+",\"observed_vin\":"+quote(vin)+
        ",\"selected_pids\":[5,12],\"advertised_pids\":[5,12],\"inventory_complete\":true,"
        "\"requested_cycles\":2,\"completed_cycles\":2,\"interval_ms\":100,\"budget_ms\":1000,"
        "\"duration_ms\":105,\"completed\":true,\"stop_reason\":null,\"raw_only\":"+(raw?"true":"false")+
        (adapter?",\"expected_protocol\":6,\"observed_protocol\":6,\"raw_protocol_response\":\"6>\"":"")+
        ",\"admission\":["+row("INITIAL_VIN","0902",identity(),0,0,0,raw,adapter)+","+
        row("INVENTORY","0100","410008100000",1,0,0,raw,adapter)+","+
        row("FINAL_VIN","0902",identity(),104,0,0,raw,adapter)+"],\"observations\":["+
        row("SAMPLE","0105","410564",2,1,5,raw,adapter)+","+row("SAMPLE","010C","410C1F40",3,1,12,raw,adapter)+","+
        row("SAMPLE","0105","410564",102,2,5,raw,adapter)+","+row("SAMPLE","010C","410C1F40",103,2,12,raw,adapter)+
        "],\"summary\":["+stats(5,raw)+","+stats(12,raw)+"],\"multi_signal_summary\":[]}";
}
void replace(std::string& s,std::string_view from,std::string_view to){
    const auto p=s.find(from);assert(p!=std::string::npos);s.replace(p,from.size(),to);
}
void rejected(std::string s,std::string_view from,std::string_view to){
    replace(s,from,to);bool bad=false;
    try{(void)inspect_historical_live(cd::json::parse_report(s));}catch(const std::invalid_argument&){bad=true;}
    assert(bad);
}
}
int main(){
    const auto source=report();
    auto r=inspect_historical_live(cd::json::parse_report(source),std::string(vin));
    assert(r.completed&&r.final_identity&&r.completed_cycles==2&&!r.adapter_replayed);
    assert(r.proof.at("summary").array()[0].at("mean").finite_number()==60);
    assert(r.proof.at("summary").array()[1].at("mean").finite_number()==2000);
    assert(!r.proof.at("physical_vehicle_verified").boolean());
    auto proof=cd::json::parse_report(cd::network::history::historical_live_report(r,"digest"));
    assert(proof.at("source_sha256").string()=="digest");
    rejected(source,"\"mean\":60","\"mean\":61");
    rejected(source,"\"last_verified_elapsed_ms\":103","\"last_verified_elapsed_ms\":104");
    rejected(source,"\"advertised_pids\":[5,12]","\"advertised_pids\":[5,13]");
    rejected(source,"\"request_hex\":\"0105\"","\"request_hex\":\"0106\"");
    rejected(source,"\"started_elapsed_ms\":102","\"started_elapsed_ms\":20");
    rejected(source,"\"completed_cycles\":2","\"completed_cycles\":1");
    auto windows=source;replace(windows,"\"selected_pids\":[5,12]","\"selected_pids\":\"BQw=\"");
    assert(inspect_historical_live(cd::json::parse_report(windows)).completed);
    rejected(windows,"\"BQw=\"","\"BQx=\"");
    auto raw=report(true,true);
    auto replay=inspect_historical_live(cd::json::parse_report(raw));
    assert(replay.completed&&replay.raw_only&&replay.adapter_replayed);
    assert(replay.proof.at("summary").array()[0].at("mean").null());
    rejected(raw,"7E8 03 41 05 64","7E9 03 41 05 64");
    rejected(raw,"\"observed_protocol\":6","\"observed_protocol\":8");
    rejected(raw,"\"RAW_UNINTERPRETED\"","\"INTERPRETED\"");

    const std::array recordings{r,r};
    const std::array digests{std::string(64,'a'),std::string(64,'b')};
    const auto comparison=cd::network::history::compare_historical_live(recordings,digests);
    assert(comparison.at("shared_pids").array().size()==2);
    assert(comparison.at("comparison").array()[0].at("mean_range").finite_number()==0);
    assert(!comparison.at("time_aligned").boolean()&&comparison.at("diagnostic_verdict").null());
    bool denied=false;
    try{const std::array dup{digests[0],digests[0]};(void)cd::network::history::compare_historical_live(recordings,dup);}
    catch(const std::invalid_argument&){denied=true;}assert(denied);
    denied=false;
    try{const std::array different{r,replay};(void)cd::network::history::compare_historical_live(different,digests);}
    catch(const std::invalid_argument&){denied=true;}assert(denied);
    auto second=r;
    second.pids={5};
    auto summaries=second.proof.at("summary").array();summaries.resize(1);
    auto fields=second.proof.object();fields["summary"]=Value{std::move(summaries)};second.proof=Value{std::move(fields)};
    const std::array subset{r,second};
    const auto shared=cd::network::history::compare_historical_live(subset,digests);
    assert(shared.at("shared_pids").array().size()==1&&shared.at("shared_pids").array()[0].unsigned_integer()==5);
}
