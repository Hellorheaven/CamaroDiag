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
    assert(r.basic_summary_checked&&r.measurements.size()==1&&r.measurements[0].number==60);
    assert(r.dtcs[0].codes&&r.dtcs[0].codes->empty());
    auto scalar_row=temp;
    scalar_row.pop_back();
    scalar_row+=",\"measurement\":{\"mode\":1,\"pid\":5,\"name\":\"coolant-temperature\",\"unit\":\"degC\",\"number\":60.0}}";
    auto with_summary=steps;with_summary[5]=scalar_row;
    assert(inspect_plan(cd::json::parse_report(wrap(with_summary)),PlanOrder::historical,std::string(vin)).basic_summary_checked);
    const auto position=scalar_row.find("60.0");assert(position!=std::string::npos);
    scalar_row.replace(position,4,"61.0");with_summary[5]=scalar_row;
    rejects(wrap(with_summary));
    auto aggregate=wrap(steps);aggregate.pop_back();
    aggregate+=",\"measurements\":[{\"mode\":1,\"pid\":5,\"name\":\"coolant-temperature\",\"unit\":\"degC\",\"number\":6e1}]}";
    assert(inspect_plan(cd::json::parse_report(aggregate),PlanOrder::historical,std::string(vin)).measurements.size()==1);
    aggregate=wrap(steps);aggregate.pop_back();aggregate+=",\"measurements\":[]}";
    rejects(aggregate);
    auto evidence_steps=steps;
    evidence_steps[4].pop_back();
    evidence_steps[4]+=",\"application_evidence\":{\"shape\":\"PASSED\",\"supported_page\":{\"mode\":1,\"base\":0,\"supported_ids\":[5],\"has_next_page\":false}}}";
    auto evidence_plan=inspect_plan(cd::json::parse_report(wrap(evidence_steps)),PlanOrder::historical,std::string(vin));
    assert(evidence_plan.application_evidence_checked==1);
    auto bad_page=evidence_steps[4];
    auto pid_pos=bad_page.find("\"supported_ids\":[5]");assert(pid_pos!=std::string::npos);
    bad_page.replace(pid_pos,std::string("\"supported_ids\":[5]").size(),"\"supported_ids\":[6]");
    evidence_steps[4]=bad_page;rejects(wrap(evidence_steps));
    evidence_steps=steps;evidence_steps[6].pop_back();
    evidence_steps[6]+=",\"application_evidence\":{\"shape\":\"PASSED\",\"freeze_trigger\":{\"present\":false,\"dtc\":null}}}";
    assert(inspect_plan(cd::json::parse_report(wrap(evidence_steps)),PlanOrder::historical,std::string(vin)).application_evidence_checked==1);
    auto& trigger=evidence_steps[6];auto pos=trigger.find("\"present\":false");assert(pos!=std::string::npos);
    trigger.replace(pos,std::string("\"present\":false").size(),"\"present\":true");
    rejects(wrap(evidence_steps));
    evidence_steps=steps;evidence_steps[0].pop_back();
    evidence_steps[0]+=",\"applicationEvidence\":{\"shape\":\"PASSED\",\"vehicleInfo\":{\"infoType\":2,\"values\":[\"1G1FF3D36C9104199\"]},\"observedVin\":\"1G1FF3D36C9104199\"}}";
    assert(inspect_plan(cd::json::parse_report(wrap(evidence_steps)),PlanOrder::historical,std::string(vin)).application_evidence_checked==1);
    evidence_steps=steps;evidence_steps[0].pop_back();evidence_steps[0]+=",\"application_evidence\":null}";
    rejects(wrap(evidence_steps));
    auto derived=steps;
    derived[4]=row("0100","410028000000");
    derived.insert(derived.begin()+5,row("0103","41030204","RAW"));
    derived[8]=row("0600","460080000000");
    derived.insert(derived.begin()+9,row("0601","4601010703E8000007D0"));
    auto report=wrap(derived);
    const auto native=inspect_plan(cd::json::parse_report(report),PlanOrder::historical,std::string(vin));
    const auto groups=cd::json::parse_report(native.derived_summary);
    assert(groups.at("scaled_monitor_records").array().size()==1);
    assert(groups.at("status_records").array().size()==1);
    report.pop_back();
    report+=R"(,"scaled_monitor_records":[{"request_hex":"0601","record_index":0,"monitor_id":1,"test_id":1,"unit_scaling_id":7,"value_encoded":1000,"minimum_encoded":0,"maximum_encoded":2000,"scaled":{"definition":"ISO15031-5:2006-ANNEX-E-SUBSET","unit":"rpm","value":250.0,"minimum":0,"maximum":500,"numeric_relation":"WITHIN","monitor_verdict":null}}]})";
    assert(inspect_plan(cd::json::parse_report(report),PlanOrder::historical,std::string(vin)).derived_groups_checked==1);
    const auto raw_position=report.find("\"value_encoded\":1000");assert(raw_position!=std::string::npos);
    report.replace(raw_position,std::string("\"value_encoded\":1000").size(),"\"value_encoded\":1001");
    rejects(report);
    auto multis=steps;
    // PID 14 (narrowband) with unavailable trim, PID 24 with historical
    // arithmetic, and extended PID 84 with an exact old-catalogue exclusion.
    multis[4]=row("0100","410008001001");
    multis.insert(multis.begin()+5,row("0120","412010000001"));
    multis.insert(multis.begin()+6,row("0140","414000000001"));
    multis.insert(multis.begin()+7,row("0160","416000000001"));
    multis.insert(multis.begin()+8,row("0180","418010000000"));
    multis.insert(multis.begin()+10,row("0114","411464FF","RAW"));
    multis.insert(multis.begin()+11,row("0124","412480002000","RAW"));
    multis.insert(multis.begin()+12,row("0184","418464","RAW"));
    auto multi_body=wrap(multis);
    const auto mplan=inspect_plan(cd::json::parse_report(multi_body),PlanOrder::historical,std::string(vin));
    assert(cd::json::parse_report(mplan.derived_summary).at("multi_signal_records").array().size()==3);
    const std::string saved=R"([{"request_hex":"0114","signals":[{"pid":20,"name":"o2-sensor-1-voltage","unit":"V","raw_encoded":100,"number":0.5}]},{"request_hex":"0124","signals":[{"pid":36,"name":"wideband-o2-sensor-1-equivalence-ratio","unit":"ratio","raw_encoded":32768,"number":1},{"pid":36,"name":"wideband-o2-sensor-1-voltage","unit":"V","raw_encoded":8192,"number":1}]}])";
    multi_body.pop_back();multi_body+=",\"multi_signal_records\":"+saved+"}";
    const auto old=inspect_plan(cd::json::parse_report(multi_body),PlanOrder::historical,std::string(vin));
    assert(cd::json::parse_report(old.derived_summary).at("multi_signal_report_catalog").string()=="HISTORICAL_49");
    auto wrong_multi=multi_body;const auto number_pos=wrong_multi.find("\"number\":0.5");
    assert(number_pos!=std::string::npos);wrong_multi.replace(number_pos,12,"\"number\":0.6");rejects(wrong_multi);
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
