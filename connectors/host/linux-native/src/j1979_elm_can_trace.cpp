#include "j1979_elm_can_trace.hpp"
#include "j1979_host_maintenance.hpp"
#include <algorithm>
#include <array>
#include <cstdio>
#include <stdexcept>
namespace cd::elmcan {
namespace {
void require(bool condition,const char* reason){if(!condition)throw std::invalid_argument(reason);}
bool ascii(std::string_view s){
    return std::all_of(s.begin(),s.end(),[](unsigned char c){return (c>=32&&c<=126)||c=='\r'||c=='\n'||c=='\t';});
}
std::string id(uint32_t n,bool ext){
    char buf[9]{};std::snprintf(buf,sizeof(buf),ext?"%08X":"%03X",n);return buf;
}
std::vector<std::string> setup(const Scope& scope){
    const auto tx=id(scope.tx,scope.extended()),rx=id(scope.rx,scope.extended());
    return {"ATZ","ATE0","ATL0","ATH1","ATS1","ATD0","ATSP"+std::to_string(scope.profile),
        "ATCAF0","ATSH"+tx,"ATCRA"+rx,"ATFCSH"+tx,"ATFCSD300000","ATFCSM1","ATCFC1","ATDPN"};
}
bool setup_valid(std::string_view command,std::string_view raw,unsigned profile){
    std::vector<std::string> tokens;std::size_t begin=0;
    for(std::size_t i=0;i<=raw.size();++i){
        if(i<raw.size()&&raw[i]!='\r'&&raw[i]!='\n'&&raw[i]!='>')continue;
        auto part=raw.substr(begin,i-begin);begin=i+1;
        while(!part.empty()&&(part.front()==' '||part.front()=='\t'))part.remove_prefix(1);
        while(!part.empty()&&(part.back()==' '||part.back()=='\t'))part.remove_suffix(1);
        if(part.empty())continue;
        std::string line(part);
        for(char& c:line)if(c>='a'&&c<='z')c=char(c-'a'+'A');
        if(line!=command)tokens.push_back(std::move(line));
    }
    if(command=="ATZ"){
        return std::any_of(tokens.begin(),tokens.end(),[](const auto& token){
            return token.find("ELM")!=std::string::npos||token.find("STN")!=std::string::npos||token.find("OBDLINK")!=std::string::npos;
        });
    }
    if(command=="ATDPN")return fixed_profile(raw)==profile;
    return tokens==std::vector<std::string>{"OK"};
}
bool allowed(const Trace& t,Bytes request){
    if(request.empty())return false;
    if(t.operation==TraceOperation::scan||t.operation==TraceOperation::live)
        return j1979_can_allowed_read(request.data(),request.size());
    if(t.operation==TraceOperation::freeze)
        return (request.size()==2&&request[0]==9&&request[1]==2)||
            (request.size()==3&&request[0]==2);
    if(t.operation==TraceOperation::maintenance){
        if(request.size()==1)return request[0]==3||request[0]==4||request[0]==7||request[0]==10;
        return request.size()==2&&((request[0]==9&&request[1]==2)||
            (request[0]==1&&(request[1]==1||request[1]==12||request[1]==13))||
            (request[0]==8&&(request[1]==0||request[1]==1)));
    }
    return false;
}
void check_row(const Observation& row){
    require(row.command.size()>=2&&row.command.size()<=32&&row.raw.size()<=32768&&
        row.error.size()<=512&&row.response.size()<=4095&&row.request.size()<=7&&
        row.duration_ms<=4000&&row.timeout_ms>=1&&row.timeout_ms<=4000&&
        row.status>=-1&&row.status<=1&&ascii(row.raw)&&ascii(row.error),"Invalid ELM trace row");
    require(row.status==-1?!row.error.empty()&&row.response.empty():
        row.error.empty()&&row.duration_ms<row.timeout_ms,"Contradictory ELM transport outcome");
}
bool match(Bytes a,Bytes b){return std::equal(a.begin(),a.end(),b.begin(),b.end());}
}
Replay replay_trace(const Trace& t){
    require(t.target.valid()&&t.vin.size()==17&&j1979_can_valid_vin(t.vin.c_str())&&
        static_cast<unsigned>(t.operation)>=1&&static_cast<unsigned>(t.operation)<=4&&
        t.rows.size()<=trace_max_rows,"Invalid ELM evidence scope");
    Replay result{};
    const auto commands=setup(t.target);
    size_t raw=0;
    unsigned mutation_count=0;
    for(const auto& row:t.rows){
        require(!result.terminal_fault,"Exchange after retired ELM session");
        check_row(row);
        require(row.raw.size()<=trace_max_raw-raw,"ELM trace aggregate limit");
        raw+=row.raw.size();
        if(result.setup_rows<commands.size()){
            require(row.command==commands[result.setup_rows]&&row.request.empty()&&row.response.empty()&&
                row.status!=1,"ELM setup order or payload mismatch");
            ++result.setup_rows;
            if(row.status<0||!setup_valid(row.command,row.raw,t.target.profile)){
                result.terminal_fault=true;++result.faults;
            }else if(result.setup_rows==commands.size())result.configured=true;
            continue;
        }
        require(result.configured&&allowed(t,row.request)&&row.timeout_ms<=2500,"ELM request not admitted");
        Data wire{static_cast<uint8_t>(row.request.size())};
        wire.insert(wire.end(),row.request.begin(),row.request.end());
        require(row.command==legacy::hex(wire),"ELM wire request does not match logical request");
        if(row.request[0]==4||(row.request.size()==2&&row.request[0]==8&&row.request[1]==1)){
            require(t.operation==TraceOperation::maintenance&&++mutation_count==1,"Unexpected ELM mutation or retry");
        }
        ++result.diagnostic_rows;
        if(row.status==-1){result.terminal_fault=true;++result.faults;continue;}
        const auto decoded=decode(row.raw,t.target,row.request);
        if(row.status==1){
            require(decoded.status==DecodeStatus::no_data&&row.response.empty(),"Forged ELM NO DATA result");
            ++result.no_data;
        }else{
            require(decoded.status==DecodeStatus::received&&decoded.payload==row.response,"ELM application response differs from raw frames");
            if(row.response[0]==0x7f)++result.negative;else ++result.positive;
        }
    }
    return result;
}
Replay correlate_freeze(const Trace& t,const cf1_evidence& e){
    require(t.operation==TraceOperation::freeze&&t.vin==e.plan.vin&&t.target.tx==e.plan.tx&&
        t.target.rx==e.plan.rx&&t.target.extended()==e.plan.extended,"ELM/CF1 scope mismatch");
    const auto r=replay_trace(t);
    J1979FreezeScope plan{};
    std::copy_n(reinterpret_cast<const uint8_t*>(e.plan.vin),17,plan.vin);
    plan.frameCount=e.plan.frame_count;plan.pidCount=e.plan.pid_count;
    std::copy_n(e.plan.frames,e.plan.frame_count,plan.frames);std::copy_n(e.plan.pids,e.plan.pid_count,plan.pids);
    require(plan.valid(),"Invalid independent CF1 plan");
    size_t next=r.setup_rows;
    for(size_t i=0;i<e.observation_count;++i){
        const auto& x=e.observations[i];
        if(!x.attempted)continue;
        require(next<t.rows.size(),"Missing ELM evidence for attempted CF1 observation");
        const auto& row=t.rows[next++];
        require(plan.allows(row.request.data(),row.request.size())&&match(row.request,{x.request,x.request_length}),
            "ELM/CF1 request mismatch");
        if(x.outcome==1||x.outcome==6){
            require(row.status==0&&match(row.response,{x.response,x.response_length}),"ELM/CF1 reply mismatch");
        }else if(x.outcome==2)require(row.status==1,"ELM/CF1 timeout mismatch");
        else require(x.outcome==3||x.outcome==5,"Unsupported attempted CF1 outcome");
        require(row.duration_ms<=x.duration_ms,"ELM duration exceeds enclosing CF1 observation");
    }
    require(next==t.rows.size(),"Unmatched ELM exchanges after CF1");
    return r;
}
Replay correlate_maintenance(const Trace& t,const cm1::Inspection& e){
    require(t.operation==TraceOperation::maintenance&&t.vin==e.scope.vin&&t.target.tx==e.scope.tx&&
        t.target.rx==e.scope.rx&&t.target.extended()==e.scope.extended,"ELM/CM1 scope mismatch");
    const auto r=replay_trace(t);
    size_t next=r.setup_rows;
    for(const auto& x:e.observations){
        require(next<t.rows.size(),"Missing ELM evidence for CM1 observation");
        const auto& row=t.rows[next++];
        require(match(row.request,x.request)&&row.duration_ms<=x.end-x.start&&row.timeout_ms<=x.timeout,
            "ELM/CM1 request or timing mismatch");
        if(x.status==1||x.status==2)require(row.status==0&&match(row.response,x.response),"ELM/CM1 reply mismatch");
        else if(x.status==3)require(row.status==1,"ELM/CM1 timeout mismatch");
        else require(x.status==4,"Invalid CM1 observation");
    }
    // A journal without terminal may end before the last raw ELM exchange.
    // Never promote that unmatched suffix to a verified operation outcome.
    require(next==t.rows.size(),"Unmatched ELM exchanges after CM1");
    return r;
}
}
