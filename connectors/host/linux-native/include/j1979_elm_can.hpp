#pragma once
#include "j1979_elm.hpp"
#include "j1979_can.h"
#include "j1979_live.h"
#include "j1979_freeze_scope.h"
namespace cd::hostmaint {class ElmOwner;}
namespace cd::elmcan {
using legacy::Bytes;
using legacy::Data;
struct Scope{
    unsigned profile{};
    uint32_t tx{},rx{};
    bool extended()const{return profile==7||profile==9;}
    bool valid()const;
};
enum class DecodeStatus {received,no_data};
struct Decoded{
    DecodeStatus status{DecodeStatus::no_data};
    Data payload;
    unsigned selected_frames{},other_frames{},pending_frames{};
};
std::optional<unsigned> fixed_profile(std::string_view);
Decoded decode(std::string_view raw,const Scope&,Bytes request);
struct Observation{
    std::string command,raw,error;
    Data request,response;
    unsigned duration_ms{};
    int status{-1};
};
std::string report(const Scope&,std::string_view vin,std::string_view operation,std::string_view native_report,
    std::span<const Observation>,std::string_view error,bool completed);
class Session{
    friend class cd::hostmaint::ElmOwner;
    legacy::Link& link_;
    Scope scope_;
    legacy::Current current_;
    bool configured_{},used_{},retired_{};
    std::size_t total_raw_{};
    std::vector<Observation> evidence_;
    std::string setup(std::string_view,unsigned);
    void record(Observation);
    int exchange_admitted(Bytes,std::span<uint8_t>,std::size_t&,unsigned,bool);
public:
    Session(legacy::Link&,Scope,legacy::Current);
    Session(const Session&)=delete;
    Session& operator=(const Session&)=delete;
    void configure();
    bool current()const;
    const Scope& scope()const{return scope_;}
    const std::vector<Observation>& evidence()const{return evidence_;}
    int exchange(Bytes,std::span<uint8_t>,std::size_t&,unsigned);
    int exchange_freeze(const J1979FreezeScope&,Bytes,std::span<uint8_t>,std::size_t&,unsigned);
    static bool current_callback(void*);
    static int exchange_callback(void*,const uint8_t*,size_t,uint8_t*,size_t*,unsigned);
};
}
