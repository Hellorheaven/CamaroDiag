#pragma once
#include "j1979_legacy.hpp"
#include "j1979_legacy_freeze_plan.hpp"
#include <functional>
#include <memory>
#include <stdexcept>
namespace cd::legacy {
struct Cancelled : std::runtime_error { Cancelled():std::runtime_error("Legacy session cancelled"){} };
struct TransportError : std::runtime_error {using std::runtime_error::runtime_error;};
using Current=std::function<bool()>;
using Clock=std::function<std::uint64_t()>;
class Link {
public:
    virtual ~Link()=default;
    virtual std::string command(std::string_view,unsigned timeout_ms)=0;
};
class MutationSession;
class Elm {
    friend class MutationSession;
    Link& link_;
    Protocol protocol_;
    bool headered_,ready_{},used_{},maintenance_used_{};
    Current current_;
    std::string command(std::string_view,unsigned timeout);
public:
    Elm(Link&,Protocol,bool headered,Current);
    Elm(const Elm&)=delete;
    Elm& operator=(const Elm&)=delete;
    void configure();
    std::string exchange(Bytes request,unsigned timeout);
    std::string exchange_freeze(const FreezePlan&,Bytes,unsigned timeout);
    std::string probe(unsigned timeout);
    bool headered()const{return headered_;}
    Protocol protocol()const{return protocol_;}
    bool ready()const{return ready_;}
};
std::unique_ptr<Link> serial_link(std::string_view port,unsigned baud,Current current);
std::uint64_t monotonic_ms();
}
