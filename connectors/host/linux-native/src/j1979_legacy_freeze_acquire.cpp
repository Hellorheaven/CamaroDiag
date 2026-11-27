#include "j1979_legacy_freeze.hpp"
#include <algorithm>
#include <limits>
namespace cd::legacy {
FreezeResult acquire_freeze(Elm& elm,unsigned source,const FreezePlan& plan,Clock clock,Current current){
    if(!elm.ready()||!elm.headered()||!plan.valid()||!valid_source(source)||!clock||!current)
        throw std::invalid_argument("Explicit configured legacy freeze scope required");
    const auto started=clock();
    std::uint64_t previous=started;
    std::size_t retained=0;
    const auto elapsed=[&](){
        const auto now=clock();
        if(now<previous||now<started)throw TransportError("Monotonic clock regressed");
        previous=now;return now-started;
    };
    return freeze_recipe(elm.protocol(),source,plan,[&](std::string_view command){
        FreezeExchange x{};x.command=command;
        if(!current()){x.outcome=FreezeOutcome::cancelled;return x;}
        std::uint64_t begin=0;
        try{
            begin=elapsed();
        }catch(const TransportError&){x.outcome=FreezeOutcome::cancelled;return x;}
        if(begin>=plan.budget_ms){x.outcome=FreezeOutcome::budget;return x;}
        // Do not send a request if its maximum response cannot be retained.
        if(freeze_max_raw-retained<32768){x.outcome=FreezeOutcome::cancelled;return x;}
        const auto left=plan.budget_ms-begin;
        const unsigned timeout=static_cast<unsigned>(std::min<std::uint64_t>(left,command=="ATDPN"?2000:4000));
        x.attempted=true;
        try{
            if(command=="ATDPN")x.raw=elm.probe(timeout);
            else x.raw=elm.exchange_freeze(plan,unhex(command),timeout);
            retained+=x.raw.size();
            const auto end=elapsed();
            x.duration_ms=static_cast<unsigned>(std::min<std::uint64_t>(3600000,end-begin));
            if(!current()){
                x.raw.clear();x.outcome=FreezeOutcome::cancelled;
            }else if(end>=plan.budget_ms||end-begin>timeout)x.outcome=FreezeOutcome::late;
            else x.outcome=FreezeOutcome::received;
        }catch(const Cancelled&){
            x.raw.clear();x.outcome=FreezeOutcome::cancelled;
        }catch(const std::exception&){
            x.raw.clear();x.outcome=FreezeOutcome::transport;
        }
        if(x.outcome!=FreezeOutcome::received&&x.outcome!=FreezeOutcome::late){
            try{x.duration_ms=static_cast<unsigned>(std::min<std::uint64_t>(3600000,elapsed()-begin));}
            catch(const TransportError&){x.outcome=FreezeOutcome::cancelled;}
        }
        return x;
    });
}
}
