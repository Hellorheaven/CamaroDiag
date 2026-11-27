#include "j1979_legacy_scan.hpp"
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
bool vin_valid(std::string_view vin){return cd_j1979_native_vin(vin.data(),vin.size());}
class Campaign {
    Elm& elm_;
    Clock clock_;
    Current current_;
    unsigned budget_;
    std::uint64_t start_;
    std::size_t raw_size_{};
    Scan result_;
    unsigned remaining(){
        if(!current_())throw Cancelled();
        const auto now=clock_();
        if(now<start_||now-start_>=budget_){
            result_.budget_expired=true;
            throw TransportError("BUDGET_EXHAUSTED");
        }
        return static_cast<unsigned>(budget_-(now-start_));
    }
    std::vector<Data> read(Data request,Validator valid,bool single){
        if(result_.steps.size()>=max_steps)throw TransportError("OBSERVATION_LIMIT");
        const unsigned timeout=std::min(4000u,remaining());
        ScanStep step{};step.request=std::move(request);
        step.status="NOT_TESTED";
        const auto begin=clock_();
        try{
            step.raw=elm_.exchange(step.request,timeout);
            if(step.raw.size()>max_raw-raw_size_)throw TransportError("RAW_EVIDENCE_LIMIT");
            raw_size_+=step.raw.size();
            (void)remaining();
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
        }catch(...){
            step.status="FAILED";step.detail="TRANSPORT_CANCELLED_OR_LIMIT";
            const auto now=clock_();step.duration_ms=now>=begin?now-begin:0;
            result_.steps.push_back(std::move(step));throw;
        }
        const auto now=clock_();step.duration_ms=now>=begin?now-begin:0;
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
        if(rows.size()!=5){mark_last("VIN_SEGMENTS_INCOMPLETE");return {};}
        Data bytes;
        for(unsigned n=0;n<5;++n){
            if(rows[n][2]!=n+1){mark_last("VIN_SEGMENTS_UNORDERED");return {};}
            bytes.insert(bytes.end(),rows[n].begin()+3,rows[n].end());
        }
        if(bytes.size()!=20||bytes[0]||bytes[1]||bytes[2]){mark_last("VIN_PADDING_INVALID");return {};}
        const std::string vin(bytes.begin()+3,bytes.end());
        if(!vin_valid(vin)){mark_last("VIN_FORMAT_INVALID");return {};}
        return vin;
    }
    void read_identification(const Inventory& info){
        std::map<unsigned,unsigned> counts;
        for(auto pid:info.advertised){
            if(pid==1||pid==2)continue;
            if((pid%2==1&&pid<=9)||pid==12||pid==14){
                auto rows=read({9,static_cast<std::uint8_t>(pid)},[=](const Data& r){
                    return r.size()==3&&r[0]==0x49&&r[1]==pid&&r[2]>0&&r[2]<=128;
                },true);
                if(!rows.empty())counts[pid]=rows[0][2];
            }else{
                auto rows=read({9,static_cast<std::uint8_t>(pid)},[=](const Data& r){
                    return r.size()==7&&r[0]==0x49&&r[1]==pid&&r[2]>0;
                },false);
                if(rows.empty())continue;
                auto count=counts.find(pid==11?7:pid-1);
                bool ordered=count!=counts.end()&&rows.size()==count->second;
                for(std::size_t n=0;n<rows.size();++n)ordered=ordered&&rows[n][2]==n+1;
                if(!ordered)mark_last("IDENTIFICATION_SEGMENTS_UNVERIFIED");
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
    Campaign(Elm& elm,unsigned source,std::string_view vin,unsigned budget,Clock clock,Current current)
        :elm_(elm),clock_(std::move(clock)),current_(std::move(current)),budget_(budget),start_(clock_()){
        result_.protocol=elm.protocol();result_.source=source;result_.expected_vin=vin;
    }
    Scan run(){
        try{
            const auto first=page(1,0,0);
            if(first.empty())throw TransportError("NO_SELECTED_PID00");
            result_.initial_probe=elm_.probe(std::min(2000u,remaining()));
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
            result_.final_probe=elm_.probe(std::min(2000u,remaining()));
            if(fixed_protocol(result_.final_probe)!=result_.protocol)throw TransportError("FINAL_PROTOCOL_MISMATCH");
            result_.final_vin=read_vin();
            if(result_.final_vin!=result_.expected_vin)throw TransportError("FINAL_VIN_UNVERIFIED_OR_CHANGED");
            (void)remaining();
            result_.identity_rechecked=true;
            result_.completed=mode1.complete;
            if(!result_.completed)result_.error="MODE01_INVENTORY_PARTIAL";
        }catch(const Cancelled&){result_.error="CANCELLED";}
        catch(const TransportError& e){result_.error=e.what();}
        return std::move(result_);
    }
};
}
Scan scan_selected(Elm& elm,unsigned source,std::string_view vin,unsigned budget,Clock clock,Current current){
    if(!elm.headered()||!elm.ready()||!valid_source(source)||!vin_valid(vin)||
        !clock||!current||!budget||budget>600000)
        throw std::invalid_argument("Explicit selected source, documented VIN and bounded headered session required");
    return Campaign(elm,source,vin,budget,std::move(clock),std::move(current)).run();
}
}
