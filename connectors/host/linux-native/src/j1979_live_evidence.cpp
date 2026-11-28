#include "j1979_live_evidence.hpp"
#include "j1979_native_report_detail.hpp"
#include "j1979_socket_campaign.hpp"
#include "j1979_elm_can_campaign.hpp"
#include <algorithm>

namespace cd::network::history {
namespace {
using namespace detail;
Value str(std::string_view s){return Value{std::string(s)};}
Value flag(bool v){return Value{v};}
std::string_view kind_name(LiveEvidenceKind kind){
    switch(kind){
    case LiveEvidenceKind::socket_journal:return "SCJ/1";
    case LiveEvidenceKind::elm_campaign:return "ECP/1";
    case LiveEvidenceKind::cl1:return "CL/1";
    case LiveEvidenceKind::report:return "NATIVE_REPORT";
    }
    throw std::invalid_argument("Invalid evidence kind");
}
bool digest_valid(std::string_view d){
    return d.size()==64&&std::all_of(d.begin(),d.end(),[](char c){
        return (c>='0'&&c<='9')||(c>='a'&&c<='f');
    });
}
}
LiveEvidenceProjection project_live_evidence(LiveEvidenceKind kind,
    std::span<const std::uint8_t> source,Pair pair,std::string_view vin,unsigned elm_profile){
    require(!source.empty(),"Empty live evidence");
    constexpr auto bound=64u*1024u*1024u;
    require(source.size()<=bound,"Live evidence exceeds bounded offline projection");
    require(kind==LiveEvidenceKind::elm_campaign||!elm_profile,"Unexpected ELM profile");
    std::string report;
    LiveEvidenceProjection result{};
    result.kind=kind;
    result.source_sha256=cd::socketcampaign::sha256(source);
    if(kind==LiveEvidenceKind::socket_journal){
        const auto journal=cd::socketcampaign::decode(source,false);
        require(journal.terminal&&!journal.torn&&journal.plan.operation==cd::socketcampaign::Operation::live,
            "A complete live SCJ/1 terminal is required");
        require(journal.plan.tx==pair.tx&&journal.plan.rx==pair.rx&&journal.plan.vin==vin,
            "SCJ/1 differs from independent ECU scope");
        auto replay=cd::socketcampaign::replay(journal);
        require(!replay.prefix&&!replay.pending_exchange&&!replay.report.empty(),"SCJ/1 replay incomplete");
        report=std::move(replay.report);
    }else if(kind==LiveEvidenceKind::elm_campaign){
        require(elm_profile>=6&&elm_profile<=9,"Independent ELM CAN profile required");
        const auto capture=cd::elmcan::decode_campaign(source);
        require(capture.plan.operation==cd::elmcan::TraceOperation::live&&
            capture.plan.target.profile==elm_profile&&capture.plan.target.tx==pair.tx&&
            capture.plan.target.rx==pair.rx&&capture.plan.vin==vin,"ECP/1 differs from independent live ECU scope");
        report=cd::elmcan::replay_campaign(capture).native_report;
    }else if(kind==LiveEvidenceKind::report){
        report.assign(reinterpret_cast<const char*>(source.data()),source.size());
    }else if(kind==LiveEvidenceKind::cl1){
        result.projection=project_cl1_live(source,pair,vin);
    }else{
        throw std::invalid_argument("Unsupported live evidence");
    }
    if(kind!=LiveEvidenceKind::cl1){
        require(!report.empty()&&report.size()<=bound,"Replayed live report exceeds bound");
        result.raw_report_sha256=cd::socketcampaign::sha256(report);
        result.projection=project_native_live(json::parse_report(report,bound),pair,vin);
    }
    result.projection=bind_native_live_projection(std::move(result.projection),result.source_sha256);
    auto fields=result.projection.report.object();
    fields.emplace("evidence_kind",str(kind_name(kind)));
    fields.emplace("raw_report_sha256",result.raw_report_sha256.empty()?Value{}:str(result.raw_report_sha256));
    fields.emplace("transport_recipe_replayed",flag(kind==LiveEvidenceKind::socket_journal||
        kind==LiveEvidenceKind::elm_campaign));
    fields.emplace("original_capture_replayed",flag(kind!=LiveEvidenceKind::report));
    result.projection.report=Value{std::move(fields)};
    return result;
}
json::Value verify_live_evidence(const LiveEvidenceProjection& expected,const json::Value& saved,
    std::string_view projection_digest){
    require(digest_valid(expected.source_sha256)&&digest_valid(projection_digest)&&
        expected.source_sha256!=projection_digest,"Distinct evidence and projection digests required");
    no_claims(saved);
    const bool consistent=compare(saved,expected.projection.report);
    return Value{Value::Object{
        {"schema",str("camarodiag-j1979-live-evidence-verification-v1")},
        {"consistent",flag(consistent)},
        {"evidence_kind",str(kind_name(expected.kind))},
        {"source_sha256",str(expected.source_sha256)},
        {"projection_sha256",str(projection_digest)},
        {"raw_report_sha256",expected.raw_report_sha256.empty()?Value{}:str(expected.raw_report_sha256)},
        {"numeric_projection_complete",flag(consistent&&expected.projection.complete)},
        {"transport_recipe_replayed",flag(consistent&&(expected.kind==LiveEvidenceKind::socket_journal||
            expected.kind==LiveEvidenceKind::elm_campaign))},
        {"original_capture_replayed",flag(consistent&&expected.kind!=LiveEvidenceKind::report)},
        {"physical_vehicle_verified",flag(false)},{"authenticated",flag(false)},{"permits_programming",flag(false)}
    }};
}
}
