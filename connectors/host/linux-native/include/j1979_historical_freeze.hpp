#pragma once
#include "j1979_legacy_freeze_plan.hpp"
#include "native_json.hpp"
namespace cd::network::history {
struct HistoricalFreeze {
    legacy::FreezePlan plan;
    json::Value replayed;
    bool completed{},all_frames_stable{};
};
// Only application-level consistency. Link evidence is validated separately.
HistoricalFreeze replay_historical_freeze(const json::Value&,std::optional<std::string> expected={});
void validate_historical_timestamp(const json::Value&);
std::string encode_historical_json(const json::Value&);
}
