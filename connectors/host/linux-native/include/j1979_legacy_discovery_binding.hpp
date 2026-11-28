#pragma once
#include "j1979_legacy_discovery_history.hpp"
#include "j1979_legacy_scan_wire.hpp"
namespace cd::legacy::history {
struct ScanBinding {
    Protocol protocol{};
    unsigned source{};
    std::array<std::uint8_t,4> discovery_bitmap{};
    std::optional<std::array<std::uint8_t,4>> scan_bitmap;
    bool scan_completed{},identity_rechecked{};
    std::string observed_vin;
};
// The independently supplied target is mandatory. Discovery eligibility is
// historical evidence only, never a grant of current session authorization.
ScanBinding bind_capture(const DiscoveryInspection&,Bytes,Protocol,unsigned,std::string_view vin);
std::string binding_report(const ScanBinding&,std::string_view discovery_digest,std::string_view capture_digest);
}
