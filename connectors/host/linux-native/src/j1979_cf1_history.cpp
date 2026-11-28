#include "j1979_cf1_history.hpp"
#include "j1979_historical_freeze.hpp"
#include "j1979_native_report_detail.hpp"
#include "j1979_policy.h"
#include "cf1.h"
#include <algorithm>
#include <array>
#include <memory>
#include <set>
#include <sstream>

namespace cd::cfhistory {
namespace {
using json::Value;
using Object=Value::Object;
using Array=Value::Array;
using Bytes=std::span<const std::uint8_t>;
using cd::network::history::detail::require;
using cd::network::history::detail::compare;
Value obj(Object x){return Value{std::move(x)};}
Value arr(Array x){return Value{std::move(x)};}
Value str(std::string_view s){return Value{std::string(s)};}
Value num(std::uint64_t n){return Value{json::Integer{std::to_string(n)}};}
Value flag(bool b){return Value{b};}
Value nullable(std::string_view s){return s.empty()?Value{}:str(s);}
template<class T>Value numbers(const T& range){Array a;for(auto n:range)a.push_back(num(n));return arr(std::move(a));}
unsigned le16(Bytes b,std::size_t p){return unsigned(b[p])|(unsigned(b[p+1])<<8);}
std::uint32_t le32(Bytes b,std::size_t p){
    return std::uint32_t(b[p])|(std::uint32_t(b[p+1])<<8)|(std::uint32_t(b[p+2])<<16)|(std::uint32_t(b[p+3])<<24);
}
std::string id(unsigned n){std::ostringstream o;o<<std::uppercase<<std::hex<<n;return o.str();}
bool zero(Bytes b){return std::all_of(b.begin(),b.end(),[](auto v){return v==0;});}
constexpr std::array<std::string_view,7> outcomes{"","RECEIVED","TIMEOUT","TRANSPORT_ERROR","BUDGET","CONTEXT_CHANGED","LATE_RESPONSE"};
constexpr std::array<std::string_view,8> stops{"","BUDGET_EXHAUSTED","TRANSPORT_ERROR","CONTEXT_CHANGED",
    "VIN_UNVERIFIED","VIN_MISMATCH","FINAL_VIN_UNVERIFIED","FINAL_VIN_MISMATCH"};
constexpr std::array<std::string_view,5> states{"UNAVAILABLE","ABSENT","COMPLETE","PARTIAL","CHANGED"};
Value profile(Bytes b){
    require(b.size()==CF1_PLAN_SIZE,"Invalid historical CF/1 plan size");
    const auto tx=le32(b,0),rx=le32(b,4),budget=le32(b,9);
    require(b[8]<=1&&cd_j1979_native_physical_pair(tx,rx,b[8])&&budget>=1000&&budget<=600000,"Invalid historical CF/1 profile");
    const std::string vin(b.begin()+13,b.begin()+30);
    const unsigned nf=b[30],np=b[39];
    require(nf>=1&&nf<=8&&np<=64&&zero(b.subspan(31+nf,8-nf))&&zero(b.subspan(40+np,64-np)),
        "Noncanonical historical CF/1 selection");
    cd::legacy::FreezePlan p{vin,{b.begin()+31,b.begin()+31+nf},{b.begin()+40,b.begin()+40+np},budget};
    require(p.valid(),"Invalid historical CF/1 plan");
    return obj({{"request_can_id",str(id(tx))},{"response_can_id",str(id(rx))},{"extended",flag(b[8])},
        {"plan",obj({{"expected_vin",str(vin)},{"frames",numbers(p.frames)},{"selected_pids",numbers(p.pids)},
            {"budget_ms",num(budget)}})}});
}
Value observation(Bytes b){
    require(b.size()>=12,"Truncated historical CF/1 observation");
    const unsigned outcome=b[0],attempted=b[1],qsize=b[2],duration=le32(b,4),rsize=le16(b,8);
    require(outcome>=1&&outcome<=6&&attempted<=1&&(qsize==2||qsize==3)&&b[3]==0&&duration<=3600000&&
        rsize<=4095&&b.size()==10u+qsize+rsize,"Malformed historical CF/1 observation");
    const bool received=outcome==1||outcome==6;
    require(received==(rsize!=0)&&(!received||attempted)&&
        ((outcome!=2&&outcome!=3)||attempted)&&((outcome!=4&&outcome!=5)||!attempted),"Historical CF/1 transport outcome contradiction");
    const auto q=b.subspan(10,qsize),r=b.subspan(10+qsize,rsize);
    require((q.size()==2&&q[0]==9&&q[1]==2)||(q.size()==3&&q[0]==2),"Unexpected historical CF/1 request");
    return obj({{"request_hex",str(cd::legacy::hex(q))},{"response_hex",r.empty()?Value{}:str(cd::legacy::hex(r))},
        {"transport_outcome",str(outcomes[outcome])},{"duration_ms",num(duration)},{"transmission_attempted",flag(attempted)}});
}
Value terminal(Bytes b,const Value& p,std::size_t count,std::uint64_t elapsed){
    require(b.size()==20,"Invalid historical CF/1 terminal length");
    const unsigned stop=b[0],visited=b[1],flags=b[2],observations=le32(b,4),duration=le32(b,8);
    require(stop<stops.size()&&visited<=p.at("plan").at("frames").array().size()&&!(flags&0xf8)&&!b[3]&&
        observations==count&&count&&duration<=3600000&&elapsed<=duration,"Historical CF/1 terminal counters differ");
    Array state;
    for(unsigned i=0;i<8;++i){
        const auto s=b[12+i];require(s<states.size()&&(i<visited||s==0),"Invalid historical CF/1 terminal frame state");
        if(i<visited)state.push_back(str(states[s]));
    }
    return obj({{"stop_reason",nullable(stops[stop])},{"visited_frames",num(visited)},{"completed",flag(flags&1)},
        {"identity_rechecked",flag(flags&2)},{"all_frames_stable",flag(flags&4)},{"steps",num(count)},
        {"duration_ms",num(duration)},{"frame_states",arr(std::move(state))}});
}
Value reconstructed_terminal(const Value& r){
    Array state;
    for(const auto& f:r.at("frames").array())state.push_back(f.at("status"));
    return obj({{"stop_reason",r.at("stop_reason")},{"visited_frames",num(state.size())},{"completed",r.at("completed")},
        {"identity_rechecked",r.at("identity_rechecked")},{"all_frames_stable",r.at("all_frames_stable")},
        {"steps",num(r.at("steps").array().size())},{"duration_ms",r.at("duration_ms")},{"frame_states",arr(std::move(state))}});
}
bool truth(const Value& v,std::string_view k,bool fallback=false){
    const auto* p=v.find(k);return p?p->boolean():fallback;
}
const Value& optional(const Value& v,std::string_view k){static const Value nil{};const auto* p=v.find(k);return p?*p:nil;}
std::string digest(std::string_view s){
    require(s.size()==64,"Invalid CF/1 SHA-256");
    std::string out(s);
    for(char& c:out){
        if(c>='A'&&c<='F')c=static_cast<char>(c-'A'+'a');
        require((c>='0'&&c<='9')||(c>='a'&&c<='f'),"Invalid CF/1 SHA-256 digit");
    }
    return out;
}
void verify_metadata(const Value& r){
    constexpr std::array<std::string_view,14> core{"schema","protocol","source","profile","capture_integrity_verified",
        "device_credential_authenticated","hardware_provenance_verified","evidence_authenticated","physical_vehicle_verified",
        "permits_programming","observations_received","summary","result","error"};
    const std::set<std::string_view> extra{"client_proof_sent","cancelled","host_elapsed_ms","capture_sha256","raw_capture_name",
        "version","declared_connector","declared_ecu","connection_generation","started_at_utc","finished_at_utc",
        "frame_integrity_verified","execution_completed"};
    for(auto k:core)if(!k.empty())require(r.find(k),"Missing historical CF/1 field");
    for(const auto& [k,v]:r.object()){
        (void)v;
        require(std::find(core.begin(),core.end(),k)!=core.end()||extra.contains(k),"Unexpected historical CF/1 field");
    }
    for(auto k:{"hardware_provenance_verified","evidence_authenticated","physical_vehicle_verified","permits_programming"})
        require(!r.at(k).boolean(),"Unsupported native CF/1 provenance claim");
    for(auto k:{"capture_integrity_verified","device_credential_authenticated","client_proof_sent","cancelled","frame_integrity_verified","execution_completed"})
        if(const auto* p=r.find(k))(void)p->boolean();
    for(auto k:{"started_at_utc","finished_at_utc"})
        if(const auto* p=r.find(k))cd::network::history::validate_historical_timestamp(*p);
    for(auto k:{"version","declared_connector","declared_ecu","raw_capture_name"})
        if(const auto* p=r.find(k);p&&!p->null())require(!p->string().empty()&&p->string().size()<=1024,"Invalid CF/1 host metadata");
    for(auto k:{"host_elapsed_ms","connection_generation"})
        if(const auto* p=r.find(k))require(p->signed_integer()>=0,"Invalid historical host counter");
    const auto& e=r.at("error");
    if(!e.null())require(!e.string().empty()&&e.string().size()<=8192,"Invalid CF/1 host error");
}
}
json::Value decode(Bytes raw){
    Value p,summary,result;
    Array observations;
    std::string error;
    try{
        require(!raw.empty()&&raw.size()<=CF1_MAX_BYTES,"Historical CF/1 input bound");
        std::size_t offset=0;
        std::uint64_t elapsed=0;
        bool ended=false;
        for(std::size_t seq=0;seq<CF1_MAX_STEPS+2;++seq){
            require(raw.size()-offset>=8,"Truncated CF/1 record header");
            const auto h=raw.subspan(offset,8);
            const unsigned kind=h[3],length=le16(h,6);
            require(h[0]=='C'&&h[1]=='F'&&h[2]==1&&le16(h,4)==seq&&kind>=1&&kind<=3&&
                length>=1&&length<=CF1_MAX_BODY,"Invalid CF/1 framing or sequence");
            require(raw.size()-offset>=length+12,"Truncated CF/1 record");
            require(cf1_crc32(raw.data()+offset,8+length)==le32(raw,offset+8+length),"CF/1 CRC mismatch");
            const auto b=raw.subspan(offset+8,length);offset+=length+12;
            if(seq==0){require(kind==1,"Missing CF/1 plan");p=profile(b);continue;}
            require(kind!=1,"Duplicate CF/1 plan");
            if(kind==2){
                require(observations.size()<CF1_MAX_STEPS,"CF/1 observation limit");
                auto v=observation(b);elapsed+=v.at("duration_ms").unsigned_integer();observations.push_back(std::move(v));
                continue;
            }
            summary=terminal(b,p,observations.size(),elapsed);
            require(offset==raw.size(),"Trailing bytes after CF/1 terminal");
            const auto rebuilt=cd::network::history::rebuild_historical_freeze(p.at("plan"),observations,
                static_cast<unsigned>(summary.at("duration_ms").unsigned_integer()));
            require(compare(reconstructed_terminal(rebuilt.replayed),summary),"CF/1 terminal disagrees with independent replay");
            // Cross-check the established native binary recipe as a second
            // independent implementation; neither view authenticates an ECU.
            cf1_evidence evidence{};
            struct Cleanup {cf1_evidence* e;~Cleanup(){cf1_evidence_free(e);}} cleanup{&evidence};
            char why[256]{};
            require(cf1_inspect_complete(raw.data(),raw.size(),rebuilt.plan.vin.c_str(),&evidence,why,sizeof(why))==0,
                "CF/1 native binary replay disagrees");
            result=rebuilt.replayed;ended=true;break;
        }
        require(ended,"CF/1 terminal missing or record limit exceeded");
    }catch(const std::invalid_argument& failure){error=failure.what();result=Value{};}
    return obj({{"schema",str("camarodiag-functional-j1979-native-freeze-v1")},{"protocol",str("SAE-J1979")},
        {"source",str("OFFLINE_CF1")},{"profile",p},{"capture_integrity_verified",flag(error.empty())},
        {"device_credential_authenticated",flag(false)},{"hardware_provenance_verified",flag(false)},
        {"evidence_authenticated",flag(false)},{"physical_vehicle_verified",flag(false)},{"permits_programming",flag(false)},
        {"observations_received",num(observations.size())},{"summary",summary},{"result",result},{"error",nullable(error)}});
}
Inspection verify(Bytes raw,const Value& host,std::string_view capture_digest,std::string_view report_digest,
    std::optional<std::string> independent){
    verify_metadata(host);
    require(host.at("schema").string()=="camarodiag-functional-j1979-native-freeze-v1"&&
        host.at("protocol").string()=="SAE-J1979","Unsupported historical CF/1 schema");
    const auto& source=host.at("source").string();const bool live=source=="LIVE_USB_CU2";
    require(live||source=="OFFLINE_CF1","Unsupported CF/1 source");
    const bool auth=truth(host,"device_credential_authenticated"),sent=truth(host,"client_proof_sent"),
        cancelled=truth(host,"cancelled"),claimed=truth(host,"capture_integrity_verified");
    if(live)require(host.find("client_proof_sent")&&host.find("cancelled")&&(!sent||auth),"Invalid declared CF/1 credential exchange");
    else require(!auth&&!sent&&!cancelled,"Offline CF/1 claims live session");
    const auto decoded=decode(raw);
    const auto& p=decoded.at("profile");
    require(!p.null()&&compare(host.at("profile"),p),"CF/1 plan differs from binary");
    if(independent)require(p.at("plan").at("expected_vin").string()==*independent,"Independent VIN differs from CF/1 plan");
    require(compare(host.at("observations_received"),decoded.at("observations_received")),"CF/1 observation count differs");
    const bool valid=decoded.at("capture_integrity_verified").boolean();
    require(!claimed||(valid&&host.at("error").null()&&!cancelled),"CF/1 host integrity claim contradicts binary");
    if(live)require(!claimed||(auth&&sent),"Successful live report lacks declared credential exchange");
    else require(claimed==valid,"Offline CF/1 integrity differs");
    require(!truth(host,"frame_integrity_verified")||valid,"CF/1 frame claim contradicts binary");
    const auto cap=digest(capture_digest),rep=digest(report_digest);
    const auto& declared=optional(host,"capture_sha256");const bool has_digest=!declared.null();
    if(has_digest)require(digest(declared.string())==cap,"CF/1 capture digest differs");
    const auto& host_summary=host.at("summary");
    if(!host_summary.null())require(compare(host_summary,decoded.at("summary")),"CF/1 summary differs");
    const auto& host_result=host.at("result");
    if(!host_result.null()){
        require(valid,"Malformed CF/1 claims complete result");
        const auto rebuilt=cd::network::history::replay_historical_freeze(host_result,independent);
        require(compare(rebuilt.replayed,decoded.at("result")),"CF/1 host recipe differs from binary");
    }
    require(!claimed||(!host_summary.null()&&!host_result.null()),"Verified CF/1 report lacks complete result");
    const bool complete=claimed&&valid&&!cancelled&&decoded.at("summary").at("completed").boolean();
    if(host.find("execution_completed"))require(truth(host,"execution_completed")==complete,"CF/1 execution completion differs");
    auto proof=obj({{"schema",str("camarodiag-j1979-native-freeze-inspection-v1")},{"consistent",flag(true)},
        {"verified",flag(claimed&&valid)},{"execution_completed",flag(complete)},
        {"all_frames_stable",flag(complete&&decoded.at("summary").at("all_frames_stable").boolean())},
        {"capture_sha256",str(cap)},{"report_sha256",str(rep)},{"host_digest_matched",flag(has_digest)},
        {"declared_live_credential_authenticated",flag(live&&auth)},{"credential_independently_verified",flag(false)},
        {"hardware_provenance_verified",flag(false)},{"physical_vehicle_verified",flag(false)},{"permits_programming",flag(false)},
        {"expected_vin_independently_supplied",flag(independent.has_value())},{"replayed",decoded}});
    return {std::move(proof),claimed&&valid,complete};
}
}
