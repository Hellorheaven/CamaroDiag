#include "j1979_host_maintenance.hpp"
#include <algorithm>
#include <array>
#include <stdexcept>
namespace cd::hostmaint {
namespace {
using Scratch=J1979CanReadExchange::Result;
using State=J1979CanReadExchange::State;
}
class ElmOwner final:public Campaign::Owner,public Campaign::Link {
    const Scope scope_;
    elmcan::Session session_;
    legacy::Link& transport_;
    Journal& journal_;
    Current current_;
    bool used_{},sent_{},retired_{},latched_{};
    bool authorized_{};
    void exchange(const uint8_t* q,size_t n,uint32_t timeout,Scratch& result,bool mutation){
        result.state=State::Fault;result.responseSize=0;result.elapsedMs=0;
        if(!active()||!timeout||timeout>Campaign::Sequence::MaxExchangeMs||
            !(mutation?scope_.mutation(q,n):scope_.read(q,n)))return;
        const auto started=legacy::monotonic_ms();
        if(!started)return;
        if(mutation){
            if(sent_||!journal_.consumeIntent(scope_)||!active())return;
            sent_=true;
        }
        const auto ready=legacy::monotonic_ms();
        if(ready<started||ready-started>=timeout)return;
        size_t size=sizeof(result.response);
        // This private entry point is not accessible through generic scan,
        // live, freeze or raw commands. The shared recipe controls every call.
        const int rc=session_.exchange_admitted({q,n},result.response,size,
            timeout-static_cast<unsigned>(ready-started),true);
        const auto ended=legacy::monotonic_ms();
        if(ended<started||ended-started>timeout||!active())return;
        result.elapsedMs=static_cast<uint32_t>(ended-started);
        if(rc==1&&!mutation){result.state=State::Timeout;return;}
        if(rc!=0||!size||size>Campaign::MaxReply)return;
        const auto shape=scope_.classify(q,n,result.response,size);
        if(shape==Scope::Shape::Positive)result.state=State::Positive;
        else if(shape==Scope::Shape::Negative)result.state=State::Negative;
        else return;
        result.responseSize=size;
    }
public:
    ElmOwner(const Scope& scope,elmcan::Scope target,legacy::Link& link,Journal& journal,Current current,bool approved)
      :scope_(scope),session_(link,target,current),transport_(link),journal_(journal),current_(std::move(current)),authorized_(approved){
        if(!scope_.valid()||!target.valid()||target.tx!=scope_.tx||target.rx!=scope_.rx||
            target.extended()!=scope_.extended||!current_||!approved)throw std::invalid_argument("Invalid ELM primary maintenance scope");
    }
    bool approved(const Scope& scope)override{return authorized_&&!retired_&&current_()&&scope_.same(scope);}
    bool start(const Scope& scope)override{
        if(used_||!approved(scope))return false;
        used_=true;
        try{session_.configure();return session_.current();}catch(const std::exception&){return false;}
    }
    bool stopAndVerify()override{
        retired_=true;
        // Local descriptor release is the only claim. No hardware
        // transceiver shutdown or physical vehicle state is attested.
        try{return transport_.finish();}catch(const std::exception&){return false;}
    }
    void latchRecovery()override{latched_=true;retired_=true;}
    bool active()override{return approved(scope_)&&session_.current()&&legacy::monotonic_ms()!=0;}
    uint32_t monotonicMs()override{return static_cast<uint32_t>(legacy::monotonic_ms());}
    bool matches(const Scope& scope)const override{return scope_.same(scope);}
    void read(const uint8_t* q,size_t n,uint32_t timeout,Scratch& result)override{
        exchange(q,n,timeout,result,false);
    }
    void command(const Scope& scope,uint32_t timeout,Scratch& result)override{
        result.state=State::Fault;result.responseSize=0;result.elapsedMs=0;
        if(!scope_.same(scope))return;
        const std::array<uint8_t,2> q=scope_.operation==Scope::Operation::Clear?
            std::array<uint8_t,2>{4,0}:std::array<uint8_t,2>{8,1};
        exchange(q.data(),scope_.operation==Scope::Operation::Clear?1:2,timeout,result,true);
    }
    bool latched()const{return latched_;}
    const auto& observations()const{return session_.evidence();}
};
ElmResult run_elm(const Scope& scope,elmcan::Scope target,legacy::Link& link,Current current,
                  const char* directory,const char* name,bool warning,bool approved){
    if(!scope.valid()||!target.valid()||target.tx!=scope.tx||target.rx!=scope.rx||
        target.extended()!=scope.extended||!warning||!approved||!current||!current())
        throw std::invalid_argument("Explicit fixed ELM primary maintenance approval required");
    ElmResult result{};auto& r=result.result;r.evidence.scope=scope;
    FileStorage storage(directory,name);r.journal_created=true;
    Journal::Scratch scratch{};
    Journal journal(storage,scratch);
    ElmOwner owner(scope,target,link,journal,current,approved);
    Scratch exchange{};
    Campaign::run(owner,journal,owner,scope,r.evidence,exchange);
    r.local_socket_closed=false; // serial, not SocketCAN
    r.recovery_latched=storage.latched()||owner.latched()||r.evidence.recoveryRequired;
    if(!r.evidence.terminalPersisted)r.error="TERMINAL_NOT_PERSISTED";
    result.observations=owner.observations();
    return result;
}
}
