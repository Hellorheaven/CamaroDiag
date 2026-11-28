#pragma once
#include "j1979_maintenance_campaign.h"
#include "j1979_maintenance_journal.h"
#include "j1979_elm_can.hpp"
#include <functional>
#include <string>
#include <string_view>
#include <signal.h>
namespace cd::hostmaint {
using Campaign=J1979MaintenanceCampaign;
using Scope=Campaign::Scope;
using Journal=J1979MaintenanceJournal;
using Current=std::function<bool()>;
struct Result{
    Campaign::Evidence evidence;
    bool journal_created{},recovery_latched{},local_socket_closed{},local_serial_closed{},elm_transport{};
    std::string error;
};
Result run_socket(const Scope&,const char* interface,const char* directory,const char* journal_name,
    bool warning,bool approved,const volatile sig_atomic_t* cancel);
struct ElmResult{Result result;std::vector<elmcan::Observation> observations;};
ElmResult run_elm(const Scope&,elmcan::Scope,legacy::Link&,Current,const char* directory,const char* journal_name,bool warning,bool approved,elmcan::EvidenceSink={});
std::string report(const Result&,std::string_view digest,std::string_view independent_journal_report);
class FileStorage final:public Journal::Storage{
    int dir_{-1},fd_{-1},marker_{-1};
    std::string journal_name_;
    bool failed_{},latched_{};
    bool same_file()const;
    bool same_marker()const;
public:
    FileStorage(const char* directory,const char* journal_name);
    ~FileStorage()override;
    FileStorage(const FileStorage&)=delete;
    FileStorage& operator=(const FileStorage&)=delete;
    bool ready()override;
    bool size(size_t&)override;
    bool append(const uint8_t*,size_t)override;
    bool flush()override;
    bool read(size_t,uint8_t*,size_t)override;
    void latchRecovery()override;
    bool latched()const{return latched_;}
};
}
