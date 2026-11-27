#include "j1979_can_network.hpp"
#include "j1979_can.h"
#include "j1979_isotp.h"
#include <algorithm>
#include <cstdio>
#include <cstdlib>
#include <memory>
#include <stdexcept>
namespace cd::network {
Scan scan_selected(const Discovery& discovery,std::span<const std::uint32_t> ids,std::string_view vin,
                   unsigned budget,std::function<std::uint64_t()> clock,std::function<bool()> current,PhysicalScan physical){
    const std::string expected(vin);
    if(expected.size()!=17||!j1979_can_valid_vin(expected.c_str())||budget<1||budget>600000||
        !clock||!current||!physical||ids.empty()||ids.size()>max_targets)
        throw std::invalid_argument("Explicit VIN, selected CAN responders and bounded budget required");
    Scan result{};result.discovery=discovery;result.vin=expected;result.selected.assign(ids.begin(),ids.end());
    for(auto id:ids){
        const auto pair=response_pair(id,discovery.extended);
        if(!pair)throw std::invalid_argument("Selected responder outside classic CAN family");
        EcuReport row{};row.pair=*pair;result.ecus.push_back(std::move(row));
    }
    const auto start=clock();auto previous=start;
    auto elapsed=[&](){
        const auto n=clock();
        if(n<previous||n-start>3600000)throw std::runtime_error("CLOCK_INVALID");
        previous=n;return static_cast<unsigned>(n-start);
    };
    if(!valid_targets(discovery,ids))result.stop="SELECTED_RESPONDER_UNVERIFIED";
    for(auto& row:result.ecus){
        if(!result.stop.empty()){row.error=result.stop;continue;}
        try{
            const auto time=elapsed();
            if(!current()){result.stop=row.error="CANCELLED";continue;}
            if(time>=budget){result.stop=row.error="BUDGET_EXHAUSTED";continue;}
            const auto pair=row.pair;
            auto scanned=physical(pair,budget-time);
            if(scanned.pair!=pair)throw std::runtime_error("PHYSICAL_SCOPE_CHANGED");
            row=std::move(scanned);
            if(!current()){row.status="NOT_TESTED";result.stop=row.error="CANCELLED";}
            else if(elapsed()>=budget){row.status="NOT_TESTED";result.stop=row.error="BUDGET_EXHAUSTED";}
            else if(row.status!="PASSED"){
                result.stop=row.error.empty()?"PHYSICAL_SCAN_INCOMPLETE":row.error;
            }
        }catch(const std::exception&){row.status="FAILED";result.stop=row.error="PHYSICAL_SCAN_FAILED";}
    }
    result.duration_ms=elapsed();
    result.completed=result.stop.empty()&&std::all_of(result.ecus.begin(),result.ecus.end(),[](const auto& r){return r.status=="PASSED";});
    return result;
}
EcuReport scan_socket(Pair pair,unsigned budget,const char* interface,const char* vin,const volatile sig_atomic_t* cancel){
    EcuReport row{};row.pair=pair;
    if(!j1979_can_physical_pair(pair.tx,pair.rx)||!vin||!j1979_can_valid_vin(vin)||!budget||budget>600000)
        throw std::invalid_argument("Invalid physical scan scope");
    struct Link{
        j1979_isotp_link value{};
        Link(){value.socket_fd=-1;}
        ~Link(){j1979_isotp_close(&value);}
    } link;
    if(!j1979_isotp_open(&link.value,interface,pair.tx,pair.rx,cancel)){
        row.error="TRANSPORT_UNAVAILABLE";return row;
    }
    j1979_can_plan plan{j1979_isotp_exchange,nullptr,j1979_isotp_current,&link.value,pair.tx,pair.rx,budget,vin};
    struct Result{
        j1979_can_result value{};
        ~Result(){j1979_can_result_free(&value);}
    } result;
    const auto status=j1979_can_scan(&plan,&result.value);
    const auto& r=result.value;
    if(status!=0||!r.steps){row.error="CAMPAIGN_FAILED";return row;}
    char* buffer=nullptr;std::size_t size=0;
    FILE* stream=open_memstream(&buffer,&size);
    if(!stream)throw std::runtime_error("REPORT_ALLOCATION_FAILED");
    const bool serialized=j1979_can_write_json(stream,&plan,&r,interface);
    const bool closed=fclose(stream)==0;
    std::unique_ptr<char,decltype(&std::free)> owned(buffer,&std::free);
    if(!serialized||!closed||!buffer||!size||size>32*1024*1024)throw std::runtime_error("REPORT_SERIALIZATION_FAILED");
    row.json.assign(buffer,size);
    if(r.completed&&r.vin_valid&&r.final_vin_valid&&std::string_view(r.observed_vin)==vin&&std::string_view(r.final_vin)==vin)
        row.status="PASSED";
    else row.error=r.stop_reason[0]?r.stop_reason:"IDENTITY_OR_SCAN_UNVERIFIED";
    return row;
}
}
