#include "j1979_live_projection_detail.hpp"
#include "cl1.h"
#include <array>
#include <stdexcept>
#include <string>

namespace cd::network::history {
namespace {
using json::Value;
using Object=Value::Object;
using Array=Value::Array;
using Bytes=std::span<const std::uint8_t>;
void require(bool b,const char* reason){if(!b)throw std::invalid_argument(reason);}
Value number(std::uint64_t n){return Value{json::Integer{std::to_string(n)}};}
Value text(std::string_view s){return Value{std::string(s)};}
Value object(Object o){return Value{std::move(o)};}
std::string hex(Bytes bytes){
    constexpr char digits[]="0123456789ABCDEF";
    std::string out;out.reserve(bytes.size()*2);
    for(auto b:bytes){out+=digits[b>>4];out+=digits[b&15];}
    return out;
}
std::string address(std::uint32_t n){
    constexpr char digits[]="0123456789ABCDEF";
    std::string out;
    do{out.insert(out.begin(),digits[n&15]);n>>=4;}while(n);
    return out;
}
unsigned u16(Bytes b,std::size_t at){
    return unsigned(b[at])|(unsigned(b[at+1])<<8);
}
std::uint32_t u32(Bytes b,std::size_t at){
    return std::uint32_t(b[at])|(std::uint32_t(b[at+1])<<8)|
        (std::uint32_t(b[at+2])<<16)|(std::uint32_t(b[at+3])<<24);
}
std::string_view phase(unsigned n){
    switch(n){
    case 1:return "INITIAL_VIN";
    case 2:return "INVENTORY";
    case 3:return "PERIODIC_VIN";
    case 4:return "SAMPLE";
    case 5:return "FINAL_VIN";
    default:throw std::invalid_argument("Invalid CL/1 phase");
    }
}
std::string_view status(unsigned n,bool accepted){
    if(!accepted)return "FAILED";
    switch(n){
    case 1:return "PASSED";
    case 2:return "RAW";
    case 3:return "NEGATIVE";
    case 4:return "TIMEOUT";
    default:throw std::invalid_argument("Invalid CL/1 observation state");
    }
}
std::string_view stop(unsigned n){
    switch(n){
    case 0:return "";
    case 2:return "BUDGET_EXHAUSTED";
    case 3:return "CANCELLED";
    case 4:return "TRANSPORT_ERROR";
    case 5:return "VIN_UNVERIFIED";
    case 6:return "VIN_MISMATCH";
    case 7:return "INVENTORY_INCOMPLETE";
    case 8:return "INCOMPLETE_SAMPLES";
    case 11:return "CAPACITY_LIMIT";
    default:throw std::invalid_argument("Invalid CL/1 terminal reason");
    }
}
Value observation(Bytes body){
    require(body.size()>=CL1_EVENT_SIZE,"Truncated CL/1 event");
    const auto response=body.subspan(CL1_EVENT_SIZE);
    const auto request=body.subspan(24,2);
    const bool accepted=body[2]==1;
    const auto state=status(body[1],accepted);
    const unsigned start=u32(body,8),end=u32(body,12);
    require(end>=start&&u16(body,18)==response.size(),"CL/1 event shape changed");
    Value detail;
    if(!accepted)detail=text("REJECTED_BY_RECORDER");
    else if(state=="NEGATIVE")detail=text("NRC_"+hex(response.last(1)));
    return object({{"phase",text(phase(body[0]))},{"status",text(state)},
        {"cycle",number(u16(body,4))},{"request_hex",text(hex(request))},
        {"response_hex",response.empty()?Value{}:text(hex(response))},
        {"started_elapsed_ms",number(start)},{"elapsed_ms",number(end)},
        {"duration_ms",number(end-start)},{"detail",std::move(detail)}});
}
}
NativeLiveProjection project_cl1_live(Bytes capture,Pair pair,std::string_view expected_vin){
    const std::string vin(expected_vin);
    require(vin.size()==17&&vin.find('\0')==std::string::npos,"Independent CL/1 VIN required");
    cl1_result state{};std::array<char,256> failure{};
    if(cl1_inspect_complete(capture.data(),capture.size(),vin.c_str(),&state,
        failure.data(),failure.size())!=0)throw std::invalid_argument(failure.data());
    require(state.capture_integrity_verified&&state.terminal_present,
        "Complete CL/1 receipt required");
    require(state.plan.tx==pair.tx&&state.plan.rx==pair.rx,
        "CL/1 capture differs from independently selected CAN pair");
    Array rows,pids;
    rows.reserve(state.steps);
    for(unsigned i=0;i<state.plan.pid_count;++i)pids.push_back(number(state.plan.pids[i]));
    // The original bytes have passed full CRC, order, plan, observation and
    // terminal replay. Still bound every slice independently for this adapter.
    for(std::size_t at=0;at<capture.size();){
        require(capture.size()-at>=12,"Truncated CL/1 record");
        const auto h=capture.subspan(at,8);
        const auto size=u16(h,6);
        require(size<=capture.size()-at-12,"CL/1 record length exceeds capture");
        if(h[3]==2)rows.push_back(observation(capture.subspan(at+8,size)));
        at+=size+12;
    }
    require(rows.size()==state.steps,"CL/1 replay observation count differs");
    const auto reason=stop(state.stop);
    const auto root=object({{"steps",Value{std::move(rows)}},
        {"selected_pids",Value{std::move(pids)}},{"requested_cycles",number(state.plan.cycles)},
        {"tx_can_id",text(address(pair.tx))},{"rx_can_id",text(address(pair.rx))},
        {"stop_reason",reason.empty()?Value{}:text(reason)}});
    LiveInspection replay{};
    replay.pair=pair;replay.vin=vin;replay.transport="OFFLINE_CL1";
    replay.stop_reason=reason;replay.cycles=state.completed_cycles;
    replay.raw_samples=static_cast<unsigned>(state.raw_samples);
    replay.missing_samples=static_cast<unsigned>(state.negative_samples+state.timeout_samples);
    replay.completed=state.completed;replay.initial_identity=state.initial_vin;
    replay.final_identity=state.final_vin;
    return projection_detail::project_replayed_rows(root,replay,vin);
}
}
