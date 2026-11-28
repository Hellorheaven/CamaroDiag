#pragma once
#include "j1979_elm_can_trace.hpp"
namespace cd::elmcan {
constexpr std::size_t campaign_max_events=200000;
constexpr std::size_t campaign_max_bytes=trace_max_bytes+campaign_max_events*12+512;
struct CampaignPlan{
    Scope target;
    std::string vin,interface;
    TraceOperation operation{};
    unsigned budget_ms{},cycles{},interval_ms{};
    Data pids;
    bool valid()const;
};
enum class EventKind:std::uint8_t {clock=1,current=2,exchange=3,wait=4};
struct Event {
    EventKind kind{};
    uint32_t value{},aux{};
    bool operator==(const Event&)const=default;
};
struct CampaignCapture{
    CampaignPlan plan;
    Trace trace;
    std::vector<Event> events;
};
struct CampaignResult{
    std::string native_report,stop;
    bool completed{};
    size_t steps{};
};
using Wait=std::function<bool(unsigned)>;
CampaignResult record_campaign(Session&,const CampaignPlan&,legacy::Clock,Wait,CampaignCapture&);
CampaignResult replay_campaign(const CampaignCapture&);
Data encode_campaign(const CampaignCapture&);
CampaignCapture decode_campaign(Bytes);
}
