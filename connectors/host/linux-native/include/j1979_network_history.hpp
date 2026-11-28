#pragma once
#include "j1979_can_network.hpp"
#include "native_json.hpp"
namespace cd::network::history {
struct Inspection {
    Discovery discovery;
    std::string platform;
    std::optional<unsigned> profile;
    bool probe_verified{};
};
Inspection inspect_discovery(const json::Value& root);
std::string report(const Inspection&,std::string_view source_sha256={});
}
