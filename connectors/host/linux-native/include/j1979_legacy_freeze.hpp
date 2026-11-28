#pragma once
#include "j1979_elm.hpp"
#include <set>
namespace cd::legacy {
constexpr std::size_t freeze_max_steps=8*(8+2+247)+2;
constexpr std::size_t freeze_max_trace=freeze_max_steps+8;
constexpr std::size_t freeze_max_raw=32*1024*1024;
enum class FreezeOutcome : std::uint8_t {received=1,timeout=2,transport=3,budget=4,cancelled=5,late=6};
struct FreezeExchange{
    std::string command,raw;
    FreezeOutcome outcome{FreezeOutcome::transport};
    unsigned duration_ms{};
    bool attempted{};
    bool operator==(const FreezeExchange&)const=default;
};
struct FreezeStep{
    Data request,response;
    FreezeOutcome outcome{FreezeOutcome::transport};
    std::string status,detail;
    unsigned first_exchange{},exchange_count{};
};
struct FrozenFrame{
    unsigned frame{};
    std::string state{"UNAVAILABLE"},detail;
    std::optional<std::uint16_t> trigger,final_trigger;
    std::set<unsigned> advertised,read,unadvertised,unread;
    std::vector<unsigned> pages,measurement_steps;
    bool inventory_complete{},trigger_stable{};
};
struct FreezeResult{
    Protocol protocol{};
    unsigned source{};
    FreezePlan plan;
    std::vector<FreezeExchange> trace;
    std::vector<FreezeStep> steps;
    std::vector<FrozenFrame> frames;
    std::set<unsigned> other_sources;
    std::string initial_vin,final_vin,error;
    bool completed{},identity_rechecked{},all_frames_stable{};
};
using FreezeRead=std::function<FreezeExchange(std::string_view)>;
FreezeResult freeze_recipe(Protocol,unsigned source,const FreezePlan&,const FreezeRead&);
FreezeResult acquire_freeze(Elm&,unsigned source,const FreezePlan&,Clock,Current,const std::function<void(const FreezeExchange&)>& sink={});
FreezeResult replay_freeze(Protocol,unsigned source,const FreezePlan&,std::span<const FreezeExchange>);
std::string freeze_report(const FreezeResult&);
}
