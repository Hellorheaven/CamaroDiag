#pragma once
#include "j1979_context.hpp"
#include <optional>
#include <ostream>
#include <string_view>
#include <vector>
namespace cd::j1979 {
struct MultiSignal {
    std::string_view name,unit;
    std::uint64_t raw{};
    std::optional<double> number;
    bool context_required{};
};
struct MultiRecord {
    unsigned pid{};
    std::vector<MultiSignal> signals;
};
std::optional<unsigned> multi_width(unsigned pid);
std::optional<MultiRecord> multi_signal(const Observation& row,
    const ScalingContext* context=nullptr);
void write_multi_json(std::ostream&,std::span<const Observation>,const ScalingContext* context=nullptr);
}
