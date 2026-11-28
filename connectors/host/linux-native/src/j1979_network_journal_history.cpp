#include "j1979_network_journal.hpp"
#include "j1979_network_history.hpp"
#include "j1979_native_report_detail.hpp"
#include "j1979_isotp.h"
#include <algorithm>
#include <set>
namespace cd::network::history {
namespace {
using detail::require;
using json::Value;
using Object=Value::Object;
using Array=Value::Array;
Value obj(Object o){return Value{std::move(o)};}
Value arr(Array a){return Value{std::move(a)};}
Value text(std::string_view s){return Value{std::string(s)};}
Value num(std::uint64_t n){return Value{json::Integer{std::to_string(n)}};}
Value flag(bool b){return Value{b};}
bool digest_ok(std::string_view s){
    return s.size()==64&&std::all_of(s.begin(),s.end(),[](char c){return (c>='0'&&c<='9')||(c>='a'&&c<='f');});
}
}
json::Value verify_network_journals(const Value& source,const NetworkJournalInput& inputs,
    std::string_view vin,std::string_view interface,std::string_view report_digest){
    require(vin.size()==17&&j1979_can_valid_vin(std::string(vin).c_str()),"Explicit independent network VIN required");
    require(interface.find('\0')==std::string_view::npos&&j1979_isotp_valid_interface(std::string(interface).c_str()),
        "Explicit independent SocketCAN interface required");
    require(digest_ok(report_digest),"Invalid network report SHA-256");
    require(source.at("schema").string()=="camarodiag-j1979-can-network-linux-v1","Native network report required");
    auto checked=inspect_network(source,std::string(vin));
    require(!checked.discovery_only&&checked.admission.platform=="LINUX_NATIVE","Selected native network required");
    require(!inputs.discovery.empty()&&inputs.discovery.size()<=4u*1024u*1024u,"Bounded discovery evidence required");
    const auto discovery=json::parse_report(inputs.discovery,4u*1024u*1024u);
    require(detail::compare(discovery,source.at("discovery")),"Network discovery differs from durable discovery");
    const auto discovery_check=inspect_discovery(discovery);
    require(discovery_check.discovery.eligible==checked.admission.discovery.eligible&&
        discovery_check.discovery.completed==checked.admission.discovery.completed,"Network discovery eligibility differs");
    const auto& saved=source.at("ecu_reports").array();
    require(saved.size()==checked.rows.size()&&saved.size()==checked.selected.size(),"Network physical rows differ");
    std::set<std::uint32_t> permitted(checked.selected.begin(),checked.selected.end());
    for(const auto& [id,bytes]:inputs.journals){
        (void)bytes;require(permitted.contains(id),"Journal belongs to an unselected ECU");
    }
    bool all_bound=true;
    bool terminal=false;
    bool physical_started=false;
    unsigned replayed=0,header_only=0,partial=0,unattempted=0;
    std::uint64_t total_budget=0,previous_budget=0;
    Array rows;
    for(std::size_t i=0;i<checked.rows.size();++i){
        const auto& row=checked.rows[i];
        const auto& evidence=saved[i].at("report");
        const auto found=inputs.journals.find(row.pair.rx);
        Object item{{"tx",num(row.pair.tx)},{"rx",num(row.pair.rx)},{"status",text(row.status)},
            {"error",row.error.empty()?Value{}:text(row.error)}};
        if(found==inputs.journals.end()){
            require(evidence.null()&&row.status!="PASSED","Network ECU report missing its journal");
            ++unattempted;
            item.emplace("journal",Value{});
            item.emplace("binding",text("NO_JOURNAL_PRESENT"));
            item.emplace("transport_attempt_verified",flag(false));
            if(!terminal&&row.error!="SELECTED_RESPONDER_UNVERIFIED"&&row.error!="BUDGET_EXHAUSTED"&&row.error!="CANCELLED")
                all_bound=false;
        }else{
            require(!terminal&&checked.admission.discovery.completed&&
                std::find(checked.admission.discovery.eligible.begin(),checked.admission.discovery.eligible.end(),row.pair.rx)!=checked.admission.discovery.eligible.end(),
                "Journal for an ineligible target or after a terminal network outcome");
            const auto& bytes=found->second;
            const auto journal=socketcampaign::decode(bytes,true);
            require(journal.plan.operation==socketcampaign::Operation::scan&&journal.plan.tx==row.pair.tx&&journal.plan.rx==row.pair.rx&&
                journal.plan.vin==vin&&journal.plan.interface==interface,"Network journal physical scope differs");
            require(!physical_started||journal.plan.budget_ms<=previous_budget,"Network remaining budget increased");
            physical_started=true;previous_budget=journal.plan.budget_ms;
            const auto replay=socketcampaign::replay(journal);
            const auto digest=socketcampaign::sha256(bytes);
            item.emplace("journal",text(journal_name(row.pair.rx)));
            item.emplace("inspection",socketcampaign::proof(journal,replay,digest));
            item.emplace("transport_attempt_verified",flag(false));
            if(journal.terminal){
                require(!evidence.null()&&!replay.prefix&&!replay.report.empty(),"Terminal ECU journal lacks corresponding report");
                const auto rebuilt=json::parse_report(replay.report,socketcampaign::max_report);
                require(detail::compare(evidence,rebuilt),"Network ECU embedded report differs from callback replay");
                require(row.native_plan.has_value()&&row.native_plan->completed==replay.completed,"ECU report completion differs");
                require(row.status!="PASSED"||replay.completed,"Successful ECU has incomplete journal replay");
                if(row.status!="PASSED")require(row.error=="CANCELLED"||row.error=="BUDGET_EXHAUSTED"||row.error==replay.stop,
                    "Network terminal status differs from ECU journal");
                const auto duration=rebuilt.at("duration_ms").unsigned_integer();
                require(duration<=3600000,"Native ECU elapsed duration limit");
                if(total_budget+duration<total_budget)throw std::invalid_argument("Network elapsed overflow");
                total_budget+=duration;
                ++replayed;
                item.emplace("binding",text("TERMINAL_CALLBACK_REPLAY_MATCHED"));
            }else{
                require(evidence.null()&&row.status!="PASSED","Incomplete journal cannot support an embedded report");
                require(row.error=="TRANSPORT_UNAVAILABLE"||row.error=="PHYSICAL_SCAN_FAILED"||
                    row.error=="CAMPAIGN_FAILED"||row.error=="CANCELLED"||row.error=="BUDGET_EXHAUSTED",
                    "Incomplete journal does not explain ECU outcome");
                if(journal.events.empty()&&!journal.torn){++header_only;item.emplace("binding",text("HEADER_ONLY"));}
                else{++partial;item.emplace("binding",text("INCOMPLETE_CALLBACK_PREFIX"));}
                all_bound=false;
            }
        }
        if(row.status!="PASSED")terminal=true;
        rows.push_back(obj(std::move(item)));
    }
    const auto duration=source.at("duration_ms").unsigned_integer();
    require(duration<=3600000&&total_budget<=duration,"Network duration shorter than its completed physical campaigns");
    const bool complete=checked.completed&&all_bound&&replayed==checked.selected.size();
    require(!checked.completed||complete,"Completed network lacks complete bound journals");
    return obj({{"schema",text("camarodiag-j1979-network-journal-inspection-v1")},
        {"network_report_sha256",text(report_digest)},{"discovery_sha256",text(socketcampaign::sha256(std::string_view(inputs.discovery)))},
        {"vin",text(vin)},{"interface",text(interface)},{"network_completed",flag(checked.completed)},
        {"all_reported_campaigns_bound",flag(all_bound)},{"completed",flag(complete)},
        {"terminal_journals",num(replayed)},{"header_only_journals",num(header_only)},{"partial_journals",num(partial)},
        {"unattempted_rows",num(unattempted)},{"ecu_reports",arr(std::move(rows))},
        {"stop_reason",checked.stop.empty()?Value{}:text(checked.stop)},
        {"discovery_transmission_independently_verified",flag(false)},{"physical_vehicle_verified",flag(false)},
        {"authenticated",flag(false)},{"whole_vehicle_coverage_verified",flag(false)},
        {"permits_programming",flag(false)},{"permits_automatic_retry",flag(false)}});
}
}
