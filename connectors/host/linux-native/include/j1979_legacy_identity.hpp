#pragma once
#include "j1979_legacy.hpp"
#include <span>
namespace cd::legacy {
bool valid_vin(std::string_view);
std::optional<std::string> normalized_vin(std::span<const Data> fragments);
bool vin_support_page(Bytes);
bool vin_fragment_count(Bytes);
}
