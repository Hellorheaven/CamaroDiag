#pragma once
#include "j1979_can_network.hpp"
#include "native_json.hpp"

namespace cd::network::history {
// Strict replay of the native C++ scan format, which deliberately differs
// from the historic Python/Android/J2534 campaign in ordering and status.
// A report is not proof of connector authenticity or physical vehicle access.
struct NativeInspection {
    Pair pair{};
    std::string vin,transport,stop_reason;
    bool completed{};
    unsigned steps{};
    std::string canonical;
};
NativeInspection inspect_native_plan(const json::Value&,Pair,std::string_view expected_vin);
std::string native_plan_report(const NativeInspection&);
}
