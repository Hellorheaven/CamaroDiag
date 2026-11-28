#include "j1979_historical_can_plan.hpp"
#include "j1979_context.hpp"
#include "j1979_multi.hpp"
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
bool extended_pid(unsigned pid){
    constexpr std::array<unsigned,18> extended{0x6b,0x6f,0x81,0x82,0x83,0x84,0x85,0x87,
        0x89,0x8a,0x8b,0x8c,0x8d,0x8e,0x8f,0x93,0x94,0x9c};
    return std::find(extended.begin(),extended.end(),pid)!=extended.end();
}
std::string multi(std::span<const cd::j1979::Observation> observations,bool old_catalogue){
    std::ostringstream o;o.imbue(std::locale::classic());o<<'[';
    bool first=true;
    for(const auto& observation:observations){
        const auto decoded=cd::j1979::historical_multi_signal(observation);
        if(!decoded||(old_catalogue&&extended_pid(decoded->pid)))continue;
        if(!first)o<<',';
        first=false;
        o<<"{\"request_hex\":"<<json::quote(hex(observation.request))<<",\"signals\":[";
        bool first_signal=true;
        for(const auto& signal:decoded->signals){
            require(signal.number.has_value(),"Historical multi-signal arithmetic missing");
            if(!first_signal)o<<',';
            first_signal=false;
            o<<"{\"pid\":"<<decoded->pid<<",\"name\":"<<json::quote(signal.name)
                <<",\"unit\":"<<json::quote(signal.unit)<<",\"raw_encoded\":"<<signal.raw<<",\"number\":";
            number(o,*signal.number);o<<'}';
        }
        o<<"]}";
    }
    o<<']';return o.str();
}
std::string range_state(cd::j1979::RangeState s){
    using enum cd::j1979::RangeState;
    switch(s){
        case unverified:return "UNVERIFIED";
        case default_unsupported:return "DEFAULT_UNSUPPORTED";
        case ecu_configuration:return "ECU_CONFIGURATION";
    }
    throw std::invalid_argument("Unknown historical range");
}
void optional_number(std::ostream& o,std::optional<double> n){if(n)number(o,*n);else o<<"null";}
void optional_bool(std::ostream& o,std::optional<bool> b){if(b)o<<(*b?"true":"false");else o<<"null";}
std::string scaling(std::span<const cd::j1979::Observation> observations){
    const auto context=cd::j1979::derive_context(observations);
    std::ostringstream o;o.imbue(std::locale::classic());
    o<<"{\"configuration\":[";
    const std::array<std::pair<unsigned,cd::j1979::Range>,2> ranges{{{0x4f,context.scaling.pid4f},{0x50,context.scaling.pid50}}};
    for(std::size_t i=0;i<ranges.size();++i){
        if(i)o<<',';
        const auto& [pid,r]=ranges[i];
        o<<"{\"pid\":"<<pid<<",\"state\":"<<json::quote(range_state(r.state))<<",\"data_hex\":";
        if(r.state==cd::j1979::RangeState::ecu_configuration)o<<json::quote(hex(r.data));else o<<"null";
        o<<'}';
    }
    o<<"],\"records\":[";bool first=true;
    for(const auto& row:observations){
        const auto record=cd::j1979::scale(row,context.scaling);
        if(!record)continue;
        if(!first)o<<',';
        first=false;
        o<<"{\"request_hex\":"<<json::quote(hex(row.request))<<",\"configuration_pid\":"<<record->configuration_pid<<",\"signals\":[";
        for(std::size_t i=0;i<record->signals.size();++i){
            if(i)o<<',';
            const auto& s=record->signals[i];
            o<<"{\"name\":"<<json::quote(s.name)<<",\"unit\":"<<json::quote(s.unit)
                <<",\"raw_encoded\":"<<s.raw<<",\"number\":";
            optional_number(o,s.number);o<<",\"scale_source\":"<<json::quote(s.scale_source)<<'}';
        }
        o<<"]}";
    }
    o<<"]}";return o.str();
}
struct HistoricalContext {
    std::optional<cd::j1979::Bytes> layout,baseline,cycle;
};
HistoricalContext historic_context(std::span<const cd::j1979::Observation> observations){
    HistoricalContext ctx;
    unsigned layouts=0,baselines=0,cycles=0;
    for(const auto& row:observations){
        if(row.request.size()!=2||row.request[0]!=1)continue;
        const auto data=cd::j1979::payload(row);
        if(!data)continue;
        switch(row.request[1]){
            case 0x1d:++layouts;ctx.layout=data;break;
            case 1:++baselines;ctx.baseline=data;break;
            case 0x41:++cycles;ctx.cycle=data;break;
            default:break;
        }
    }
    if(layouts!=1||!ctx.layout||ctx.layout->size()!=1)ctx.layout.reset();
    if(baselines!=1)ctx.baseline.reset();
    if(cycles!=1)ctx.cycle.reset();
    return ctx;
}
std::string operating(std::span<const cd::j1979::Observation> observations){
    const auto ctx=historic_context(observations);
    cd::j1979::Layout layout{};
    if(ctx.layout)layout={cd::j1979::LayoutState::verified,ctx.layout->front()};
    std::ostringstream o;o.imbue(std::locale::classic());
    o<<"{\"drive_cycle\":";
    const auto cycle=ctx.cycle?cd::j1979::drive_cycle(*ctx.cycle,ctx.baseline):std::nullopt;
    if(!cycle)o<<"null";
    else{
        o<<"{\"request_hex\":\"0141\",\"raw_hex\":"<<json::quote(hex(cycle->raw))<<",\"baseline_raw_hex\":";
        if(ctx.baseline)o<<json::quote(hex(*ctx.baseline));else o<<"null";
        o<<",\"support_verified\":"<<(cycle->support_verified?"true":"false")
            <<",\"compression_ignition\":"<<(cycle->compression_ignition?"true":"false")
            <<",\"consistent\":"<<(cycle->consistent?"true":"false")<<",\"monitors\":[";
        for(std::size_t i=0;i<cycle->monitors.size();++i){
            if(i)o<<',';
            const auto& m=cycle->monitors[i];
            o<<"{\"name\":"<<json::quote(m.name)<<",\"supported\":";optional_bool(o,m.supported);
            o<<",\"enabled\":";optional_bool(o,m.enabled);
            o<<",\"complete\":";optional_bool(o,m.complete);
            o<<",\"enabled_bit\":"<<(m.enabled_bit?"true":"false")
                <<",\"incomplete_bit\":"<<(m.incomplete_bit?"true":"false")
                <<",\"consistent\":"<<(m.consistent?"true":"false")<<'}';
        }
        o<<"]}";
    }
    o<<",\"fuel_trims\":[";bool first=true;
    for(const auto& row:observations){
        const auto trim=cd::j1979::trims(row,layout);
        if(!trim)continue;
        if(!first)o<<',';
        first=false;
        o<<"{\"request_hex\":"<<json::quote(hex(row.request))<<",\"frame\":";
        if(trim->frame)o<<*trim->frame;else o<<"null";
        o<<",\"layout_1d\":";
        if(layout.value)o<<unsigned(*layout.value);else o<<"null";
        o<<",\"upper_bank_verified\":";optional_bool(o,trim->upper_bank_verified);
        o<<",\"signals\":[";
        for(std::size_t i=0;i<trim->signals.size();++i){
            if(i)o<<',';
            const auto& s=trim->signals[i];
            o<<"{\"bank\":"<<s.bank<<",\"name\":"<<json::quote(s.name)<<",\"raw_encoded\":"<<s.raw
                <<",\"unit\":\"%\",\"number\":";
            optional_number(o,s.number);
            o<<",\"attribution\":"<<json::quote(s.number?"DEFINED":"UNVERIFIED_UPPER_BANK")<<'}';
        }
        o<<"]}";
    }
    o<<"]}";return o.str();
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
HistoricalProjections historical_projections(std::span<const cd::j1979::Observation> observations){
    std::ostringstream states;
    cd::j1979::write_status_json(states,observations);
    return {states.str(),multi(observations,false),multi(observations,true),
        scaling(observations),operating(observations)};
}
void verify_derived_summary(const Value& body,PlanInspection& p){
    std::vector<cd::j1979::Observation> observations;
    for(const auto& s:p.steps){
        const cd::j1979::Bytes response=s.response?cd::j1979::Bytes(*s.response):cd::j1979::Bytes{};
        observations.push_back({s.request,response,s.status=="RAW"||s.status=="PASSED",true});
    }
    const auto projection=historical_projections(observations);
    const auto m=monitors(p),v=info(p);
    const auto& s=projection.status;const auto& c=projection.context;const auto& op=projection.operating;
    for(const auto& [name,encoded]:std::array<std::pair<std::string_view,std::string_view>,5>{{
        {"scaled_monitor_records",m},{"extended_vehicle_info",v},{"status_records",s},
        {"contextual_measurements",c},{"operating_context",op}}}){
        if(const auto* saved=claim(body,name)){
            const auto canonical=name=="extended_vehicle_info"?canonical_info(*saved):normalize(*saved);
            require(equal(canonical,json::parse_report(encoded)),"Derived report differs from recorded bytes");
            ++p.derived_groups_checked;
        }
    }
    const auto& current_multi=projection.multi;
    std::string catalogue="NOT_REPORTED";
    if(const auto* saved=claim(body,"multi_signal_records")){
        const auto canonical=normalize(*saved);
        if(equal(canonical,json::parse_report(current_multi)))catalogue="CURRENT";
        else{
            require(equal(canonical,json::parse_report(projection.old_multi)),"Historical multi-signal records differ");
            catalogue="HISTORICAL_49";
        }
        ++p.derived_groups_checked;
    }
    p.derived_summary="{\"scaled_monitor_records\":"+m+",\"extended_vehicle_info\":"+v+",\"status_records\":"+s+
        ",\"multi_signal_records\":"+current_multi+",\"multi_signal_report_catalog\":"+json::quote(catalogue)+
        ",\"contextual_measurements\":"+c+",\"operating_context\":"+op+
        ",\"historical_arithmetic_only\":true,\"physical_identity_verified\":false}";
}
}
