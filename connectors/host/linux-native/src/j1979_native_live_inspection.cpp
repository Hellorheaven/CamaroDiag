#include "j1979_native_live_inspection.hpp"
#include "j1979_native_report_detail.hpp"
#include "j1979_live.h"
#include "j1979_context.hpp"
#include <array>
#include <cstdio>
#include <cstdlib>
#include <memory>
#include <sstream>

namespace cd::network::history {
namespace {
using namespace detail;
constexpr std::uint64_t max_elapsed=3600000;
unsigned bounded(const Value& v,unsigned low,unsigned high){
    const auto n=v.unsigned_integer();
    require(n>=low&&n<=high,"Native live integer out of bounds");
    return static_cast<unsigned>(n);
}
struct Recorded {
    Bytes request,response;
    std::string phase,status,reason;
    unsigned cycle{},start{},end{},duration{};
};
Recorded read_row(const Value& v){
    Recorded r{};
    r.request=bytes(v.at("request_hex"),2);
    require(r.request.size()==2&&(r.request[0]==1||r.request==Bytes{9,2}),
            "Unexpected native live service");
    const auto& response=v.at("response_hex");
    if(!response.null()){
        r.response=bytes(response,J1979_CAN_MAX_PAYLOAD);
        require(!r.response.empty(),"Empty native live payload must be null");
    }
    r.phase=v.at("phase").string();
    require(r.phase=="INITIAL_VIN"||r.phase=="PERIODIC_VIN"||r.phase=="FINAL_VIN"||
        r.phase=="INVENTORY"||r.phase=="CONTEXT"||r.phase=="SAMPLE","Invalid native live phase");
    r.status=v.at("status").string();
    require(r.status=="RAW"||r.status=="PASSED"||r.status=="FAILED"||r.status=="NEGATIVE"||
        r.status=="TIMEOUT"||r.status=="NOT_TESTED","Invalid native live status");
    r.reason=nullable(v.at("detail"),47);
    r.cycle=bounded(v.at("cycle"),0,1000);
    r.start=bounded(v.at("started_elapsed_ms"),0,max_elapsed);
    r.end=bounded(v.at("elapsed_ms"),r.start,max_elapsed);
    r.duration=bounded(v.at("duration_ms"),0,max_elapsed);
    require(r.end-r.start==r.duration,"Native live exchange duration differs from timestamps");
    return r;
}
bool no_exchange_stop(std::string_view s){
    return s=="CONTEXT_CHANGED"||s=="BUDGET_EXHAUSTED"||s=="CANCELLED"||s=="STEP_LIMIT";
}
bool lost_response(std::string_view s){
    return s=="UNBOUNDED_REPLY"||s=="MEMORY_EXHAUSTED"||s=="INVALID_REPLY_SIZE"||s=="TRANSPORT_ERROR";
}
class Replay{
    const j1979_live_plan& plan_;
    std::vector<Recorded>& source_;
    std::vector<j1979_live_step> steps_;
    std::string declared_stop_;
    std::size_t index_{};
    unsigned last_end_{};
    j1979_live_result result_{};
    bool halted()const{return result_.stop_reason[0]!=0;}
    void halt(std::string_view s){if(!halted())set(result_.stop_reason,s);}
    const Recorded* next()const{return index_<source_.size()?&source_[index_]:nullptr;}
    bool raw(const j1979_live_step* s)const{return s&&std::string_view(s->status)=="RAW";}
    void no_row(bool can_cancel){
        require(no_exchange_stop(declared_stop_)&&
            (declared_stop_!="CANCELLED"||can_cancel)&&
            (declared_stop_!="STEP_LIMIT"||index_==J1979_LIVE_MAX_STEPS),
            "Native live report ends before its next required exchange");
        halt(declared_stop_);
    }
    // Recalculate response classification, not the caller's projected status.
    void classify(const Recorded& r,j1979_live_step& s){
        set(s.status,"NOT_TESTED");
        const auto timeout=std::min<unsigned>(2500,plan_.channel.budget_ms-r.start);
        if(r.reason=="LATE_RESPONSE"){
            require(r.end>=plan_.channel.budget_ms,"Native late-response claim contradicts elapsed time");
            set(s.detail,r.reason);halt("BUDGET_EXHAUSTED");return;
        }
        if(r.reason=="EXCHANGE_DEADLINE_EXCEEDED"){
            require(r.duration>timeout&&r.end<plan_.channel.budget_ms,"Native exchange deadline claim differs");
            set(s.detail,r.reason);halt("TRANSPORT_ERROR");return;
        }
        if(r.response.empty()&&r.status=="NOT_TESTED"&&r.reason.empty()&&lost_response(declared_stop_)){
            require(index_==source_.size(),"Native transport termination followed by another exchange");
            halt(declared_stop_);return;
        }
        if(r.status=="NOT_TESTED"&&r.reason.empty()&&declared_stop_=="CONTEXT_CHANGED"){
            require(index_==source_.size()&&r.end<plan_.channel.budget_ms,"Native context withdrawal followed by exchange");
            halt(declared_stop_);return;
        }
        require(r.end<plan_.channel.budget_ms&&r.duration<=timeout,"Native accepted exchange outside deadline");
        if(r.response.empty()){
            require(r.status=="TIMEOUT"&&r.reason.empty(),"Native response missing without known timeout");
            set(s.status,"TIMEOUT");return;
        }
        const auto& b=r.response;
        if(b.size()==3&&b[0]==0x7f&&b[1]==r.request[0]&&b[2]&&b[2]!=0x78){
            constexpr char alphabet[]="0123456789ABCDEF";
            std::string detail="NRC_";detail+=alphabet[b[2]>>4];detail+=alphabet[b[2]&15];
            set(s.status,"NEGATIVE");set(s.detail,detail);return;
        }
        if(b.size()<3||b[0]!=r.request[0]+0x40||b[1]!=r.request[1]){
            set(s.status,"FAILED");halt("WRONG_ECU_RESPONSE");return;
        }
        if(r.request[0]==1){
            j1979_can_step shape{};
            if(!j1979_can_classify_read(r.request.data(),r.request.size(),b.data(),b.size(),&shape)){
                set(s.status,"FAILED");set(s.detail,shape.detail);halt(shape.detail);return;
            }
        }
        set(s.status,"RAW");
    }
    j1979_live_step* take(std::string_view phase,unsigned cycle,Bytes q){
        if(halted())return nullptr;
        if(!next()){no_row(false);return nullptr;}
        auto& r=source_[index_];
        require(r.phase==phase&&r.cycle==cycle&&r.request==q,"Native live phase or request order differs");
        require(r.start>=last_end_&&r.start<plan_.channel.budget_ms,"Overlapping or expired native live exchange");
        last_end_=r.end;
        auto& s=steps_[index_++];
        std::copy(q.begin(),q.end(),s.request);
        set(s.phase,phase);s.cycle=cycle;s.started_ms=r.start;s.elapsed_ms=r.end;s.duration_ms=r.duration;
        if(!r.response.empty()){
            s.response=r.response.data();s.response_size=r.response.size();
            result_.raw_bytes+=r.response.size();
            require(result_.raw_bytes<=J1979_LIVE_MAX_RAW,"Native live raw evidence limit");
        }
        classify(r,s);
        return &s;
    }
    void verify_status(const j1979_live_step* s)const{
        if(!s)return;
        const auto& r=source_[index_-1];
        require(r.status==s->status&&r.reason==s->detail,"Native live response classification differs");
    }
    bool identity(std::string_view phase,bool final){
        auto* s=take(phase,0,{9,2});
        if(!raw(s)||s->response_size!=20||s->response[2]!=1){
            halt(final?"FINAL_VIN_UNVERIFIED":"VIN_UNVERIFIED");verify_status(s);return false;
        }
        const std::string vin(reinterpret_cast<const char*>(s->response+3),17);
        if(!j1979_can_valid_vin(vin.c_str())||vin!=plan_.channel.expected_vin){
            halt("VIN_MISMATCH");verify_status(s);return false;
        }
        if(final){set(result_.final_vin,vin);result_.final_vin_valid=true;}
        else{set(result_.initial_vin,vin);result_.initial_vin_valid=true;}
        set(s->status,"PASSED");verify_status(s);return true;
    }
    bool inventory(){
        for(unsigned base=0;base<=224&&!halted();base+=32){
            auto* s=take("INVENTORY",0,{1,static_cast<std::uint8_t>(base)});
            if(!raw(s)||s->response_size!=6){
                halt("INVENTORY_INCOMPLETE");verify_status(s);return false;
            }
            const auto* b=s->response+2;
            for(unsigned bit=0;bit<31;++bit)
                if(b[bit/8]&(0x80u>>(bit%8)))result_.advertised[base+bit+1]=true;
            set(s->status,"PASSED");verify_status(s);
            if(!(b[3]&1)){result_.inventory_complete=true;break;}
            if(base==224){halt("INVALID_SUPPORT_CHAIN");return false;}
        }
        if(!result_.inventory_complete){halt("INVENTORY_INCOMPLETE");return false;}
        for(unsigned i=0;i<plan_.pid_count;++i)
            if(!result_.advertised[plan_.pids[i]]){halt("PID_NOT_ADVERTISED");return false;}
        return true;
    }
    bool context(){
        std::array<bool,256> needed{};
        for(unsigned i=0;i<plan_.pid_count;++i){
            const unsigned pid=plan_.pids[i];
            if(cd::j1979::trim_pid(pid))needed[0x1d]=true;
            if(const auto dep=cd::j1979::dependency(pid))needed[*dep]=true;
            if(pid==0x41)needed[1]=true;
        }
        for(auto pid:{0x1d,0x4f,0x50,1}){
            if(!needed[pid]||!result_.advertised[pid])continue;
            auto* s=take("CONTEXT",0,{1,static_cast<std::uint8_t>(pid)});
            verify_status(s);if(!s||halted())return false;
        }
        return true;
    }
    void samples(){
        unsigned previous_anchor=0,last_identity=0;
        // Clock callbacks between exchanges are not present in JSON. Use
        // observable cycle bounds; do not claim reconstruction of wait calls.
        for(unsigned cycle=1;cycle<=plan_.cycles&&!halted();++cycle){
            if(!next()){no_row(cycle>1);break;}
            const auto anchor=next()->start;
            if(cycle>1)require(anchor>=previous_anchor+plan_.interval_ms,
                "Native live cycle begins before configured interval");
            if(next()->phase=="PERIODIC_VIN"){
                require(anchor>=last_identity+30000,"Unexpected early periodic identity");
                if(!identity("PERIODIC_VIN",false))break;
                last_identity=last_end_;
            }else{
                require(anchor<=last_identity+30000,"Native live cycle lacks timely identity recheck");
            }
            for(unsigned i=0;i<plan_.pid_count&&!halted();++i){
                auto* s=take("SAMPLE",cycle,{1,plan_.pids[i]});
                verify_status(s);
                if(!s)break;
                if(raw(s))++result_.raw_samples;
                else ++result_.missing_samples;
            }
            if(halted())break;
            ++result_.cycles_completed;previous_anchor=anchor;
        }
    }
public:
    Replay(const j1979_live_plan& p,std::vector<Recorded>& rows,std::string stop):
        plan_(p),source_(rows),steps_(std::max<std::size_t>(1,rows.size())),declared_stop_(std::move(stop)){
        result_.steps=steps_.data();
    }
    const j1979_live_result& run(unsigned duration){
        if(source_.empty()&&declared_stop_=="CLOCK_UNAVAILABLE")halt(declared_stop_);
        if(!halted()&&identity("INITIAL_VIN",false)&&inventory()&&context())samples();
        if(!halted()&&result_.cycles_completed==plan_.cycles)identity("FINAL_VIN",true);
        if(!halted()&&result_.missing_samples)halt("INCOMPLETE_SAMPLES");
        require(index_==source_.size(),"Extra native live exchanges after terminal phase");
        require(declared_stop_==result_.stop_reason,"Native live stop reason differs from replay");
        require(duration>=last_end_,"Native live report ends before final exchange");
        if(declared_stop_=="BUDGET_EXHAUSTED")require(duration>=plan_.channel.budget_ms,"Native budget termination before budget");
        result_.step_count=index_;result_.duration_ms=duration;
        result_.completed=!halted()&&result_.final_vin_valid&&result_.cycles_completed==plan_.cycles;
        return result_;
    }
};
int unavailable_exchange(void*,const std::uint8_t*,std::size_t,std::uint8_t*,std::size_t*,unsigned){return -1;}
bool unavailable_context(void*){return false;}
std::string serialize(const j1979_live_plan& plan,const j1979_live_result& result,
                      const std::string& interface,const std::string& transport){
    char* buffer=nullptr;std::size_t size=0;
    auto* f=open_memstream(&buffer,&size);
    if(!f)throw std::runtime_error("Cannot allocate native live report");
    const bool ok=j1979_live_write_json_transport(f,&plan,&result,interface.c_str(),transport.c_str());
    const bool closed=fclose(f)==0;
    std::unique_ptr<char,decltype(&std::free)> owner(buffer,&std::free);
    require(ok&&closed&&buffer&&size&&size<=64u*1024u*1024u,"Native live serialization failed");
    return std::string(buffer,size);
}
}
LiveInspection inspect_native_live(const Value& root,Pair pair,std::string_view expected_vin){
    no_claims(root);
    require(root.at("schema").string()=="camarodiag-j1979-live-linux-c-v1"&&
        root.at("protocol").string()=="SAE-J1979"&&root.at("source").string()=="LIVE_PHYSICAL_PAIR"&&
        root.at("raw_only").boolean(),"Unsupported native live schema");
    require(address(root.at("tx_can_id"))==pair.tx&&address(root.at("rx_can_id"))==pair.rx,
        "Native live CAN pair differs from independent target");
    const std::string vin(expected_vin);
    require(vin.size()==17&&j1979_can_valid_vin(vin.c_str())&&root.at("expected_vin").string()==vin,
        "Native live VIN differs from independent target");
    const auto transport=root.at("transport").string();
    require(transport=="LINUX_SOCKETCAN_ISOTP"||transport=="ELM_SERIAL_CLASSIC_ISOTP","Unsupported native live transport");
    const auto interface=root.at("interface").string();
    require(!interface.empty()&&interface.size()<=4096&&interface.find('\0')==std::string::npos,"Invalid native interface label");
    j1979_live_plan plan{};
    plan.channel.exchange=unavailable_exchange;plan.channel.current=unavailable_context;
    plan.channel.tx=pair.tx;plan.channel.rx=pair.rx;plan.channel.expected_vin=vin.c_str();
    plan.channel.budget_ms=bounded(root.at("budget_ms"),1000,600000);
    plan.cycles=bounded(root.at("requested_cycles"),1,1000);
    plan.interval_ms=bounded(root.at("interval_ms"),100,60000);
    const auto& pids=root.at("selected_pids").array();
    require(!pids.empty()&&pids.size()<=J1979_LIVE_MAX_PIDS,"Native selected PID count");
    for(const auto& p:pids)plan.pids[plan.pid_count++]=static_cast<std::uint8_t>(bounded(p,1,255));
    require(j1979_live_plan_valid(&plan),"Invalid native live plan");
    const auto stop=nullable(root.at("stop_reason"),47);
    const auto& rows=root.at("steps").array();
    require(rows.size()<=J1979_LIVE_MAX_STEPS,"Native live observation limit");
    std::vector<Recorded> recorded;recorded.reserve(rows.size());
    std::size_t total=0;
    for(const auto& v:rows){
        recorded.push_back(read_row(v));total+=recorded.back().response.size();
        require(total<=J1979_LIVE_MAX_RAW,"Native live payload limit");
    }
    Replay replay(plan,recorded,stop);
    const auto& result=replay.run(bounded(root.at("duration_ms"),0,max_elapsed));
    auto canonical=serialize(plan,result,interface,transport);
    require(compare(root,json::parse_report(canonical,64u*1024u*1024u)),
        "Native live report or measurement projection differs from raw observations");
    return {pair,vin,transport,stop,std::move(canonical),result.cycles_completed,result.raw_samples,
        result.missing_samples,result.completed,result.initial_vin_valid,result.final_vin_valid};
}
std::string native_live_report(const LiveInspection& r){
    std::ostringstream o;
    o<<"{\"schema\":\"camarodiag-j1979-native-live-inspection-v1\","
        "\"consistent\":true,\"verification_scope\":\"LIVE_SEQUENCE_AND_SERIALIZED_PROJECTIONS\","
        "\"independent_plan_replay\":true,\"shared_application_decoder\":true,"
        "\"timing_provenance_verified\":false,\"transport_provenance_verified\":false,"
        "\"physical_vehicle_verified\":false,\"authenticated\":false,\"permits_programming\":false,"
        "\"replayed_report\":"<<r.canonical<<"}";
    return o.str();
}
}
