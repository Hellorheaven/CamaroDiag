#pragma once
#include "j1979_legacy_scan.hpp"
namespace cd::legacy {
constexpr std::size_t scan_max_capture=scan_max_raw+scan_max_exchanges*40+64;
struct ScanCapture{
    Protocol protocol{};
    unsigned source{},budget_ms{};
    std::string vin;
    std::vector<ScanExchange> trace;
};
Data encode_scan_capture(const ScanCapture&);
ScanCapture decode_scan_capture(Bytes);
Scan inspect_scan_capture(Bytes,Protocol,unsigned,std::string_view);
}
