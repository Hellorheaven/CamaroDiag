#pragma once
#include "j1979_legacy_mutation.hpp"
#include "native_json.hpp"
namespace cd::legacy::history {
struct LegacyMaintenanceInspection {
    json::Value proof;
    bool attempted{},acknowledged{},baseline_consistent{};
};
LegacyMaintenanceInspection inspect_historical_maintenance(const json::Value& final,const json::Value* baseline,
    std::optional<std::string> independent_vin,std::string_view report_digest={},std::string_view baseline_digest={});
}
