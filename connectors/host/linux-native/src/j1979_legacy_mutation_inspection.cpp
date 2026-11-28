#include "j1979_legacy_mutation_inspection.hpp"
#include "j1979_legacy_identity.hpp"
#include "j1979_policy.h"
#include "native_json.hpp"
#include <algorithm>
#include <utility>

namespace cd::legacy {
namespace {
using Step=std::pair<std::string_view,std::string_view>;
void require(bool condition,const char* why){if(!condition)throw std::invalid_argument(why);}
struct Recipe {
    std::vector<Step> steps;
    size_t baseline{},intent{};
};
Recipe recipe(MutationOperation operation){
    Recipe p{};
    p.steps={{"SCOPE","0100"},{"PROTOCOL","ATDPN"},{"IDENTITY","0901"},{"IDENTITY","0902"}};
    if(operation==MutationOperation::clear){
        for(auto q:{"03","07","0A","0101"})p.steps.emplace_back("BEFORE",q);
    }else p.steps.emplace_back("SUPPORT","08000000000000");
    p.steps.insert(p.steps.end(),{{"SCOPE","0100"},{"STATIONARY","010D"},{"STATIONARY","010C"}});
    p.baseline=p.steps.size();
    p.steps.insert(p.steps.end(),{{"PROTOCOL","ATDPN"},{"SCOPE","0100"},{"IDENTITY","0901"},{"IDENTITY","0902"}});
    if(operation==MutationOperation::evap)p.steps.emplace_back("SUPPORT","08000000000000");
    p.steps.insert(p.steps.end(),{{"STATIONARY","010D"},{"STATIONARY","010C"}});
    p.intent=p.steps.size();
    p.steps.emplace_back("MUTATION",operation==MutationOperation::clear?"04":"08010000000000");
    if(operation==MutationOperation::clear){
        for(auto q:{"03","07","0A","0101"})p.steps.emplace_back("AFTER",q);
        p.steps.insert(p.steps.end(),{{"SCOPE","0100"},{"IDENTITY","0901"},{"IDENTITY","0902"}});
    }
    return p;
}
using Grouped=std::map<unsigned,std::vector<Data>>;
const std::vector<Data>& at(const Grouped& g,unsigned source){
    static const std::vector<Data> empty;
    const auto i=g.find(source);return i==g.end()?empty:i->second;
}
std::optional<std::vector<std::string>> codes(unsigned mode,const std::vector<Data>& rows){
    if(rows.empty())return {};
    std::vector<std::string> values;
    for(const auto& r:rows){
        if(r.size()!=7||r[0]!=mode+64)return {};
        for(size_t i=1;i<7;i+=2){
            if(!r[i]&&!r[i+1])continue;
            char code[6]{};
            if(!cd_j1979_native_dtc_code(r.data()+i,code))return {};
            if(std::find(values.begin(),values.end(),code)==values.end())values.emplace_back(code);
        }
    }
    return values;
}
bool verified(const MutationPlan& p,const DtcSnapshot& snapshot,unsigned mode){
    return std::all_of(p.sources.begin(),p.sources.end(),[&](unsigned s){return snapshot.at(s).at(mode).has_value();});
}
bool residual(const DtcSnapshot& snapshot){
    for(const auto& [source,modes]:snapshot){
        (void)source;
        for(unsigned mode:{3u,7u})if(modes.at(mode)&&!modes.at(mode)->empty())return true;
    }
    return false;
}
struct ReplayState{
    DtcSnapshot before,after;
    std::map<unsigned,std::string> acks;
    std::set<unsigned> unexpected;
    std::string vin;
    bool blocked{},attempted{},ack{},fresh{};
    bool baseline_admitted{},intent_admitted{};
};
ReplayState replay(const MutationResult& r,const Recipe& p){
    ReplayState v{};
    const auto& scope=r.plan;
    require(r.observations.size()<=p.steps.size(),"Unexpected mutation observation or retry");
    for(auto s:scope.sources){
        v.acks[s]="NOT_TESTED";
        for(unsigned mode:{3u,7u,10u}){v.before[s][mode]={};v.after[s][mode]={};}
    }
    Grouped counts;
    std::optional<unsigned> identity,speed,rpm;
    const auto fresh=[&](unsigned when){
        if(!identity||!speed||!rpm)return false;
        const auto first=std::min({*identity,*speed,*rpm});
        return when>=first&&when-first<5000;
    };
    for(size_t i=0;i<r.observations.size();++i){
        const auto& row=r.observations[i];
        const auto& [stage,command]=p.steps[i];
        require(!v.blocked&&row.stage==stage&&row.command==command,"Unexpected or post-failure legacy operation");
        if(i==p.baseline)require(v.baseline_admitted&&r.baseline_saved,"Recheck without baseline declaration");
        if(stage=="MUTATION"){
            require(v.intent_admitted&&r.intent_saved&&r.baseline_saved&&fresh(row.started_ms),
                "Mutation without fresh prerequisites and intent");
            v.fresh=true;v.attempted=true;
        }
        require(row.started_ms<scope.budget_ms,"Exchange started after budget");
        if(row.error.empty())require(row.ended_ms<scope.budget_ms&&
            row.ended_ms-row.started_ms<=(command=="ATDPN"?2000u:4000u),"Successful exchange exceeded deadline");
        if(command=="ATDPN"){
            require(row.frames.empty(),"Protocol probe contains diagnostic frames");
            if(row.error.empty())require(fixed_protocol(row.raw)==scope.protocol,"Recorded protocol differs");
            v.blocked=!row.error.empty();
            if(v.blocked)continue;
        }else{
            const auto q=unhex(command);
            std::vector<Frame> parsed;
            bool malformed=false;
            try{
                parsed=stage=="MUTATION"?mutation_response_frames(scope,q,row.raw):frames(scope.protocol,command,row.raw);
            }catch(const std::invalid_argument&){malformed=true;}
            if(row.error.empty())require(!malformed&&parsed==row.frames,"Legacy frame evidence differs from raw bytes");
            else if(!row.frames.empty())require(!malformed&&parsed==row.frames,"Faulted frame evidence differs from raw bytes");
            Grouped groups;
            for(const auto& f:row.frames){
                groups[f.source].push_back(f.payload);
                if(!scope.sources.contains(f.source))v.unexpected.insert(f.source);
            }
            if(!row.error.empty()||!v.unexpected.empty()){
                require(!row.error.empty(),"Foreign ECU did not retire operation");
                v.blocked=true;continue;
            }
            if(stage=="SCOPE"){
                v.blocked=groups.size()!=scope.sources.size();
                for(auto s:scope.sources){
                    const auto& rows=at(groups,s);
                    if(rows.size()!=1||rows[0].size()!=6||rows[0][0]!=0x41||rows[0][1]!=0)v.blocked=true;
                }
            }else if(stage=="IDENTITY"){
                if(command=="0901")counts=groups;
                else{
                    for(auto s:scope.sources){
                        const auto& count=at(counts,s);const auto& rows=at(groups,s);
                        if(s!=scope.engine&&count.empty()&&rows.empty())continue;
                        const auto vin=normalized_vin(rows);
                        if(count.size()!=1||!vin_fragment_count(count[0])||!vin||*vin!=scope.vin){
                            v.blocked=true;break;
                        }
                        if(s==scope.engine)v.vin=*vin;
                    }
                    if(v.vin!=scope.vin)v.blocked=true;
                    if(!v.blocked)identity=row.ended_ms;
                }
            }else if(stage=="STATIONARY"){
                const Data expected=command=="010D"?Data{0x41,0x0d,0}:Data{0x41,0x0c,0,0};
                v.blocked=at(groups,scope.engine)!=std::vector<Data>{expected};
                for(const auto& [s,rows]:groups){
                    if(rows.size()!=1){v.blocked=true;continue;}
                    const auto& data=rows[0];
                    if(data==expected)continue;
                    if(s==scope.engine||data.size()!=3||data[0]!=0x7f||data[1]!=1||
                        (data[2]!=0x11&&data[2]!=0x12&&data[2]!=0x31))v.blocked=true;
                }
                if(!v.blocked){
                    if(command=="010D")speed=row.ended_ms;
                    else rpm=row.ended_ms;
                }
            }else if(stage=="BEFORE"||stage=="AFTER"){
                auto& snapshot=stage=="BEFORE"?v.before:v.after;
                if(q.size()==1)for(auto s:scope.sources)snapshot[s][q[0]]=codes(q[0],at(groups,s));
                if(stage=="BEFORE"&&command=="0101"&&!verified(scope,v.before,3))v.blocked=true;
            }else if(stage=="SUPPORT"){
                std::set<unsigned> capable;
                for(const auto& [s,rows]:groups){
                    if(rows.size()!=1||rows[0].size()!=7||rows[0][0]!=0x48||rows[0][1]!=0||rows[0][2]!=0){
                        v.blocked=true;continue;
                    }
                    if(rows[0][3]&128)capable.insert(s);
                }
                if(capable!=std::set<unsigned>{scope.engine})v.blocked=true;
            }else if(stage=="MUTATION"){
                const bool clear=scope.operation==MutationOperation::clear;
                const auto wanted=clear?scope.sources:std::set<unsigned>{scope.engine};
                const Data positive=clear?Data{0x44}:Data{0x48,1,0,0,0,0,0};
                v.ack=true;
                for(auto s:scope.sources){
                    const auto& rows=at(groups,s);
                    if(rows.empty()){if(wanted.contains(s))v.ack=false;continue;}
                    if(!wanted.contains(s))v.ack=false;
                    if(rows==std::vector<Data>{positive})v.acks[s]="ACKNOWLEDGED";
                    else if(rows.size()==1&&rows[0].size()==3&&rows[0][0]==0x7f&&
                        rows[0][1]==(clear?4:8)&&rows[0][2]&&rows[0][2]!=0x78){
                        v.acks[s]="REFUSED_"+hex(Bytes(rows[0]).subspan(2));v.ack=false;
                    }else{v.acks[s]="UNCERTAIN";v.ack=false;}
                }
            }
        }
        if(i+1==p.baseline)v.baseline_admitted=!v.blocked;
        if(i+1==p.intent)v.intent_admitted=!v.blocked;
    }
    return v;
}
void compare(const MutationResult& r,const ReplayState& s){
    require(r.before==s.before&&r.after==s.after&&r.vin==s.vin&&r.unexpected_sources==s.unexpected&&
        r.attempted==s.attempted&&r.complete_ack==s.ack,"Mutation claims differ from replay");
    require(r.acknowledgments==s.acks||(r.observations.empty()&&r.acknowledgments.empty()&&!r.attempted),
        "Acknowledgments differ from replay");
}
void prefix(const MutationResult& prior,const MutationResult& final,size_t length){
    require(prior.plan==final.plan&&prior.observations.size()==length&&final.observations.size()>=length&&
        std::equal(prior.observations.begin(),prior.observations.end(),final.observations.begin()),
        "Separate maintenance journal differs from operation prefix");
}
}
MutationInspection inspect_mutation(std::string_view report,std::string_view baseline,std::string_view intent,std::string_view independent_vin){
    MutationInspection inspection{};
    auto& r=inspection.result;r=parse_mutation_report(report);
    require(r.plan.vin==independent_vin,"Legacy operation differs from independent VIN");
    const auto p=recipe(r.plan.operation);
    const auto s=replay(r,p);compare(r,s);
    require(!r.baseline_saved||s.baseline_admitted,"Invalid saved baseline declaration");
    require(!r.intent_saved||(r.baseline_saved&&s.intent_admitted),"Invalid saved intent declaration");
    if(!baseline.empty()){
        const auto b=parse_mutation_report(baseline);
        prefix(b,r,p.baseline);
        const auto state=replay(b,p);compare(b,state);
        require(b.outcome=="BASELINE"&&b.reason.empty()&&state.baseline_admitted&&
            !b.baseline_saved&&!b.intent_saved&&!b.attempted,"Invalid separate baseline");
        inspection.baseline_correlated=true;
    }
    if(!intent.empty()){
        const auto in=parse_mutation_report(intent);
        prefix(in,r,p.intent);
        const auto state=replay(in,p);compare(in,state);
        require(in.outcome=="INTENT"&&in.reason.empty()&&state.intent_admitted&&in.baseline_saved&&
            !in.intent_saved&&!in.attempted,"Invalid separate intent");
        inspection.intent_correlated=true;
    }
    if(r.attempted)require(inspection.baseline_correlated&&inspection.intent_correlated&&r.baseline_saved&&r.intent_saved,
        "Attempted maintenance requires both independent journal prefixes");
    inspection.fresh_checks=s.fresh;
    inspection.sequence_complete=r.observations.size()==p.steps.size()&&!s.blocked;
    if(!s.attempted)require(r.outcome=="NOT_TESTED","Unattempted command cannot claim success");
    else if(!s.ack)require(r.outcome=="UNCERTAIN","Missing acknowledgment cannot claim success");
    else if(r.plan.operation==MutationOperation::evap){
        require(r.outcome=="ACKNOWLEDGED_PARTIAL"||
            (r.outcome=="ACKNOWLEDGED"&&inspection.sequence_complete&&r.reason=="LEAK_TEST_NOT_PERFORMED"),
            "EVAP acknowledgment exceeds evidence");
    }else if(r.outcome=="ACKNOWLEDGED_READBACK"){
        require(inspection.sequence_complete&&verified(r.plan,s.after,3)&&verified(r.plan,s.after,7)&&
            r.reason==(residual(s.after)?"DTCS_STILL_PRESENT":""),"DTC readback exceeds evidence");
    }else require(r.outcome=="ACKNOWLEDGED_PARTIAL","Invalid acknowledged operation outcome");
    return inspection;
}
std::string inspection_report(const MutationInspection& e,std::string_view report_digest,
    std::string_view baseline_digest,std::string_view intent_digest){
    auto nullable=[](std::string_view s){return s.empty()?std::string("null"):cd::json::quote(s);};
    const auto tf=[](bool b){return b?"true":"false";};
    return std::string("{\"schema\":\"camarodiag-j1979-legacy-mutation-replay-linux-v1\",\"consistent\":true,")+
        "\"physical_vehicle_verified\":false,\"whole_vehicle_coverage_verified\":false,\"operator_consent_verified\":false,"
        "\"durable_before_tx_verified\":false,\"evidence_authenticated\":false,\"leak_test_performed\":false,"
        "\"permits_automatic_retry\":false,\"permits_programming\":false,\"baseline_correlated\":"+
        tf(e.baseline_correlated)+",\"intent_correlated\":"+tf(e.intent_correlated)+
        ",\"fresh_checks_recorded\":"+tf(e.fresh_checks)+",\"sequence_complete\":"+tf(e.sequence_complete)+
        ",\"command_attempted\":"+tf(e.result.attempted)+",\"complete_acknowledgment\":"+tf(e.result.complete_ack)+
        ",\"expected_vin\":"+cd::json::quote(e.result.plan.vin)+",\"outcome\":"+cd::json::quote(e.result.outcome)+
        ",\"reason\":"+nullable(e.result.reason)+",\"report_sha256\":"+nullable(report_digest)+
        ",\"baseline_sha256\":"+nullable(baseline_digest)+",\"intent_sha256\":"+nullable(intent_digest)+"}\n";
}
}
