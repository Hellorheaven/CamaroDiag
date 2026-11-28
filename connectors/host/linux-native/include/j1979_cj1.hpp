#pragma once
#include <array>
#include <cstdint>
#include <span>
#include <string>
#include <string_view>
#include <vector>

namespace cd::cj1 {
using Bytes=std::span<const std::uint8_t>;
constexpr std::size_t max_records=2048, max_body=4110;
constexpr std::size_t max_capture=max_records*(max_body+12), max_report=32*1024*1024;
struct Profile {
    std::uint32_t tx{},rx{},budget{};
    bool extended{};
    bool operator==(const Profile&) const=default;
};
struct Step {
    unsigned sequence{},state{};
    std::uint32_t duration{};
    std::vector<std::uint8_t> request,response;
};
struct Evidence {
    Profile profile;
    bool profile_verified{},integrity_verified{};
    std::vector<Step> steps;
    std::array<unsigned,6> counts{};
    unsigned end{},flags{};
    std::string vin,error;
};
bool valid_profile(const Profile& profile);
std::array<std::uint8_t,13> encode_profile(const Profile& profile);
Evidence inspect(Bytes bytes);
std::string report(const Evidence& evidence,std::string_view expected_vin,std::string_view digest,
    bool live=false,bool authenticated=false,bool proof_sent=false);
}
