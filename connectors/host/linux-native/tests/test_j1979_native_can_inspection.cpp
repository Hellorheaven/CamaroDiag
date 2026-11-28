#include "j1979_native_can_inspection.hpp"
#include "j1979_network_history.hpp"
#include "j1979_can.h"
#include <algorithm>
#include <cassert>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <stdexcept>

using namespace cd::network;
namespace {
constexpr char vin[]="1G1FA1RXXR0123456";
constexpr Pair pair{0x7e0,0x7e8};
struct Fake {unsigned count{};bool mismatch{},partial{},transport_fault{},inactive{},oversized{},bad_error_length{};};
bool current(void* p){return !static_cast<Fake*>(p)->inactive;}
std::uint64_t clock(void*){return 0;}
int exchange(void* p,const std::uint8_t* q,std::size_t n,std::uint8_t* r,std::size_t* size,unsigned){
    auto& f=*static_cast<Fake*>(p);++f.count;
    if(f.oversized&&f.count==2){*size=J1979_CAN_MAX_PAYLOAD+1;return 0;}
    if(f.bad_error_length&&f.count==2){*size=SIZE_MAX;return -1;}
    if(f.transport_fault&&f.count==2)return -1;
    std::vector<std::uint8_t> out;
    if(n==2&&q[0]==9&&q[1]==2){
        out={0x49,2,1};
        const std::string_view identity=f.mismatch&&f.count>1?"2G1FA1RXXR0123456":vin;
        out.insert(out.end(),identity.begin(),identity.end());
    }else if(n==1)out={static_cast<std::uint8_t>(q[0]+0x40),0};
    else if(q[0]==2)out={0x42,2,0,0,0};
    else if(q[1]==0){
        if(q[0]==6&&f.partial)return 1;
        out={static_cast<std::uint8_t>(q[0]+0x40),0,static_cast<std::uint8_t>(q[0]==1?8:0),0,0,0};
    }else if(q[0]==1&&q[1]==5)out={0x41,5,100};
    else assert(false);
    assert(out.size()<=*size);std::copy(out.begin(),out.end(),r);*size=out.size();return 0;
}
std::string fixture(Fake f){
    j1979_can_plan plan{exchange,clock,current,&f,pair.tx,pair.rx,10000,vin};
    j1979_can_result result{};assert(j1979_can_scan(&plan,&result)==0);
    char* buffer=nullptr;std::size_t count=0;
    auto* file=open_memstream(&buffer,&count);assert(file);
    assert(j1979_can_write_json(file,&plan,&result,"vcan0"));
    assert(fclose(file)==0);
    std::string output(buffer,count);std::free(buffer);j1979_can_result_free(&result);
    return output;
}
std::string replace(std::string s,std::string_view old,std::string_view replacement){
    const auto p=s.find(old);assert(p!=std::string::npos);
    s.replace(p,old.size(),replacement);return s;
}
void rejects(const std::string& s,Pair scope=pair){
    bool failed=false;
    try{(void)history::inspect_native_plan(cd::json::parse_report(s),scope,vin);}
    catch(const std::exception&){failed=true;}
    assert(failed);
}
}
int main(){
    const auto raw=fixture({});
    const auto ok=history::inspect_native_plan(cd::json::parse_report(raw),pair,vin);
    assert(ok.completed&&ok.steps==11&&ok.stop_reason.empty());
    const auto proof=cd::json::parse_report(history::native_plan_report(ok));
    assert(!proof.at("physical_vehicle_verified").boolean()&&!proof.at("transport_provenance_verified").boolean());
    rejects(replace(raw,"\"number\":60","\"number\":61"));
    rejects(replace(raw,"\"request_hex\":\"0105\"","\"request_hex\":\"04\""));
    rejects(replace(raw,"\"final_vin_checked\":true","\"final_vin_checked\":false"));
    rejects(replace(raw,"\"advertised_ids\":[5]","\"advertised_ids\":[6]"));
    rejects(replace(raw,"\"physical_vehicle_verified\":false","\"physical_vehicle_verified\":true"));
    rejects(replace(raw,"\"duration_ms\":0","\"duration_ms\":-1"));
    rejects(raw,{0x7e1,0x7e9});
    Frame frame{pair.rx,false,false,false,{6,0x41,0,8,0,0,0,0}};
    Scan network{};
    network.discovery=replay_discovery(false,true,std::array{frame},"",0);
    network.vin=vin;network.completed=true;network.selected={pair.rx};
    network.ecus={{pair,"PASSED","",raw}};
    auto network_json=report(network);
    auto inspected=history::inspect_network(cd::json::parse_report(network_json),std::string(vin));
    assert(inspected.completed&&inspected.rows[0].native_plan&&inspected.rows[0].native_plan->steps==11);
    auto invalid_network=replace(network_json,"\"number\":60","\"number\":61");
    bool rejected=false;
    try{(void)history::inspect_network(cd::json::parse_report(invalid_network),std::string(vin));}
    catch(const std::exception&){rejected=true;}
    assert(rejected);
    network.completed=false;network.stop="CANCELLED";
    network.ecus[0].status="NOT_TESTED";network.ecus[0].error="CANCELLED";
    inspected=history::inspect_network(cd::json::parse_report(report(network)),std::string(vin));
    assert(!inspected.completed&&inspected.rows[0].native_plan->completed);
    Fake f{};f.partial=true;
    const auto partial=history::inspect_native_plan(cd::json::parse_report(fixture(f)),pair,vin);
    assert(!partial.completed&&partial.stop_reason=="INVENTORY_INCOMPLETE");
    f={};f.mismatch=true;
    const auto mismatch=history::inspect_native_plan(cd::json::parse_report(fixture(f)),pair,vin);
    assert(!mismatch.completed&&mismatch.stop_reason=="VIN_CHANGED");
    f={};f.transport_fault=true;
    const auto fault=history::inspect_native_plan(cd::json::parse_report(fixture(f)),pair,vin);
    assert(!fault.completed&&fault.stop_reason=="TRANSPORT_ERROR"&&fault.steps==2);
    f={};f.oversized=true;
    const auto too_large=fixture(f);
    const auto rejected_length=history::inspect_native_plan(cd::json::parse_report(too_large),pair,vin);
    assert(!rejected_length.completed&&rejected_length.stop_reason=="TRANSPORT_ERROR"&&rejected_length.steps==2);
    assert(too_large.find("UNBOUNDED_TRANSPORT_REPLY")!=std::string::npos);
    f={};f.bad_error_length=true;
    const auto error_length=fixture(f);
    assert(error_length.find("UNBOUNDED_TRANSPORT_REPLY")==std::string::npos);
    assert(history::inspect_native_plan(cd::json::parse_report(error_length),pair,vin).stop_reason=="TRANSPORT_ERROR");
    f={};f.inactive=true;
    const auto inactive=history::inspect_native_plan(cd::json::parse_report(fixture(f)),pair,vin);
    assert(!inactive.completed&&inactive.stop_reason=="CONTEXT_CHANGED"&&inactive.steps==0);
}
