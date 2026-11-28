#pragma once
#include "j1979_can_network.hpp"
#include "j1979_legacy.hpp"
#include "native_json.hpp"
namespace cd::network::history {
struct HistoricalLiveRow {
    std::string phase,status,detail;
    std::vector<std::uint8_t> request,response;
    unsigned start{},end{},duration{},cycle{},pid{};
    bool sample{};
    const json::Value* original{}; // borrowed only during inspection
};
struct HistoricalLive {
    std::string vin,stop;
    Pair pair{};
    std::optional<legacy::Protocol> protocol;
    unsigned source{},cycles{},completed_cycles{},interval{},budget{},duration{};
    bool legacy_driver{},raw_only{},completed{},final_identity{},adapter_replayed{};
    std::vector<unsigned> pids;
    json::Value channel,proof;
};
// Verifies chronology, admission and all reported statistics, then calls the
// independent physical transcript verifier for source-specific formats.
HistoricalLive inspect_historical_live(const json::Value&,std::optional<std::string> expected={});
void verify_live_transport(const json::Value& root,HistoricalLive&,std::span<const HistoricalLiveRow> rows);
json::Value compare_historical_live(std::span<const HistoricalLive>,std::span<const std::string> digests);
std::string historical_live_report(const HistoricalLive&,std::string_view digest={});
}
