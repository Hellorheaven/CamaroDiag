#pragma once
#include "j1979_elm_can_trace.hpp"
namespace cd::elmcan {
constexpr size_t journal_max_bytes=trace_max_bytes+trace_max_rows*12+64;
struct JournalInspection {
    Trace trace;
    Replay replay;
    size_t verified_bytes{},total_bytes{};
    bool terminal{},torn{};
};
class EvidenceJournal {
    int fd_;
    Trace scope_;
    uint32_t sequence_{},rolling_{0xffffffffu};
    size_t bytes_{},raw_{};
    bool failed_{},finished_{};
    void persist(Bytes);
public:
    EvidenceJournal(int fd,Scope,std::string_view vin,TraceOperation);
    EvidenceJournal(const EvidenceJournal&)=delete;
    EvidenceJournal& operator=(const EvidenceJournal&)=delete;
    void append(const Observation&);
    void finish();
};
JournalInspection inspect_journal(Bytes,bool allow_prefix);
Data recover_trace(Bytes);
}
