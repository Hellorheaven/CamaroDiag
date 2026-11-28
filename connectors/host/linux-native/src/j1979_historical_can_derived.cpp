#include "j1979_historical_can_plan.hpp"
#include "j1979_context.hpp"
#include "j1979_status.hpp"
#include "j1979_monitor_scaling.h"
#include "j1979_vehicle_info.h"
#include <algorithm>
#include <cmath>
#include <iomanip>
#include <locale>
#include <sstream>
#include <stdexcept>
namespace cd::network::history {
namespace {
using json::Value;
void require(bool ok,const char* reason){if(!ok)throw std::invalid_argument(reason);}
std::string key(std::string_view s){
    std::string result;
    for(char c:s){
        if(c>='A'&&c<='Z'){result+='_';result+=static_cast<char>(c-'A'+'a');}
        else result+=c;
    }
    return result;
}
Value normalize(const Value& v){
    if(const auto* o=std::get_if<Value::Object>(&v.data)){
        Value::Object normalized;
        for(const auto& [k,x]:*o)
            require(normalized.emplace(key(k),normalize(x)).second,"Ambiguous derived field");
        return Value{std::move(normalized)};
    }
    if(const auto* a=std::get_if<Value::Array>(&v.data)){
        Value::Array normalized;
        for(const auto& x:*a)normalized.push_back(normalize(x));
        return Value{std::move(normalized)};
    }
    return v;
}
bool equal(const Value& a,const Value& b){
    if(std::holds_alternative<json::Integer>(b.data))
        return std::holds_alternative<json::Integer>(a.data)&&a.signed_integer()==b.signed_integer();
    if(std::holds_alternative<json::Decimal>(b.data)){
        if(!std::holds_alternative<json::Integer>(a.data)&&!std::holds_alternative<json::Decimal>(a.data))return false;
        const double n=b.finite_number();
        return std::abs(a.finite_number()-n)<=std::max(1e-9,1e-9*std::abs(n));
    }
    if(a.data.index()!=b.data.index())return false;
    if(b.null())return true;
    if(const auto* x=std::get_if<bool>(&b.data))return a.boolean()==*x;
    if(const auto* x=std::get_if<std::string>(&b.data))return a.string()==*x;
    if(const auto* x=std::get_if<Value::Array>(&b.data)){
        const auto& aa=a.array();
        if(aa.size()!=x->size())return false;
        for(std::size_t i=0;i<aa.size();++i)if(!equal(aa[i],x->at(i)))return false;
        return true;
    }
    const auto& aa=a.object();const auto& bb=b.object();
    if(aa.size()!=bb.size())return false;
    for(const auto& [k,x]:bb){const auto i=aa.find(k);if(i==aa.end()||!equal(i->second,x))return false;}
    return true;
}
const Value* claim(const Value& body,std::string_view name){
    const Value* found=nullptr;
    for(const auto& [k,v]:body.object())if(key(k)==name){
        require(!found,"Ambiguous aggregate claim");found=&v;
    }
    return found&&!found->null()?found:nullptr;
}
std::string hex(std::span<const std::uint8_t> bytes){
    constexpr char digits[]="0123456789ABCDEF";std::string out;
    for(auto c:bytes){out+=digits[c>>4];out+=digits[c&15];}
    return out;
}
void number(std::ostream& o,double v){
    // Always emit a decimal token for engineering quantities; discrete
    // counters and IDs must retain their strict integer type.
    o<<std::scientific<<std::setprecision(15)<<v<<std::defaultfloat;
}
std::string monitors(const PlanInspection& p){
    std::ostringstream o;o.imbue(std::locale::classic());o<<'[';bool first=true;
    for(const auto& s:p.steps){
        if(s.status!="PASSED"||!s.response||s.request.size()!=2||s.request[0]!=6)continue;
        const auto& r=*s.response;
        const auto n=j1979_monitor_count(s.request.data(),r.data(),r.size());
        for(std::size_t i=0;i<n;++i){
            j1979_monitor_record m{};
            require(j1979_monitor_decode(s.request.data(),r.data(),r.size(),i,&m),"Invalid raw monitor record");
            if(!first)o<<',';
            first=false;
            o<<"{\"request_hex\":"<<json::quote(hex(s.request))<<",\"record_index\":"<<i
                <<",\"monitor_id\":"<<unsigned(m.monitor_id)<<",\"test_id\":"<<unsigned(m.test_id)
                <<",\"unit_scaling_id\":"<<unsigned(m.scaling_id)<<",\"value_encoded\":"<<m.encoded_value
                <<",\"minimum_encoded\":"<<m.encoded_minimum<<",\"maximum_encoded\":"<<m.encoded_maximum
                <<",\"scaled\":";
            if(!m.scaled)o<<"null";
            else{
                o<<"{\"definition\":\"ISO15031-5:2006-ANNEX-E-SUBSET\",\"unit\":"<<json::quote(m.unit)
                    <<",\"value\":";number(o,m.value);o<<",\"minimum\":";number(o,m.minimum);
                o<<",\"maximum\":";number(o,m.maximum);
                o<<",\"numeric_relation\":"<<json::quote(j1979_monitor_relation_name(m.relation))
                    <<",\"monitor_verdict\":null}";
            }
            o<<'}';
        }
    }
    o<<']';return o.str();
}
std::string info(const PlanInspection& p){
    std::ostringstream o;o.imbue(std::locale::classic());o<<'[';bool first=true;
    for(const auto& s:p.steps){
        if(s.status!="RAW"||!s.response||s.request.size()!=2||s.request[0]!=9)continue;
        const auto& r=*s.response;
        j1979_vehicle_info v{};
        if(!j1979_vehicle_info_decode(s.request[1],r.data(),r.size(),&v))continue;
        if(!first)o<<',';
        first=false;
        o<<"{\"info_type\":"<<unsigned(v.info_type);
        if(v.counter_vector){
            o<<",\"general_denominator\":"<<v.general_denominator<<",\"ignition_cycles\":"<<v.ignition_cycles
                <<",\"counter_count\":"<<v.counter_count<<",\"monitors\":[";
            for(unsigned i=0;i<v.monitor_count;++i){
                if(i)o<<',';
                const auto& m=v.monitors[i];
                o<<"{\"monitor\":"<<json::quote(m.name)<<",\"numerator\":"<<m.numerator
                    <<",\"denominator\":"<<m.denominator<<",\"ratio\":";
                if(m.ratio_valid)number(o,m.ratio);else o<<"null";
                o<<'}';
            }
            o<<"],\"regulatory_verdict\":null";
        }else{
            require(v.printable_text,"Missing Mode 09 identification text");
            o<<",\"values\":["<<json::quote(v.text)<<']';
        }
        o<<'}';
    }
    o<<']';return o.str();
}
Value canonical_info(const Value& claimed){
    Value::Array rows;
    const auto normalized=normalize(claimed);
    for(const auto& v:normalized.array()){
        auto o=v.object();
        for(const auto& [k,x]:o){
            (void)x;
            require(k=="info_type"||k=="values"||k=="general_denominator"||k=="ignition_cycles"||
                k=="counter_count"||k=="monitors"||k=="regulatory_verdict","Unexpected Mode 09 claim");
        }
        const auto kind=v.at("info_type").unsigned_integer();
        const auto* verdict=v.find("regulatory_verdict");
        require(!verdict||verdict->null(),"Unsupported regulatory verdict");
        if(kind==8||kind==11){
            const auto* values=v.find("values");
            require(!values||values->array().empty(),"Unexpected counter identity values");
            o.erase("values");o["regulatory_verdict"]=Value{};
            Value::Array monitors;
            for(const auto& m:v.at("monitors").array()){
                auto record=m.object();
                if(const auto i=record.find("name");i!=record.end()){
                    require(!record.contains("monitor"),"Ambiguous IPT monitor");
                    record.emplace("monitor",i->second);record.erase(i);
                }
                monitors.push_back(Value{std::move(record)});
            }
            o["monitors"]=Value{std::move(monitors)};
        }else{
            require(kind==13||kind==15,"Unexpected InfoType");
            for(auto k:{"general_denominator","ignition_cycles","counter_count"}){
                const auto* f=v.find(k);require(!f||f->null(),"Unexpected identity counter");o.erase(k);
            }
            const auto* m=v.find("monitors");
            require(!m||m->null()||m->array().empty(),"Unexpected identity monitors");
            o.erase("monitors");o.erase("regulatory_verdict");
        }
        rows.push_back(Value{std::move(o)});
    }
    return Value{std::move(rows)};
}
}
void verify_derived_summary(const Value& body,PlanInspection& p){
    std::vector<cd::j1979::Observation> observations;
    for(const auto& s:p.steps)if(s.response)
        observations.push_back({s.request,*s.response,s.status=="RAW"||s.status=="PASSED",true});
    std::ostringstream states;
    cd::j1979::write_status_json(states,observations);
    const auto m=monitors(p),v=info(p),s=states.str();
    for(const auto& [name,encoded]:std::array<std::pair<std::string_view,std::string_view>,3>{{
        {"scaled_monitor_records",m},{"extended_vehicle_info",v},{"status_records",s}}}){
        if(const auto* saved=claim(body,name)){
            const auto canonical=name=="extended_vehicle_info"?canonical_info(*saved):normalize(*saved);
            require(equal(canonical,json::parse_report(encoded)),"Derived report differs from recorded bytes");
            ++p.derived_groups_checked;
        }
    }
    p.derived_summary="{\"scaled_monitor_records\":"+m+",\"extended_vehicle_info\":"+v+",\"status_records\":"+s+
        ",\"contextual_and_multisignal_verified\":false}";
}
}
