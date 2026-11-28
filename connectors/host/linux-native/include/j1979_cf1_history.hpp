#pragma once
#include "native_json.hpp"
#include <cstdint>
#include <optional>
#include <span>
namespace cd::cfhistory {
struct Inspection {
    json::Value proof;
    bool verified{},completed{};
};
json::Value decode(std::span<const std::uint8_t>);
Inspection verify(std::span<const std::uint8_t>,const json::Value&,std::string_view capture_digest,
    std::string_view report_digest,std::optional<std::string> independent_vin={});
}
