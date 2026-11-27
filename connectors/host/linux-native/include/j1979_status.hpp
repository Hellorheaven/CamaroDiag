#pragma once
#include "j1979_context.hpp"
#include <optional>
#include <ostream>
#include <string>
#include <string_view>
#include <vector>
namespace cd::j1979 {
struct StatusField {
    std::string name;
    unsigned raw{};
    std::string_view state;
};
struct StatusRecord {
    unsigned pid{};
    std::vector<StatusField> states;
};
std::optional<unsigned> status_width(unsigned pid);
std::optional<StatusRecord> status_record(const Observation&);
void write_status_json(std::ostream&,std::span<const Observation>);
}
