#pragma once
#include "j1979_elm_can.hpp"
#include "cf1.h"
#include "j1979_cm1.hpp"
namespace cd::elmcan {
constexpr std::size_t trace_max_rows=J1979_LIVE_MAX_STEPS*2+64;
constexpr std::size_t trace_max_raw=32*1024*1024;
constexpr std::size_t trace_max_bytes=trace_max_raw+trace_max_rows*4700+128;
enum class TraceOperation:std::uint8_t {scan=1,live=2,freeze=3,maintenance=4};
struct Trace{
    Scope target;
    std::string vin;
    TraceOperation operation{};
    std::vector<Observation> rows;
};
struct Replay{
    std::size_t setup_rows{},diagnostic_rows{},positive{},negative{},no_data{},faults{};
    bool configured{},terminal_fault{};
};
// Row codec shared by the closed ECR/1 file and incremental EJR/1 journal.
Data encode_trace_row(const Observation&,std::uint32_t sequence);
Observation decode_trace_row(Bytes,std::uint32_t sequence,std::size_t& consumed);
Data encode_trace(const Trace&);
Trace decode_trace(Bytes);
Replay replay_trace(const Trace&);
Replay correlate_freeze(const Trace&,const cf1_evidence&);
Replay correlate_maintenance(const Trace&,const cm1::Inspection&);
}
