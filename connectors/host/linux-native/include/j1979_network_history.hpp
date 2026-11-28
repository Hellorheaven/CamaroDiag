#pragma once
#include "j1979_can_network.hpp"
#include "native_json.hpp"
#include "j1979_historical_can_plan.hpp"
#include "j1979_native_can_inspection.hpp"
namespace cd::network::history {
struct Inspection {
    Discovery discovery;
    std::string platform;
    std::optional<unsigned> profile;
    bool probe_verified{};
};
Inspection inspect_discovery(const json::Value& root);
std::string report(const Inspection&,std::string_view source_sha256={});
struct PhysicalRow {
    Pair pair;
    std::string status,error;
    std::optional<PlanInspection> plan;
    std::optional<NativeInspection> native_plan;
};
struct NetworkInspection {
    Inspection admission;
    std::optional<std::string> vin;
    bool completed{},discovery_only{};
    std::string stop;
    std::vector<std::uint32_t> selected;
    std::vector<PhysicalRow> rows;
};
NetworkInspection inspect_network(const json::Value&,std::optional<std::string> expected_vin);
std::string report(const NetworkInspection&,std::string_view source_sha256={});
}
