#pragma once
#include "j1979_catalogue_exact.hpp"
#include <map>
#include <set>
#include <span>
#include <string_view>
#include <vector>
namespace cd::catalogue {
using Bytes=std::span<const std::uint8_t>;
enum class Transport{can,legacy};
enum class Mode{live,freeze};
enum class Order{msb0,le_bytes};
struct Bits{
    unsigned offset{},width{};
    Order order{Order::msb0};
    std::uint64_t extract(Bytes)const;
};
struct Presence{Bits bits;std::uint64_t mask{},equals{};};
struct Signal{
    std::string name,unit,reference;
    Bits bits;
    bool signed_value{};
    Ratio scale,offset;
    std::set<std::uint64_t> unavailable;
    std::optional<Presence> presence;
    std::optional<Ratio> minimum,maximum;
    std::map<std::uint64_t,std::string> enumeration;
};
struct Definition{
    unsigned pid{},width{};
    std::vector<Transport> protocols;
    std::string reference;
    std::vector<Signal> signals;
    std::optional<unsigned> frame;
};
struct Catalogue{
    Mode mode{Mode::live};
    std::string edition,reference_name,reference_sha256;
    std::vector<Definition> definitions;
    const Definition* find(unsigned pid,Transport,std::optional<unsigned> frame={})const;
};
struct SignalResult{
    std::string name,unit,reference,status;
    std::optional<std::string> raw,enumeration;
    std::optional<Exact> value;
};
constexpr std::size_t max_catalogue=4*1024*1024,max_reference=128*1024*1024,max_projection=128*1024*1024;
bool valid_digest(std::string_view);
Catalogue parse_catalogue(std::string_view,Mode,std::string_view reference_pin);
SignalResult project_signal(const Signal&,Bytes);
std::string inventory(const Catalogue&,std::string_view catalogue_sha);
}
