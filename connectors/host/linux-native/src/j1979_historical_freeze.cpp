#include "j1979_historical_freeze.hpp"
#include "j1979_native_report_detail.hpp"
#include "j1979_measurement.h"
#include "j1979_policy.h"
#include <algorithm>
#include <array>
#include <cmath>
#include <iomanip>
#include <locale>
#include <set>
#include <sstream>
namespace cd::network::history {
namespace {
using namespace detail;
using Object=json::Value::Object;
using Array=json::Value::Array;
Value obj(Object x){return Value{std::move(x)};}
Value arr(Array x){return Value{std::move(x)};}
Value str(std::string_view s){return Value{std::string(s)};}
Value integer(std::uint64_t n){return Value{json::Integer{std::to_string(n)}};}
Value flag(bool b){return Value{b};}
Value text_or_null(std::string_view s){return s.empty()?Value{}:str(s);}
Value real(double n){
    require(std::isfinite(n),"Nonfinite stored measurement");
    std::ostringstream o;o.imbue(std::locale::classic());o<<std::scientific<<std::setprecision(16)<<n;
    return Value{json::Decimal{o.str()}};
}
template<class T> Value numbers(const T& range){
    Array rows;for(auto n:range)rows.push_back(integer(n));return arr(std::move(rows));
}
unsigned bounded(const Value& v,unsigned minimum,unsigned maximum){
    const auto n=v.unsigned_integer();require(n>=minimum&&n<=maximum,"Historical freeze integer limit");
    return static_cast<unsigned>(n);
}
Bytes hex_bytes(const Value& v,std::size_t maximum){
    const auto result=bytes(v,maximum);
    require(legacy::hex(result)==v.string(),"Noncanonical freeze hex");
    return result;
}
legacy::FreezePlan plan(const Value& v){
    json::fields(v,{"expected_vin","frames","selected_pids","budget_ms"});
    legacy::FreezePlan out{};
    out.vin=v.at("expected_vin").string();
    out.budget_ms=bounded(v.at("budget_ms"),1000,600000);
    for(const auto& row:v.at("frames").array()){
        require(out.frames.size()<8,"Historical frame limit");
        out.frames.push_back(static_cast<std::uint8_t>(bounded(row,0,255)));
    }
    for(const auto& row:v.at("selected_pids").array()){
        require(out.pids.size()<64,"Historical PID limit");
        out.pids.push_back(static_cast<std::uint8_t>(bounded(row,1,255)));
    }
    require(out.valid(),"Invalid historical freeze selection");return out;
}
std::string dtc(std::span<const std::uint8_t> data){
    if(data[0]==0&&data[1]==0)return {};
    char code[6]{};require(cd_j1979_native_dtc_code(data.data(),code),"Invalid freeze DTC");return code;
}
bool context_free(unsigned pid){
    constexpr std::array<unsigned,47> values{4,5,0x0a,0x0c,0x0d,0x0e,0x0f,0x11,0x1f,0x21,0x22,
        0x23,0x2c,0x2d,0x2e,0x2f,0x30,0x31,0x32,0x33,0x3c,0x3d,0x3e,0x3f,0x42,0x43,0x45,0x46,
        0x47,0x48,0x49,0x4a,0x4b,0x4c,0x4d,0x4e,0x52,0x53,0x54,0x59,0x5a,0x5b,0x5c,0x5d,0x5e,0x61,0x62};
    return pid==0x63||pid==0xa6||std::find(values.begin(),values.end(),pid)!=values.end();
}
bool historical_width(unsigned pid,std::size_t n){
    if((pid>=6&&pid<=9)||(pid>=0x55&&pid<=0x58))return n==1||n==2;
    if(pid==1||pid==0xa6)return n==4;
    if(pid==0x13||pid==0x1d)return n==1;
    const auto width=j1979_measurement_width(static_cast<std::uint8_t>(pid));
    return width?n==width:n>0;
}
struct Row{
    Bytes request,response;
    std::string outcome,status{"NOT_TESTED"},reason;
    unsigned duration{};
    bool attempted{},has_response{};
    Value evidence;
    Value value()const{
        return obj({{"request_hex",str(legacy::hex(request))},
            {"response_hex",has_response?str(legacy::hex(response)):Value{}},
            {"transport_outcome",str(outcome)},{"duration_ms",integer(duration)},
            {"transmission_attempted",flag(attempted)},{"status",str(status)},
            {"detail",text_or_null(reason)},{"evidence",evidence}});
    }
};
Row classify(const Value& v,const Bytes& request,bool claimed=true){
    if(claimed)json::fields(v,{"request_hex","response_hex","transport_outcome","duration_ms",
        "transmission_attempted","status","detail","evidence"});
    else json::fields(v,{"request_hex","response_hex","transport_outcome","duration_ms","transmission_attempted"});
    Row r{};
    r.request=hex_bytes(v.at("request_hex"),3);require(r.request==request,"Unexpected historical freeze request order");
    r.outcome=v.at("transport_outcome").string();
    const auto& raw=v.at("response_hex");
    r.has_response=!raw.null();
    if(r.has_response){r.response=hex_bytes(raw,4095);require(!r.response.empty(),"Empty freeze response");}
    r.attempted=v.at("transmission_attempted").boolean();
    r.duration=bounded(v.at("duration_ms"),0,3600000);
    const bool received=r.outcome=="RECEIVED"||r.outcome=="LATE_RESPONSE";
    require(received==r.has_response&&(!received||r.attempted),"Freeze transport response contradiction");
    if(r.outcome=="TIMEOUT"||r.outcome=="TRANSPORT_ERROR")require(r.attempted,"Freeze transport failure without attempt");
    if(r.outcome=="BUDGET")require(!r.attempted,"Budget before transmission contradicted");
    if(r.outcome!="RECEIVED"){
        require(r.outcome=="TIMEOUT"||r.outcome=="TRANSPORT_ERROR"||r.outcome=="BUDGET"||
            r.outcome=="CANCELLED"||r.outcome=="CONTEXT_CHANGED"||r.outcome=="LATE_RESPONSE","Invalid freeze transport outcome");
        r.reason=r.outcome;if(r.outcome=="TRANSPORT_ERROR")r.status="FAILED";return r;
    }
    const auto& b=r.response;
    if(b.size()==3&&b[0]==0x7f&&b[1]==request[0]&&b[2]&&b[2]!=0x78){
        r.reason="NRC_"+legacy::hex(std::span(b).last(1));return r;
    }
    r.status="FAILED";
    if(request==Bytes{9,2}){
        if(b.size()!=20||b[0]!=0x49||b[1]!=2||b[2]!=1||
            !cd_j1979_native_vin(reinterpret_cast<const char*>(b.data()+3),17)){
            r.reason="INVALID_VIN";return r;
        }
        r.status="PASSED";r.evidence=obj({{"vin",str(std::string(b.begin()+3,b.end()))}});return r;
    }
    if(b.size()<=3||b[0]!=0x42||b[1]!=request[1]||b[2]!=request[2]){
        r.reason="WRONG_SERVICE_PID_OR_FRAME";return r;
    }
    const unsigned pid=request[1],frame=request[2];
    const auto data=std::span(b).subspan(3);
    Object evidence{{"frame",integer(frame)},{"pid",integer(pid)},{"data_hex",str(legacy::hex(data))}};
    if(pid%32==0){
        if(data.size()!=4||(pid==224&&(data.back()&1))){r.reason="INVALID_SUPPORT_BITMAP";return r;}
        std::vector<unsigned> ids;
        for(unsigned i=0;i<31;++i)if(data[i/8]&(0x80u>>(i%8)))ids.push_back(pid+i+1);
        evidence.emplace("page",obj({{"base",integer(pid)},{"bitmap_hex",str(legacy::hex(data))},
            {"advertised_pids",numbers(ids)},{"has_next_page",flag(data.back()&1)}}));
        r.status="PASSED";
    }else if(pid==2){
        if(data.size()!=2){r.reason="INVALID_TRIGGER_DTC";return r;}
        const auto code=dtc(data);
        evidence.emplace("trigger_present",flag(!code.empty()));evidence.emplace("trigger_dtc",text_or_null(code));
        r.status="PASSED";
    }else{
        if(frame==0&&!historical_width(pid,data.size())){r.reason="INVALID_PID_DATA_WIDTH";return r;}
        j1979_measurement m{};
        if(frame==0&&context_free(pid)&&j1979_measurement_decode(request.data(),request.size(),b.data(),b.size(),&m)&&m.interpreted){
            evidence.emplace("measurement",obj({{"mode",integer(2)},{"pid",integer(pid)},{"frame",integer(frame)},
                {"name",str(m.name)},{"unit",str(m.unit)},{"number",real(m.number)}}));r.status="PASSED";
        }else{r.status="RAW";r.reason="STORED_RAW_VALUE";}
    }
    r.evidence=obj(std::move(evidence));return r;
}
bool equal(const Value& a,const Value& b,std::string_view field={}){
    if(field=="number"&&numeric(a)&&numeric(b)){
        const auto v=b.finite_number();return std::abs(a.finite_number()-v)<=std::max(1e-12,1e-12*std::abs(v));
    }
    if(a.data.index()!=b.data.index())return false;
    if(const auto* p=std::get_if<Array>(&b.data)){
        if(a.array().size()!=p->size())return false;
        for(std::size_t i=0;i<p->size();++i)if(!equal(a.array()[i],p->at(i)))return false;
        return true;
    }
    if(const auto* p=std::get_if<Object>(&b.data)){
        if(a.object().size()!=p->size())return false;
        for(const auto& [k,v]:*p){const auto* x=a.find(k);if(!x||!equal(*x,v,k))return false;}
        return true;
    }
    return compare(a,b,field);
}
class Replay{
    const legacy::FreezePlan& plan_;
    const Array& saved_;
    Array steps_,frames_;
    std::string stop_;
    std::uint64_t elapsed_{};
    bool claimed_{};
    std::optional<Row> read(Bytes q){
        if(!stop_.empty())return {};
        require(steps_.size()<saved_.size(),"Missing historical freeze exchange");
        const auto& original=saved_[steps_.size()];
        auto r=classify(original,q,claimed_);
        auto rebuilt=r.value();
        require(!claimed_||equal(original,rebuilt),"Historical freeze step claim differs from raw evidence");
        elapsed_+=r.duration;steps_.push_back(std::move(rebuilt));
        if(r.outcome=="BUDGET"||r.outcome=="LATE_RESPONSE")stop_="BUDGET_EXHAUSTED";
        else if(r.outcome=="TRANSPORT_ERROR"||r.outcome=="CANCELLED"||r.outcome=="CONTEXT_CHANGED")stop_=r.outcome;
        return r;
    }
    std::optional<Value> accepted(Bytes q){
        const auto r=read(std::move(q));if(!r||r->status!="PASSED")return {};
        return r->evidence;
    }
    Value frame(unsigned f){
        const auto request=[&](unsigned pid){return Bytes{2,static_cast<std::uint8_t>(pid),static_cast<std::uint8_t>(f)};};
        std::set<unsigned> ids,read_ids,unadvertised,unread,selected(plan_.pids.begin(),plan_.pids.end());
        Array pages,measurement_steps;
        bool stable=false,complete=false;
        Value trigger,final_trigger;
        std::string status="UNAVAILABLE",reason;
        const auto result=[&](){
            return obj({{"frame",integer(f)},{"required_frame",flag(f==0)},{"status",str(status)},
                {"trigger_dtc",trigger},{"final_trigger_dtc",final_trigger},{"trigger_stable",flag(stable)},
                {"inventory_complete",flag(complete)},{"verified_pages",arr(pages)},{"advertised_pids",numbers(ids)},
                {"read_pids",numbers(read_ids)},{"unadvertised_selected_pids",numbers(unadvertised)},
                {"not_read_selected_pids",numbers(unread)},{"measurement_steps",arr(measurement_steps)},
                {"detail",text_or_null(reason)}});
        };
        const auto page=[&](unsigned base)->std::optional<Value>{
            const auto r=accepted(request(base));if(!r)return {};
            const auto value=r->at("page");pages.push_back(value);
            for(const auto& v:value.at("advertised_pids").array())ids.insert(static_cast<unsigned>(v.unsigned_integer()));
            return value;
        };
        const auto first=page(0);
        if(!first){reason="PID00_UNVERIFIED";return result();}
        if(!ids.contains(2)){reason="TRIGGER_NOT_ADVERTISED";return result();}
        const auto t=accepted(request(2));
        if(!t){reason="TRIGGER_UNVERIFIED";return result();}
        trigger=t->at("trigger_dtc");
        if(!t->at("trigger_present").boolean()){status="ABSENT";reason="ZERO_TRIGGER_DTC";return result();}
        bool continuation=first->at("has_next_page").boolean();complete=!continuation;
        for(unsigned base=32;base<=224&&continuation&&stop_.empty();base+=32){
            const auto next=page(base);if(!next)break;
            continuation=next->at("has_next_page").boolean();complete=!continuation;
        }
        std::set<unsigned> candidates;
        for(auto pid:ids)if(pid!=2&&(selected.empty()||selected.contains(pid)))candidates.insert(pid);
        for(auto pid:selected)if(!ids.contains(pid))(complete?unadvertised:unread).insert(pid);
        unsigned failures=0;
        for(auto pid:candidates){
            const auto r=read(request(pid));if(!r)break;
            if(r->attempted)read_ids.insert(pid);
            measurement_steps.push_back(integer(steps_.size()-1));
            if(r->status!="PASSED"&&r->status!="RAW")++failures;
        }
        for(auto pid:selected)if(ids.contains(pid)&&!read_ids.contains(pid))unread.insert(pid);
        if(!stop_.empty()){status="PARTIAL";reason=stop_;return result();}
        const auto final=accepted(request(2));
        if(final)final_trigger=final->at("trigger_dtc");
        stable=final&&equal(final_trigger,trigger);
        if(!stable){status=final?"CHANGED":"PARTIAL";reason=final?"TRIGGER_CHANGED":"FINAL_TRIGGER_UNVERIFIED";}
        else if(!complete||failures||read_ids.size()!=candidates.size()){status="PARTIAL";reason="INCOMPLETE_INVENTORY_OR_VALUES";}
        else status="COMPLETE";
        return result();
    }
public:
    Replay(const legacy::FreezePlan& p,const Array& saved,bool claimed=true):plan_(p),saved_(saved),claimed_(claimed){}
    Value run(unsigned duration,const Value& plan_json){
        Value initial,final;
        if(const auto r=accepted({9,2}))initial=r->at("vin");
        if(stop_.empty()&&(initial.null()||initial.string()!=plan_.vin))stop_=initial.null()?"VIN_UNVERIFIED":"VIN_MISMATCH";
        for(auto n:plan_.frames){if(!stop_.empty())break;frames_.push_back(frame(n));}
        if(stop_.empty()){
            if(const auto r=accepted({9,2}))final=r->at("vin");
            if(stop_.empty()&&(final.null()||!equal(initial,final)))stop_=final.null()?"FINAL_VIN_UNVERIFIED":"FINAL_VIN_MISMATCH";
        }
        require(steps_.size()==saved_.size()&&elapsed_<=duration+saved_.size(),"Extra freeze exchanges or inconsistent duration");
        const bool complete=stop_.empty()&&frames_.size()==plan_.frames.size();
        const bool stable=complete&&std::all_of(frames_.begin(),frames_.end(),[](const Value& f){
            const auto& s=f.at("status").string();return s=="COMPLETE"||s=="ABSENT";
        });
        std::vector<unsigned> unvisited(plan_.frames.begin()+static_cast<std::ptrdiff_t>(frames_.size()),plan_.frames.end());
        return obj({{"plan",plan_json},{"initial_vin",initial},{"final_vin",final},{"completed",flag(complete)},
            {"stop_reason",text_or_null(stop_)},{"identity_rechecked",flag(complete&&equal(initial,final))},
            {"frames",arr(std::move(frames_))},{"steps",arr(std::move(steps_))},{"unvisited_frames",numbers(unvisited)},
            {"all_frames_stable",flag(stable)},{"physical_vehicle_verified",flag(false)},{"authenticated",flag(false)},
            {"whole_vehicle_coverage_verified",flag(false)},{"permits_programming",flag(false)},{"duration_ms",integer(duration)}});
    }
};
void encode(std::ostream& o,const Value& v){
    if(v.null())o<<"null";
    else if(const auto* b=std::get_if<bool>(&v.data))o<<(*b?"true":"false");
    else if(const auto* n=std::get_if<json::Integer>(&v.data))o<<n->text;
    else if(const auto* n=std::get_if<json::Decimal>(&v.data))o<<n->text;
    else if(const auto* s=std::get_if<std::string>(&v.data))o<<json::quote(*s);
    else if(const auto* a=std::get_if<Array>(&v.data)){
        o<<'[';bool first=true;for(const auto& x:*a){if(!first)o<<',';first=false;encode(o,x);}o<<']';
    }else{
        o<<'{';bool first=true;for(const auto& [k,x]:v.object()){
            if(!first)o<<',';
            first=false;o<<json::quote(k)<<':';encode(o,x);
        }o<<'}';
    }
}
}
HistoricalFreeze replay_historical_freeze(const Value& root,std::optional<std::string> expected){
    auto p=plan(root.at("plan"));
    require(!expected||p.vin==*expected,"Freeze VIN differs from independently selected vehicle");
    const auto& rows=root.at("steps").array();
    require(!rows.empty()&&rows.size()<=8*(8+2+247)+2,"Historical freeze step count");
    auto replayed=Replay(p,rows).run(bounded(root.at("duration_ms"),0,3600000),root.at("plan"));
    require(equal(root,replayed),"Historical freeze report differs from application replay");
    const bool completed=replayed.at("completed").boolean(),stable=replayed.at("all_frames_stable").boolean();
    return {std::move(p),std::move(replayed),completed,stable};
}
HistoricalFreeze rebuild_historical_freeze(const Value& plan_value,const Value::Array& observations,unsigned duration){
    auto p=plan(plan_value);
    require(!observations.empty()&&observations.size()<=8*(8+2+247)+2&&duration<=3600000,"Historical freeze reconstruction limits");
    auto replayed=Replay(p,observations,false).run(duration,plan_value);
    const bool complete=replayed.at("completed").boolean(),stable=replayed.at("all_frames_stable").boolean();
    return {std::move(p),std::move(replayed),complete,stable};
}
std::string encode_historical_json(const Value& v){
    std::ostringstream o;o.imbue(std::locale::classic());encode(o,v);return o.str();
}
}
