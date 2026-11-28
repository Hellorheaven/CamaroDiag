#include "j1979_native_can_inspection.hpp"
#include "j1979_can.h"
#include <algorithm>
#include <array>
#include <charconv>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <sstream>
#include <stdexcept>

namespace cd::network::history {
namespace {
using json::Value;
using Bytes=std::vector<std::uint8_t>;
void require(bool b,const char* why){if(!b)throw std::invalid_argument(why);}
void no_claims(const Value& v){
    for(auto k:{"physical_vehicle_verified","whole_vehicle_coverage_verified","authenticated",
                "permits_programming","current_authorization_verified","permits_automatic_retry"})
        if(const auto* p=v.find(k))require(!p->boolean(),"Unsupported native verification claim");
}
unsigned address(const Value& v){
    const auto& s=v.string();unsigned n{};
    require(!s.empty()&&s.size()<=8,"Invalid native CAN address");
    const auto [p,e]=std::from_chars(s.data(),s.data()+s.size(),n,16);
    require(e==std::errc{}&&p==s.data()+s.size()&&n<=0x1fffffffu,"Invalid native CAN address");
    return n;
}
Bytes bytes(const Value& v,std::size_t maximum){
    const auto& s=v.string();
    require(s.size()<=2*maximum&&s.size()%2==0,"Invalid native payload size");
    Bytes out;out.reserve(s.size()/2);
    for(std::size_t i=0;i<s.size();i+=2){
        unsigned n{};const auto [p,e]=std::from_chars(s.data()+i,s.data()+i+2,n,16);
        require(e==std::errc{}&&p==s.data()+i+2&&n<=255,"Invalid native payload hex");
        out.push_back(static_cast<std::uint8_t>(n));
    }
    return out;
}
std::string nullable(const Value& v,std::size_t maximum){
    if(v.null())return {};
    const auto& s=v.string();require(!s.empty()&&s.size()<=maximum,"Invalid native report text");
    return s;
}
template<std::size_t N>void set(char (&buffer)[N],std::string_view value){
    require(value.size()<N&&value.find('\0')==std::string_view::npos,"Native fixed string overflow");
    std::copy(value.begin(),value.end(),buffer);buffer[value.size()]=0;
}
bool numeric(const Value& v){
    return std::holds_alternative<json::Integer>(v.data)||std::holds_alternative<json::Decimal>(v.data);
}
bool compare(const Value& actual,const Value& expected,std::string_view field={}){
    // Only continuous quantities admit numeric tolerance. Addresses, bitmap
    // IDs, counts, encoded bytes and times remain integer-typed and exact.
    const bool continuous=field=="number"||field=="value"||field=="minimum"||field=="maximum"||field=="ratio";
    if(continuous&&numeric(actual)&&numeric(expected)){
        const auto v=expected.finite_number(),a=actual.finite_number();
        return std::abs(a-v)<=std::max(1e-9,1e-9*std::abs(v));
    }
    if(actual.data.index()!=expected.data.index())return false;
    if(expected.null())return true;
    if(const auto* p=std::get_if<bool>(&expected.data))return *p==actual.boolean();
    if(const auto* p=std::get_if<json::Integer>(&expected.data))return p->text==std::get<json::Integer>(actual.data).text;
    if(const auto* p=std::get_if<json::Decimal>(&expected.data))return p->text==std::get<json::Decimal>(actual.data).text;
    if(const auto* p=std::get_if<std::string>(&expected.data))return *p==actual.string();
    if(const auto* p=std::get_if<Value::Array>(&expected.data)){
        const auto& a=actual.array();if(a.size()!=p->size())return false;
        for(std::size_t i=0;i<a.size();++i)if(!compare(a[i],p->at(i),field))return false;
        return true;
    }
    const auto& a=actual.object();const auto& e=expected.object();
    if(a.size()!=e.size())return false;
    for(const auto& [k,v]:e){const auto i=a.find(k);if(i==a.end()||!compare(i->second,v,k))return false;}
    return true;
}
struct Recorded {
    Bytes request;
    std::optional<Bytes> response;
    std::string status,detail;
    std::uint32_t duration{};
};
Recorded parse_step(const Value& v){
    Recorded out{};
    out.request=bytes(v.at("request_hex"),3);
    require(j1979_can_allowed_read(out.request.data(),out.request.size()),"Non-read request in native report");
    const auto& r=v.at("response_hex");
    if(!r.null())out.response=bytes(r,J1979_CAN_MAX_PAYLOAD);
    out.status=v.at("status").string();
    require(out.status=="PASSED"||out.status=="RAW"||out.status=="NOT_TESTED"||out.status=="FAILED",
            "Invalid native observation status");
    out.detail=nullable(v.at("detail"),79);
    const auto ms=v.at("duration_ms").unsigned_integer();
    require(ms<=3600000,"Native observation duration exceeds limit");
    out.duration=static_cast<std::uint32_t>(ms);
    return out;
}
class Replay {
    std::vector<Recorded>& recorded_;
    std::vector<j1979_can_step> rows_;
    std::uint8_t empty_payload_{};
    std::size_t index_{};
    std::string declared_stop_,expected_vin_;
    j1979_can_result result_{};
    enum class Role {member,inventory,initial_vin,final_vin,freeze_trigger};
    void stop(std::string_view reason){if(!result_.stop_reason[0])set(result_.stop_reason,reason);}
    bool stopped()const{return result_.stop_reason[0]!=0;}
    bool raw(const j1979_can_step* s)const{return s&&std::string_view(s->status)=="RAW";}
    void positive_vin(j1979_can_step& s,bool final){
        bool valid=false;
        if(s.response_size==20&&s.response[2]==1){
            auto* target=final?result_.final_vin:result_.observed_vin;
            std::memcpy(target,s.response+3,17);target[17]=0;
            valid=j1979_can_valid_vin(target);
            if(!valid)std::memset(target,0,18);
        }
        if(final)result_.final_vin_valid=valid;else result_.vin_valid=valid;
        if(valid)set(s.status,"PASSED");
        else if(!final){set(s.status,"FAILED");set(s.detail,"INVALID_VIN_RECORD");}
    }
    void apply_role(j1979_can_step& s,Role role){
        if(!raw(&s))return;
        switch(role){
        case Role::initial_vin:positive_vin(s,false);break;
        case Role::final_vin:positive_vin(s,true);break;
        case Role::freeze_trigger:
            if(s.response_size==5){
                result_.freeze_trigger_verified=true;
                result_.freeze_present=s.response[3]!=0||s.response[4]!=0;
                set(s.status,"PASSED");
            }else{set(s.status,"FAILED");set(s.detail,"INVALID_FREEZE_TRIGGER");}
            break;
        case Role::inventory:
            if(s.response_size!=s.request_size+4u||
                (s.request[1]==0xe0&&(s.response[s.response_size-1]&1))){
                set(s.status,"FAILED");set(s.detail,"INVALID_SUPPORT_BITMAP");
            }else set(s.status,"PASSED");
            break;
        case Role::member:break;
        }
    }
    void classify(j1979_can_step& s,const Recorded& source,Role role){
        const std::string_view detail=source.detail;
        set(s.status,"NOT_TESTED");
        if(detail=="LATE_RESPONSE"||detail=="CONTEXT_CHANGED"){
            set(s.detail,detail);stop(detail=="LATE_RESPONSE"?"BUDGET_EXHAUSTED":"CONTEXT_CHANGED");
        }else if(detail=="TRANSPORT_ERROR"||detail=="UNBOUNDED_TRANSPORT_REPLY"){
            require(!source.response,"Transport failure must not contain response payload");
            set(s.status,"FAILED");set(s.detail,detail);stop("TRANSPORT_ERROR");
        }else if(detail=="TIMEOUT"){
            require(!source.response,"Timed out native request must not contain response");
            set(s.detail,"TIMEOUT");
        }else if(!source.response&&detail.empty()&&declared_stop_=="MEMORY_EXHAUSTED"){
            stop("MEMORY_EXHAUSTED");
        }else{
            require(source.response.has_value(),"Missing native response or transport outcome");
            (void)j1979_can_classify_read(s.request,s.request_size,s.response,s.response_size,&s);
            apply_role(s,role);
        }
        require(source.status==s.status&&source.detail==s.detail,"Native status or detail differs from raw reply");
    }
    j1979_can_step* read(Bytes q,Role role=Role::member){
        if(stopped())return nullptr;
        if(index_==recorded_.size()){
            // Timing and context withdrawal outside a recorded exchange
            // cannot be reconstructed. Only these exact bounded prefix
            // causes may end the recipe before its next request.
            require(declared_stop_=="CONTEXT_CHANGED"||declared_stop_=="BUDGET_EXHAUSTED"||
                (declared_stop_=="RECORD_LIMIT"&&index_==J1979_CAN_MAX_STEPS),
                "Native report ends before next required request");
            stop(declared_stop_);return nullptr;
        }
        auto& src=recorded_[index_];
        require(src.request==q,"Unexpected native request or service order");
        auto& step=rows_[index_++];
        step.request_size=static_cast<std::uint8_t>(q.size());
        std::copy(q.begin(),q.end(),step.request);
        if(src.response){
            // Borrow the owned mutable replay buffer; no cast or transfer of ownership.
            step.response=src.response->empty()?&empty_payload_:src.response->data();
            step.response_size=src.response->size();
        }
        step.duration_ms=src.duration;
        classify(step,src,role);return &step;
    }
    void inventory(unsigned mode,bool members){
        if(stopped())return;
        const auto req=[&](unsigned id){
            Bytes q{static_cast<std::uint8_t>(mode),static_cast<std::uint8_t>(id)};
            if(mode==2)q.push_back(0);
            return q;
        };
        bool complete=false;
        for(unsigned base=0;base<=0xe0&&!stopped();base+=32){
            const auto* row=read(req(base),Role::inventory);
            if(!row||std::string_view(row->status)!="PASSED")break;
            const auto* data=row->response+row->request_size;
            for(unsigned i=0;i<31;++i)if(data[i/8]&(0x80u>>(i%8))){
                result_.advertised[mode][base+i+1]=1;++result_.advertised_count[mode];
            }
            if(!(data[3]&1)){complete=true;break;}
        }
        result_.inventory_state[mode]=complete?1:2;
        if(!complete||!members)return;
        for(unsigned id=1;id<=255&&!stopped();++id)
            if(result_.advertised[mode][id]&&id%32!=0&&!(mode==9&&id==2))read(req(id));
    }
public:
    Replay(std::vector<Recorded>& rows,std::string stop_reason,std::string expected):
        recorded_(rows),rows_(std::max<std::size_t>(1,rows.size())),
        declared_stop_(std::move(stop_reason)),expected_vin_(std::move(expected)){
        result_.steps=rows_.data();
        set(result_.vin_match,"UNVERIFIED");set(result_.final_vin_match,"NOT_TESTED");
    }
    const j1979_can_result& run(std::uint64_t duration){
        read({9,2},Role::initial_vin);
        if(result_.vin_valid&&result_.observed_vin==expected_vin_)set(result_.vin_match,"MATCH");
        else{
            set(result_.vin_match,result_.vin_valid?"MISMATCH":"UNVERIFIED");
            stop(result_.vin_valid?"VIN_MISMATCH":"VIN_UNVERIFIED");
        }
        for(auto mode:{3,7,10})read({static_cast<std::uint8_t>(mode)});
        inventory(1,true);
        read({2,2,0},Role::freeze_trigger);
        if(result_.freeze_present)inventory(2,true);
        inventory(6,true);inventory(8,false);inventory(9,true);
        if(!stopped()&&result_.vin_valid){
            auto* s=read({9,2},Role::final_vin);
            result_.final_vin_checked=s!=nullptr;
            if(!result_.final_vin_valid){
                set(result_.final_vin_match,"UNVERIFIED");stop("FINAL_VIN_UNVERIFIED");
            }else if(result_.final_vin!=expected_vin_){
                set(result_.final_vin_match,"MISMATCH");stop("VIN_CHANGED");
            }else set(result_.final_vin_match,"MATCH");
        }
        require(index_==recorded_.size(),"Native exchanges after terminal campaign state");
        for(std::size_t i=0;i<index_;++i){
            const auto& s=rows_[i];const std::string_view state=s.status;
            if(state=="FAILED")++result_.failed_responses;
            else if(state=="NOT_TESTED"){
                if(std::string_view(s.detail).starts_with("NRC_"))++result_.negative_responses;
                else ++result_.timeout_responses;
            }
        }
        if(!stopped()&&(result_.inventory_state[1]!=1||
            (result_.freeze_present&&result_.inventory_state[2]!=1)||
            result_.inventory_state[6]!=1||result_.inventory_state[8]!=1||result_.inventory_state[9]!=1))
            stop("INVENTORY_INCOMPLETE");
        if(!stopped()&&!result_.freeze_trigger_verified)stop("FREEZE_TRIGGER_UNVERIFIED");
        if(!stopped()&&(result_.negative_responses||result_.timeout_responses||result_.failed_responses))
            stop("OBSERVATION_INCOMPLETE");
        require(declared_stop_==result_.stop_reason,"Native stop reason differs from replay");
        result_.step_count=index_;result_.duration_ms=duration;
        result_.cancelled=declared_stop_=="CANCELLED";
        result_.completed=!stopped()&&result_.final_vin_valid;
        return result_;
    }
};
std::string serialize(const j1979_can_plan& plan,const j1979_can_result& result,
                      const std::string& interface,const std::string& transport){
    char* buffer=nullptr;std::size_t count=0;
    auto* stream=open_memstream(&buffer,&count);
    if(!stream)throw std::runtime_error("Cannot allocate native report replay");
    const bool ok=j1979_can_write_json_transport(stream,&plan,&result,interface.c_str(),transport.c_str());
    const bool closed=fclose(stream)==0;
    const std::unique_ptr<char,decltype(&std::free)> owned(buffer,&std::free);
    require(ok&&closed&&buffer&&count&&count<=32u*1024u*1024u,"Native report serialization failed");
    return std::string(buffer,count);
}
}
NativeInspection inspect_native_plan(const Value& root,Pair pair,std::string_view expected_vin){
    no_claims(root);
    require(root.at("schema").string()=="camarodiag-functional-j1979-can-linux-c-v1"&&
        root.at("protocol").string()=="SAE-J1979","Unsupported native physical report schema");
    require(j1979_can_physical_pair(pair.tx,pair.rx)&&address(root.at("tx_can_id"))==pair.tx&&
        address(root.at("rx_can_id"))==pair.rx,"Native physical pair differs from selection");
    require(expected_vin.size()==17&&j1979_can_valid_vin(std::string(expected_vin).c_str())&&
        root.at("declared_vin").string()==expected_vin,"Native VIN differs from independent scope");
    const auto transport=root.at("transport").string();
    require(transport=="LINUX_SOCKETCAN_ISOTP"||transport=="ELM_SERIAL_CLASSIC_ISOTP",
        "Unsupported native report transport");
    const auto interface=root.at("interface").string();
    require(!interface.empty()&&interface.size()<=4096&&interface.find('\0')==std::string::npos,
        "Invalid native interface label");
    const auto duration=root.at("duration_ms").unsigned_integer();
    require(duration<=3600000,"Native scan duration exceeds bound");
    const auto stop=nullable(root.at("stop_reason"),39);
    const auto& steps=root.at("steps").array();
    require(steps.size()<=J1979_CAN_MAX_STEPS,"Native scan observation limit");
    std::vector<Recorded> recorded;recorded.reserve(steps.size());
    std::uint64_t summed_duration=0;
    for(const auto& row:steps){recorded.push_back(parse_step(row));summed_duration+=recorded.back().duration;}
    require(summed_duration<=duration,"Native exchange durations exceed campaign duration");
    Replay replay(recorded,stop,std::string(expected_vin));
    const auto& result=replay.run(duration);
    j1979_can_plan plan{};const std::string vin(expected_vin);
    plan.tx=pair.tx;plan.rx=pair.rx;plan.expected_vin=vin.c_str();
    auto canonical=serialize(plan,result,interface,transport);
    require(compare(root,json::parse_report(canonical,32u*1024u*1024u)),
        "Native report fields or derived projections differ from replay");
    return {pair,vin,transport,stop,result.completed,static_cast<unsigned>(result.step_count),std::move(canonical)};
}
std::string native_plan_report(const NativeInspection& r){
    std::ostringstream out;
    out<<"{\"schema\":\"camarodiag-j1979-native-can-plan-inspection-v1\","
        "\"consistent\":true,\"verification_scope\":\"NATIVE_SEQUENCE_AND_SERIALIZED_PROJECTIONS\","
        "\"independent_plan_replay\":true,\"shared_application_decoder\":true,"
        "\"physical_vehicle_verified\":false,\"authenticated\":false,"
        "\"transport_provenance_verified\":false,\"timing_provenance_verified\":false,"
        "\"permits_programming\":false,\"replayed_report\":"<<r.canonical<<"}";
    return out.str();
}
}
