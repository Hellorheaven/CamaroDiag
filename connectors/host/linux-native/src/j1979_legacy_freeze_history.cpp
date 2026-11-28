#include "j1979_legacy_freeze_history.hpp"
#include "j1979_legacy_scan_history.hpp"
#include "j1979_legacy_identity.hpp"
#include "j1979_native_report_detail.hpp"
#include <algorithm>
#include <array>
#include <chrono>
#include <set>
#include <sstream>
namespace cd::legacy::history {
namespace {
using json::Value;
using cd::network::history::detail::require;
using cd::network::history::detail::bytes;
unsigned bounded(const Value& v,unsigned low,unsigned high){
    const auto n=v.unsigned_integer();
    require(n>=low&&n<=high,"Legacy freeze integer out of range");return static_cast<unsigned>(n);
}
Data raw_bytes(const Value& v,unsigned max){
    auto b=bytes(v,max);require(hex(b)==v.string(),"Noncanonical legacy freeze hex");return b;
}
unsigned source(const Value& v){
    const auto b=raw_bytes(v,1);require(b.size()==1&&valid_source(b[0]),"Invalid selected freeze source");return b[0];
}
std::string address(unsigned n){return hex(Data{static_cast<std::uint8_t>(n)});}
struct Exchange {
    std::string command,status;
    std::vector<Data> selected;
};
bool negative(const std::vector<Data>& r,unsigned mode){
    return r.size()==1&&r[0].size()==3&&r[0][0]==0x7f&&r[0][1]==mode&&r[0][2]&&r[0][2]!=0x78;
}
std::optional<Data> identity(const std::vector<Exchange>& rows,bool driver){
    std::vector<std::string_view> order{"0100"};
    if(!driver)order.push_back("ATDPN");
    order.insert(order.end(),{"0900","0901","0902"});
    require(rows.size()<=order.size(),"Excessive legacy VIN exchanges");
    for(std::size_t i=0;i<rows.size();++i)require(rows[i].command==order[i],"Legacy VIN query order differs");
    if(rows.empty())return {};
    const auto& initial=rows.front();
    if(initial.status!="PASSED"||initial.selected.size()!=1||initial.selected[0].size()!=6||
        initial.selected[0][0]!=0x41||initial.selected[0][1]!=0){
        require(rows.size()==1,"Legacy VIN continued after failed PID00");return {};
    }
    std::size_t index=1;
    if(!driver){
        if(index==rows.size())return {};
        if(rows[index].status!="PASSED"){require(rows.size()==2,"Legacy VIN continued after failed probe");return {};}
        ++index;
    }
    for(;index<rows.size();++index){
        const auto& row=rows[index];const auto& p=row.selected;
        if(row.status=="NEGATIVE"&&negative(p,9)){
            require(index+1==rows.size(),"Legacy VIN continued after negative");return p.front();
        }
        if(row.status!="PASSED"){require(index+1==rows.size(),"Legacy VIN continued after failure");return {};}
        if(row.command=="0900")require(p.size()==1&&vin_support_page(p.front()),"VIN inventory differs");
        else if(row.command=="0901")require(p.size()==1&&vin_fragment_count(p.front()),"VIN count differs");
        else{
            const auto v=normalized_vin(p);require(v.has_value(),"Legacy VIN fragments differ");
            Data result{0x49,2,1};result.insert(result.end(),v->begin(),v->end());return result;
        }
    }
    return {};
}
Exchange parse_exchange(const Value& v,const HistoricalFreezeInspection& report,
    std::set<unsigned>& others,std::size_t& total,bool& probe_verified){
    Exchange out{};
    out.command=v.at("request_hex").string();
    out.status=v.at("status").string();
    require(out.status=="NOT_TESTED"||out.status=="TIMEOUT"||out.status=="FAILED"||
        out.status=="CORRELATED"||out.status=="PASSED"||out.status=="NEGATIVE","Invalid legacy freeze trace status");
    const auto& detail=v.at("detail");
    if(!detail.null())require(detail.string().size()<=1024,"Legacy freeze detail too long");
    const auto& frames_json=v.at("frames").array();
    require(frames_json.size()<=128,"Legacy freeze frame limit");
    const bool probe=out.command=="ATDPN";
    if(probe)require(!report.driver,"Unexpected J2534 adapter command");
    Data q;
    if(!probe){
        q=raw_bytes(v.at("request_hex"),3);
        require(q.size()>=2&&report.result.plan.allows(q),"Legacy freeze request outside selected plan");
    }
    if(report.driver){
        json::fields(v,{"logical_query","request_hex","frames","status","detail","tx_hex"});
        const auto& tx=v.at("tx_hex");
        if(tx.null()){
            require(frames_json.empty()&&(out.status=="NOT_TESTED"||out.status=="TIMEOUT"||out.status=="FAILED"),
                "Driver freeze frame without recorded request");
        }else{
            require(raw_bytes(tx,8)==driver_request_frame(report.protocol,q),"Driver freeze transmitted bytes differ");
            for(const auto& f:frames_json){
                const auto decoded=inspect_driver_frame(f,report.protocol,report.source);
                total+=decoded.raw.size()*2;out.selected.push_back(decoded.payload);
            }
        }
        return out;
    }
    const bool linux_serial=report.transport=="ELM_SERIAL_LEGACY_HEADERED";
    json::fields(v,{"logical_query","request_hex","frames","status","detail","raw_response","selected_records_hex"},
        linux_serial&&probe?std::initializer_list<std::string_view>{"observed_protocol"}:std::initializer_list<std::string_view>{});
    const auto& selected=v.at("selected_records_hex").array();
    require(selected.size()<=128,"Legacy freeze selected record limit");
    const auto& raw=v.at("raw_response");
    if(!raw.null()){require(raw.string().size()<=32768,"Legacy freeze transcript size");total+=raw.string().size();}
    if(probe){
        require(frames_json.empty()&&selected.empty(),"Protocol probe includes application evidence");
        const auto actual=raw.null()?std::nullopt:fixed_protocol(raw.string());
        if(out.status=="PASSED"){
            require(actual==report.protocol,"Legacy freeze fixed protocol not verified");probe_verified=true;
        }
        if(const auto* p=v.find("observed_protocol"))
            require(out.status=="PASSED"?bounded(*p,1,5)==static_cast<unsigned>(report.protocol):p->null(),
                "Saved protocol probe differs");
        return out;
    }
    if(raw.null()){
        require(frames_json.empty()&&selected.empty()&&(out.status=="NOT_TESTED"||out.status=="TIMEOUT"||out.status=="FAILED"),
            "Unsubstantiated legacy freeze response");return out;
    }
    std::vector<Frame> parsed;
    try{parsed=freeze_frames(report.protocol,report.result.plan,q,raw.string());}
    catch(const std::invalid_argument&){
        require((out.status=="FAILED"||out.status=="NOT_TESTED")&&frames_json.empty()&&selected.empty(),
            "Malformed freeze transcript accepted");return out;
    }
    if(!detail.null()&&detail.string()=="LATE_RESPONSE"&&frames_json.empty()){
        require((out.status=="TIMEOUT"||out.status=="NOT_TESTED")&&selected.empty(),"Late legacy freeze payload accepted");return out;
    }
    require(parsed.size()==frames_json.size(),"Freeze frame count differs from original transcript");
    for(std::size_t i=0;i<parsed.size();++i){
        const auto& f=parsed[i];const auto& v=frames_json[i];
        require(source(v.at("source"))==f.source&&raw_bytes(v.at("raw_hex"),11)==f.raw&&
            raw_bytes(v.at("payload_hex"),7)==f.payload,"Legacy freeze frame differs from transcript");
        if(f.source==report.source)out.selected.push_back(f.payload);else others.insert(f.source);
    }
    require(out.selected.size()==selected.size(),"Legacy freeze selected record count differs");
    for(std::size_t i=0;i<selected.size();++i)require(raw_bytes(selected[i],7)==out.selected[i],"Legacy freeze selected payload differs");
    return out;
}
}
HistoricalFreezeInspection inspect_historical_freeze(const Value& root,std::optional<std::string> expected){
    require(root.at("schema").string()=="camarodiag-functional-j1979-legacy-freeze-v1"&&
        root.at("protocol").string()=="SAE-J1979","Invalid legacy freeze schema");
    HistoricalFreezeInspection out{};
    out.transport=root.at("transport").string();
    const bool linux_serial=out.transport=="ELM_SERIAL_LEGACY_HEADERED",android=out.transport=="ANDROID_ELM_LEGACY_HEADERED";
    out.driver=out.transport=="WINDOWS_J2534_LEGACY";
    require(linux_serial||android||out.driver,"Unsupported historical legacy freeze transport");
    std::set<std::string_view> keys{"schema","protocol","transport","version","scope","physically_addressed",
        "functional_scope_confirmed_by_operator","expected_protocol","selected_response_source","response_attribution",
        "response_hex_representation","started_at_utc","finished_at_utc","transport_problem","execution_completed",
        "transport_exchanges","result"};
    if(linux_serial)keys.insert({"port","baud","dedicated_adapter_confirmed_by_operator","observed_protocol","other_response_sources"});
    else if(android)keys.insert({"adapter","observed_protocol","other_response_sources"});
    else keys.insert({"adapter","j2534_protocol_id","baud","initialization_evidence","physical_vehicle_verified","whole_vehicle_coverage_verified"});
    require(root.object().size()==keys.size(),"Unexpected legacy freeze report fields");
    for(auto k:keys)require(root.find(k)!=nullptr,"Missing legacy freeze report field");
    const auto& label=root.at(linux_serial?"port":"adapter").string();
    const auto& version=root.at("version").string();
    require(!label.empty()&&label.size()<=1024&&!version.empty()&&version.size()<=128,"Invalid transport metadata");
    require(root.at("scope").string()=="FUNCTIONAL_SELECTED_RESPONSE_FREEZE"&&!root.at("physically_addressed").boolean()&&
        root.at("functional_scope_confirmed_by_operator").boolean()&&
        root.at("response_hex_representation").string()=="NORMALIZED_APPLICATION_PAYLOAD","Legacy freeze functional scope differs");
    require(root.at("response_attribution").string()==(out.driver?"J2534_DRIVER_MANAGED_SELECTED_HEADER":"CHECKSUM_VERIFIED_LEGACY_HEADER"),
        "Legacy freeze response attribution differs");
    cd::network::history::validate_historical_timestamp(root.at("started_at_utc"));cd::network::history::validate_historical_timestamp(root.at("finished_at_utc"));
    const auto& problem=root.at("transport_problem");
    require(problem.null()||problem.string()=="TRANSPORT_ERROR"||problem.string()=="CANCELLED","Unknown legacy freeze transport problem");
    out.protocol=static_cast<Protocol>(bounded(root.at("expected_protocol"),1,5));
    out.source=source(root.at("selected_response_source"));
    if(linux_serial){
        require(root.at("dedicated_adapter_confirmed_by_operator").boolean(),"Dedicated adapter not confirmed");
        constexpr std::array<unsigned,6> baud{9600,19200,38400,57600,115200,230400};
        require(std::find(baud.begin(),baud.end(),bounded(root.at("baud"),9600,230400))!=baud.end(),"Invalid legacy serial baud");
    }
    if(out.driver){
        require(!root.at("physical_vehicle_verified").boolean()&&!root.at("whole_vehicle_coverage_verified").boolean(),
            "Unsupported physical freeze claim");
        constexpr std::array<unsigned,5> profiles{2,1,3,4,4};
        require(bounded(root.at("j2534_protocol_id"),1,4)==profiles[static_cast<unsigned>(out.protocol)-1]&&
            bounded(root.at("baud"),10400,41600)==(out.protocol==Protocol::pwm?41600u:10400u),"Driver freeze profile differs");
        const auto& init=root.at("initialization_evidence");
        if(!init.null()){
            const auto b=raw_bytes(init,11);
            if(out.protocol==Protocol::iso9141||out.protocol==Protocol::kwp_slow)require(b.size()==2,"Missing slow init keywords");
            else if(out.protocol==Protocol::kwp_fast)require(b.size()==6&&b[0]==0x83&&b[1]==0xf1&&b[2]==out.source&&b[3]==0xc1,
                "Invalid fast init evidence");
            else throw std::invalid_argument("Unexpected driver initialization");
        }else if(static_cast<unsigned>(out.protocol)>=3)require(!problem.null(),"Missing required driver initialization");
    }
    out.result=cd::network::history::replay_historical_freeze(root.at("result"),expected);
    const auto& steps=out.result.replayed.at("steps").array();
    const auto& trace=root.at("transport_exchanges").array();
    require(trace.size()<=8*(8+2+247)+12,"Legacy freeze exchange count");
    std::vector<std::vector<Exchange>> groups(steps.size());
    std::set<unsigned> others;std::size_t total=0;unsigned previous=0;bool probe=false;
    for(const auto& row:trace){
        const auto index=bounded(row.at("logical_query"),0,static_cast<unsigned>(steps.size()-1));
        require(index>=previous,"Unordered legacy freeze exchange");previous=index;
        require(groups[index].size()<5,"Too many exchanges for one logical freeze query");
        groups[index].push_back(parse_exchange(row,out,others,total,probe));
        require(total<=32u*1024u*1024u,"Legacy freeze trace byte limit");
    }
    for(std::size_t i=0;i<steps.size();++i){
        const auto& step=steps[i];const auto& rows=groups[i];
        if(!step.at("transmission_attempted").boolean()){require(rows.empty(),"Trace for unattempted freeze request");continue;}
        const auto& command=step.at("request_hex").string();
        const auto& outcome=step.at("transport_outcome").string();
        require(!rows.empty()||outcome=="CANCELLED"||outcome=="CONTEXT_CHANGED"||outcome=="TRANSPORT_ERROR"||outcome=="TIMEOUT",
            "Missing legacy freeze transport evidence");
        std::optional<Data> payload;
        if(command=="0902")payload=identity(rows,out.driver);
        else if(!rows.empty()){
            require(rows.size()==1&&rows.front().command==command,"Legacy freeze measurement command differs");
            if(rows.front().selected.size()==1&&rows.front().status=="CORRELATED")payload=rows.front().selected.front();
        }
        if(outcome=="RECEIVED"||outcome=="LATE_RESPONSE")
            require(payload&&hex(*payload)==step.at("response_hex").string(),"Normalized freeze payload differs from selected source");
        else if(outcome=="TIMEOUT")require(!payload,"Timeout contradicts correlated legacy reply");
    }
    if(out.driver)out.protocol_verified=!trace.empty()&&(static_cast<unsigned>(out.protocol)<3||!root.at("initialization_evidence").null());
    else{
        const auto& observed=root.at("observed_protocol");
        if(!observed.null())require(bounded(observed,1,5)==static_cast<unsigned>(out.protocol)&&probe,"Missing matching freeze probe");
        out.protocol_verified=!observed.null();
        std::set<unsigned> claimed;
        for(const auto& v:root.at("other_response_sources").array())require(claimed.insert(source(v)).second,"Duplicate legacy freeze source");
        require(claimed==others,"Other legacy freeze sources differ");
    }
    out.execution_completed=out.result.completed&&problem.null()&&out.protocol_verified;
    require(root.at("execution_completed").boolean()==out.execution_completed,"Legacy freeze completion differs");
    out.all_frames_stable=out.execution_completed&&out.result.all_frames_stable;
    return out;
}
std::string historical_freeze_report(const HistoricalFreezeInspection& s,std::string_view digest){
    std::ostringstream o;
    o<<"{\"schema\":\"camarodiag-j1979-legacy-freeze-inspection-v1\",\"consistent\":true,"
        "\"source_transport\":"<<json::quote(s.transport)<<",\"execution_completed\":"<<(s.execution_completed?"true":"false")
        <<",\"all_frames_stable\":"<<(s.all_frames_stable?"true":"false")<<",\"selected_response_source\":"<<json::quote(address(s.source))
        <<",\"physically_addressed\":false,\"legacy_checksums_independently_verified\":"<<(!s.driver?"true":"false")
        <<",\"driver_checksums_independently_verified\":false,\"physical_vehicle_verified\":false,\"authenticated\":false,"
        "\"whole_vehicle_coverage_verified\":false,\"permits_programming\":false,\"source_sha256\":"
        <<(digest.empty()?"null":json::quote(digest))<<",\"replayed\":"<<cd::network::history::encode_historical_json(s.result.replayed)<<"}\n";
    return o.str();
}
}
