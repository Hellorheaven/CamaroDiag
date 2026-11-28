#pragma once
#include "j1979_legacy.hpp"
#include "j1979_vehicle_info.h"
namespace cd::legacy {
struct Identification {
    unsigned kind{};
    std::vector<std::string> values;
    std::optional<j1979_vehicle_info> counters;
    Data encoded;
    bool interpreted{};
};
std::optional<Identification> decode_identification(unsigned kind,unsigned count,std::span<const Data> rows);
bool count_kind(unsigned kind);
unsigned matching_count_kind(unsigned kind);
}
