#pragma once
#include "j1979_elm.hpp"
#include <set>
namespace cd::legacy {
struct ScanStep {
    Data request;
    std::string raw,status,detail;
    std::vector<Frame> observations;
    std::vector<Data> selected;
    std::uint64_t duration_ms{};
};
struct Inventory {
    unsigned mode{},sensor{},pages{};
    std::set<unsigned> advertised;
    bool complete{};
};
enum class ScanOutcome : std::uint8_t {received=1,transport=2,cancelled=3,budget=4};
struct ScanExchange {
    std::string command,raw;
    ScanOutcome outcome{ScanOutcome::received};
    unsigned started_ms{},ended_ms{};
    bool attempted{};
    bool operator==(const ScanExchange&) const=default;
};
constexpr std::size_t scan_max_exchanges=4098,scan_max_raw=8*1024*1024;
struct Scan {
    Protocol protocol{};
    unsigned source{};
    std::string expected_vin,initial_vin,final_vin,initial_probe,final_probe,error;
    unsigned budget_ms{};
    std::vector<ScanExchange> trace;
    std::vector<ScanStep> steps;
    std::vector<Inventory> inventories;
    std::set<unsigned> other_sources;
    bool completed{},identity_rechecked{},budget_expired{};
};
Scan scan_selected(Elm&,unsigned source,std::string_view expected_vin,unsigned budget_ms,Clock,Current,const std::function<void(const ScanExchange&)>& sink={});
Scan replay_scan(Protocol,unsigned source,std::string_view expected_vin,unsigned budget_ms,std::span<const ScanExchange>);
std::string scan_report(const Scan&);
}
