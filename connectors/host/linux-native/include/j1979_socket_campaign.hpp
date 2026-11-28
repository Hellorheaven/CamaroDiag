#pragma once
#include "j1979_can.h"
#include "j1979_live.h"
#include "native_json.hpp"
#include <functional>
#include <optional>
#include <span>
#include <string>
#include <vector>

namespace cd::socketcampaign {
using Bytes=std::span<const std::uint8_t>;
using Data=std::vector<std::uint8_t>;
constexpr std::size_t max_events=200000,max_journal=64u*1024u*1024u,max_report=64u*1024u*1024u;
enum class Operation {scan,live};
struct Plan {
    Operation operation{};
    std::uint32_t tx{},rx{};
    std::string vin,interface;
    unsigned budget_ms{},cycles{},interval_ms{};
    Data pids;
    bool valid()const;
    bool operator==(const Plan&)const=default;
};
enum class Kind {clock,current,wait,intent,response,terminal};
struct Event {
    Kind kind{};
    std::uint64_t value{};
    unsigned aux{};
    Data bytes;
    bool operator==(const Event&)const=default;
};
struct Journal {
    Plan plan;
    std::vector<Event> events;
    std::size_t verified_bytes{},total_bytes{};
    bool torn{},terminal{};
};
struct Result {
    std::string report,stop;
    bool completed{},prefix{},pending_exchange{};
    unsigned exchanges{},dispatch_calls{},response_records{};
};
Data encode_header(const Plan&);
Data encode_event(const Event&,std::size_t sequence);
Journal decode(Bytes,bool allow_prefix);
std::string sha256(Bytes);
std::string sha256(std::string_view);
Result replay(const Journal&);
json::Value proof(const Journal&,const Result&,std::string_view journal_digest);
class Writer {
    int fd_;
    std::size_t bytes_{},sequence_{};
    bool failed_{},finished_{};
    void persist(Bytes);
public:
    Writer(int fd,const Plan&);
    Writer(const Writer&)=delete;
    Writer& operator=(const Writer&)=delete;
    void append(const Event&);
    void append_batch(std::span<const Event>);
    bool healthy()const noexcept{return !failed_&&!finished_;}
};
// All callback implementations are injected; replay never opens a transport.
// Writer owns no descriptor; caller holds it for the whole campaign.
struct Link {
    j1979_can_exchange exchange{};
    j1979_can_clock clock{};
    j1979_can_context_current current{};
    j1979_live_wait wait{};
    void* context{};
};
Result record(const Plan&,const Link&,Writer&);
void finish(Writer&,const Result&);
}
