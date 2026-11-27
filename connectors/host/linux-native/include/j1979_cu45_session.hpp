#pragma once
#include "j1979_cu45.hpp"
#include <functional>
#include <stdexcept>
namespace cd::cu45 {
struct TransportError : std::runtime_error {using std::runtime_error::runtime_error;};
struct Link {
    virtual ~Link()=default;
    virtual void write(cm1::Bytes,std::uint64_t deadline)=0;
    virtual std::size_t read(std::span<std::uint8_t>,std::uint64_t deadline)=0;
    virtual void quiet(std::uint64_t deadline)=0;
    virtual void finish()=0;
};
using Clock=std::function<std::uint64_t()>;
using Current=std::function<bool()>;
using Sink=std::function<void(cm1::Bytes)>;
struct Result {
    Mode mode{Mode::archive};
    cm1::Scope scope;
    bool authenticated{},proof_attempted{},proof_sent{},cancelled{},file_created{},receipt_verified{};
    std::optional<Declaration> declared;
    std::optional<cm1::Inspection> inspection;
    std::vector<std::uint8_t> journal;
    std::string error;
    std::uint64_t elapsed_ms{};
    [[nodiscard]] bool verified()const{return error.empty()&&inspection.has_value();}
    [[nodiscard]] bool recovery_required()const{
        if(mode==Mode::archive)return !verified()||!declared||declared->recovery;
        return proof_attempted&&(!verified()||inspection->recovery_required());
    }
};
class Session {
    Link& link_;
    Clock clock_;
    Current current_;
    bool used_{};
public:
    Session(Link&,Clock,Current);
    Session(const Session&)=delete;
    Session& operator=(const Session&)=delete;
    Result run(Mode,const cm1::Scope&,std::span<const std::uint8_t,32>,const Nonce&,Sink,
               bool operator_authorized);
};
Result capture_serial(Mode,const cm1::Scope&,std::span<const std::uint8_t,32>,
    const char* port,const char* path,bool operator_authorized,Current current);
std::string report(const Result&,std::string_view digest);
}
