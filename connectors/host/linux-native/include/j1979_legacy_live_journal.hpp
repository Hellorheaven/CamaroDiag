#pragma once
#include "j1979_legacy_journal.hpp"
#include "native_json.hpp"

namespace cd::legacy {
struct LiveJournalProjection {
    json::Value report;
    std::string csv,journal_sha256,capture_sha256;
    bool completed{};
};
// A sealed LJ/1 terminal is distinct from a successful diagnostic result.
LiveJournalProjection project_live_journal(Bytes,Protocol,unsigned source,std::string_view vin);
json::Value verify_live_journal(const LiveJournalProjection&,const json::Value&,std::string_view report_sha256);
}
