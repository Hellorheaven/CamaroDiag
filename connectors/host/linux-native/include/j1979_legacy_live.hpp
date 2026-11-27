#pragma once
#include "j1979_legacy_freeze.hpp"
#include "j1979_context.hpp"
#include <map>
namespace cd::legacy {
constexpr std::size_t live_max_trace=11200,live_max_raw=16*1024*1024;
struct LivePlan {
    std::string vin;
    std::vector<std::uint8_t> pids;
    unsigned cycles{30},interval_ms{1000},budget_ms{180000};
    bool raw_only{};
    bool valid()const;
    bool allows(Bytes)const;
    bool operator==(const LivePlan&)const=default;
};
enum class LivePhase : std::uint8_t {initial=1,inventory=2,context=3,periodic=4,sample=5,final=6};
struct LiveExchange{
    FreezeExchange exchange;
    LivePhase phase{};
    unsigned cycle{},started_ms{},ended_ms{};
    bool operator==(const LiveExchange&)const=default;
};
struct LiveSample{
    unsigned cycle{},pid{},elapsed_ms{};
    std::string status,detail;
    Data response;
};
struct LiveResult{
    Protocol protocol{};
    unsigned source{};
    LivePlan plan;
    std::vector<LiveExchange> trace;
    std::vector<LiveSample> samples;
    std::set<unsigned> advertised,other_sources;
    std::vector<Data> context_requests,context_responses;
    std::string initial_vin,final_vin,error;
    unsigned completed_cycles{},duration_ms{};
    bool inventory_complete{},identity_rechecked{},completed{};
};
using Wait=std::function<void(unsigned)>;
LiveResult acquire_live(Elm&,unsigned,const LivePlan&,Clock,Current,Wait);
LiveResult replay_live(Protocol,unsigned,const LivePlan&,std::span<const LiveExchange>);
std::string live_report(const LiveResult&);
}
