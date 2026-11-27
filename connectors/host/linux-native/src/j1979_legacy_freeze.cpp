#include "j1979_legacy_freeze.hpp"
#include "j1979_measurement.h"
#include <algorithm>
#include <array>
#include <stdexcept>
#include <utility>
namespace cd::legacy {
namespace {
using Validator=std::function<bool(const Data&)>;
bool starts(Bytes data,Bytes prefix){
    return data.size()>=prefix.size()&&std::equal(prefix.begin(),prefix.end(),data.begin());
}
bool negative(const Data& data,unsigned mode){
    return data.size()==3&&data[0]==0x7f&&data[1]==mode&&data[2]&&data[2]!=0x78;
}
bool valid_vin(std::string_view vin){
    return vin.size()==17&&std::all_of(vin.begin(),vin.end(),[](char c){
        return (c>='0'&&c<='9')||(c>='A'&&c<='Z'&&c!='I'&&c!='O'&&c!='Q');
    });
}
std::string_view outcome_error(FreezeOutcome outcome){
    switch(outcome){
    case FreezeOutcome::received:case FreezeOutcome::timeout:return "";
    case FreezeOutcome::transport:return "TRANSPORT_ERROR";
    case FreezeOutcome::budget:case FreezeOutcome::late:return "BUDGET_EXHAUSTED";
    case FreezeOutcome::cancelled:return "CONTEXT_CHANGED";
    }
    throw std::invalid_argument("Invalid freeze exchange outcome");
}
class Recipe{
    FreezeResult r_;
    const FreezeRead& observe_;
    std::size_t bytes_{};
    std::uint64_t duration_{};
    void require(bool condition,const char* error){if(!condition)throw std::invalid_argument(error);}
    FreezeExchange exchange(std::string command){
        require(r_.error.empty(),"Exchange after terminal state");
        require(r_.trace.size()<freeze_max_trace,"Freeze trace limit");
        auto observation=observe_(command);
        require(observation.command==command,"Legacy freeze request order mismatch");
        const auto state=observation.outcome;
        require(static_cast<unsigned>(state)>=1&&static_cast<unsigned>(state)<=6&&
            observation.raw.size()<=32768&&observation.raw.size()<=freeze_max_raw-bytes_&&
            observation.duration_ms<=3600000,"Invalid legacy freeze transport observation");
        require((state!=FreezeOutcome::received&&state!=FreezeOutcome::late)||observation.attempted,
            "Received data without exchange attempt");
        require(state!=FreezeOutcome::budget||!observation.attempted,"Budget exhausted after transmission");
        require((state!=FreezeOutcome::timeout&&state!=FreezeOutcome::transport)||observation.attempted,
            "Transport failure without attempt");
        require(state==FreezeOutcome::received||state==FreezeOutcome::late||observation.raw.empty(),
            "Raw response for non-received exchange");
        bytes_+=observation.raw.size();duration_+=observation.duration_ms;
        require(duration_<=3600000,"Freeze trace duration limit");
        // Duration of exchanges is a lower bound; gaps outside exchanges are not attested.
        require(state!=FreezeOutcome::received||duration_<r_.plan.budget_ms,"Received reply exceeds campaign budget");
        if(const auto error=outcome_error(state);!error.empty())r_.error=error;
        r_.trace.push_back(observation);
        return observation;
    }
    std::vector<Data> selected(const FreezeExchange& exchange,Bytes request){
        if(exchange.outcome!=FreezeOutcome::received)return {};
        std::vector<Frame> parsed;
        try{parsed=freeze_frames(r_.protocol,r_.plan,request,exchange.raw);}
        catch(const std::invalid_argument&){r_.error="MALFORMED_LEGACY_RESPONSE";return {};}
        std::vector<Data> result;
        for(const auto& frame:parsed){
            if(frame.source==r_.source)result.push_back(frame.payload);
            else r_.other_sources.insert(frame.source);
        }
        return result;
    }
    std::vector<Data> query(Data q,const Validator& valid,bool single){
        require(r_.plan.allows(q),"Logical request outside approved freeze scope");
        auto row=exchange(hex(q));
        const auto parsed=selected(row,q);
        if(parsed.empty()||(single&&parsed.size()!=1)||
            !std::all_of(parsed.begin(),parsed.end(),valid))return {};
        return parsed;
    }
    void identity(FreezeStep& step){
        const auto page=query({1,0},[](const Data& r){return r.size()==6&&r[0]==0x41&&r[1]==0;},true);
        if(page.empty()||!r_.error.empty())return;
        auto probe=exchange("ATDPN");
        if(probe.outcome!=FreezeOutcome::received)return;
        if(fixed_protocol(probe.raw)!=r_.protocol){r_.error="PROTOCOL_MISMATCH";return;}
        const auto support=query({9,0},[](const Data& r){
            return r.size()==7&&r[0]==0x49&&r[1]==0&&r[2]==1&&(r[3]&0xc0)==0xc0;
        },true);
        if(support.empty()||!r_.error.empty())return;
        const auto count=query({9,1},[](const Data& r){return r==Data{0x49,1,5};},true);
        if(count.empty()||!r_.error.empty())return;
        const auto rows=query({9,2},[](const Data& r){return r.size()==7&&r[0]==0x49&&r[1]==2;},false);
        if(rows.size()!=5||!r_.error.empty())return;
        Data vin;
        for(unsigned i=0;i<5;++i){
            if(rows[i][2]!=i+1)return;
            vin.insert(vin.end(),rows[i].begin()+3,rows[i].end());
        }
        if(vin.size()!=20||vin[0]||vin[1]||vin[2]||
            !valid_vin(std::string_view(reinterpret_cast<const char*>(vin.data()+3),17)))return;
        step.response={0x49,2,1};
        step.response.insert(step.response.end(),vin.begin()+3,vin.end());
        step.status="PASSED";step.detail.clear();
    }
    void frame_data(FreezeStep& step){
        const auto x=exchange(hex(step.request));
        const auto rows=selected(x,step.request);
        if(rows.empty()){step.detail="NO_SELECTED_RESPONSE";return;}
        if(rows.size()!=1){step.status="FAILED";step.detail="AMBIGUOUS_SELECTED_RESPONSE";return;}
        step.response=rows.front();
        const auto& q=step.request;const auto& r=step.response;
        if(negative(r,q[0])){step.detail="NRC_"+hex(Bytes(r).subspan(2));return;}
        const Data expected{0x42,q[1],q[2]};
        if(!starts(r,expected)||r.size()<=3){
            step.status="FAILED";step.detail="WRONG_SERVICE_PID_OR_FRAME";return;
        }
        if(q[1]%32==0){
            if(r.size()==7&&(q[1]!=224||!(r.back()&1)))step.status="PASSED";
            else {step.status="FAILED";step.detail="INVALID_SUPPORT_BITMAP";}
        }else if(q[1]==2){
            if(r.size()==5)step.status="PASSED";
            else {step.status="FAILED";step.detail="INVALID_TRIGGER_DTC";}
        }else{
            if(q[2]==0){
                const unsigned width=j1979_measurement_width(q[1]);
                const bool trim=q[1]>=6&&q[1]<=9;
                if(width&&r.size()!=3+width&&!(trim&&r.size()==5)){
                    step.status="FAILED";step.detail="INVALID_PID_DATA_WIDTH";return;
                }
                j1979_measurement m{};
                // A known scalar definition must have its exact width.
                // No current Mode 01 context is applied to frozen measurements.
                if(j1979_measurement_decode(q.data(),q.size(),r.data(),r.size(),&m)&&m.interpreted&&!m.context_required&&!trim){
                    step.status="PASSED";return;
                }
            }
            step.status="RAW";step.detail="STORED_RAW_VALUE";
        }
    }
    const FreezeStep* read(Data q){
        if(!r_.error.empty())return nullptr;
        require(r_.steps.size()<freeze_max_steps&&r_.plan.allows(q),"Freeze logical step limit or scope");
        FreezeStep step{};step.request=std::move(q);step.status="NOT_TESTED";
        step.first_exchange=static_cast<unsigned>(r_.trace.size());
        if(step.request==Data{9,2})identity(step);
        else frame_data(step);
        step.exchange_count=static_cast<unsigned>(r_.trace.size())-step.first_exchange;
        if(step.exchange_count)step.outcome=r_.trace.back().outcome;
        if(!r_.error.empty()){
            step.status="NOT_TESTED";step.detail=r_.error;step.response.clear();
        }
        r_.steps.push_back(std::move(step));return &r_.steps.back();
    }
    FrozenFrame frame(unsigned frame_number){
        FrozenFrame f{};f.frame=frame_number;
        const auto q=[&](unsigned pid){return Data{2,static_cast<std::uint8_t>(pid),static_cast<std::uint8_t>(frame_number)};};
        bool more=false;
        auto page=[&](unsigned base){
            const auto* step=read(q(base));
            if(!step||step->status!="PASSED")return false;
            const auto& r=step->response;
            f.pages.push_back(base);
            for(unsigned i=0;i<31;++i)if(r[3+i/8]&(0x80u>>(i%8)))f.advertised.insert(base+i+1);
            more=(r[6]&1)!=0;return true;
        };
        if(!page(0)){f.detail="PID00_UNVERIFIED";return f;}
        if(!f.advertised.contains(2)){f.detail="TRIGGER_NOT_ADVERTISED";return f;}
        auto trigger=read(q(2));
        if(!trigger||trigger->status!="PASSED"){f.detail="TRIGGER_UNVERIFIED";return f;}
        f.trigger=std::uint16_t(unsigned(trigger->response[3])*256+trigger->response[4]);
        if(*f.trigger==0){f.state="ABSENT";f.detail="ZERO_TRIGGER_DTC";return f;}
        f.inventory_complete=!more;
        for(unsigned base=32;more&&base<=224&&r_.error.empty();base+=32){
            if(!page(base))break;
            f.inventory_complete=!more;
        }
        unsigned failures=0;
        for(unsigned pid:f.advertised){
            if(pid==2||(!r_.plan.pids.empty()&&
                std::find(r_.plan.pids.begin(),r_.plan.pids.end(),pid)==r_.plan.pids.end()))continue;
            auto step=read(q(pid));
            if(!step)break;
            f.measurement_steps.push_back(static_cast<unsigned>(r_.steps.size()-1));
            if(step->exchange_count&&r_.trace[step->first_exchange].attempted)f.read.insert(pid);
            if(step->status!="PASSED"&&step->status!="RAW")++failures;
        }
        for(auto pid:r_.plan.pids){
            if(!f.advertised.contains(pid)&&f.inventory_complete)f.unadvertised.insert(pid);
            else if(!f.read.contains(pid))f.unread.insert(pid);
        }
        if(!r_.error.empty()){f.state="PARTIAL";f.detail=r_.error;return f;}
        auto final=read(q(2));
        if(final&&final->status=="PASSED")f.final_trigger=std::uint16_t(unsigned(final->response[3])*256+final->response[4]);
        f.trigger_stable=f.final_trigger&&*f.final_trigger==*f.trigger;
        if(!f.trigger_stable){
            f.state=f.final_trigger?"CHANGED":"PARTIAL";
            f.detail=f.final_trigger?"TRIGGER_CHANGED":"FINAL_TRIGGER_UNVERIFIED";
        }else if(!f.inventory_complete||failures){f.state="PARTIAL";f.detail="INCOMPLETE_INVENTORY_OR_VALUES";}
        else f.state="COMPLETE";
        return f;
    }
    std::string vin(){
        const auto* step=read({9,2});
        if(!step||step->status!="PASSED"||step->response.size()!=20)return {};
        return {step->response.begin()+3,step->response.end()};
    }
public:
    Recipe(Protocol p,unsigned source,const FreezePlan& plan,const FreezeRead& observe):observe_(observe){
        require(valid_protocol(p)&&valid_source(source)&&plan.valid()&&bool(observe),"Invalid freeze scope");
        r_.protocol=p;r_.source=source;r_.plan=plan;
    }
    FreezeResult run(){
        r_.initial_vin=vin();
        if(r_.error.empty()&&r_.initial_vin!=r_.plan.vin)
            r_.error=r_.initial_vin.empty()?"VIN_UNVERIFIED":"VIN_MISMATCH";
        for(auto number:r_.plan.frames){
            if(!r_.error.empty())break;
            r_.frames.push_back(frame(number));
        }
        if(r_.error.empty()){
            r_.final_vin=vin();
            if(r_.error.empty()&&r_.final_vin!=r_.plan.vin)
                r_.error=r_.final_vin.empty()?"FINAL_VIN_UNVERIFIED":"FINAL_VIN_MISMATCH";
        }
        r_.completed=r_.error.empty()&&r_.frames.size()==r_.plan.frames.size();
        r_.identity_rechecked=r_.completed&&r_.initial_vin==r_.final_vin;
        r_.all_frames_stable=r_.completed&&std::all_of(r_.frames.begin(),r_.frames.end(),[](const auto& f){
            return f.state=="COMPLETE"||f.state=="ABSENT";
        });
        return std::move(r_);
    }
};
}
FreezeResult freeze_recipe(Protocol p,unsigned source,const FreezePlan& plan,const FreezeRead& observe){
    return Recipe(p,source,plan,observe).run();
}
FreezeResult replay_freeze(Protocol p,unsigned source,const FreezePlan& plan,std::span<const FreezeExchange> trace){
    std::size_t index=0;
    auto result=freeze_recipe(p,source,plan,[&](std::string_view command){
        if(index>=trace.size()||trace[index].command!=command)
            throw std::invalid_argument("Missing or reordered legacy freeze exchange");
        return trace[index++];
    });
    if(index!=trace.size())throw std::invalid_argument("Extra legacy freeze exchanges after recipe");
    return result;
}
}
