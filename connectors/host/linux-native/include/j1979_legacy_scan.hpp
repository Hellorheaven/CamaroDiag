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
struct Scan {
    Protocol protocol{};
    unsigned source{};
    std::string expected_vin,initial_vin,final_vin,initial_probe,final_probe,error;
    std::vector<ScanStep> steps;
    std::vector<Inventory> inventories;
    std::set<unsigned> other_sources;
    bool completed{},identity_rechecked{},budget_expired{};
};
Scan scan_selected(Elm&,unsigned source,std::string_view expected_vin,unsigned budget_ms,Clock,Current);
std::string scan_report(const Scan&);
}
