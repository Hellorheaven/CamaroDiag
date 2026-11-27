#pragma once
#include "j1979_legacy_freeze.hpp"
namespace cd::legacy {
constexpr std::size_t freeze_max_capture=freeze_max_raw+freeze_max_trace*32+128;
struct FreezeCapture{
    Protocol protocol{};
    unsigned source{};
    FreezePlan plan;
    std::vector<FreezeExchange> trace;
};
Data encode_freeze_capture(const FreezeCapture&);
FreezeCapture decode_freeze_capture(Bytes);
FreezeResult inspect_freeze_capture(Bytes,Protocol,unsigned source,std::string_view vin);
}
