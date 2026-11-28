#include "j1979_legacy_discovery_history.hpp"
#include <algorithm>
#include <map>
#include <sstream>
#include <stdexcept>

namespace cd::legacy::history {
namespace {
using json::Value;
void require(bool b,const char* reason){if(!b)throw std::invalid_argument(reason);}
const Value& optional(const Value& v,std::string_view k){
    static const Value null{};
    const auto* p=v.find(k);return p?*p:null;
}
std::optional<std::string> nullable(const Value& v){
    if(v.null())return {};
    require(v.string().size()<=4096,"Invalid discovery error");
    return v.string();
}
void no_claims(const Value& v){
    for(const auto k:{"physical_vehicle_verified","whole_vehicle_coverage_verified",
        "evidence_authenticated","permits_programming","automatic_selection_permitted"}){
        const auto* p=v.find(k);
        require(!p||(!p->null()&&!p->boolean()),"Unsupported discovery claim");
    }
}
const Value::Array& bounded(const Value& v,std::size_t maximum){
    const auto& a=v.array();require(a.size()<=maximum,"Too many discovery records");return a;
}
const std::string& string(const Value& v,std::size_t maximum){
    const auto& s=v.string();require(s.size()<=maximum,"Discovery text too long");return s;
}
Protocol protocol_number(const Value& v){
    const auto n=v.unsigned_integer();require(n>=1&&n<=5,"Invalid fixed legacy transport");
    return static_cast<Protocol>(n);
}
std::optional<Protocol> observed(const Value& v){
    if(v.null())return {};
    return protocol_number(v);
}
Protocol variant(const Value& v){
    const auto& s=v.string();
    if(s=="Pwm")return Protocol::pwm;
    if(s=="Vpw")return Protocol::vpw;
    if(s=="Iso9141")return Protocol::iso9141;
    if(s=="KwpSlow")return Protocol::kwp_slow;
    if(s=="KwpFast")return Protocol::kwp_fast;
    throw std::invalid_argument("Unknown driver variant");
}
Data bytes(const Value& v,std::size_t max){
    const auto& s=v.string();require(!s.empty()&&s.size()<=2*max&&s.size()%2==0,"Invalid discovery hex size");
    Data out;out.reserve(s.size()/2);
    const auto digit=[](char c)->unsigned{
        if(c>='0'&&c<='9')return unsigned(c-'0');
        if(c>='A'&&c<='F')return unsigned(c-'A'+10);
        if(c>='a'&&c<='f')return unsigned(c-'a'+10);
        throw std::invalid_argument("Invalid discovery hex");
    };
    for(std::size_t i=0;i<s.size();i+=2)out.push_back(static_cast<std::uint8_t>(16*digit(s[i])+digit(s[i+1])));
    return out;
}
unsigned source(const Value& v){
    auto b=bytes(v,1);require(b.size()==1&&valid_source(b[0]),"Invalid legacy source");return b[0];
}
std::string address(unsigned n){return hex(Data{static_cast<std::uint8_t>(n)});}
void expect_null_or_string(const Value& v,const std::optional<std::string>& expected){
    require(expected?(!v.null()&&v.string()==*expected):v.null(),"Saved discovery string differs");
}
void expect_sources(const Value& v,const std::vector<unsigned>& expected){
    const auto& a=bounded(v,254);require(a.size()==expected.size(),"Saved eligible sources differ");
    for(std::size_t i=0;i<a.size();++i)require(a[i].string()==address(expected[i]),"Saved eligible source differs");
}
std::vector<unsigned> eligible(const std::vector<Responder>& rows){
    std::vector<unsigned> out;
    for(const auto& r:rows)if(r.status=="PASSED")out.push_back(r.source);
    return out;
}
// Android and J2534 accept repeated identical positives; ELM does not.
// Do not reuse the ELM-only classification for these historical reports.
std::vector<Responder> project(const std::vector<Frame>& frames,bool elm){
    std::map<unsigned,std::vector<Frame>> grouped;
    for(const auto& f:frames)grouped[f.source].push_back(f);
    std::vector<Responder> out;
    for(auto& [id,rows]:grouped){
        Responder r{};r.source=id;
        const auto& first=rows.front().payload;
        const bool same=std::all_of(rows.begin(),rows.end(),[&](const Frame& f){return f.payload==first;});
        const bool positive=same&&first.size()==6&&first[0]==0x41&&first[1]==0&&(!elm||rows.size()==1);
        const bool negative=std::all_of(rows.begin(),rows.end(),[&](const Frame& f){
            return f.payload.size()==3&&f.payload[0]==0x7f&&f.payload[1]==1&&f.payload[2]!=0&&
                (!elm||f.payload[2]!=0x78);
        })&&(!elm||rows.size()==1);
        r.status=positive?"PASSED":negative?"NOT_TESTED":"FAILED";
        if(positive){
            std::array<std::uint8_t,4> bitmap{};
            std::copy_n(first.begin()+2,4,bitmap.begin());r.bitmap=bitmap;
            for(unsigned i=0;i<31;++i)if(bitmap[i/8]&(0x80u>>(i%8)))r.supported.push_back(i+1);
            r.next=(bitmap.back()&1)!=0;
        }else if(negative){
            r.detail=elm?"NRC_"+hex(Data{first[2]}):"NEGATIVE_RESPONSE";
        }else r.detail=elm?"AMBIGUOUS_OR_INVALID_PID00":"CONFLICTING_OR_MALFORMED_RESPONSE";
        r.observations=std::move(rows);out.push_back(std::move(r));
    }
    return out;
}
void compare_frame(const Value& v,const Frame& f){
    require(v.at("source").string()==address(f.source)&&
        v.at("raw_hex").string()==hex(f.raw)&&v.at("payload_hex").string()==hex(f.payload),
        "Saved frame differs from original bytes");
}
void compare_frames(const Value& v,const std::vector<Frame>& f){
    const auto& a=bounded(v,128);
    require(a.size()==f.size(),"Saved frame count differs");
    for(std::size_t i=0;i<a.size();++i)compare_frame(a[i],f[i]);
}
void compare_android(const Value& body,const std::vector<Responder>& expected){
    const auto& a=bounded(body.at("sources"),254);
    require(a.size()==expected.size(),"Android responder count differs");
    for(std::size_t i=0;i<a.size();++i){
        const auto& r=expected[i];const auto& v=a[i];
        require(v.at("source").string()==address(r.source)&&v.at("status").string()==r.status,
            "Android responder differs");
        expect_null_or_string(v.at("supported_pid_bitmap"),r.bitmap?std::optional<std::string>(hex(*r.bitmap)):std::nullopt);
        const auto& raw=bounded(v.at("raw_frames"),128);
        require(raw.size()==r.observations.size(),"Android frame count differs");
        for(std::size_t j=0;j<raw.size();++j)require(raw[j].string()==hex(r.observations[j].raw),"Android frame differs");
    }
}
void compare_elm(const Value& body,const std::vector<Responder>& expected){
    const auto& a=bounded(body.at("responders"),254);
    require(a.size()==expected.size(),"ELM responder count differs");
    for(std::size_t i=0;i<a.size();++i){
        const auto& r=expected[i];const auto& v=a[i];
        require(v.at("source").string()==address(r.source)&&v.at("status").string()==r.status,
            "ELM responder differs");
        const auto& p=optional(v,"supported_pids");
        const auto& n=optional(v,"has_next_page");
        if(r.bitmap){
            const auto& pids=bounded(p,31);
            require(pids.size()==r.supported.size(),"ELM PID count differs");
            for(std::size_t j=0;j<pids.size();++j)require(pids[j].unsigned_integer()==r.supported[j],"ELM PID differs");
            require(n.boolean()==*r.next,"ELM continuation differs");
        }else require(p.null()&&n.null(),"Unsupported ELM PID claims");
        expect_null_or_string(optional(v,"detail"),r.detail.empty()?std::nullopt:std::optional<std::string>(r.detail));
        compare_frames(v.at("frames"),r.observations);
    }
}
Frame driver_frame(const Value& v,Protocol p,unsigned id){
    auto raw=bytes(v.at("raw_hex"),11);
    auto payload=bytes(v.at("payload_hex"),7);
    require(raw.size()>=4&&raw[2]==id&&source(v.at("source"))==id,"Driver source differs");
    std::size_t offset=3;
    if(p==Protocol::kwp_slow||p==Protocol::kwp_fast){
        require((raw[0]&0xc0)==0x80&&raw[1]==0xf1,"Invalid driver KWP address");
        unsigned width=raw[0]&0x3f;
        if(!width){require(raw.size()>=5,"Missing explicit KWP length");width=raw[3];offset=4;}
        require(width==payload.size(),"Driver KWP length differs");
    }else require(raw[0]==(p==Protocol::pwm?0x41:0x48)&&raw[1]==0x6b,"Invalid driver header");
    require(std::equal(raw.begin()+static_cast<std::ptrdiff_t>(offset),raw.end(),payload.begin(),payload.end()),
        "Driver payload differs from raw bytes");
    require(v.at("timestamp").unsigned_integer()<=0xffffffffu,"Invalid driver timestamp");
    if(!v.at("in_frame_response_hex").null())(void)bytes(v.at("in_frame_response_hex"),12);
    return {id,std::move(raw),std::move(payload)};
}
std::optional<Protocol> historical_probe(std::string raw){
    // Historical Android's parser accepts lowercase echo.
    for(char& c:raw)if(c>='a'&&c<='z')c=char(c-'a'+'A');
    return fixed_protocol(raw);
}
DiscoveryInspection android(const Value& body){
    DiscoveryInspection r;r.platform="ANDROID";r.protocol=protocol_number(body.at("selected_protocol"));
    const auto probe=historical_probe(string(body.at("raw_protocol_response"),4096));
    require(observed(body.at("observed_protocol"))==probe,"Android protocol probe differs");
    const auto& raw=string(body.at("raw_response"),32768);
    r.error=nullable(optional(body,"error"));
    std::vector<Frame> parsed;
    if(probe!=r.protocol)require(r.error=="PROTOCOL_UNVERIFIED","Unreflected protocol mismatch");
    else{
        bool valid=true;
        try{parsed=frames(r.protocol,"0100",raw);}
        catch(const std::invalid_argument&){valid=false;}
        require(valid?!r.error:r.error=="MALFORMED_LEGACY_FRAME","Unreflected frame parsing outcome");
    }
    r.responders=project(parsed,false);compare_android(body,r.responders);
    r.completed=!r.error;r.probe_verified=probe==r.protocol;r.checksum_verified=!parsed.empty();
    if(r.completed)r.eligible=eligible(r.responders);
    expect_sources(body.at("verified_sources"),r.eligible);return r;
}
DiscoveryInspection driver(const Value& root,const Value& body){
    DiscoveryInspection r;r.platform="J2534";r.protocol=variant(root.at("variant"));
    require(root.at("selected_response_source").null(),"Discovery pins a source");
    require(root.at("baud").unsigned_integer()==(r.protocol==Protocol::pwm?41600u:10400u),"Driver baud differs");
    const bool kwp=r.protocol==Protocol::kwp_slow||r.protocol==Protocol::kwp_fast;
    const Data tx{static_cast<std::uint8_t>(kwp?0xc2:r.protocol==Protocol::pwm?0x61:0x68),
        static_cast<std::uint8_t>(kwp?0x33:0x6a),0xf1,1,0};
    require(body.at("request_hex").string()=="0100"&&bytes(body.at("tx_hex"),10)==tx,"Driver discovery request differs");
    const auto& groups=bounded(body.at("sources"),254);
    std::vector<Frame> all;
    for(const auto& group:groups){
        const auto id=source(group.at("response_source"));
        const auto& a=bounded(group.at("records"),128);
        for(const auto& v:a)all.push_back(driver_frame(v,r.protocol,id));
    }
    r.responders=project(all,false);
    require(groups.size()==r.responders.size(),"Driver responder count differs");
    for(std::size_t i=0;i<groups.size();++i){
        const auto& v=groups[i];const auto& row=r.responders[i];
        require(v.at("response_source").string()==address(row.source)&&v.at("status").string()==row.status,
            "Driver responder differs");
        require(v.at("detail").string()==(row.bitmap?hex(*row.bitmap):row.detail),"Driver status detail differs");
    }
    r.eligible=eligible(r.responders);
    require(body.at("has_verified_source").boolean()==!r.eligible.empty(),"Driver eligibility differs");
    r.error=nullable(optional(root,"error"));r.completed=!r.error;
    if(!r.completed)r.eligible.clear();
    // The driver did not supply a link checksum or independently observed probe.
    return r;
}
DiscoveryInspection elm(const Value& root){
    DiscoveryInspection r;r.platform="ELM";r.protocol=protocol_number(root.at("expected_protocol"));
    const auto probe=observed(root.at("observed_protocol"));
    require(!probe||probe==r.protocol,"ELM observed protocol differs");
    const auto& steps=bounded(root.at("steps"),1);
    require(steps.size()==1&&steps[0].at("request_hex").string()=="0100","Expected one PID00 observation");
    const auto& step=steps[0];
    r.error=nullable(optional(root,"stop_reason"));r.completed=root.at("completed").boolean();
    require(r.completed==!r.error,"Inconsistent discovery completion");
    std::vector<Frame> parsed;
    const auto& raw=optional(step,"raw_response");
    if(!raw.null()){
        const auto& raw_text=string(raw,256*1024);
        try{parsed=frames(r.protocol,"0100",raw_text);}
        catch(const std::invalid_argument&){
            require(r.error=="INVALID_RESPONSE_OR_PROTOCOL"||r.error=="BUDGET_EXHAUSTED","Malformed discovery not reflected");
        }
    }
    const auto& claimed=step.at("frames");
    if(r.error=="BUDGET_EXHAUSTED"&&claimed.array().empty())parsed.clear();
    compare_frames(claimed,parsed);
    if(probe==r.protocol){r.responders=project(parsed,true);r.checksum_verified=!parsed.empty();}
    compare_elm(root,r.responders);
    r.eligible=eligible(r.responders);
    require(!r.completed||!r.eligible.empty(),"Discovery completed without eligible source");
    if(!r.completed)r.eligible.clear();
    return r;
}
const char* tf(bool v){return v?"true":"false";}
}
DiscoveryInspection inspect(const Value& root){
    no_claims(root);
    if(const auto* body=root.find("legacySourceDiscovery")){no_claims(*body);return android(*body);}
    require(root.at("schema").string()=="camarodiag-functional-j1979-legacy-discovery-v1","Unsupported historical discovery schema");
    if(const auto* body=root.find("discovery")){no_claims(*body);return driver(root,*body);}
    return elm(root);
}
std::string report(const DiscoveryInspection& r,std::string_view digest){
    std::ostringstream out;
    out<<"{\"schema\":\"camarodiag-j1979-legacy-discovery-inspection-v1\",\"consistent\":true,\"platform\":"
        <<json::quote(r.platform)<<",\"protocol\":"<<static_cast<unsigned>(r.protocol)<<",\"completed\":"<<tf(r.completed)
        <<",\"error\":"<<(r.error?json::quote(*r.error):"null")<<",\"responders\":[";
    for(std::size_t i=0;i<r.responders.size();++i){
        if(i)out<<',';
        const auto& row=r.responders[i];
        out<<"{\"source\":"<<json::quote(address(row.source))<<",\"status\":"<<json::quote(row.status)
            <<",\"supported_pid_bitmap\":"<<(row.bitmap?json::quote(hex(*row.bitmap)):"null")<<",\"supported_pids\":";
        if(row.bitmap){
            out<<'[';
            for(std::size_t j=0;j<row.supported.size();++j){if(j)out<<',';out<<row.supported[j];}
            out<<']';
        }else out<<"null";
        out<<",\"has_next_page\":"<<(row.next?tf(*row.next):"null")<<",\"raw_frames\":[";
        for(std::size_t j=0;j<row.observations.size();++j){if(j)out<<',';out<<json::quote(hex(row.observations[j].raw));}
        out<<"]}";
    }
    out<<"],\"eligible_sources\":[";
    for(std::size_t i=0;i<r.eligible.size();++i){if(i)out<<',';out<<json::quote(address(r.eligible[i]));}
    out<<"],\"link_checksum_verified\":"<<tf(r.checksum_verified)
        <<",\"protocol_probe_independently_verified\":"<<tf(r.probe_verified)
        <<",\"whole_vehicle_coverage_verified\":false,\"physical_vehicle_verified\":false,"
          "\"evidence_authenticated\":false,\"automatic_selection_permitted\":false";
    if(!digest.empty())out<<",\"source_sha256\":"<<json::quote(digest);
    out<<"}\n";return out.str();
}
}
