#pragma once
#include "j1979_legacy_scan.hpp"
#include "j1979_legacy_discovery_history.hpp"
#include "j1979_legacy_info.hpp"
#include "native_json.hpp"
#include <map>
namespace cd::legacy::history {
struct HistoricalStep {
    Data request;
    std::string status;
    std::vector<Data> accepted;
    unsigned duration{};
};
struct HistoricalScan {
    Protocol protocol{};
    unsigned source{},duration{},verified_responses{};
    bool driver{},completed{},probe_observed{},probe_replayed{};
    std::optional<std::string> declared_vin,observed_vin;
    std::string stop;
    std::vector<HistoricalStep> steps;
    std::vector<Inventory> inventories;
    std::vector<Identification> identification;
    std::map<unsigned,std::set<std::string>> dtcs;
    std::set<unsigned> other_sources;
    std::optional<bool> freeze_present;
    std::optional<std::string> freeze_dtc;
    std::vector<std::string> checked_summary_fields;
};
// Historical J2534 evidence has no link checksum; only headers and bytes.
Frame inspect_driver_frame(const json::Value&,Protocol,unsigned source);
Data driver_request_frame(Protocol,Bytes request);
HistoricalScan inspect_historical_scan(const json::Value&,std::optional<std::string> expected_vin={});
std::string historical_scan_report(const HistoricalScan&,std::string_view digest={});
json::Value compare_historical_discovery_scan(const DiscoveryInspection&,const HistoricalScan&,
    std::string_view discovery_digest={},std::string_view scan_digest={});
void verify_historical_scan_summary(const json::Value&,HistoricalScan&);
}
