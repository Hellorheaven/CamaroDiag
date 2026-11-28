#pragma once
#include "j1979_legacy.hpp"
#include <set>
namespace cd::legacy {
struct FreezePlan {
    std::string vin;
    std::vector<std::uint8_t> frames,pids;
    unsigned budget_ms{180000};
    [[nodiscard]] bool valid()const;
    [[nodiscard]] bool allows(Bytes request)const;
    bool operator==(const FreezePlan&)const=default;
};
std::vector<Frame> freeze_frames(Protocol,const FreezePlan&,Bytes request,std::string_view raw);
}
