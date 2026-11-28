#pragma once
#include "j1979_can_network.hpp"
#include "j1979_socket_campaign.hpp"
#include "native_json.hpp"
#include <map>
#include <set>
namespace cd::network {
// One newly-created private directory per selected network campaign.
// Never resumes an existing acquisition or sends automatically again.
class JournalDirectory {
    int fd_{-1};
    std::string path_;
    bool discovery_saved_{};
    std::set<std::uint32_t> attempted_;
    void verify_directory()const;
public:
    explicit JournalDirectory(const char* path);
    ~JournalDirectory();
    JournalDirectory(const JournalDirectory&)=delete;
    JournalDirectory& operator=(const JournalDirectory&)=delete;
    void save_discovery(const Discovery&);
    EcuReport scan(Pair,unsigned budget,const char* interface,const char* vin,const volatile sig_atomic_t* cancel);
};
std::string journal_name(std::uint32_t rx);
namespace history {
struct NetworkJournalInput{
    std::string discovery;
    std::map<std::uint32_t,socketcampaign::Data> journals;
};
// Inputs are exact bytes, not already-projected claims. No physical connection.
json::Value verify_network_journals(const json::Value& source,const NetworkJournalInput&,
    std::string_view vin,std::string_view interface,std::string_view report_digest);
// Read only the expected files through an anchored directory descriptor.
NetworkJournalInput load_network_journals(const char* directory,std::span<const std::uint32_t> selected);
}
}
