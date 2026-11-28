#include "j1979_legacy_scan.hpp"
#include "j1979_legacy_identity.hpp"
#include "j1979_legacy_info.hpp"
#include "j1979_policy.h"
#include "j1979_projection.hpp"
#include <algorithm>
#include <map>
#include <sstream>
#include <utility>

namespace cd::legacy {
namespace {
using Validator=std::function<bool(const Data&)>;
constexpr unsigned max_steps=4096;
constexpr std::size_t max_raw=8*1024*1024;
bool prefix(const Data& row,Bytes required){
    return row.size()>=required.size()&&std::equal(required.begin(),required.end(),row.begin());
}
class Campaign {
    using Observe=std::function<ScanExchange(std::string_view)>;
    Observe observe_;
    unsigned budget_;
    unsigned last_{};
    std::size_t raw_size_{};
    Scan result_;
    ScanExchange exchange(std::string_view command){
        if(result_.trace.size()>=scan_max_exchanges)throw TransportError("OBSERVATION_LIMIT");
        auto row=observe_(command);
        const auto kind=static_cast<unsigned>(row.outcome);
        if(row.command!=command||kind<1||kind>4||row.started_ms<last_||
            row.ended_ms<row.started_ms||row.ended_ms>3600000||row.raw.size()>32768||
            row.raw.size()>max_raw-raw_size_||
            (row.outcome==ScanOutcome::received&&(!row.attempted||row.ended_ms>=budget_||
             row.ended_ms-row.started_ms>(command=="ATDPN"?2000u:4000u)))||
            (row.outcome==ScanOutcome::transport&&!row.attempted)||
            (row.outcome!=ScanOutcome::received&&!row.raw.empty()))
            throw std::logic_error("Invalid scan transcript");
        last_=row.ended_ms;raw_size_+=row.raw.size();result_.trace.push_back(row);
        switch(row.outcome){
        case ScanOutcome::received:return row;
        case ScanOutcome::cancelled:throw Cancelled();
        case ScanOutcome::budget:result_.budget_expired=true;throw TransportError("BUDGET_EXHAUSTED");
        case ScanOutcome::transport:throw TransportError("TRANSPORT_ERROR");
        }
        throw std::logic_error("Invalid scan outcome");
    }
    std::vector<Data> read(Data request,Validator valid,bool single){
        if(result_.steps.size()>=max_steps)throw TransportError("OBSERVATION_LIMIT");
        ScanStep step{};step.request=std::move(request);
        step.status="NOT_TESTED";
        try{
            const auto row=exchange(hex(step.request));
            step.raw=row.raw;step.duration_ms=row.ended_ms-row.started_ms;
            step.observations=frames(result_.protocol,hex(step.request),step.raw);
            std::vector<Data> selected;
            for(const auto& f:step.observations){
                if(f.source==result_.source)selected.push_back(f.payload);
                else result_.other_sources.insert(f.source);
            }
            if(selected.empty())step.detail="NO_SELECTED_RESPONSE";
            else if(selected.size()==1&&selected[0].size()==3&&selected[0][0]==0x7f&&
                    selected[0][1]==step.request[0]&&selected[0][2]!=0&&selected[0][2]!=0x78){
                step.detail="NRC_"+hex(Bytes(selected[0]).subspan(2));
            }else if((single&&selected.size()!=1)||
                    !std::all_of(selected.begin(),selected.end(),valid)){
                step.status="FAILED";step.detail="AMBIGUOUS_OR_INVALID_SELECTED_RESPONSE";
            }else{
                step.selected=std::move(selected);step.status="PASSED";
            }
        }catch(const std::invalid_argument&){
            step.status="FAILED";step.detail="MALFORMED_LEGACY_FRAME";
        }catch(const TransportError&){
            step.status="FAILED";step.detail="TRANSPORT_CANCELLED_OR_LIMIT";
            if(!result_.trace.empty())step.duration_ms=result_.trace.back().ended_ms-result_.trace.back().started_ms;
            result_.steps.push_back(std::move(step));throw;
        }catch(const Cancelled&){
            step.status="FAILED";step.detail="TRANSPORT_CANCELLED_OR_LIMIT";
            if(!result_.trace.empty())step.duration_ms=result_.trace.back().ended_ms-result_.trace.back().started_ms;
            result_.steps.push_back(std::move(step));throw;
        }
        auto selected=step.selected;result_.steps.push_back(std::move(step));
        return selected;
    }
    Data page_request(unsigned mode,unsigned page,unsigned sensor){
        Data q{static_cast<std::uint8_t>(mode),static_cast<std::uint8_t>(page)};
        if(mode==2||mode==5)q.push_back(static_cast<std::uint8_t>(sensor));
        if(mode==8)q.resize(7,0);
        return q;
    }
    std::vector<Data> page(unsigned mode,unsigned base,unsigned sensor){
        return read(page_request(mode,base,sensor),[=](const Data& row){
            const unsigned offset=mode==1?2:3;
            if(row.size()!=offset+4||row[0]!=mode+0x40||row[1]!=base)return false;
            if(mode==2||mode==5||mode==8){if(row[2]!=sensor)return false;}
            if(mode==6&&row[2]!=0xff)return false;
            if(mode==9&&row[2]!=1)return false;
            return base!=224||!(row.back()&1);
        },true);
    }
    Inventory inventory(unsigned mode,unsigned sensor,const std::vector<Data>* first=nullptr){
        Inventory i{};i.mode=mode;i.sensor=sensor;
        for(unsigned base=0;base<=224;base+=32){
            auto rows=base==0&&first?*first:page(mode,base,sensor);
            if(rows.empty())break;
            const auto& r=rows.front();++i.pages;
            const unsigned offset=mode==1?2:3;
            for(unsigned bit=0;bit<31;++bit)
                if(r[offset+bit/8]&(0x80u>>(bit%8)))i.advertised.insert(base+bit+1);
            if(!(r.back()&1)){i.complete=true;break;}
        }
        result_.inventories.push_back(i);return i;
    }
    void mark_last(std::string detail){
        auto& step=result_.steps.back();step.status="FAILED";step.detail=std::move(detail);step.selected.clear();
    }
    std::string read_vin(){
        const auto count=read({9,1},[](const Data& r){return r.size()==3&&r[0]==0x49&&r[1]==1&&r[2]==5;},true);
        if(count.empty())return {};
        const auto rows=read({9,2},[](const Data& r){return r.size()==7&&r[0]==0x49&&r[1]==2&&r[2]>=1&&r[2]<=5;},false);
        const auto vin=normalized_vin(rows);
        if(!vin){mark_last("VIN_SEGMENTS_OR_FORMAT_INVALID");return {};}
        return *vin;
    }
    void read_identification(const Inventory& info){
        std::map<unsigned,unsigned> counts;
        for(auto pid:info.advertised){
            if(pid==1||pid==2)continue;
            if(count_kind(pid)){
                auto rows=read({9,static_cast<std::uint8_t>(pid)},[=](const Data& r){
                    return r.size()==3&&r[0]==0x49&&r[1]==pid&&r[2]>0&&r[2]<=128;
                },true);
                if(!rows.empty())counts[pid]=rows[0][2];
            }else{
                auto rows=read({9,static_cast<std::uint8_t>(pid)},[=](const Data& r){
                    return r.size()==7&&r[0]==0x49&&r[1]==pid&&r[2]>0;
                },false);
                if(rows.empty())continue;
                const auto count=counts.find(matching_count_kind(pid));
                if(count==counts.end()){
                    mark_last("IDENTIFICATION_COUNT_UNVERIFIED");continue;
                }
                const auto decoded=decode_identification(pid,count->second,rows);
                if(!decoded)mark_last("IDENTIFICATION_SEGMENTS_OR_FORMAT_INVALID");
                else if(!decoded->interpreted){
                    result_.steps.back().status="RAW";result_.steps.back().detail="UNINTERPRETED_INFOTYPE";
                }
            }
        }
    }
    std::vector<Data> read_pid(unsigned mode,unsigned pid){
        Data q{static_cast<std::uint8_t>(mode),static_cast<std::uint8_t>(pid)};
        if(mode==2)q.push_back(0);
        return read(q,[&](const Data& r){
            Data expected=q;expected[0]+=0x40;
            if(!prefix(r,expected)||r.size()<=expected.size()||r.size()>7)return false;
            if(mode==1&&(pid==0x13||pid==0x1d)&&r.size()!=3)return false;
            if(mode==1){
                const cd::j1979::Observation row{q,r,true,true};
                if(!cd::j1979::known_shape(row))return false;
            }
            return true;
        },true);
    }
public:
    Campaign(Protocol protocol,unsigned source,std::string_view vin,unsigned budget,Observe observe)
        :observe_(std::move(observe)),budget_(budget){
        result_.protocol=protocol;result_.source=source;result_.expected_vin=vin;result_.budget_ms=budget;
    }
    Scan run(){
        try{
            const auto first=page(1,0,0);
            if(first.empty())throw TransportError("NO_SELECTED_PID00");
            result_.initial_probe=exchange("ATDPN").raw;
            if(fixed_protocol(result_.initial_probe)!=result_.protocol)throw TransportError("PROTOCOL_MISMATCH");
            const auto info=inventory(9,0);
            if(!info.advertised.contains(1)||!info.advertised.contains(2))throw TransportError("VIN_NOT_ADVERTISED");
            result_.initial_vin=read_vin();
            if(result_.initial_vin!=result_.expected_vin)throw TransportError("VIN_UNVERIFIED_OR_MISMATCH");
            read_identification(info);
            const auto mode1=inventory(1,0,&first);
            for(unsigned mode:{3u,7u,10u})
                read({static_cast<std::uint8_t>(mode)},[=](const Data& r){return r.size()==7&&r[0]==mode+0x40;},false);
            std::set<unsigned> sensors;
            for(auto pid:mode1.advertised){
                auto rows=read_pid(1,pid);
                if((pid==0x13||pid==0x1d)&&!rows.empty())
                    for(unsigned bit=0;bit<8;++bit)if(rows[0][2]&(1u<<bit))sensors.insert(1u<<bit);
            }
            auto trigger=read({2,2,0},[](const Data& r){return r.size()==5&&r[0]==0x42&&r[1]==2&&r[2]==0;},true);
            if(!trigger.empty()&&(trigger[0][3]||trigger[0][4])){
                const auto freeze=inventory(2,0);
                for(auto pid:freeze.advertised)if(pid!=2)read_pid(2,pid);
            }
            for(auto sensor:sensors){
                const auto monitor=inventory(5,sensor);
                for(auto tid:monitor.advertised)
                    read({5,static_cast<std::uint8_t>(tid),static_cast<std::uint8_t>(sensor)},[=](const Data& r){
                        return r.size()==(tid>=1&&tid<=4?4u:6u)&&r[0]==0x45&&r[1]==tid&&r[2]==sensor;
                    },false);
            }
            const auto monitor=inventory(6,0);
            for(auto tid:monitor.advertised)
                read({6,static_cast<std::uint8_t>(tid)},[=](const Data& r){return r.size()==7&&r[0]==0x46&&r[1]==tid;},false);
            // Mode 08 availability only, never send a control request.
            (void)inventory(8,0);
            result_.final_probe=exchange("ATDPN").raw;
            if(fixed_protocol(result_.final_probe)!=result_.protocol)throw TransportError("FINAL_PROTOCOL_MISMATCH");
            result_.final_vin=read_vin();
            if(result_.final_vin!=result_.expected_vin)throw TransportError("FINAL_VIN_UNVERIFIED_OR_CHANGED");
            result_.identity_rechecked=true;
            result_.completed=mode1.complete&&std::none_of(result_.steps.begin(),result_.steps.end(),[](const ScanStep& step){return step.status=="FAILED";});
            if(!result_.completed)result_.error=mode1.complete?"OBSERVATION_FAILED":"MODE01_INVENTORY_PARTIAL";
        }catch(const Cancelled&){result_.error="CANCELLED";}
        catch(const TransportError& e){result_.error=e.what();}
        return std::move(result_);
    }
};
}
Scan scan_selected(Elm& elm,unsigned source,std::string_view vin,unsigned budget,Clock clock,Current current,const std::function<void(const ScanExchange&)>& sink){
    if(!elm.headered()||!elm.ready()||!valid_source(source)||!valid_vin(vin)||
        !clock||!current||!budget||budget>600000)
        throw std::invalid_argument("Explicit selected source, documented VIN and bounded headered session required");
    const auto start=clock();std::uint64_t previous=start;std::size_t retained=0;
    auto elapsed=[&](){
        const auto now=clock();
        if(now<previous||now-start>3600000)throw std::logic_error("Invalid monotonic clock");
        previous=now;return static_cast<unsigned>(now-start);
    };
    auto observe=[&](std::string_view command){
        ScanExchange row{};row.command=command;row.started_ms=row.ended_ms=elapsed();
        if(!current()||max_raw-retained<32768){row.outcome=ScanOutcome::cancelled;return row;}
        if(row.started_ms>=budget){row.outcome=ScanOutcome::budget;return row;}
        const auto timeout=std::min(budget-row.started_ms,command=="ATDPN"?2000u:4000u);
        row.attempted=true;
        try{
            row.raw=command=="ATDPN"?elm.probe(timeout):elm.exchange(unhex(command),timeout);
            row.ended_ms=elapsed();
            if(!current())row.outcome=ScanOutcome::cancelled;
            else if(row.ended_ms>=budget)row.outcome=ScanOutcome::budget;
            else if(row.ended_ms-row.started_ms>timeout)row.outcome=ScanOutcome::transport;
        }catch(const Cancelled&){row.outcome=ScanOutcome::cancelled;row.ended_ms=elapsed();}
        catch(const TransportError&){row.outcome=ScanOutcome::transport;row.ended_ms=elapsed();}
        if(row.outcome!=ScanOutcome::received)row.raw.clear();
        retained+=row.raw.size();return row;
    };
    return Campaign(elm.protocol(),source,vin,budget,[&](std::string_view command){
        auto row=observe(command);if(sink)sink(row);return row;
    }).run();
}
Scan replay_scan(Protocol protocol,unsigned source,std::string_view vin,unsigned budget,std::span<const ScanExchange> trace){
    if(!valid_protocol(protocol)||!valid_source(source)||!valid_vin(vin)||!budget||budget>600000||
       trace.empty()||trace.size()>scan_max_exchanges)throw std::invalid_argument("Invalid independent scan scope");
    std::size_t offset=0;
    auto result=Campaign(protocol,source,vin,budget,[&](std::string_view command){
        if(offset>=trace.size()||trace[offset].command!=command)throw std::logic_error("Missing or reordered scan exchange");
        return trace[offset++];
    }).run();
    if(offset!=trace.size())throw std::invalid_argument("Extra scan exchanges");
    return result;
}
}
