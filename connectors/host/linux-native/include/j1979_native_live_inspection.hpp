#pragma once
#include "j1979_can_network.hpp"
#include "native_json.hpp"
namespace cd::network::history {
struct LiveInspection {
    Pair pair{};
    std::string vin,transport,stop_reason,canonical;
    unsigned cycles{},raw_samples{},missing_samples{};
    bool completed{},initial_identity{},final_identity{};
};
LiveInspection inspect_native_live(const json::Value&,Pair,std::string_view expected_vin);
std::string native_live_report(const LiveInspection&);
}
