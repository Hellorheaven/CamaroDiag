#include "j1979_historical_can_plan.hpp"
#include "j1979_policy.h"
#include <algorithm>
#include <charconv>
#include <map>
#include <sstream>
#include <stdexcept>
namespace cd::network::history {
namespace {
using json::Value;
void require(bool b,const char* reason){if(!b)throw std::invalid_argument(reason);}
const Value* get(const Value& v,std::string_view key){
    std::string camel;bool upper=false;
    for(char c:key){
        if(c=='_'){upper=true;continue;}
        if(upper&&c>='a'&&c<='z')c=static_cast<char>(c-'a'+'A');
        upper=false;camel+=c;
    }
    const auto* a=v.find(key);const auto* b=camel==key?nullptr:v.find(camel);
    require(!a||!b,"Ambiguous physical report field");return a?a:b;
}
const Value& field(const Value& v,std::string_view key){
    const auto* p=get(v,key);require(p,"Missing physical report field");return *p;
}
std::string text(const Value& v,std::string_view key,std::size_t max=256){
    const auto* p=get(v,key);if(!p||p->null())return {};
    const auto& s=p->string();require(s.size()<=max,"Physical report string exceeds limit");return s;
}
std::optional<std::string> vin_field(const Value& v,std::string_view key){
    const auto* p=get(v,key);if(!p||p->null())return {};
    const auto& s=p->string();require(cd_j1979_native_vin(s.data(),s.size()),"Invalid reported VIN");return s;
}
void no_claims(const Value& v){
    for(auto k:{"physical_vehicle_verified","whole_vehicle_coverage_verified","authenticated",
                "evidence_authenticated","permits_programming","permits_automatic_recovery"}){
        const auto* p=get(v,k);require(!p||!p->boolean(),"Unsupported physical proof claim");
    }
}
Bytes bytes(const Value& v,std::size_t maximum){
    const auto& s=v.string();
    require(s.size()%2==0&&s.size()<=maximum*2,"Invalid physical payload width");
    Bytes out;
    for(std::size_t i=0;i<s.size();i+=2){
        unsigned n{};
        const auto [end,ec]=std::from_chars(s.data()+i,s.data()+i+2,n,16);
        require(ec==std::errc{}&&end==s.data()+i+2&&n<=255,"Invalid physical payload hex");
        out.push_back(static_cast<std::uint8_t>(n));
    }
    return out;
}
std::string hex(std::span<const std::uint8_t> bytes){
    constexpr char digits[]="0123456789ABCDEF";
    std::string out;out.reserve(bytes.size()*2);
    for(auto b:bytes){out+=digits[b>>4];out+=digits[b&15];}
    return out;
}
enum class Shape {invalid,passed,raw,negative};
bool trim(unsigned p){
    return (p>=6&&p<=9)||(p>=0x55&&p<=0x58);
}
unsigned width(unsigned mode,unsigned p){
    if(mode==2&&p==2)return 2;
    if(p==1||p==0xa6)return 4;
    switch(p){
    case 0x04:case 0x05:case 0x0a:case 0x0b:case 0x0d:case 0x0e:case 0x0f:
    case 0x11:case 0x13:case 0x1d:case 0x2c:case 0x2d:case 0x2e:case 0x2f:
    case 0x30:case 0x33:case 0x45:case 0x46:case 0x47:case 0x48:case 0x49:
    case 0x4a:case 0x4b:case 0x4c:case 0x52:case 0x5a:case 0x5b:case 0x5c:
    case 0x61:case 0x62:return 1;
    case 0x0c:case 0x10:case 0x1f:case 0x21:case 0x22:case 0x23:case 0x31:
    case 0x32:case 0x3c:case 0x3d:case 0x3e:case 0x3f:case 0x42:case 0x43:
    case 0x44:case 0x4d:case 0x4e:case 0x53:case 0x54:case 0x59:case 0x5d:
    case 0x5e:case 0x63:return 2;
    default:return 0;
    }
}
Shape shape(const Bytes& q,const Bytes& r){
    if(r.empty()||q.empty())return Shape::invalid;
    if(r[0]==0x7f)return r.size()==3&&r[1]==q[0]&&r[2]&&r[2]!=0x78?Shape::negative:Shape::invalid;
    if(r[0]!=q[0]+0x40)return Shape::invalid;
    const auto mode=q[0];
    if(mode==3||mode==7||mode==10){
        if(r.size()<2||r.size()!=2u+2u*r[1])return Shape::invalid;
        for(std::size_t i=2;i<r.size();i+=2)if(!r[i]&&!r[i+1])return Shape::invalid;
        return Shape::passed;
    }
    if(r.size()<=q.size()||!std::equal(q.begin()+1,q.end(),r.begin()+1))return Shape::invalid;
    const auto pid=q[1];const auto n=r.size()-q.size();
    if(pid%32==0)return n==4&&!(pid==0xe0&&(r.back()&1))?Shape::passed:Shape::invalid;
    if(mode==1||mode==2){
        if(trim(pid))return n==1&&pid<0x55?Shape::passed:n==1||n==2?Shape::raw:Shape::invalid;
        const auto w=width(mode,pid);
        return w?(w==n?Shape::passed:Shape::invalid):Shape::raw;
    }
    if(mode==6){
        if(r.size()<10||(r.size()-1)%9)return Shape::invalid;
        for(std::size_t i=1;i<r.size();i+=9)if(r[i]!=pid)return Shape::invalid;
        return Shape::passed;
    }
    if(mode==9){
        if(pid==2)return r.size()==20&&r[2]==1&&cd_j1979_native_vin(
            reinterpret_cast<const char*>(r.data()+3),17)?Shape::passed:Shape::invalid;
        const unsigned w=pid==4?16:pid==6?4:pid==10?20:0;
        return w?(r.size()>=3&&r[2]&&r.size()==3u+r[2]*w?Shape::passed:Shape::invalid):Shape::raw;
    }
    return Shape::invalid;
}
std::string status(Shape s){
    switch(s){
    case Shape::passed:return "PASSED";case Shape::raw:return "RAW";
    case Shape::negative:return "NEGATIVE";case Shape::invalid:return "INVALID";
    }
    return "INVALID";
}
bool positive(const Step* s){return s&&s->status=="PASSED"&&s->response.has_value();}
std::optional<std::string> observed(const Step* s){
    if(!positive(s))return {};
    const auto& r=*s->response;
    if(s->request!=Bytes{9,2}||r.size()!=20||r[2]!=1)return {};
    return std::string(r.begin()+3,r.end());
}
bool terminal(const Step& s,PlanOrder order){
    if(s.detail=="LATE_RESPONSE"||s.detail=="CANCELLED"||s.detail=="BUDGET_EXHAUSTED")return true;
    if(s.status!="FAILED")return false;
    return order==PlanOrder::android||s.detail.starts_with("TRANSPORT_ERROR")||
        s.detail=="OSError"||s.detail=="ConnectionError"||s.detail=="BrokenPipeError"||s.detail=="ConnectionResetError";
}
Step read_step(const Value& v){
    Step s{};s.request=bytes(field(v,"request_hex"),3);
    require(cd_j1979_native_read_only(s.request.data(),s.request.size()),"Mutation or invalid request in read campaign");
    const auto& r=field(v,"response_hex");if(!r.null())s.response=bytes(r,4095);
    s.status=field(v,"status").string();s.detail=text(v,"detail");
    require(s.status=="PASSED"||s.status=="RAW"||s.status=="NEGATIVE"||s.status=="TIMEOUT"||
        s.status=="FAILED"||s.status=="NOT_TESTED","Unknown physical observation status");
    const auto ms=field(v,"duration_ms").unsigned_integer();
    require(ms<=600000,"Physical observation duration exceeds bound");s.duration_ms=static_cast<unsigned>(ms);
    const auto sh=s.response?shape(s.request,*s.response):Shape::invalid;
    if(s.status=="PASSED"||s.status=="RAW"||s.status=="NEGATIVE")
        require(s.status==status(sh),"Physical application envelope differs from status");
    if(s.status=="NOT_TESTED"&&s.detail.starts_with("NRC_"))
        require(sh==Shape::negative&&s.detail=="NRC_"+hex(std::span(*s.response).subspan(2)),"Negative response detail differs");
    // The report may contain derived JSON fields. Do not treat those
    // as verified measurements; this module certifies only the sequence.
    return s;
}
class Replay{
    PlanInspection& out_;
    std::size_t index_{};
    bool halted_{};
public:
    explicit Replay(PlanInspection& out):out_(out){}
    const Step* take(Bytes q){
        if(halted_)return nullptr;
        if(index_==out_.steps.size()){halted_=true;return nullptr;}
        const auto& s=out_.steps[index_++];
        require(s.request==q,"Unexpected physical request or inventory order");
        if(terminal(s,out_.order))halted_=true;
        return &s;
    }
    void inventory(std::size_t slot,const Step* cached=nullptr,bool members=true){
        auto& inv=out_.inventories.at(slot);const auto mode=inv.mode;
        const auto request=[&](unsigned pid){
            Bytes q{static_cast<std::uint8_t>(mode),static_cast<std::uint8_t>(pid)};
            if(mode==2)q.push_back(0);
            return q;
        };
        const auto read_member=[&](unsigned pid){
            if((mode==2||mode==9)&&pid==2)return;
            take(request(pid));
        };
        for(unsigned base=0;base<=0xe0&&!halted_;base+=32){
            const auto* row=base==0&&cached?cached:take(request(base));
            if(!positive(row))break;
            const auto& res=*row->response;
            const auto offset=row->request.size();
            require(res.size()==offset+4,"Invalid support bitmap");
            ++inv.pages;inv.state="PARTIAL";
            for(unsigned i=0;i<31;++i)if(res[offset+i/8]&(0x80u>>(i%8))){
                const auto id=base+i+1;inv.ids.push_back(id);
                if(out_.order==PlanOrder::android&&members)read_member(id);
            }
            if(!(res.back()&1)){inv.state="COMPLETE";break;}
        }
        if(out_.order==PlanOrder::historical&&members)for(auto pid:inv.ids)read_member(pid);
    }
    void run(){
        const Step* initial=nullptr;
        if(out_.order==PlanOrder::android){
            initial=take({1,0});if(!positive(initial))halted_=true;
        }
        out_.initial_vin=observed(take({9,2}));
        if(out_.declared_vin&&out_.initial_vin!=out_.declared_vin)halted_=true;
        for(auto mode:{3,7,10})take({static_cast<std::uint8_t>(mode)});
        inventory(0,initial);
        const auto* trigger=take({2,2,0});
        if(positive(trigger)){
            const auto& res=*trigger->response;
            require(res.size()==5,"Invalid freeze trigger");
            out_.freeze_trigger_verified=true;out_.freeze_present=res[3]||res[4];
            if(out_.freeze_present)inventory(1);
            else out_.inventories[1].state="NOT_APPLICABLE";
        }
        inventory(2);inventory(3,nullptr,false);inventory(4);
        if(out_.order==PlanOrder::android){
            out_.final_vin=observed(take({9,2}));
            if(out_.declared_vin&&out_.final_vin!=out_.declared_vin)halted_=true;
        }
        require(index_==out_.steps.size(),"Read operations recorded after plan halted");
        out_.sequence_exhausted=!halted_;
        require(!out_.completed||out_.sequence_exhausted,"Incomplete physical plan claimed complete");
    }
};
const char* tf(bool b){return b?"true":"false";}
}
PlanInspection inspect_plan(const Value& body,PlanOrder order,std::optional<std::string> vin){
    no_claims(body);
    if(vin)require(cd_j1979_native_vin(vin->data(),vin->size()),"Invalid external VIN");
    if(order==PlanOrder::android)require(vin.has_value(),"Android campaign requires external VIN");
    else require(vin_field(body,"declared_vin")==vin,"Declared VIN differs from external scope");
    PlanInspection out{};out.order=order;out.declared_vin=std::move(vin);
    const std::array<unsigned,5> modes{1,2,6,8,9};
    for(std::size_t i=0;i<modes.size();++i)out.inventories[i].mode=modes[i];
    out.completed=field(body,"completed").boolean();out.stop_reason=text(body,"stop_reason");
    require(out.completed==out.stop_reason.empty(),"Physical completion contradicts stop reason");
    const auto& steps=field(body,"steps").array();
    require(steps.size()<=1400,"Physical step limit");
    std::size_t total=0;
    for(const auto& v:steps){
        auto s=read_step(v);
        if(s.response){total+=s.response->size();require(total<=4*1024*1024,"Physical response byte limit");}
        out.steps.push_back(std::move(s));
    }
    Replay(out).run();
    require(vin_field(body,"observed_vin")==out.initial_vin,"Reported VIN differs from raw response");
    if(const auto* final=get(body,"final_vin");final)require(vin_field(body,"final_vin")==out.final_vin,"Final reported VIN differs");
    return out;
}
std::string plan_report(const PlanInspection& r,std::string_view digest){
    std::ostringstream out;
    const auto nullable=[](const std::optional<std::string>& s){return s?json::quote(*s):std::string("null");};
    out<<"{\"schema\":\"camarodiag-j1979-historical-can-plan-inspection-linux-v1\","
        "\"verification_scope\":\"READ_SEQUENCE_AND_APPLICATION_ENVELOPE_ONLY\","
        "\"consistent\":true,\"order\":"<<json::quote(r.order==PlanOrder::android?"ANDROID":"HISTORICAL")
        <<",\"declared_vin\":"<<nullable(r.declared_vin)<<",\"observed_vin\":"<<nullable(r.initial_vin)
        <<",\"final_vin\":"<<nullable(r.final_vin)<<",\"completed\":"<<tf(r.completed)
        <<",\"sequence_exhausted\":"<<tf(r.sequence_exhausted)<<",\"stop_reason\":"
        <<(r.stop_reason.empty()?"null":json::quote(r.stop_reason))
        <<",\"freeze_trigger_verified\":"<<tf(r.freeze_trigger_verified)<<",\"freeze_present\":"<<tf(r.freeze_present)
        <<",\"inventory\":[";
    for(std::size_t i=0;i<r.inventories.size();++i){
        if(i)out<<',';
        const auto& inv=r.inventories[i];
        out<<"{\"mode\":"<<inv.mode<<",\"state\":"<<json::quote(inv.state)<<",\"verified_pages\":"<<inv.pages<<",\"advertised_ids\":[";
        for(std::size_t j=0;j<inv.ids.size();++j){if(j)out<<',';out<<inv.ids[j];}out<<"]}";
    }
    out<<"],\"steps\":[";
    for(std::size_t i=0;i<r.steps.size();++i){
        if(i)out<<',';
        const auto& s=r.steps[i];
        out<<"{\"request_hex\":"<<json::quote(hex(s.request))<<",\"response_hex\":"
            <<(s.response?json::quote(hex(*s.response)):"null")<<",\"status\":"<<json::quote(s.status)
            <<",\"detail\":"<<(s.detail.empty()?"null":json::quote(s.detail))<<",\"duration_ms\":"<<s.duration_ms<<'}';
    }
    out<<"],\"source_sha256\":"<<(digest.empty()?"null":json::quote(digest))
        <<",\"derived_projections_verified\":false,\"physical_vehicle_verified\":false,"
          "\"authenticated\":false,\"permits_programming\":false}\n";
    return out.str();
}
}
