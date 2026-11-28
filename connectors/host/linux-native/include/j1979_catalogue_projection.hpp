#pragma once
#include "j1979_catalogue.hpp"
namespace cd::catalogue {
struct ProjectionRow{
    unsigned pid{},cycle{},elapsed_ms{},step_index{};
    std::optional<unsigned> frame;
    std::string source_status,status,reference;
    std::vector<std::uint8_t> response;
    std::vector<SignalResult> signals;
};
struct FrameCoverage{
    unsigned frame{};
    std::string state{"UNVISITED"};
    bool inventory_complete{},trigger_stable{};
    std::set<unsigned> documented,advertised,requested,responded,projected;
};
struct Projection{
    Mode mode{Mode::live};
    Transport transport{Transport::can};
    std::string vin,source_sha256,catalogue_sha256;
    std::string reconstructed_capture_sha256;
    std::uint32_t tx{},rx{};
    unsigned legacy_protocol{},legacy_source{};
    bool extended{},completed{},identity_rechecked{};
    std::vector<ProjectionRow> rows;
    std::vector<FrameCoverage> coverage;
};
Projection project_cl1(Bytes,std::string_view vin,const Catalogue&,std::string_view source_sha,std::string_view catalogue_sha);
Projection project_cf1(Bytes,std::string_view vin,const Catalogue&,std::string_view source_sha,std::string_view catalogue_sha);
Projection project_llr1(Bytes,std::string_view vin,unsigned protocol,unsigned source,const Catalogue&,std::string_view source_sha,std::string_view catalogue_sha);
Projection project_lj1(Bytes,std::string_view vin,unsigned protocol,unsigned source,const Catalogue&,std::string_view journal_sha,std::string_view catalogue_sha);
std::string report(const Catalogue&,const Projection&);
// Exact rational values, no conversion to floating point. One row per
// signal, or one empty signal row for an uninterpreted source observation.
std::string projection_csv(const Catalogue&,const Projection&);
}
