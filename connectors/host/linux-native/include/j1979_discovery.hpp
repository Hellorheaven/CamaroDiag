#pragma once
#include "j1979_elm.hpp"
namespace cd::legacy {
struct Discovery {
    Protocol protocol{};
    std::string response,probe,error;
    std::vector<Frame> observations;
    std::vector<Responder> responders;
    bool queried{},probed{},complete{};
};
Discovery discover(Elm&,unsigned budget_ms,Clock,Current);
Discovery replay_discovery(Protocol,std::string_view response,std::string_view probe);
std::string discovery_report(const Discovery&);
}
