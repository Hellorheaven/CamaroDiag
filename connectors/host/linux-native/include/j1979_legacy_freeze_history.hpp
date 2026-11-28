#pragma once
#include "j1979_historical_freeze.hpp"
namespace cd::legacy::history {
struct HistoricalFreezeInspection {
    Protocol protocol{};
    unsigned source{};
    bool driver{},protocol_verified{},execution_completed{},all_frames_stable{};
    std::string transport;
    cd::network::history::HistoricalFreeze result;
};
HistoricalFreezeInspection inspect_historical_freeze(const json::Value&,std::optional<std::string> expected={});
std::string historical_freeze_report(const HistoricalFreezeInspection&,std::string_view digest={});
}
