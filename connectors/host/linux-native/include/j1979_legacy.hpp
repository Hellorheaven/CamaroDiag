#pragma once
#include <cstdint>
#include <optional>
#include <span>
#include <string>
#include <string_view>
#include <vector>
#include <array>
namespace cd::legacy {
using Bytes=std::span<const std::uint8_t>;
using Data=std::vector<std::uint8_t>;
enum class Protocol : unsigned { pwm=1,vpw=2,iso9141=3,kwp_slow=4,kwp_fast=5 };
bool valid_protocol(Protocol);
bool valid_source(unsigned);
std::string_view name(Protocol);
std::uint8_t checksum(Protocol,Bytes);
std::string hex(Bytes);
Data unhex(std::string_view);
bool read_allowed(Bytes);
std::optional<Protocol> fixed_protocol(std::string_view response);
struct Frame {
    unsigned source{};
    Data raw,payload;
    bool operator==(const Frame&) const=default;
};
std::vector<Frame> frames(Protocol,std::string_view command,std::string_view response);
std::vector<Data> anonymous_records(std::string_view command,std::string_view response);
struct Responder {
    unsigned source{};
    std::string status,detail;
    std::optional<std::array<std::uint8_t,4>> bitmap;
    std::vector<unsigned> supported;
    std::optional<bool> next;
    std::vector<Frame> observations;
};
std::vector<Responder> classify(std::span<const Frame>);
}
