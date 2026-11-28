#pragma once
#include "native_json.hpp"
#include "j1979_legacy.hpp"
#include <array>
namespace cd::canhistory {
constexpr size_t max_report=8*1024*1024;
using Data=legacy::Data;
struct Scope{
    bool evap{};
    std::string vin;
    uint32_t tx{},rx{};
    bool operator==(const Scope&)const=default;
};
struct Row{
    std::string stage,command,error;
    std::optional<Data> response;
    bool operator==(const Row&)const=default;
};
using Dtc=std::array<std::optional<std::vector<std::string>>,3>;
struct Readiness{
    bool mil{};
    unsigned count{};
    std::string bits;
};
struct Inspection{
    Scope scope;
    std::string platform,outcome,reason,observed,baseline_origin;
    std::vector<Row> rows;
    Dtc before,after;
    std::array<std::optional<Readiness>,2> readiness;
    bool attempted{},acknowledged{},baseline_consistent{},fresh{};
};
Inspection inspect(std::string_view report,std::string_view baseline,std::string_view independent_vin);
std::string report(const Inspection&,std::string_view digest,std::string_view baseline_digest);
}
