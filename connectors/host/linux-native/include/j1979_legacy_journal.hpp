#pragma once
#include "j1979_legacy_scan_wire.hpp"
#include "j1979_legacy_freeze_wire.hpp"
#include "j1979_legacy_live_wire.hpp"
namespace cd::legacy {
enum class JournalKind : uint8_t { scan=1,freeze=2,live=3 };
constexpr size_t journal_max_bytes=freeze_max_capture+64;
struct JournalEvidence {
    JournalKind kind{};
    unsigned observations{};
    size_t verified_bytes{},torn_bytes{};
    bool terminal{};
    Data capture;
};
// The journal is append-only; every exchange is synced before another
// request can be sent. No terminal is inferred from an interrupted file.
class CaptureJournal {
    int fd_{-1};
    JournalKind kind_;
    Data header_;
    unsigned count_{};
    size_t bytes_{};
    bool finished_{},failed_{};
    void persist(Bytes);
    void append_single(Data);
public:
    CaptureJournal(const char* path,const ScanCapture& scope);
    CaptureJournal(const char* path,const FreezeCapture& scope);
    CaptureJournal(const char* path,const LiveCapture& scope);
    ~CaptureJournal();
    CaptureJournal(const CaptureJournal&)=delete;
    CaptureJournal& operator=(const CaptureJournal&)=delete;
    void append(const ScanExchange&);
    void append(const FreezeExchange&);
    void append(const LiveExchange&);
    void finish();
private:
    ScanCapture scan_{};
    FreezeCapture freeze_{};
    LiveCapture live_{};
    void begin(const char*,Data);
};
JournalEvidence inspect_journal(Bytes,Protocol,unsigned,std::string_view);
}
