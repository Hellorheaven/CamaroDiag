#pragma once
#include "j1979_cj1.hpp"
#include "native_json.hpp"
#include <optional>
namespace cd::cj1 {
struct HistoricalInspection {
    json::Value proof;
    bool consistent{},verified{};
};
// Rebuild historical host report claims from unsigned CJ/1 bytes. Neither
// a host's authentication flag nor a successful CRC is a credential proof.
HistoricalInspection verify_historical(Bytes,const json::Value&,std::string_view capture_digest,
    std::string_view report_digest,std::optional<std::string> independent_vin={});
}
