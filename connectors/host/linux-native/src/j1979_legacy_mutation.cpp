#include "j1979_legacy_mutation.hpp"
#include "j1979_legacy_identity.hpp"
#include "j1979_policy.h"
#include <algorithm>
#include <utility>

namespace cd::legacy {
namespace {
using Read=std::function<std::string(Bytes,unsigned)>;
using Probe=std::function<std::string(unsigned)>;
using Transmit=std::function<std::string(unsigned)>;
std::vector<Data> for_source(std::span<const Frame> rows,unsigned source){
    std::vector<Data> result;
    for(const auto& frame:rows)if(frame.source==source)result.push_back(frame.payload);
    return result;
}
std::optional<std::vector<std::string>> dtcs(unsigned mode,std::span<const Data> rows){
    if(rows.empty())return {};
    std::vector<std::string> codes;
    for(const auto& r:rows){
        if(r.size()!=7||r[0]!=mode+64)return {};
        for(unsigned i=1;i<7;i+=2){
            if(!r[i]&&!r[i+1])continue;
            char code[6]{};
            if(!cd_j1979_native_dtc_code(r.data()+i,code))return {};
            if(std::find(codes.begin(),codes.end(),code)==codes.end())codes.emplace_back(code);
        }
    }
    return codes;
}
class Recipe {
    MutationResult r_;
    Read read_;
    Probe probe_;
    Transmit transmit_;
    Clock clock_;
    Current current_;
    MutationJournal journal_;
    std::uint64_t started_,previous_;
    std::size_t retained_{};
    std::optional<unsigned> identity_at_,speed_at_,rpm_at_;
    unsigned freshness(unsigned now)const{
        if(!identity_at_||!speed_at_||!rpm_at_)return 0;
        const auto oldest=std::min({*identity_at_,*speed_at_,*rpm_at_});
        return now>=oldest&&now-oldest<5000?5000-(now-oldest):0;
    }
    unsigned elapsed(){
        const auto n=clock_();
        if(n<previous_||n-started_>3600000)throw TransportError("CLOCK_INVALID");
        previous_=n;r_.elapsed_ms=static_cast<unsigned>(n-started_);return r_.elapsed_ms;
    }
    unsigned remaining(){
        if(!current_())throw Cancelled();
        const auto n=elapsed();
        if(n>=r_.plan.budget_ms)throw TransportError("BUDGET_EXHAUSTED");
        return r_.plan.budget_ms-n;
    }
    MutationResult finish(std::string outcome,std::string reason={}){
        r_.outcome=std::move(outcome);r_.reason=std::move(reason);
        try{elapsed();}catch(const std::exception&){}
        return std::move(r_);
    }
    std::vector<Frame> exchange(std::string stage,Data q,bool mutation=false){
        if(r_.observations.size()>=40||retained_>1024*1024-32768)
            throw TransportError("EVIDENCE_LIMIT");
        unsigned timeout=std::min(4000u,remaining());
        MutationObservation row{};row.stage=std::move(stage);row.command=hex(q);row.started_ms=elapsed();
        if(!mutation&&!read_allowed(q))throw std::logic_error("Non-read prerequisite");
        if(mutation){
            timeout=std::min(timeout,freshness(row.started_ms));
            if(!timeout)throw TransportError("STALE_STATIONARY_OR_IDENTITY_EVIDENCE");
        }
        row.attempted=true;
        try{
            if(mutation){
                // Persistent intent precedes any possibly transmitted command.
                if(!r_.baseline_saved||!r_.intent_saved||r_.attempted)throw TransportError("MUTATION_NOT_ADMITTED");
                r_.attempted=true;
                row.raw=transmit_(timeout);
            }else row.raw=read_(q,timeout);
            row.ended_ms=elapsed();
            if(row.raw.size()>32768)throw TransportError("RESPONSE_LIMIT");
            retained_+=row.raw.size();
            (void)remaining();
            if(row.ended_ms-row.started_ms>timeout)throw TransportError("RESPONSE_LATE");
            row.frames=mutation?mutation_response_frames(r_.plan,q,row.raw):frames(r_.plan.protocol,row.command,row.raw);
            for(const auto& f:row.frames)if(!r_.plan.sources.contains(f.source))r_.unexpected_sources.insert(f.source);
            if(!r_.unexpected_sources.empty())throw TransportError("UNEXPECTED_RESPONSE_SOURCE");
            auto result=row.frames;r_.observations.push_back(std::move(row));return result;
        }catch(const std::exception& e){
            row.error=e.what();try{row.ended_ms=elapsed();}catch(const std::exception&){}
            r_.observations.push_back(std::move(row));throw;
        }
    }
    bool protocol(){
        const unsigned timeout=std::min(2000u,remaining());
        MutationObservation row{};row.stage="PROTOCOL";row.command="ATDPN";row.started_ms=elapsed();row.attempted=true;
        try{
            row.raw=probe_(timeout);row.ended_ms=elapsed();
            if(row.raw.size()>4096)throw TransportError("PROBE_LIMIT");
            (void)remaining();
            const bool ok=row.ended_ms-row.started_ms<=timeout&&fixed_protocol(row.raw)==r_.plan.protocol;
            if(!ok)row.error="PROTOCOL_MISMATCH";
            r_.observations.push_back(std::move(row));return ok;
        }catch(const std::exception& e){
            row.error=e.what();try{row.ended_ms=elapsed();}catch(const std::exception&){}
            r_.observations.push_back(std::move(row));throw;
        }
    }
    bool scope(){
        const auto rows=exchange("SCOPE",{1,0});
        for(auto s:r_.plan.sources){
            const auto selected=for_source(rows,s);
            if(selected.size()!=1||selected[0].size()!=6||selected[0][0]!=0x41||selected[0][1]!=0)return false;
        }
        return true;
    }
    bool identity(){
        const auto counts=exchange("IDENTITY",{9,1});
        const auto fragments=exchange("IDENTITY",{9,2});
        for(auto s:r_.plan.sources){
            const auto count=for_source(counts,s),rows=for_source(fragments,s);
            if(s!=r_.plan.engine&&count.empty()&&rows.empty())continue;
            if(count.size()!=1||!vin_fragment_count(count[0]))return false;
            const auto vin=normalized_vin(rows);
            if(!vin||*vin!=r_.plan.vin)return false;
            if(s==r_.plan.engine)r_.vin=*vin;
        }
        const bool matched=r_.vin==r_.plan.vin;
        if(matched)identity_at_=r_.observations.back().ended_ms;
        return matched;
    }
    bool stationary(Data q,Data expected){
        const auto pid=q.at(1);
        const auto rows=exchange("STATIONARY",std::move(q));
        if(for_source(rows,r_.plan.engine)!=std::vector<Data>{expected})return false;
        std::set<unsigned> seen;
        for(const auto& row:rows){
            if(!seen.insert(row.source).second)return false;
            if(row.payload==expected)continue;
            const auto& p=row.payload;
            if(row.source==r_.plan.engine||p.size()!=3||p[0]!=0x7f||p[1]!=1||
                (p[2]!=0x11&&p[2]!=0x12&&p[2]!=0x31))return false;
        }
        if(pid==0x0d)speed_at_=r_.observations.back().ended_ms;
        else rpm_at_=r_.observations.back().ended_ms;
        return true;
    }
    bool parked(){
        return stationary({1,0x0d},{0x41,0x0d,0})&&stationary({1,0x0c},{0x41,0x0c,0,0});
    }
    void snapshot(std::string stage,DtcSnapshot& target){
        for(unsigned mode:{3u,7u,10u}){
            const auto rows=exchange(stage,{static_cast<std::uint8_t>(mode)});
            for(auto s:r_.plan.sources)target[s][mode]=dtcs(mode,for_source(rows,s));
        }
        (void)exchange(std::move(stage),{1,1});
    }
    bool verified(const DtcSnapshot& d,unsigned mode)const{
        for(auto s:r_.plan.sources){
            auto a=d.find(s);if(a==d.end())return false;
            auto b=a->second.find(mode);if(b==a->second.end()||!b->second)return false;
        }
        return true;
    }
    bool residual()const{
        for(const auto& [source,modes]:r_.after){
            (void)source;
            for(unsigned mode:{3u,7u}){
                const auto& codes=modes.at(mode);if(codes&&!codes->empty())return true;
            }
        }
        return false;
    }
    bool support(){
        const auto rows=exchange("SUPPORT",{8,0,0,0,0,0,0});
        std::set<unsigned> capable;
        for(auto s:r_.plan.sources){
            const auto selected=for_source(rows,s);
            if(selected.empty())continue;
            if(selected.size()!=1||selected[0].size()!=7||selected[0][0]!=0x48||
                selected[0][1]!=0||selected[0][2]!=0)return false;
            if(selected[0][3]&0x80)capable.insert(s);
        }
        return capable==std::set<unsigned>{r_.plan.engine};
    }
    void ack(const std::vector<Frame>& rows){
        const bool clear=r_.plan.operation==MutationOperation::clear;
        const auto expected=clear?r_.plan.sources:std::set<unsigned>{r_.plan.engine};
        const Data positive=clear?Data{0x44}:Data{0x48,1,0,0,0,0,0};
        bool complete=true;
        for(auto s:r_.plan.sources){
            const auto selected=for_source(rows,s);
            if(selected.empty()){if(expected.contains(s))complete=false;continue;}
            if(!expected.contains(s))complete=false;
            if(selected==std::vector<Data>{positive})r_.acknowledgments[s]="ACKNOWLEDGED";
            else if(selected.size()==1&&selected[0].size()==3&&selected[0][0]==0x7f&&
                selected[0][1]==(clear?4:8)&&selected[0][2]&&selected[0][2]!=0x78){
                r_.acknowledgments[s]="REFUSED_"+hex(Bytes(selected[0]).subspan(2));complete=false;
            }else{r_.acknowledgments[s]="UNCERTAIN";complete=false;}
        }
        r_.complete_ack=complete;
    }
public:
    Recipe(const MutationPlan& p,Read read,Probe probe,Transmit transmit,Clock clock,Current current,MutationJournal journal)
      :read_(std::move(read)),probe_(std::move(probe)),transmit_(std::move(transmit)),clock_(std::move(clock)),
       current_(std::move(current)),journal_(std::move(journal)),started_(clock_()),previous_(started_){
        r_.plan=p;
        for(auto s:p.sources){
            r_.acknowledgments[s]="NOT_TESTED";
            for(unsigned mode:{3u,7u,10u}){r_.before[s][mode]={};r_.after[s][mode]={};}
        }
    }
    MutationResult run(){
        try{
            if(!scope()||!protocol())return finish("NOT_TESTED","RESPONDER_OR_PROTOCOL_UNVERIFIED");
            if(!identity())return finish("NOT_TESTED","VIN_UNVERIFIED_OR_MISMATCH");
            const bool clear=r_.plan.operation==MutationOperation::clear;
            if(clear){
                snapshot("BEFORE",r_.before);
                if(!verified(r_.before,3))return finish("NOT_TESTED","BASELINE_UNVERIFIED");
            }else if(!support())return finish("NOT_TESTED","EVAP_TARGET_UNVERIFIED");
            if(!scope()||!parked())return finish("NOT_TESTED","STATIONARY_OR_SCOPE_UNVERIFIED");
            journal_("BASELINE",r_);r_.baseline_saved=true;
            if(!protocol()||!scope()||!identity()||!parked())return finish("NOT_TESTED","CONTEXT_CHANGED_BEFORE_INTENT");
            if(!freshness(elapsed()))return finish("NOT_TESTED","STALE_STATIONARY_OR_IDENTITY_EVIDENCE");
            (void)remaining();
            journal_("INTENT",r_);r_.intent_saved=true;
            // After the durable intent a revocation is not silently ignored.
            (void)remaining();
            const auto rows=exchange("MUTATION",clear?Data{4}:Data{8,1,0,0,0,0,0},true);
            ack(rows);
            if(clear){
                snapshot("AFTER",r_.after);
                if(r_.complete_ack&&verified(r_.after,3)){
                    if(!verified(r_.after,7))return finish("ACKNOWLEDGED_PARTIAL","PENDING_READBACK_UNAVAILABLE");
                    if(residual())return finish("ACKNOWLEDGED_READBACK","DTCS_STILL_PRESENT");
                    return finish("ACKNOWLEDGED_READBACK");
                }
            }else if(r_.complete_ack)return finish("ACKNOWLEDGED","LEAK_TEST_NOT_PERFORMED");
            return finish(r_.complete_ack?"ACKNOWLEDGED_PARTIAL":"UNCERTAIN","MISSING_OR_CONFLICTING_EVIDENCE");
        }catch(const std::exception& e){
            return finish(r_.complete_ack?"ACKNOWLEDGED_PARTIAL":r_.attempted?"UNCERTAIN":"NOT_TESTED",e.what());
        }
    }
};
}
MutationResult MutationSession::run(const MutationPlan& p,bool warning,bool confirmed,Clock clock,Current current,MutationJournal journal){
    if(used_||elm_.maintenance_used_||!p.valid()||!warning||!confirmed||!clock||!current||!journal||
        !elm_.ready()||!elm_.headered()||elm_.protocol()!=p.protocol||!current())
        throw std::invalid_argument("Single-use maintenance requires explicit consent, scope and durable journal");
    used_=true;elm_.maintenance_used_=true;
    bool sent=false;
    return Recipe(p,[&](Bytes q,unsigned t){return elm_.exchange(q,t);},
        [&](unsigned t){return elm_.probe(t);},
        [&](unsigned t){
            if(sent||!elm_.ready_||!elm_.headered_||!current())throw TransportError("MUTATION_RETIRED");
            sent=true;
            return elm_.command(p.operation==MutationOperation::clear?"04":"08010000000000",t);
        },clock,current,journal).run();
}
}
