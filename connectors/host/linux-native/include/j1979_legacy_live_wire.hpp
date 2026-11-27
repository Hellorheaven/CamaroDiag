#pragma once
#include "j1979_legacy_live.hpp"
namespace cd::legacy {
constexpr std::size_t live_max_capture=live_max_raw+live_max_trace*40+128;
struct LiveCapture{
    Protocol protocol{};
    unsigned source{};
    LivePlan plan;
    std::vector<LiveExchange> trace;
};
Data encode_live_capture(const LiveCapture&);
LiveCapture decode_live_capture(Bytes);
LiveResult inspect_live_capture(Bytes,Protocol,unsigned,std::string_view independent_vin);
}
