#pragma once
#include "j1979_historical_freeze.hpp"
#include "j1979_can_network.hpp"
namespace cd::network::history {
struct CanFreezeInspection {
    HistoricalFreeze result;
    Pair pair{};
    std::string transport,transport_problem;
    bool execution_completed{},all_frames_stable{},adapter_transcript_replayed{},independent_vin{};
};
CanFreezeInspection inspect_historical_can_freeze(const json::Value&,std::optional<std::string> expected={});
std::string historical_can_freeze_report(const CanFreezeInspection&,std::string_view digest={});
}
