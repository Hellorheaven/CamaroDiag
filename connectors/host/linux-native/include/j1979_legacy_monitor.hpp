#pragma once
#include "j1979_legacy.hpp"
#include <optional>
#include <ostream>
namespace cd::legacy {
struct MonitorValue{
    unsigned mode{},test_id{},sensor_mask{},component{},value{};
    std::optional<unsigned> minimum,maximum,limit;
    std::string encoded_relation,limit_type;
    struct Scale {
        std::string_view name,unit;
        double factor{};
        std::string_view relation;
    };
    std::optional<Scale> scaled;
    Data encoded;
};
std::optional<MonitorValue> decode_monitor(Bytes request,Bytes response);
void write_monitor(std::ostream&,const MonitorValue&);
}
