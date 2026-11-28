#pragma once
#include "native_json.hpp"
#include "j1979_context.hpp"
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
struct Scalar {
    unsigned mode{},pid{};
    std::string name,unit;
    double number{};
};
struct DtcMode {
    unsigned mode{};
    std::optional<std::string> status;
    std::optional<std::vector<std::string>> codes;
};
struct PlanInspection {
    PlanOrder order{};
    std::optional<std::string> declared_vin,initial_vin,final_vin;
    bool completed{},freeze_trigger_verified{},freeze_present{},sequence_exhausted{};
    std::string stop_reason;
    std::vector<Step> steps;
    std::array<Inventory,5> inventories{};
    std::vector<Scalar> measurements;
    std::array<DtcMode,3> dtcs{};
    bool basic_summary_checked{};
    unsigned application_evidence_checked{};
    std::string derived_summary;
    unsigned derived_groups_checked{};
};
// Verifies read-only request order and the historical application envelope.
// Derived report projections are intentionally not certified by this API.
PlanInspection inspect_plan(const json::Value& body,PlanOrder,std::optional<std::string> vin);
// Recompute only the historical scalar and DTC catalogue, not current ECU
// configuration-dependent numerical claims. Compare saved basic summaries.
void verify_basic_summary(const json::Value&,PlanInspection&);
std::string basic_summary_json(const PlanInspection&);
// Common historical arithmetic projections, never an authorization for live values.
struct HistoricalProjections {
    std::string status,multi,old_multi,context,operating;
};
HistoricalProjections historical_projections(std::span<const cd::j1979::Observation>);
void verify_derived_summary(const json::Value&,PlanInspection&);
std::string plan_report(const PlanInspection&,std::string_view digest={});
}
