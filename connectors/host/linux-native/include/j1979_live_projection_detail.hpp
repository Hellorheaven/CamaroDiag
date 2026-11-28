#pragma once
#include "j1979_native_live_inspection.hpp"

namespace cd::network::history::projection_detail {
// Internal adapter boundary. Inputs must be constructed only by successful
// independent replay, never directly from a user-supplied JSON projection.
NativeLiveProjection project_replayed_rows(const json::Value& root,
    const LiveInspection& verified,std::string_view vin);
}
