#pragma once
#include "j1979_can_network.hpp"
#include "native_json.hpp"
#include <span>
namespace cd::network::history {
struct LiveInspection {
    Pair pair{};
    std::string vin,transport,stop_reason,canonical;
    unsigned cycles{},raw_samples{},missing_samples{};
    bool completed{},initial_identity{},final_identity{};
};
LiveInspection inspect_native_live(const json::Value&,Pair,std::string_view expected_vin);
std::string native_live_report(const LiveInspection&);
struct NativeLiveProjection {
    json::Value report;
    bool complete{};
};
// Independently replay an immutable raw native report first; only then
// project engineering values scoped to its final identity and configuration.
NativeLiveProjection project_native_live(const json::Value&,Pair,std::string_view expected_vin);
std::string native_live_csv(const NativeLiveProjection&);
NativeLiveProjection bind_native_live_projection(NativeLiveProjection,std::string_view source_digest);
json::Value verify_native_live_projection(const json::Value& source,const json::Value& saved,
    Pair,std::string_view vin,std::string_view source_digest,std::string_view projection_digest);
// Only independently replayed native physical recordings may enter this comparison.
// Scope and scaling are kept separate; absent values are not zero.
json::Value compare_native_live(std::span<const NativeLiveProjection>,std::span<const std::string> digests);
}
