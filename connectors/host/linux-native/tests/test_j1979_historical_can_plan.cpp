#include "j1979_historical_can_plan.hpp"
#include <cassert>
#include <stdexcept>
using namespace cd::network::history;
namespace {
constexpr auto vin="1G1FF3D36C9104199";
std::string row(std::string_view q,std::string_view r,std::string_view status="PASSED"){
    return "{\"request_hex\":\""+std::string(q)+"\",\"response_hex\":\""+std::string(r)+
        "\",\"status\":\""+std::string(status)+"\",\"detail\":null,\"duration_ms\":5}";
}
std::string wrap(const std::vector<std::string>& steps,bool completed=true,std::string_view stop="null"){
    std::string out="{\"declared_vin\":\""+std::string(vin)+"\",\"observed_vin\":\""+vin+
        "\",\"completed\":"+(completed?"true":"false")+",\"stop_reason\":"+std::string(stop)+",\"steps\":[";
    for(std::size_t i=0;i<steps.size();++i){if(i)out+=',';out+=steps[i];}
    return out+"]}";
}
void rejects(const std::string& s,PlanOrder order=PlanOrder::historical){
    bool failed=false;
    try{(void)inspect_plan(cd::json::parse_report(s),order,std::string(vin));}
    catch(const std::exception&){failed=true;}
    assert(failed);
}
}
int main(){
    const auto v=row("0902","4902013147314646334433364339313034313939");
    const auto support=row("0100","410008000000");
    const auto temp=row("0105","410564");
    std::vector<std::string> steps{v,row("03","4300"),row("07","4700"),row("0A","4A00"),
        support,temp,row("020200","4202000000"),row("0600","460000000000"),
        row("0800","480080000000"),row("0900","490040000000")};
    auto r=inspect_plan(cd::json::parse_report(wrap(steps)),PlanOrder::historical,std::string(vin));
    assert(r.completed&&r.initial_vin==vin&&!r.final_vin&&r.freeze_trigger_verified&&!r.freeze_present);
    assert(r.inventories[0].ids==std::vector<unsigned>{5}&&r.inventories[1].state=="NOT_APPLICABLE");
    assert(r.inventories[3].ids==std::vector<unsigned>{1});
    auto changed=steps;changed[5]=row("0106","410680");rejects(wrap(changed));
    changed=steps;changed[5]=row("0105","4105");rejects(wrap(changed));
    changed=steps;changed[5]=row("04","44");rejects(wrap(changed));
    changed=steps;changed.push_back(v);rejects(wrap(changed));
    changed=steps;changed.pop_back();rejects(wrap(changed));
    auto partial=inspect_plan(cd::json::parse_report(wrap(changed,false,"\"BUDGET_EXHAUSTED\"")),PlanOrder::historical,std::string(vin));
    assert(!partial.completed&&!partial.sequence_exhausted);
    auto android=steps;android.erase(android.begin()+4);android.insert(android.begin(),support);android.push_back(v);
    auto a=inspect_plan(cd::json::parse_report(wrap(android)),PlanOrder::android,std::string(vin));
    assert(a.completed&&a.final_vin==vin);
    rejects(wrap(steps),PlanOrder::android);
    auto json=cd::json::parse_report(plan_report(a,"digest"));
    assert(!json.at("derived_projections_verified").boolean()&&!json.at("physical_vehicle_verified").boolean());
}
