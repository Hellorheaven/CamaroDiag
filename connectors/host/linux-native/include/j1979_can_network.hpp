#pragma once
#include <array>
#include <signal.h>
#include <cstdint>
#include <functional>
#include <optional>
#include <span>
#include <string>
#include <string_view>
#include <vector>
namespace cd::network {
constexpr unsigned max_frames=512,max_targets=32;
struct Frame{
    std::uint32_t id{};
    bool extended{},rtr{},error{};
    std::vector<std::uint8_t> data;
    bool operator==(const Frame&)const=default;
};
struct Pair{
    std::uint32_t tx{},rx{};
    bool operator==(const Pair&)const=default;
};
enum class State:std::uint8_t {positive,negative,pending,invalid,conflicting};
struct Classification{
    Pair pair;
    State state{State::invalid};
    std::array<std::uint8_t,4> bitmap{};
    std::uint8_t nrc{};
    bool operator==(const Classification&)const=default;
};
struct Responder{
    Classification classification;
    std::vector<Frame> frames;
};
struct Discovery{
    bool extended{},sent{},completed{};
    unsigned duration_ms{},ignored{};
    std::string stop;
    std::vector<Frame> frames;
    std::vector<Responder> responders;
    std::vector<std::uint32_t> eligible;
};
Frame functional_request(bool extended);
std::optional<Pair> response_pair(std::uint32_t,bool extended);
std::optional<Classification> classify(const Frame&,bool extended);
Discovery replay_discovery(bool extended,bool sent,std::span<const Frame>,std::string_view stop,unsigned duration);
bool valid_targets(const Discovery&,std::span<const std::uint32_t>);
Discovery discover_socket(const char* interface,bool extended,unsigned window_ms,std::function<bool()> current);
std::string report(const Discovery&);
struct EcuReport{
    Pair pair;
    std::string status{"NOT_TESTED"},error,json;
};
struct Scan{
    Discovery discovery;
    std::string vin,stop;
    std::vector<std::uint32_t> selected;
    std::vector<EcuReport> ecus;
    unsigned duration_ms{};
    bool completed{};
};
using PhysicalScan=std::function<EcuReport(Pair,unsigned)>;
Scan scan_selected(const Discovery&,std::span<const std::uint32_t>,std::string_view vin,unsigned budget_ms,
    std::function<std::uint64_t()> clock,std::function<bool()> current,PhysicalScan);
EcuReport scan_socket(Pair,unsigned,const char* interface,const char* vin,const volatile sig_atomic_t* cancel);
std::string report(const Scan&);
}
