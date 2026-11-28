#pragma once
#include "j1979_legacy_mutation.hpp"
namespace cd::legacy {
constexpr size_t mutation_report_max=8*1024*1024;
struct MutationInspection{
    MutationResult result;
    bool baseline_correlated{},intent_correlated{},fresh_checks{},sequence_complete{};
};
MutationResult parse_mutation_report(std::string_view);
MutationInspection inspect_mutation(std::string_view final_report,std::string_view baseline,
    std::string_view intent,std::string_view independent_vin);
std::string inspection_report(const MutationInspection&,std::string_view report_digest,
    std::string_view baseline_digest,std::string_view intent_digest);
}
