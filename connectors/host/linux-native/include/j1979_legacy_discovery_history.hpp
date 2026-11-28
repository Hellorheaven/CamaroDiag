#pragma once
#include "j1979_legacy.hpp"
#include "native_json.hpp"
namespace cd::legacy::history {
struct DiscoveryInspection {
    Protocol protocol{};
    std::string platform;
    std::optional<std::string> error;
    bool completed{}, checksum_verified{}, probe_verified{};
    std::vector<Responder> responders;
    std::vector<unsigned> eligible;
};
DiscoveryInspection inspect(const json::Value&);
std::string report(const DiscoveryInspection&,std::string_view source_sha256={});
}
