#include "j1979_cj1.hpp"
#include "j1979_policy.h"
#include "j1979_measurement.h"
#include "j1979_monitor_scaling.h"
#include "j1979_vehicle_info.h"
#include <algorithm>
#include <array>
#include <charconv>
#include <iomanip>
#include <initializer_list>
#include <utility>
#include <locale>
#include <sstream>
#include <stdexcept>

namespace cd::cj1 {
namespace {
constexpr std::array<std::string_view,6> states{"","PASSED","RAW","NEGATIVE","TIMEOUT","FAILED"};
constexpr std::array<std::string_view,6> ends{"","COMPLETE","INITIAL_UNVERIFIED","BUDGET","LINK_FAULT","SINK_STOPPED"};
constexpr std::array<std::string_view,8> flags{"transport_observed","pid_inventory_complete","freeze_trigger_observed",
    "freeze_frame_present","freeze_inventory_complete","monitor_inventory_complete","control_inventory_complete",
    "identity_inventory_complete"};
constexpr auto one=std::to_array<unsigned>({4,5,6,7,8,9,10,11,13,14,15,17,19,29,44,45,46,47,48,51,69,70,71,72,73,74,75,76,82,90,91,92,97,98});
constexpr auto two=std::to_array<unsigned>({12,16,31,33,34,35,49,50,60,61,62,63,66,67,68,77,78,83,84,89,93,94,99});
void require(bool ok,const char* message){if(!ok)throw std::invalid_argument(message);}
unsigned u16(Bytes b,std::size_t p){return unsigned(b[p])|unsigned(b[p+1])<<8;}
std::uint32_t u32(Bytes b,std::size_t p){return std::uint32_t(b[p])|std::uint32_t(b[p+1])<<8|
    std::uint32_t(b[p+2])<<16|std::uint32_t(b[p+3])<<24;}
std::uint32_t crc(Bytes b){
    std::uint32_t c=0xffffffffu;
    for(auto v:b){c^=v;for(unsigned i=0;i<8;++i)c=(c>>1)^((c&1)?0xedb88320u:0u);}
    return c^0xffffffffu;
}
std::string hex(Bytes b){
    constexpr std::string_view digits="0123456789ABCDEF";
    std::string s;s.reserve(2*b.size());for(auto v:b){s+=digits[v>>4];s+=digits[v&15];}return s;
}
bool vin_valid(std::string_view s){return cd_j1979_native_vin(s.data(),s.size());}
unsigned positive(Bytes q,Bytes r){
    if(r.empty()||r[0]!=q[0]+64u)return 0;
    const unsigned mode=q[0];
    if(mode==3||mode==7||mode==10){
        if(r.size()<2||r.size()!=2u+2u*r[1])return 0;
        for(std::size_t i=2;i<r.size();i+=2)if(r[i]==0&&r[i+1]==0)return 0;
        return 1;
    }
    if(r.size()<2||r[1]!=q[1])return 0;
    const unsigned pid=q[1],off=mode==2?3:2;
    if(mode==2&&(r.size()<3||r[2]!=q[2]))return 0;
    if(pid%32==0)return r.size()==off+4&&!(pid==224&&(r.back()&1))?1:0;
    if(mode==1||mode==2){
        if((pid>=6&&pid<=9)||(pid>=0x55&&pid<=0x58)){
            if(r.size()==off+1)return pid<0x55?1:2;
            return r.size()==off+2?2:0;
        }
        const unsigned width=mode==2&&pid==2?2:pid==1||pid==0xa6?4:
            std::binary_search(one.begin(),one.end(),pid)?1:std::binary_search(two.begin(),two.end(),pid)?2:0;
        return width?(r.size()==off+width?1:0):(r.size()>off?2:0);
    }
    if(mode==6){
        if(r.size()<10||(r.size()-1)%9)return 0;
        for(std::size_t i=1;i<r.size();i+=9)if(r[i]!=pid)return 0;
        return 1;
    }
    if(mode==9){
        if(pid==2)return r.size()==20&&r[2]==1&&vin_valid(std::string_view(
            reinterpret_cast<const char*>(r.data()+3),17))?1:0;
        const unsigned width=pid==4?16:pid==6?4:pid==10?20:0;
        return width?(r.size()>=3&&r[2]&&r.size()==3u+r[2]*width?1:0):(r.size()>2?2:0);
    }
    return 0;
}
Step step(Bytes b,unsigned seq){
    require(b.size()>=9,"Truncated CJ/1 observation");
    const unsigned state=b[0],n=b[1],rlen=u16(b,6);
    require(state>=1&&state<=5&&n>=1&&n<=7&&rlen<=4095&&b.size()==8+n+rlen&&
        (state<=3)==(rlen>0),"Invalid CJ/1 observation");
    const auto q=b.subspan(8,n),r=b.subspan(8+n,rlen);
    require(cd_j1979_native_read_only(q.data(),q.size()),"Unexpected mutating request in evidence");
    if(state<=2){
        const auto expected=positive(q,r);
        require(state==expected||(state==2&&q.size()>=2&&(q[0]==1||q[0]==2)&&q[1]==0xa6&&expected==1),
            "Response shape or claimed evidence status inconsistent");
    }
    if(state==3)require(r.size()==3&&r[0]==0x7f&&r[1]==q[0]&&r[2]!=0&&r[2]!=0x78,"Invalid terminal negative record");
    return Step{seq,state,u32(b,2),{q.begin(),q.end()},{r.begin(),r.end()}};
}
class Replay {
    const std::vector<Step>& steps_;
    std::size_t index_{};
    unsigned stop_{},computed_{};
    const Step* initial_{};
    const Step* read(std::initializer_list<unsigned> q){
        if(stop_)return nullptr;
        if(index_==steps_.size()){stop_=1;return nullptr;}
        const auto& s=steps_[index_++];
        require(q.size()==s.request.size()&&std::equal(q.begin(),q.end(),s.request.begin()),"Unexpected CJ/1 request order");
        if(s.state==5)stop_=2;
        return s.state==1?&s:nullptr;
    }
    const Step* page(unsigned mode,unsigned pid){return mode==2?read({mode,pid,0}):read({mode,pid});}
    bool inventory(unsigned mode,bool cached=false,bool members=true){
        const unsigned off=mode==2?3:2;
        for(unsigned base=0;base<=224&&!stop_;base+=32){
            const auto* s=cached&&base==0?initial_:page(mode,base);
            if(!s)return false;
            const auto& r=s->response;
            if(members)for(unsigned bit=0;bit<31&&!stop_;++bit){
                if(!(r[off+bit/8]&(0x80u>>(bit%8))))continue;
                const unsigned pid=base+bit+1;
                if((mode==2||mode==9)&&pid==2)continue;
                (void)page(mode,pid);
            }
            if(!(r[off+3]&1))return !stop_;
        }
        return false;
    }
public:
    explicit Replay(const std::vector<Step>& steps):steps_(steps){}
    void verify(unsigned end,unsigned claimed){
        initial_=read({1,0});
        if(initial_){
            computed_|=1;(void)read({9,2});(void)read({3});(void)read({7});(void)read({10});
            if(inventory(1,true))computed_|=2;
            if(const auto* trigger=read({2,2,0})){
                computed_|=4;
                if(trigger->response[3]||trigger->response[4]){
                    computed_|=8;if(inventory(2))computed_|=16;
                }
            }
            if(inventory(6))computed_|=32;
            if(inventory(8,false,false))computed_|=64;
            if(inventory(9))computed_|=128;
        }
        const unsigned expected=stop_==2?4:stop_==1?(steps_.empty()&&end==4?4:3):!initial_?2:1;
        require(index_==steps_.size()&&expected==end&&claimed==computed_,"CJ/1 summary differs from exact campaign replay");
    }
};
void quote(std::ostream& out,std::string_view s){
    out<<'"';
    constexpr std::string_view digits="0123456789abcdef";
    for(unsigned char c:s){
        if(c=='"'||c=='\\')out<<'\\'<<char(c);
        else if(c<32||c==127)out<<"\\u00"<<digits[c>>4]<<digits[c&15];
        else out<<char(c);
    }
    out<<'"';
}
void nullable(std::ostream& out,std::string_view s){if(s.empty())out<<"null";else quote(out,s);}
void boolean(std::ostream& out,bool v){out<<(v?"true":"false");}
std::string canhex(std::uint32_t n){
    std::ostringstream s;s.imbue(std::locale::classic());s<<std::hex<<std::uppercase<<n;return s.str();
}
void measurement(std::ostream& out,const Step& s){
    j1979_measurement m{};
    if(s.state!=1||!j1979_measurement_decode(s.request.data(),s.request.size(),s.response.data(),s.response.size(),&m)){
        out<<"null";return;
    }
    out<<"{\"mode\":"<<unsigned(m.mode)<<",\"pid\":"<<unsigned(m.pid)<<",\"name\":";quote(out,m.name);
    out<<",\"unit\":";quote(out,m.unit);out<<",\"context_required\":";boolean(out,m.context_required);
    out<<",\"number\":";if(m.interpreted)out<<std::setprecision(15)<<m.number;else out<<"null";out<<'}';
}
void monitors(std::ostream& out,const Step& s){
    if(s.state!=1||s.request.size()!=2||s.request[0]!=6||s.request[1]%32==0){out<<"null";return;}
    out<<'[';
    auto n=j1979_monitor_count(s.request.data(),s.response.data(),s.response.size());
    for(std::size_t i=0;i<n;++i){
        j1979_monitor_record m{};
        require(j1979_monitor_decode(s.request.data(),s.response.data(),s.response.size(),i,&m),"Invalid monitor record");
        if(i)out<<',';
        out<<"{\"monitor_id\":"<<unsigned(m.monitor_id)<<",\"test_id\":"<<unsigned(m.test_id)
            <<",\"unit_scaling_id\":"<<unsigned(m.scaling_id)<<",\"value_encoded\":"<<m.encoded_value
            <<",\"minimum_encoded\":"<<m.encoded_minimum<<",\"maximum_encoded\":"<<m.encoded_maximum
            <<",\"scaled\":";
        if(m.scaled){
            out<<"{\"unit\":";quote(out,m.unit);out<<",\"value\":"<<std::setprecision(15)<<m.value
                <<",\"minimum\":"<<m.minimum<<",\"maximum\":"<<m.maximum<<",\"numeric_relation\":";
            quote(out,j1979_monitor_relation_name(m.relation));out<<",\"monitor_verdict\":null}";
        }else out<<"null";
        out<<'}';
    }
    out<<']';
}
void codes(std::ostream& out,const Step& s){
    const unsigned m=s.request[0];
    if(s.state!=1||(m!=3&&m!=7&&m!=10)){out<<"null";return;}
    out<<'[';
    for(std::size_t i=2;i<s.response.size();i+=2){
        if(i>2)out<<',';
        char code[6]{};
        require(cd_j1979_native_dtc_code(s.response.data()+i,code),"Invalid DTC");
        quote(out,code);
    }
    out<<']';
}
void page_json(std::ostream& out,const Step& s){
    if(s.state!=1||s.request.size()<2||s.request[1]%32){out<<"null";return;}
    const unsigned m=s.request[0],base=s.request[1],off=m==2?3:2;
    out<<"{\"mode\":"<<m<<",\"base\":"<<base<<",\"supported_ids\":[";
    bool first=true;
    for(unsigned bit=0;bit<31;++bit)if(s.response[off+bit/8]&(0x80u>>(bit%8))){
        if(!first)out<<',';
        first=false;out<<base+bit+1;
    }
    out<<"],\"has_next_page\":";boolean(out,(s.response[off+3]&1)!=0);out<<'}';
}
}
bool valid_profile(const Profile& p){
    return p.budget>=1&&p.budget<=600000&&cd_j1979_native_physical_pair(p.tx,p.rx,p.extended);
}
std::array<std::uint8_t,13> encode_profile(const Profile& p){
    require(valid_profile(p),"Invalid CJ/1 physical profile");
    std::array<std::uint8_t,13> a{};
    const auto put=[&](unsigned offset,std::uint32_t n){for(unsigned i=0;i<4;++i)a[offset+i]=std::uint8_t(n>>(8*i));};
    put(0,p.tx);put(4,p.rx);a[8]=p.extended?1:0;put(9,p.budget);return a;
}
Evidence inspect(Bytes bytes){
    Evidence e{};
    try{
        require(!bytes.empty()&&bytes.size()<=max_capture,"Invalid CJ/1 input size");
        std::size_t offset=0;
        for(unsigned seq=0;seq<max_records;++seq){
            require(bytes.size()-offset>=8,"Truncated CJ/1 header");
            const auto h=bytes.subspan(offset,8);
            const unsigned length=u16(h,6),kind=h[3];
            require(h[0]=='C'&&h[1]=='J'&&h[2]==1&&u16(h,4)==seq&&length<=max_body,
                "Invalid CJ/1 header or sequence");
            require(bytes.size()-offset>=12+length,"Truncated CJ/1 body");
            const auto body=bytes.subspan(offset+8,length);
            require(crc(bytes.subspan(offset,8+length))==u32(bytes,offset+8+length),"CJ/1 CRC mismatch");
            offset+=12+length;
            if(seq==0){
                require(kind==1&&length==13&&body[8]<=1,"Missing or invalid CJ/1 campaign profile");
                const Profile p{u32(body,0),u32(body,4),u32(body,9),body[8]==1};
                require(valid_profile(p),"Invalid CJ/1 physical profile");
                e.profile=p;e.profile_verified=true;continue;
            }
            if(kind==2){
                auto s=step(body,seq);
                if(s.state==1&&s.request==std::vector<std::uint8_t>{9,2})
                    e.vin.assign(s.response.begin()+3,s.response.end());
                ++e.counts[s.state];e.steps.push_back(std::move(s));continue;
            }
            require(kind==3&&length==22,"Unexpected CJ/1 record");
            const unsigned end=body[0],claimed=body[1];
            require(end>=1&&end<=5&&u32(body,2)==e.steps.size(),"Invalid CJ/1 terminal counters");
            for(unsigned i=1;i<=4;++i)require(u32(body,2+4*i)==e.counts[i],"CJ/1 terminal counters differ");
            Replay(e.steps).verify(end,claimed);
            require(offset==bytes.size(),"Trailing CJ/1 bytes");
            e.end=end;e.flags=claimed;e.integrity_verified=true;return e;
        }
        throw std::invalid_argument("CJ/1 record limit exceeded");
    }catch(const std::invalid_argument& failure){e.error=failure.what();}
    return e;
}
std::string report(const Evidence& e,std::string_view expected,std::string_view digest,bool live,bool authenticated,bool proof_sent){
    require(expected.empty()||vin_valid(expected),"Invalid independent VIN");
    require(digest.size()==64&&std::all_of(digest.begin(),digest.end(),[](char c){return(c>='0'&&c<='9')||(c>='a'&&c<='f');}),
        "Invalid capture digest");
    std::ostringstream out;out.imbue(std::locale::classic());
    out<<"{\"schema\":\"camarodiag-functional-j1979-embedded-evidence-linux-v1\","
        "\"protocol\":\"SAE-J1979\",\"source\":";
    quote(out,live?"LIVE_USB_CU1":"OFFLINE_CAPTURE");
    out<<",\"capture_integrity_verified\":";boolean(out,e.integrity_verified);
    out<<",\"device_credential_authenticated\":";boolean(out,live&&authenticated);
    out<<",\"client_proof_sent\":";boolean(out,live&&proof_sent);
    out<<",\"physical_vehicle_verified\":false,\"hardware_provenance_verified\":false,"
        "\"evidence_authenticated\":false,\"permits_programming\":false,\"declared_vin\":";
    nullable(out,expected);out<<",\"observed_vin\":";nullable(out,e.vin);
    out<<",\"vin_match\":";quote(out,expected.empty()?"NOT_REQUESTED":
        !e.integrity_verified||e.vin.empty()||(live&&!authenticated)?"UNVERIFIED":e.vin==expected?"MATCH":"MISMATCH");
    out<<",\"profile\":";
    if(e.profile_verified){
        out<<"{\"request_can_id\":";quote(out,canhex(e.profile.tx));out<<",\"response_can_id\":";quote(out,canhex(e.profile.rx));
        out<<",\"extended\":";boolean(out,e.profile.extended);out<<",\"budget_ms\":"<<e.profile.budget<<'}';
    }else out<<"null";
    out<<",\"summary\":";
    if(e.integrity_verified){
        out<<"{\"end\":";quote(out,ends.at(e.end));out<<",\"steps\":"<<e.steps.size()<<",\"counts\":{";
        for(unsigned i=1;i<=5;++i){if(i>1)out<<',';quote(out,states[i]);out<<':'<<e.counts[i];}
        out<<'}';for(unsigned i=0;i<flags.size();++i){out<<',';quote(out,flags[i]);out<<':';boolean(out,(e.flags&(1u<<i))!=0);}
        out<<'}';
    }else out<<"null";
    out<<",\"error\":";nullable(out,e.error);
    out<<",\"steps\":[";
    bool first=true;
    for(const auto& s:e.steps){
        if(!first)out<<',';
        first=false;out<<"{\"sequence\":"<<s.sequence<<",\"status\":";quote(out,states.at(s.state));
        out<<",\"request_hex\":";quote(out,hex(s.request));out<<",\"response_hex\":";nullable(out,hex(s.response));
        out<<",\"duration_ms\":"<<s.duration<<",\"negative_response_code\":";
        if(s.state==3)out<<unsigned(s.response[2]);else out<<"null";
        out<<",\"dtc_codes\":";codes(out,s);out<<",\"measurement\":";measurement(out,s);
        out<<",\"monitor_records\":";monitors(out,s);out<<",\"supported_page\":";page_json(out,s);out<<'}';
    }
    out<<"],\"service_observations\":[";
    first=true;
    for(unsigned mode:{1u,2u,3u,6u,7u,8u,9u,10u}){
        std::array<unsigned,6> counts{};
        for(const auto& s:e.steps)if(s.request[0]==mode)++counts[s.state];
        if(!first)out<<',';
        first=false;out<<"{\"mode\":";quote(out,hex(std::array<std::uint8_t,1>{std::uint8_t(mode)}));
        unsigned total=0;for(unsigned i=1;i<=5;++i)total+=counts[i];out<<",\"attempted\":"<<total;
        for(unsigned i=1;i<=5;++i){std::string key(states[i]);for(char& c:key)c=char(c-'A'+'a');out<<',';quote(out,key);out<<':'<<counts[i];}
        out<<'}';
    }
    out<<"],\"capture_sha256\":";quote(out,digest);out<<"}\n";
    auto result=out.str();require(result.size()<=max_report,"CJ/1 report byte limit");return result;
}
}
