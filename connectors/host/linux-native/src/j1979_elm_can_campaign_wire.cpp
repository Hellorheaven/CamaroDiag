#include "j1979_elm_can_campaign.hpp"
#include <algorithm>
#include <array>
#include <stdexcept>
namespace cd::elmcan {
namespace {
constexpr size_t header=92,event_size=12;
constexpr std::array<uint8_t,4> magic{'E','C','P',1};
void require(bool b,const char* why){if(!b)throw std::invalid_argument(why);}
uint32_t get(Bytes b,size_t at){
    return uint32_t(b[at])|(uint32_t(b[at+1])<<8)|(uint32_t(b[at+2])<<16)|(uint32_t(b[at+3])<<24);
}
void put(Data& b,size_t at,uint32_t n){for(unsigned i=0;i<4;++i)b.at(at+i)=uint8_t(n>>(8*i));}
uint32_t crc(Bytes b){
    uint32_t v=0xffffffffu;
    for(auto x:b){v^=x;for(unsigned i=0;i<8;++i)v=(v>>1)^((v&1u)?0xedb88320u:0);}
    return v^0xffffffffu;
}
bool zero(Bytes b){return std::all_of(b.begin(),b.end(),[](auto n){return n==0;});}
}
Data encode_campaign(const CampaignCapture& v){
    (void)replay_campaign(v);
    const auto& p=v.plan;
    const auto trace=encode_trace(v.trace);
    require(trace.size()<=trace_max_bytes,"ECP trace limit");
    Data out(header,0);
    std::copy(magic.begin(),magic.end(),out.begin());
    out[4]=static_cast<uint8_t>(p.operation);out[5]=static_cast<uint8_t>(p.target.profile);
    out[6]=static_cast<uint8_t>(p.pids.size());out[7]=static_cast<uint8_t>(p.interface.size());
    put(out,8,p.target.tx);put(out,12,p.target.rx);put(out,16,p.budget_ms);
    put(out,20,p.cycles);put(out,24,p.interval_ms);
    put(out,28,static_cast<uint32_t>(v.events.size()));put(out,32,static_cast<uint32_t>(trace.size()));
    std::copy(p.vin.begin(),p.vin.end(),out.begin()+36);
    std::copy(p.pids.begin(),p.pids.end(),out.begin()+53);
    put(out,88,crc(Bytes(out).first(88)));
    out.insert(out.end(),p.interface.begin(),p.interface.end());
    out.insert(out.end(),trace.begin(),trace.end());
    for(const auto& e:v.events){
        const auto at=out.size();out.resize(at+event_size,0);
        out[at]=static_cast<uint8_t>(e.kind);put(out,at+4,e.value);put(out,at+8,e.aux);
    }
    const auto sum=crc(out);const auto at=out.size();out.resize(at+4);put(out,at,sum);
    require(out.size()<=campaign_max_bytes,"ECP capture limit");return out;
}
CampaignCapture decode_campaign(Bytes b){
    require(b.size()>=header+4&&b.size()<=campaign_max_bytes&&std::equal(magic.begin(),magic.end(),b.begin())&&
        get(b,88)==crc(b.first(88))&&zero(b.subspan(85,3)),"Invalid ECP/1 header");
    const auto count=get(b,28),trace_size=get(b,32);
    require(b[4]>=1&&b[4]<=2&&b[6]<=J1979_LIVE_MAX_PIDS&&b[7]&&count>=1&&count<=campaign_max_events&&
        trace_size>=44&&trace_size<=trace_max_bytes&&zero(b.subspan(53+b[6],32-b[6])),"ECP/1 scope or length");
    const size_t expected=header+b[7]+trace_size+size_t(count)*event_size+4;
    require(b.size()==expected&&get(b,b.size()-4)==crc(b.first(b.size()-4)),"ECP/1 size or checksum");
    CampaignCapture v{};
    auto& p=v.plan;
    p.operation=static_cast<TraceOperation>(b[4]);p.target={b[5],get(b,8),get(b,12)};
    p.budget_ms=get(b,16);p.cycles=get(b,20);p.interval_ms=get(b,24);
    p.vin.assign(b.begin()+36,b.begin()+53);p.pids.assign(b.begin()+53,b.begin()+53+b[6]);
    p.interface.assign(b.begin()+header,b.begin()+header+b[7]);
    require(p.valid(),"Invalid ECP/1 campaign plan");
    v.trace=decode_trace(b.subspan(header+b[7],trace_size));
    size_t pos=header+b[7]+trace_size;
    v.events.reserve(count);
    for(unsigned i=0;i<count;++i){
        const auto e=b.subspan(pos,event_size);
        require(e[0]>=1&&e[0]<=4&&zero(e.subspan(1,3)),"Invalid ECP/1 event");
        v.events.push_back({static_cast<EventKind>(e[0]),get(e,4),get(e,8)});pos+=event_size;
    }
    (void)replay_campaign(v);return v;
}
}
