#pragma once
#include "j1979_native_live_inspection.hpp"

namespace cd::network::history {
enum class LiveEvidenceKind {socket_journal,elm_campaign,cl1,report};
struct LiveEvidenceProjection {
    NativeLiveProjection projection;
    std::string source_sha256;
    std::string raw_report_sha256;
    LiveEvidenceKind kind{};
};
// A source journal must contain its full terminal. A standalone report
// remains a weaker source: its application replay cannot authenticate I/O.
LiveEvidenceProjection project_live_evidence(LiveEvidenceKind,
    std::span<const std::uint8_t>,Pair,std::string_view vin,unsigned elm_profile=0);
json::Value verify_live_evidence(const LiveEvidenceProjection&,const json::Value&,
    std::string_view projection_sha256);
}
