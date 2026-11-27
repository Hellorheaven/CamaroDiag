#include "j1979_legacy_live.hpp"
#include "j1979_legacy_identity.hpp"
#include "j1979_measurement.h"
#include "j1979_projection.hpp"
#include <algorithm>
#include <array>
#include <limits>
#include <utility>

namespace cd::legacy {
namespace {
using Observer=std::function<LiveExchange(LivePhase,unsigned,std::string_view,unsigned)>;
void require(bool ok,const char* why){if(!ok)throw std::invalid_argument(why);}
bool terminal_negative(Bytes b,unsigned mode){return b.size()==3&&b[0]==0x7f&&b[1]==mode&&b[2]&&b[2]!=0x78;}
class Recipe {
    LiveResult r_;
    const Observer& observe_;
    unsigned time_{},last_identity_{};
    std::size_t raw_bytes_{};
    std::set<unsigned> received_;
    void stop(std::string_view error){if(r_.error.empty())r_.error=error;}
    LiveExchange exchange(LivePhase phase,unsigned cycle,std::string command,unsigned earliest=0){
        require(r_.error.empty()&&r_.trace.size()<live_max_trace,"Invalid live request sequence");
        auto x=observe_(phase,cycle,command,std::max(time_,earliest));
        const auto& row=x.exchange;
        const auto state=row.outcome;
        const bool wait=command=="WAIT";
        require(x.phase==phase&&x.cycle==cycle&&row.command==command&&x.started_ms>=time_&&
            x.started_ms<=x.ended_ms&&x.ended_ms<=3600000&&row.duration_ms==x.ended_ms-x.started_ms&&
            row.raw.size()<=32768&&row.raw.size()<=live_max_raw-raw_bytes_&&
            static_cast<unsigned>(state)>=1&&static_cast<unsigned>(state)<=6,"Invalid live trace envelope");
        require((wait&&!row.attempted&&row.raw.empty())||(!wait&&
            ((state!=FreezeOutcome::received&&state!=FreezeOutcome::late)||row.attempted)),
            "Live exchange attempt contradicts trace");
        require(state!=FreezeOutcome::received||x.ended_ms<r_.plan.budget_ms,
            "Live response outside overall budget");
        if(wait){
            require(state==FreezeOutcome::received||state==FreezeOutcome::budget||state==FreezeOutcome::cancelled,
                "Invalid wait result");
            require(state!=FreezeOutcome::received||x.ended_ms>=earliest,"Live cycle starts too early");
        }else{
            require(state!=FreezeOutcome::budget||!row.attempted,"Budget exhaustion attempted request");
            require((state!=FreezeOutcome::timeout&&state!=FreezeOutcome::transport)||row.attempted,"Unattempted transport fault");
            require(state==FreezeOutcome::received||state==FreezeOutcome::late||row.raw.empty(),"Uncorrelated live raw data");
            const unsigned maximum=command=="ATDPN"?2000:4000;
            require(state!=FreezeOutcome::received||row.duration_ms<=maximum,"Live per-request deadline exceeded");
        }
        raw_bytes_+=row.raw.size();time_=x.ended_ms;
        switch(state){
        case FreezeOutcome::received:case FreezeOutcome::timeout:break;
        case FreezeOutcome::transport:stop("TRANSPORT_ERROR");break;
        case FreezeOutcome::budget:case FreezeOutcome::late:stop("BUDGET_EXHAUSTED");break;
        case FreezeOutcome::cancelled:stop("CONTEXT_CHANGED");break;
        }
        r_.trace.push_back(x);return x;
    }
    std::vector<Data> read(LivePhase phase,unsigned cycle,Data q){
        require(r_.plan.allows(q),"Request outside approved live plan");
        auto row=exchange(phase,cycle,hex(q));
        if(row.exchange.outcome!=FreezeOutcome::received)return {};
        try{
            const auto decoded=frames(r_.protocol,hex(q),row.exchange.raw);
            std::vector<Data> selected;
            for(const auto& f:decoded){
                if(f.source==r_.source)selected.push_back(f.payload);
                else r_.other_sources.insert(f.source);
            }
            return selected;
        }catch(const std::invalid_argument&){stop("MALFORMED_LEGACY_RESPONSE");return {};}
    }
    Data single(LivePhase phase,unsigned cycle,Data q){
        auto rows=read(phase,cycle,std::move(q));
        if(rows.size()>1){stop("AMBIGUOUS_SELECTED_SOURCE");return {};}
        return rows.empty()?Data{}:std::move(rows.front());
    }
    bool identity(LivePhase phase,unsigned cycle){
        const auto init=single(phase,cycle,{1,0});
        if(init.size()!=6||init[0]!=0x41||init[1]!=0){stop("VIN_ADMISSION_UNVERIFIED");return false;}
        auto probe=exchange(phase,cycle,"ATDPN");
        if(probe.exchange.outcome!=FreezeOutcome::received||fixed_protocol(probe.exchange.raw)!=r_.protocol){
            stop("PROTOCOL_UNVERIFIED_OR_CHANGED");return false;
        }
        if(!vin_support_page(single(phase,cycle,{9,0}))){stop("VIN_INFO_NOT_ADVERTISED");return false;}
        if(!vin_fragment_count(single(phase,cycle,{9,1}))){stop("VIN_COUNT_INVALID");return false;}
        const auto vin=normalized_vin(read(phase,cycle,{9,2}));
        if(!vin){stop(phase==LivePhase::final?"FINAL_VIN_UNVERIFIED":"VIN_UNVERIFIED");return false;}
        if(phase==LivePhase::initial)r_.initial_vin=*vin;
        if(phase==LivePhase::final)r_.final_vin=*vin;
        if(*vin!=r_.plan.vin){stop(phase==LivePhase::final?"FINAL_VIN_MISMATCH":"VIN_MISMATCH");return false;}
        last_identity_=time_;return true;
    }
    void retain_context(Data q,Data response){
        r_.context_requests.push_back(std::move(q));r_.context_responses.push_back(std::move(response));
    }
    void inventory(){
        for(unsigned base=0;base<=224&&r_.error.empty();base+=32){
            Data q{1,static_cast<std::uint8_t>(base)};
            const auto page=single(LivePhase::inventory,0,q);
            retain_context(q,page);
            if(page.size()!=6||page[0]!=0x41||page[1]!=base||(base==224&&(page.back()&1))){
                stop("INVENTORY_UNVERIFIED");return;
            }
            for(unsigned bit=0;bit<31;++bit)if(page[2+bit/8]&(0x80u>>(bit%8)))r_.advertised.insert(base+bit+1);
            if(!(page.back()&1)){r_.inventory_complete=true;break;}
        }
        if(!r_.inventory_complete){stop("INVENTORY_INCOMPLETE");return;}
        for(auto pid:r_.plan.pids)if(!r_.advertised.contains(pid)){stop("SELECTED_PID_NOT_VERIFIED");return;}
    }
    void context(){
        if(r_.plan.raw_only||!r_.error.empty())return;
        std::set<unsigned> needed;
        for(auto pid:r_.plan.pids){
            if(const auto dep=cd::j1979::dependency(pid))needed.insert(*dep);
            if(cd::j1979::trim_pid(pid))needed.insert(0x1d);
        }
        for(unsigned pid:needed){
            if(!r_.error.empty())return;
            if(!r_.advertised.contains(pid))continue;
            Data q{1,static_cast<std::uint8_t>(pid)};
            auto reply=single(LivePhase::context,0,q);
            if(reply.size()!=(pid==0x1d?3u:6u)||reply[0]!=0x41||reply[1]!=pid){
                retain_context(q,{});
                if(pid!=0x1d)stop("SCALING_UNVERIFIED");
            }else retain_context(q,std::move(reply));
        }
        if(!r_.error.empty())return;
        std::vector<cd::j1979::Observation> rows;
        for(std::size_t i=0;i<r_.context_requests.size();++i)
            rows.push_back({r_.context_requests[i],r_.context_responses[i],!r_.context_responses[i].empty(),true});
        const auto ctx=cd::j1979::derive_context(rows);
        for(auto pid:needed){
            if(pid==0x4f&&ctx.scaling.pid4f.state==cd::j1979::RangeState::unverified)stop("SCALING_UNVERIFIED");
            if(pid==0x50&&ctx.scaling.pid50.state==cd::j1979::RangeState::unverified)stop("SCALING_UNVERIFIED");
        }
    }
    void sample(unsigned cycle,unsigned pid){
        LiveSample s{};s.cycle=cycle;s.pid=pid;
        const Data q{1,static_cast<std::uint8_t>(pid)};
        s.response=single(LivePhase::sample,cycle,q);
        s.elapsed_ms=time_;
        if(!r_.error.empty()){s.status="FAILED";s.detail=r_.error;}
        else if(s.response.empty()){s.status="TIMEOUT";s.detail="NO_SELECTED_RESPONSE";}
        else if(terminal_negative(s.response,1)){s.status="NEGATIVE";s.detail="NRC_"+hex(Bytes(s.response).subspan(2));}
        else if(s.response.size()<3||s.response.size()>7||s.response[0]!=0x41||s.response[1]!=pid){
            s.status="FAILED";s.detail="INVALID_APPLICATION_RESPONSE";stop(s.detail);
        }else if(!r_.plan.raw_only){
            const cd::j1979::Observation obs{q,s.response,true,false};
            const auto width=j1979_measurement_width(static_cast<std::uint8_t>(pid));
            const bool trim=cd::j1979::trim_pid(pid);
            if((trim&&(s.response.size()!=3&&s.response.size()!=4))||
                (!trim&&width&&s.response.size()!=2+width)||!cd::j1979::known_shape(obs)){
                s.status="FAILED";s.detail="INVALID_MEASUREMENT";stop(s.detail);
            }else{s.status="PASSED";received_.insert(pid);}
        }else{s.status="RAW";received_.insert(pid);}
        r_.samples.push_back(std::move(s));
    }
public:
    Recipe(Protocol protocol,unsigned source,const LivePlan& plan,const Observer& observe):observe_(observe){
        require(valid_protocol(protocol)&&valid_source(source)&&plan.valid()&&bool(observe),"Invalid live recording scope");
        r_.protocol=protocol;r_.source=source;r_.plan=plan;
    }
    LiveResult run(){
        if(identity(LivePhase::initial,0))inventory();
        context();
        unsigned next=time_;
        for(unsigned cycle=1;cycle<=r_.plan.cycles&&r_.error.empty();++cycle){
            exchange(LivePhase::sample,cycle,"WAIT",next);
            if(!r_.error.empty())break;
            if(time_-last_identity_>=30000&&!identity(LivePhase::periodic,cycle))break;
            const auto actual=time_;
            for(auto pid:r_.plan.pids){
                if(!r_.error.empty())break;
                sample(cycle,pid);
            }
            if(!r_.error.empty())break;
            ++r_.completed_cycles;
            next=std::max(time_,actual+r_.plan.interval_ms);
        }
        if(r_.error.empty()&&r_.completed_cycles==r_.plan.cycles){
            r_.identity_rechecked=identity(LivePhase::final,0);
            for(auto pid:r_.plan.pids)if(!received_.contains(pid))stop("SELECTED_PID_NO_RESPONSE");
        }
        r_.duration_ms=time_;r_.completed=r_.error.empty()&&r_.identity_rechecked&&r_.completed_cycles==r_.plan.cycles;
        return std::move(r_);
    }
};
}
LiveResult replay_live(Protocol p,unsigned source,const LivePlan& plan,std::span<const LiveExchange> trace){
    std::size_t offset=0;
    auto result=Recipe(p,source,plan,[&](LivePhase phase,unsigned cycle,std::string_view cmd,unsigned){
        if(offset>=trace.size()||trace[offset].phase!=phase||trace[offset].cycle!=cycle||
            trace[offset].exchange.command!=cmd)throw std::invalid_argument("Live trace request order mismatch");
        return trace[offset++];
    }).run();
    require(offset==trace.size(),"Extra live trace after terminal recipe");
    return result;
}
LiveResult acquire_live(Elm& elm,unsigned source,const LivePlan& plan,Clock clock,Current current,Wait wait,const std::function<void(const LiveExchange&)>& sink){
    if(!elm.ready()||!elm.headered()||!clock||!current||!wait||!plan.valid()||!valid_source(source))
        throw std::invalid_argument("Configured selected legacy live context required");
    const auto started=clock();
    std::uint64_t previous=started;
    std::size_t retained=0;
    auto time=[&](){
        const auto now=clock();
        if(now<previous||now<started||now-started>3600000)throw TransportError("Live monotonic clock invalid");
        previous=now;return static_cast<unsigned>(now-started);
    };
    auto observe=[&](LivePhase phase,unsigned cycle,std::string_view command,unsigned earliest){
        LiveExchange x{};x.phase=phase;x.cycle=cycle;x.exchange.command=command;
        auto& row=x.exchange;
        x.started_ms=x.ended_ms=time();
        if(!current()){row.outcome=FreezeOutcome::cancelled;return x;}
        if(x.started_ms>=plan.budget_ms){row.outcome=FreezeOutcome::budget;return x;}
        if(command=="WAIT"){
            while(x.ended_ms<earliest&&x.ended_ms<plan.budget_ms&&current()){
                wait(std::min({100u,earliest-x.ended_ms,plan.budget_ms-x.ended_ms}));
                x.ended_ms=time();
            }
            row.outcome=!current()?FreezeOutcome::cancelled:
                x.ended_ms>=plan.budget_ms?FreezeOutcome::budget:FreezeOutcome::received;
        }else if(live_max_raw-retained<32768){
            row.outcome=FreezeOutcome::cancelled;
        }else{
            const auto timeout=std::min(plan.budget_ms-x.started_ms,command=="ATDPN"?2000u:4000u);
            row.attempted=true;
            try{
                row.raw=command=="ATDPN"?elm.probe(timeout):elm.exchange(unhex(command),timeout);
                retained+=row.raw.size();
                x.ended_ms=time();
                if(!current()){row.raw.clear();row.outcome=FreezeOutcome::cancelled;}
                else row.outcome=x.ended_ms>=plan.budget_ms||x.ended_ms-x.started_ms>timeout?
                    FreezeOutcome::late:FreezeOutcome::received;
            }catch(const Cancelled&){row.raw.clear();row.outcome=FreezeOutcome::cancelled;}
            catch(const std::exception&){row.raw.clear();row.outcome=FreezeOutcome::transport;}
            x.ended_ms=time();
            if(row.outcome==FreezeOutcome::received&&(x.ended_ms>=plan.budget_ms||x.ended_ms-x.started_ms>timeout))row.outcome=FreezeOutcome::late;
        }
        row.duration_ms=x.ended_ms-x.started_ms;return x;
    };
    return Recipe(elm.protocol(),source,plan,[&](LivePhase phase,unsigned cycle,std::string_view command,unsigned earliest){
        auto row=observe(phase,cycle,command,earliest);if(sink)sink(row);return row;
    }).run();
}
}
