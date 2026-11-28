#include "j1979_legacy_live.hpp"
#include "j1979_measurement.h"
#include "j1979_multi.hpp"
#include <algorithm>
#include <cmath>
#include <iomanip>
#include <locale>
#include <sstream>
#include <tuple>

namespace cd::legacy {
namespace {
void quote(std::ostream& o,std::string_view s){
    constexpr char digits[]="0123456789abcdef";
    o<<'"';
    for(unsigned char c:s){
        if(c=='"'||c=='\\')o<<'\\'<<char(c);
        else if(c<32||c>126)o<<"\\u00"<<digits[c>>4]<<digits[c&15];
        else o<<char(c);
    }
    o<<'"';
}
const char* tf(bool b){return b?"true":"false";}
void nullable(std::ostream& o,std::string_view s){if(s.empty())o<<"null";else quote(o,s);}
void number(std::ostream& o,std::optional<double> n){if(n&&std::isfinite(*n))o<<*n;else o<<"null";}
template<class T,class Emit>void array(std::ostream& o,const T& rows,Emit emit){
    o<<'[';bool first=true;
    for(const auto& row:rows){if(!first)o<<',';first=false;emit(row);}
    o<<']';
}
template<class T>void numbers(std::ostream& o,const T& rows){array(o,rows,[&](auto n){o<<unsigned(n);});}
std::string_view phase(LivePhase p){
    switch(p){
    case LivePhase::initial:return "INITIAL_VIN";
    case LivePhase::inventory:return "INVENTORY";
    case LivePhase::context:return "CONTEXT";
    case LivePhase::periodic:return "PERIODIC_VIN";
    case LivePhase::sample:return "SAMPLE";
    case LivePhase::final:return "FINAL_VIN";
    }
    return "INVALID";
}
std::string_view outcome(FreezeOutcome state){
    switch(state){
    case FreezeOutcome::received:return "RECEIVED";
    case FreezeOutcome::timeout:return "TIMEOUT";
    case FreezeOutcome::transport:return "TRANSPORT_ERROR";
    case FreezeOutcome::budget:return "BUDGET";
    case FreezeOutcome::cancelled:return "CONTEXT_CHANGED";
    case FreezeOutcome::late:return "LATE_RESPONSE";
    }
    return "INVALID";
}
struct Value{std::string name,unit;std::optional<double> number;};
struct Statistics{
    unsigned count{},last_time{};
    double minimum{},maximum{},mean{},last{};
    void add(double value,unsigned elapsed){
        if(!std::isfinite(value))return;
        ++count;
        if(count==1)minimum=maximum=mean=value;
        else{
            minimum=std::min(minimum,value);maximum=std::max(maximum,value);
            mean+=(value-mean)/count;
        }
        last=value;last_time=elapsed;
    }
};
std::vector<Value> values(const LiveResult& r,const LiveSample& s,const cd::j1979::Context& context){
    if(!r.identity_rechecked||r.plan.raw_only||s.status!="PASSED")return {};
    const Data q{1,static_cast<std::uint8_t>(s.pid)};
    const cd::j1979::Observation row{q,s.response,true,false};
    std::vector<Value> out;
    if(cd::j1979::trim_pid(s.pid)){
        if(const auto decoded=cd::j1979::trims(row,context.layout))
            for(const auto& v:decoded->signals)out.push_back({v.name,"%",v.number});
    }else if(const auto decoded=cd::j1979::scale(row,context.scaling)){
        for(const auto& v:decoded->signals)out.push_back({v.name,v.unit,v.number});
    }else if(const auto decoded=cd::j1979::multi_signal(row,&context.scaling)){
        for(const auto& v:decoded->signals)out.push_back({std::string(v.name),std::string(v.unit),v.number});
    }else{
        j1979_measurement m{};
        if(j1979_measurement_decode(q.data(),q.size(),s.response.data(),s.response.size(),&m)&&m.interpreted&&!m.context_required)
            out.push_back({m.name,m.unit,m.number});
    }
    return out;
}
}
std::string live_report(const LiveResult& r){
    std::ostringstream o;o.imbue(std::locale::classic());o<<std::setprecision(15);
    o<<"{\"schema\":\"camarodiag-j1979-legacy-live-linux-v1\",\"protocol\":\"SAE-J1979\","
        "\"scope\":\"FUNCTIONAL_SELECTED_RESPONSE_LIVE\",\"transport\":\"ELM_SERIAL_LEGACY_HEADERED\","
        "\"physically_addressed\":false,\"expected_protocol\":"<<static_cast<unsigned>(r.protocol)
        <<",\"selected_response_source\":";
    const std::array<std::uint8_t,1> source{static_cast<std::uint8_t>(r.source)};
    quote(o,hex(source));
    o<<",\"declared_vin\":";quote(o,r.plan.vin);
    o<<",\"observed_initial_vin\":";nullable(o,r.initial_vin);
    o<<",\"observed_final_vin\":";nullable(o,r.final_vin);
    o<<",\"identity_rechecked\":"<<tf(r.identity_rechecked)<<",\"raw_only\":"<<tf(r.plan.raw_only)
        <<",\"completed\":"<<tf(r.completed)<<",\"inventory_complete\":"<<tf(r.inventory_complete)
        <<",\"requested_cycles\":"<<r.plan.cycles<<",\"completed_cycles\":"<<r.completed_cycles
        <<",\"interval_ms\":"<<r.plan.interval_ms<<",\"budget_ms\":"<<r.plan.budget_ms
        <<",\"duration_ms\":"<<r.duration_ms<<",\"selected_pids\":";
    numbers(o,r.plan.pids);o<<",\"advertised_pids\":";numbers(o,r.advertised);
    o<<",\"other_response_sources\":";
    array(o,r.other_sources,[&](unsigned n){
        const std::array<std::uint8_t,1> b{static_cast<std::uint8_t>(n)};quote(o,hex(b));
    });
    o<<",\"physical_vehicle_verified\":false,\"whole_vehicle_coverage_verified\":false,"
        "\"authenticated\":false,\"permits_programming\":false,\"stop_reason\":";
    nullable(o,r.error);
    std::vector<cd::j1979::Observation> admission;
    for(std::size_t i=0;i<r.context_requests.size()&&i<r.context_responses.size();++i)
        admission.push_back({r.context_requests[i],r.context_responses[i],!r.context_responses[i].empty(),true});
    const auto context=cd::j1979::derive_context(admission);
    using Key=std::tuple<unsigned,std::string,std::string>;
    std::map<Key,Statistics> stats;
    std::map<unsigned,std::map<std::string,unsigned>> attempts;
    o<<",\"observations\":";
    array(o,r.samples,[&](const LiveSample& s){
        ++attempts[s.pid][s.status];
        o<<"{\"cycle\":"<<s.cycle<<",\"pid\":"<<s.pid<<",\"elapsed_ms\":"<<s.elapsed_ms<<",\"status\":";
        quote(o,s.status);o<<",\"detail\":";nullable(o,s.detail);
        o<<",\"response_hex\":";if(s.response.empty())o<<"null";else quote(o,hex(s.response));
        o<<",\"signals\":";
        const auto signal_values=values(r,s,context);
        array(o,signal_values,[&](const Value& v){
            o<<"{\"name\":";quote(o,v.name);o<<",\"unit\":";quote(o,v.unit);
            o<<",\"number\":";number(o,v.number);o<<'}';
            if(v.number&&std::isfinite(*v.number))stats[{s.pid,v.name,v.unit}].add(*v.number,s.elapsed_ms);
        });
        o<<'}';
    });
    o<<",\"summary\":";
    array(o,r.plan.pids,[&](unsigned pid){
        o<<"{\"pid\":"<<pid<<",\"status_counts\":{";
        bool first=true;
        unsigned total=0;
        for(const auto& [status,count]:attempts[pid]){
            if(!first)o<<',';
            first=false;quote(o,status);o<<':'<<count;total+=count;
        }
        o<<"},\"attempted\":"<<total<<'}';
    });
    o<<",\"signal_summary\":";
    array(o,stats,[&](const auto& item){
        const auto& [pid,name,unit]=item.first;
        const auto& s=item.second;
        unsigned total=0;
        for(const auto& entry:attempts[pid])total+=entry.second;
        o<<"{\"pid\":"<<pid<<",\"name\":";quote(o,name);o<<",\"unit\":";quote(o,unit);
        o<<",\"count\":"<<s.count<<",\"attempted\":"<<total<<",\"missing\":"<<total-s.count
            <<",\"minimum\":"<<s.minimum<<",\"maximum\":"<<s.maximum<<",\"mean\":"<<s.mean
            <<",\"last_verified_value\":"<<s.last<<",\"last_verified_elapsed_ms\":"<<s.last_time<<'}';
    });
    o<<",\"transport_exchanges\":";
    array(o,r.trace,[&](const LiveExchange& x){
        o<<"{\"phase\":";quote(o,phase(x.phase));o<<",\"cycle\":"<<x.cycle
            <<",\"request\":";quote(o,x.exchange.command);
        o<<",\"raw_response\":";quote(o,x.exchange.raw);
        o<<",\"outcome\":";quote(o,outcome(x.exchange.outcome));
        o<<",\"started_elapsed_ms\":"<<x.started_ms<<",\"elapsed_ms\":"<<x.ended_ms
            <<",\"transmission_attempted\":"<<tf(x.exchange.attempted)<<'}';
    });
    o<<"}\n";return o.str();
}

namespace {
void cell(std::ostream& o,std::string_view value){
    o<<'"';
    for(char c:value){if(c=='"')o<<'"';o<<c;}
    o<<'"';
}
void optional_cell(std::ostream& o,std::string_view value){
    if(!value.empty())cell(o,value);
}
}
std::string live_csv(const LiveResult& r){
    if(!r.plan.valid()||!valid_protocol(r.protocol)||!valid_source(r.source)||r.trace.size()>live_max_trace||
        r.samples.size()>r.plan.pids.size()*r.plan.cycles||r.context_requests.size()!=r.context_responses.size())
        throw std::invalid_argument("Invalid legacy live CSV scope");
    std::vector<cd::j1979::Observation> admission;
    for(std::size_t i=0;i<r.context_requests.size();++i)
        admission.push_back({r.context_requests[i],r.context_responses[i],!r.context_responses[i].empty(),true});
    const auto context=r.identity_rechecked?cd::j1979::derive_context(admission):cd::j1979::Context{};
    std::ostringstream o;o.imbue(std::locale::classic());o<<std::setprecision(15);
    o<<"cycle,pid_hex,started_elapsed_ms,elapsed_ms,duration_ms,status,value,unit,request_hex,response_hex,detail,signal_name\n";
    std::size_t next=0;
    for(const auto& sample:r.samples){
        while(next<r.trace.size()&&(r.trace[next].phase!=LivePhase::sample||r.trace[next].exchange.command=="WAIT"))++next;
        if(next==r.trace.size())throw std::invalid_argument("Legacy CSV sample lacks corresponding exchange");
        const auto& x=r.trace[next++];
        const Data request{1,static_cast<std::uint8_t>(sample.pid)};
        if(x.cycle!=sample.cycle||x.exchange.command!=hex(request)||x.ended_ms!=sample.elapsed_ms||
            x.started_ms>x.ended_ms||x.exchange.duration_ms!=x.ended_ms-x.started_ms)
            throw std::invalid_argument("Legacy CSV sample and trace differ");
        const auto projected=values(r,sample,context);
        const auto emit=[&](const Value* v){
            const Data pid{static_cast<std::uint8_t>(sample.pid)};
            o<<sample.cycle<<',';cell(o,hex(pid));
            o<<','<<x.started_ms<<','<<x.ended_ms<<','<<x.exchange.duration_ms<<',';cell(o,sample.status);
            o<<',';
            if(v&&v->number&&std::isfinite(*v->number))o<<*v->number;
            o<<',';if(v)cell(o,v->unit);
            o<<',';cell(o,x.exchange.command);
            o<<',';if(!sample.response.empty())cell(o,hex(sample.response));
            o<<',';optional_cell(o,sample.detail);
            o<<',';if(v)cell(o,v->name);
            o<<'\n';
        };
        if(projected.empty())emit(nullptr);
        else for(const auto& v:projected)emit(&v);
    }
    // A cancelled WAIT or periodic identity has no sample row. Every other
    // trace sample must have exactly one corresponding observation.
    for(;next<r.trace.size();++next)
        if(r.trace[next].phase==LivePhase::sample&&r.trace[next].exchange.command!="WAIT")
            throw std::invalid_argument("Legacy CSV omitted a sample exchange");
    auto result=o.str();
    if(result.size()>64u*1024u*1024u)throw std::invalid_argument("Legacy CSV output limit");
    return result;
}
}
