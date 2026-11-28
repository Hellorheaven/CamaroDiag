#pragma once
#include "j1979_elm.hpp"
#include <map>
#include <set>
namespace cd::legacy {
enum class MutationOperation : std::uint8_t {clear=1,evap=2};
struct MutationPlan {
    Protocol protocol{};
    MutationOperation operation{};
    std::string vin;
    unsigned engine{},budget_ms{60000};
    std::set<unsigned> sources;
    bool valid()const;
    bool operator==(const MutationPlan&)const=default;
};
struct MutationObservation {
    std::string stage,command,raw,error;
    std::vector<Frame> frames;
    unsigned started_ms{},ended_ms{};
    bool attempted{};
    bool operator==(const MutationObservation&)const=default;
};
using DtcSnapshot=std::map<unsigned,std::map<unsigned,std::optional<std::vector<std::string>>>>;
struct MutationResult {
    MutationPlan plan;
    std::string vin,outcome{"NOT_TESTED"},reason;
    DtcSnapshot before,after;
    std::map<unsigned,std::string> acknowledgments;
    std::set<unsigned> unexpected_sources;
    std::vector<MutationObservation> observations;
    unsigned elapsed_ms{};
    bool baseline_saved{},intent_saved{},attempted{},complete_ack{};
};
using MutationJournal=std::function<void(std::string_view,const MutationResult&)>;
class MutationSession {
    Elm& elm_;
    bool used_{};
public:
    explicit MutationSession(Elm& elm):elm_(elm){}
    MutationSession(const MutationSession&)=delete;
    MutationSession& operator=(const MutationSession&)=delete;
    MutationResult run(const MutationPlan&,bool warning_acknowledged,bool confirmed,Clock,Current,MutationJournal);
};
std::vector<Frame> mutation_response_frames(const MutationPlan&,Bytes,std::string_view);
std::string mutation_report(const MutationResult&);
}
