#pragma once
#include <array>
#include <cstdint>
#include <functional>
#include <optional>
#include <span>
#include <string>
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
}
