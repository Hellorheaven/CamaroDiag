#include "j1979_can_network.hpp"
#include <algorithm>
#include <map>
#include <set>
#include <stdexcept>

namespace cd::network {
Frame functional_request(bool extended){
    return {extended?0x18db33f1u:0x7dfu,extended,false,false,{2,1,0,0xcc,0xcc,0xcc,0xcc,0xcc}};
}
std::optional<Pair> response_pair(std::uint32_t id,bool ext){
    if(!ext){
        if(id>=0x7e8&&id<=0x7ef)return Pair{id-8,id};
        return {};
    }
    if(id>0x1fffffffu||(id&0x1fffff00u)!=0x18daf100u||(id&255)>=0xf0)return {};
    return Pair{0x18da00f1u|((id&255)<<8),id};
}
std::optional<Classification> classify(const Frame& f,bool ext){
    if(f.extended!=ext||f.rtr||f.error)return {};
    const auto pair=response_pair(f.id,ext);
    if(!pair)return {};
    Classification c{};c.pair=*pair;
    if(f.data.size()!=8)return c;
    const auto& d=f.data;
    if(d[0]==6&&d[1]==0x41&&d[2]==0){
        c.state=State::positive;std::copy_n(d.begin()+3,4,c.bitmap.begin());
    }else if(d[0]==3&&d[1]==0x7f&&d[2]==1&&d[3]){
        c.state=d[3]==0x78?State::pending:State::negative;
        if(c.state==State::negative)c.nrc=d[3];
    }
    return c;
}
Discovery replay_discovery(bool extended,bool sent,std::span<const Frame> frames,std::string_view stop,unsigned duration){
    if(frames.size()>max_frames||duration>3600000||(!sent&&!frames.empty())||
        (stop!=""&&stop!="CANCELLED"&&stop!="TRANSPORT_ERROR"&&stop!="FRAME_LIMIT"))
        throw std::invalid_argument("Invalid CAN discovery transcript");
    if(stop=="FRAME_LIMIT"&&frames.size()!=max_frames)throw std::invalid_argument("Contradictory discovery frame limit");
    Discovery d{};d.extended=extended;d.sent=sent;d.duration_ms=duration;d.stop=stop;
    std::map<std::uint32_t,std::vector<std::pair<Classification,Frame>>> groups;
    for(const auto& f:frames){
        if(f.data.size()>8||f.id>(f.extended?0x1fffffffu:0x7ffu))throw std::invalid_argument("Invalid classic CAN evidence");
        d.frames.push_back(f);
        const auto c=classify(f,extended);
        if(!c){++d.ignored;continue;}
        groups[f.id].emplace_back(*c,f);
    }
    for(const auto& [id,rows]:groups){
        const Classification* terminal=nullptr;
        bool conflict=false;
        Responder responder{};
        for(const auto& [c,f]:rows){
            responder.frames.push_back(f);
            if(c.state==State::pending)continue;
            if(!terminal)terminal=&c;
            else if(*terminal!=c)conflict=true;
        }
        responder.classification=terminal?*terminal:rows.front().first;
        if(conflict)responder.classification={responder.classification.pair,State::conflicting,{},0};
        if(!conflict&&responder.classification.state==State::positive&&d.stop.empty()&&sent)
            d.eligible.push_back(id);
        d.responders.push_back(std::move(responder));
    }
    if(d.stop.empty()&&d.eligible.empty())d.stop="NO_VERIFIED_RESPONDER";
    d.completed=d.stop.empty()&&sent;
    return d;
}
bool valid_targets(const Discovery& d,std::span<const std::uint32_t> ids){
    if(ids.empty()||ids.size()>max_targets||!d.completed||!d.sent||!d.stop.empty())return false;
    // Never admit a caller-supplied eligible list without rebuilding it from raw frames.
    const auto replay=replay_discovery(d.extended,d.sent,d.frames,"",d.duration_ms);
    if(!replay.completed)return false;
    std::set<std::uint32_t> seen;
    for(auto id:ids){
        if(!response_pair(id,d.extended)||!seen.insert(id).second||
            std::find(replay.eligible.begin(),replay.eligible.end(),id)==replay.eligible.end())return false;
    }
    return true;
}
}
