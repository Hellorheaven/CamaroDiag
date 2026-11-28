#include "j1979_legacy_live_journal.hpp"
#include "j1979_native_report_detail.hpp"
#include <openssl/evp.h>
#include <array>
#include <algorithm>

namespace cd::legacy {
namespace {
using cd::network::history::detail::require;
using json::Value;
Value str(std::string_view s){return Value{std::string(s)};}
Value flag(bool b){return Value{b};}
bool digest_ok(std::string_view s){
    return s.size()==64&&std::all_of(s.begin(),s.end(),[](char c){
        return (c>='0'&&c<='9')||(c>='a'&&c<='f');
    });
}
std::string digest(Bytes b){
    std::array<unsigned char,32> hash{};unsigned length{};
    require(EVP_Digest(b.data(),b.size(),hash.data(),&length,EVP_sha256(),nullptr)==1&&length==hash.size(),
        "Legacy live SHA-256 unavailable");
    constexpr char digits[]="0123456789abcdef";
    std::string result;
    for(auto x:hash){result+=digits[x>>4];result+=digits[x&15];}
    return result;
}
}
LiveJournalProjection project_live_journal(Bytes bytes,Protocol protocol,unsigned source,std::string_view vin){
    const auto evidence=inspect_journal(bytes,protocol,source,vin);
    require(evidence.kind==JournalKind::live&&evidence.terminal&&!evidence.torn_bytes&&!evidence.capture.empty(),
        "Sealed original legacy live journal required");
    const auto replay=inspect_live_capture(evidence.capture,protocol,source,vin);
    auto report=json::parse_report(live_report(replay),64u*1024u*1024u).object();
    LiveJournalProjection out{};
    out.journal_sha256=digest(bytes);out.capture_sha256=digest(evidence.capture);
    out.completed=replay.completed&&replay.identity_rechecked;
    report.emplace("journal_sha256",str(out.journal_sha256));
    report.emplace("capture_sha256",str(out.capture_sha256));
    report.emplace("original_capture_replayed",flag(true));
    report.emplace("transport_recipe_replayed",flag(true));
    report.emplace("journal_terminal_verified",flag(true));
    report.emplace("scope_independently_supplied",flag(true));
    out.report=Value{std::move(report)};
    out.csv=live_csv(replay);
    return out;
}
json::Value verify_live_journal(const LiveJournalProjection& expected,const json::Value& candidate,
    std::string_view report_sha256){
    using cd::network::history::detail::compare;
    using cd::network::history::detail::no_claims;
    require(digest_ok(report_sha256)&&digest_ok(expected.journal_sha256)&&digest_ok(expected.capture_sha256)&&
        report_sha256!=expected.journal_sha256&&report_sha256!=expected.capture_sha256,"Distinct legacy evidence digests required");
    no_claims(candidate);
    const bool equal=compare(candidate,expected.report);
    return Value{Value::Object{
        {"schema",str("camarodiag-j1979-legacy-live-journal-verification-v1")},
        {"consistent",flag(equal)},{"journal_sha256",str(expected.journal_sha256)},
        {"capture_sha256",str(expected.capture_sha256)},{"report_sha256",str(report_sha256)},
        {"completed",flag(equal&&expected.completed)},{"journal_terminal_verified",flag(equal)},
        {"physical_vehicle_verified",flag(false)},{"authenticated",flag(false)},{"permits_programming",flag(false)}
    }};
}
}
