#pragma once
#include "j1979_live_evidence.hpp"
#include <string>
#include <string_view>

namespace cd::liveexport {
// Reserve every requested destination before physical I/O. Never overwrite,
// follow a final symlink, or substitute an unverified report for raw evidence.
// A failed run may retain empty/partial files; no implicit retry is allowed.
class Destination {
    int fd_{-1};
    std::string path_;
    void verify()const;
public:
    explicit Destination(const char* path);
    ~Destination();
    Destination(const Destination&)=delete;
    Destination& operator=(const Destination&)=delete;
    bool requested()const noexcept{return fd_>=0;}
    int descriptor()const noexcept{return fd_;}
    const char* path()const noexcept{return path_.c_str();}
    void check_empty()const;
    void persist(std::string_view);
};
// Check a newly reserved descriptor is still named, private and empty,
// and synchronize its directory before vehicle I/O.
void validate_reservation(int fd,const char* path);
struct Result {
    bool complete{};
    std::string source_sha256;
    std::size_t samples{};
};
// Only consume the exact durable report produced by the campaign. The
// caller retains its descriptor and must close the vehicle transport first.
Result publish(int source_fd,const char* source_path,std::string_view expected_report,
    cd::network::Pair pair,std::string_view vin,Destination& projection,Destination& csv);
// Replay a sealed original journal/campaign, match the durable raw report,
// and bind exports to the capture digest instead of just the JSON digest.
Result publish_recorded(int capture_fd,const char* capture_path,
    cd::network::history::LiveEvidenceKind kind,std::string_view expected_capture_digest,
    int report_fd,const char* report_path,std::string_view expected_report,
    cd::network::Pair pair,std::string_view vin,unsigned elm_profile,Destination& projection,Destination& csv);
}
