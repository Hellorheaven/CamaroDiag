#pragma once
#include "native_json.hpp"
#include <array>
#include <optional>
#include <span>
#include <string>
#include <vector>
namespace cd::network::history {
enum class PlanOrder {historical,android};
using Bytes=std::vector<std::uint8_t>;
struct Step {
    Bytes request;
    std::optional<Bytes> response;
    std::string status,detail;
    unsigned duration_ms{};
};
struct Inventory {
    unsigned mode{},pages{};
    std::vector<unsigned> ids;
    std::string state{"UNAVAILABLE"};
};
struct PlanInspection {
    PlanOrder order{};
    std::optional<std::string> declared_vin,initial_vin,final_vin;
    bool completed{},freeze_trigger_verified{},freeze_present{},sequence_exhausted{};
    std::string stop_reason;
    std::vector<Step> steps;
    std::array<Inventory,5> inventories{};
};
// Verifies read-only request order and the historical application envelope.
// Derived report projections are intentionally not certified by this API.
PlanInspection inspect_plan(const json::Value& body,PlanOrder,std::optional<std::string> vin);
std::string plan_report(const PlanInspection&,std::string_view digest={});
}
