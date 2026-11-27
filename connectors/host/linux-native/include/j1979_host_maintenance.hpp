#pragma once
#include "j1979_maintenance_campaign.h"
#include "j1979_maintenance_journal.h"
#include <functional>
#include <string>
namespace cd::hostmaint {
using Campaign=J1979MaintenanceCampaign;
using Scope=Campaign::Scope;
using Journal=J1979MaintenanceJournal;
using Current=std::function<bool()>;
struct Result{
    Campaign::Evidence evidence;
    bool journal_created{},recovery_latched{};
    std::string error;
};
class FileStorage final:public Journal::Storage{
    int dir_{-1},fd_{-1};
    std::string journal_name_;
    bool failed_{},latched_{};
    bool same_file()const;
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
