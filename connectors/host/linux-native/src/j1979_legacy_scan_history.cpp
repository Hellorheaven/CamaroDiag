#include "j1979_legacy_scan_history.hpp"
#include "j1979_legacy_shape.hpp"
#include "j1979_policy.h"
#include "j1979_native_report_detail.hpp"
#include <algorithm>
#include <map>
#include <sstream>
#include <stdexcept>
namespace cd::legacy::history {
namespace {
using json::Value;
using cd::network::history::detail::require;
using cd::network::history::detail::bytes;
const Value* property(const Value& v,std::string_view key){
    std::string camel;bool upper=false;
    for(char c:key){
        if(c=='_'){upper=true;continue;}
        if(upper&&c>='a'&&c<='z')c=static_cast<char>(c-'a'+'A');
        upper=false;camel+=c;
    }
    const auto* a=v.find(key);
    const auto* b=camel==key?nullptr:v.find(camel);
    require(!a||!b,"Ambiguous legacy archive property");return a?a:b;
}
const Value& optional(const Value& v,std::string_view k){
    static const Value nil{};const auto* p=property(v,k);return p?*p:nil;
}
const Value& field(const Value& v,std::string_view k){
    const auto* p=property(v,k);require(p,"Missing legacy archive property");return *p;
}
const Value::Array& array(const Value& v,std::size_t limit){
    const auto& a=v.array();require(a.size()<=limit,"Legacy archive record limit");return a;
}
unsigned bounded(const Value& v,unsigned max){
    const auto n=v.unsigned_integer();require(n<=max,"Legacy archive integer limit");return static_cast<unsigned>(n);
}
unsigned source(const Value& v){
    const auto b=bytes(v,1);require(b.size()==1&&valid_source(b[0]),"Invalid selected legacy source");return b[0];
}
std::string address(unsigned n){return hex(Data{static_cast<std::uint8_t>(n)});}
std::optional<std::string> vin(const Value& v){
    if(v.null())return {};
    const auto& s=v.string();require(cd_j1979_native_vin(s.data(),s.size()),"Invalid legacy VIN");return s;
}
void no_claims(const Value& v){
    for(auto k:{"physical_vehicle_verified","whole_vehicle_coverage_verified","evidence_authenticated",
                "physically_addressed","permits_programming","current_authorization_verified"})
        if(const auto* p=property(v,k))require(!p->boolean(),"Unsupported legacy verification claim");
}
bool schema(const Value& v){
    const auto& s=field(v,"schema").string();
    return s=="camarodiag-functional-j1979-legacy-v1"||s=="camarodiag-functional-j1979-legacy-source-v1";
}
bool kwp(Protocol p){return p==Protocol::kwp_fast||p==Protocol::kwp_slow;}
Protocol variant(std::string_view s){
    if(s=="Pwm")return Protocol::pwm;
    if(s=="Vpw")return Protocol::vpw;
    if(s=="Iso9141")return Protocol::iso9141;
    if(s=="KwpSlow")return Protocol::kwp_slow;
    if(s=="KwpFast")return Protocol::kwp_fast;
    throw std::invalid_argument("Unknown legacy driver variant");
}
const Value& admission(const Value& root,HistoricalScan& out){
    require(schema(root),"Unsupported historical legacy scan schema");no_claims(root);
    const auto* driver=root.find("result");
    const auto* android=root.find("legacyRead");
    require(!driver||!android,"Ambiguous legacy archive wrapper");
    const auto& body=driver?*driver:android?*android:root;no_claims(body);
    out.driver=driver!=nullptr;
    out.source=source(field(body,"selected_response_source"));
    if(driver){
        out.protocol=variant(field(root,"variant").string());
        require(source(field(root,"selected_response_source"))==out.source,"Driver selected source mismatch");
        require(bounded(field(root,"baud"),41600)==(out.protocol==Protocol::pwm?41600u:10400u),"Driver baud differs");
        out.probe_observed=field(body,"transport_observed").boolean();
        if(out.probe_observed&&(out.protocol==Protocol::iso9141||out.protocol==Protocol::kwp_slow))
            require(bytes(field(root,"initialization_evidence"),2).size()==2,"Missing slow initialization keywords");
        if(out.probe_observed&&out.protocol==Protocol::kwp_fast){
            const auto b=bytes(field(root,"initialization_evidence"),10);
            require(b.size()==6&&b[0]==0x83&&b[1]==0xf1&&b[2]==out.source&&b[3]==0xc1,
                "Invalid fast initialization evidence");
        }
    }else{
        if(android)require(schema(body)&&vin(optional(root,"selected_vin"))==vin(optional(body,"declared_vin")),
            "Android outer VIN differs");
        out.protocol=static_cast<Protocol>(bounded(field(body,"expected_protocol"),5));
        require(valid_protocol(out.protocol),"Invalid legacy profile");
        const auto& observed=optional(body,"observed_protocol");
        if(!observed.null()){
            require(bounded(observed,5)==static_cast<unsigned>(out.protocol),"Observed legacy protocol differs");
            out.probe_observed=true;
        }
        const auto& raw=optional(body,"raw_protocol_response");
        if(!raw.null()&&out.probe_observed){
            require(fixed_protocol(raw.string())==out.protocol,"Fixed legacy protocol transcript differs");
            out.probe_replayed=true;
        }
    }
    out.declared_vin=vin(optional(body,"declared_vin"));
    return body;
}
Frame frame_claim(const Value& v){
    return {source(field(v,"source")),bytes(field(v,"raw_hex"),11),bytes(field(v,"payload_hex"),7)};
}
Frame driver_frame(const Value& v,Protocol p,unsigned id){
    auto f=frame_claim(v);
    require(f.source==id&&f.raw.size()>=4&&f.raw[2]==id&&!f.payload.empty(),"Driver response source differs");
    std::size_t offset=3;
    if(kwp(p)){
        require((f.raw[0]&0xc0)==0x80&&f.raw[1]==0xf1,"Invalid driver KWP header");
        unsigned count=f.raw[0]&63;
        if(!count){require(f.raw.size()>=5,"Missing KWP explicit length");count=f.raw[3];offset=4;}
        require(count==f.payload.size(),"Driver KWP length differs");
    }else require(f.raw[0]==(p==Protocol::pwm?0x41:0x48)&&f.raw[1]==0x6b,"Invalid driver legacy header");
    require(f.raw.size()==offset+f.payload.size()&&std::equal(f.payload.begin(),f.payload.end(),f.raw.begin()+offset),
        "Driver payload differs from raw frame");
    (void)bounded(field(v,"timestamp"),0xffffffffu);
    if(const auto& extra=optional(v,"in_frame_response_hex");!extra.null())(void)bytes(extra,12);
    return f;
}
Data tx_frame(Protocol p,Bytes q){
    Data out=kwp(p)?Data{static_cast<std::uint8_t>(0xc0u|q.size()),0x33,0xf1}:
        Data{static_cast<std::uint8_t>(p==Protocol::pwm?0x61:0x68),0x6a,0xf1};
    out.insert(out.end(),q.begin(),q.end());return out;
}
bool accepted_status(std::string_view s){return s=="PASSED"||s=="RAW";}
std::vector<Data> records(const Value& v,HistoricalStep& step,HistoricalScan& result,std::size_t& total){
    std::vector<Frame> observed;
    const auto q=Bytes(step.request);
    const bool accepted=accepted_status(step.status);
    if(result.driver){
        const auto& tx=optional(v,"tx_hex");
        if(!tx.null())require(bytes(tx,10)==tx_frame(result.protocol,q),"Driver request differs from transmitted frame");
        for(const auto& row:array(field(v,"responses"),128))observed.push_back(driver_frame(row,result.protocol,result.source));
        require(observed.empty()||!tx.null(),"Driver response without transmission");
    }else{
        const auto* claims=property(v,"accepted_records");const auto* old=property(v,"records_hex");
        require(!claims||!old,"Ambiguous accepted legacy records");
        std::vector<Data> declared;
        if(const auto* rows=claims?claims:old)for(const auto& row:array(*rows,128))declared.push_back(bytes(row,7));
        std::vector<Frame> saved;
        if(const auto* fs=property(v,"frames"))for(const auto& row:array(*fs,128))saved.push_back(frame_claim(row));
        const auto& raw=optional(v,"raw_response");
        const auto& detail=optional(v,"detail");
        const bool late=step.status=="NOT_TESTED"&&saved.empty()&&!detail.null()&&detail.string()=="LATE_RESPONSE";
        if(!raw.null()){
            const auto& text=raw.string();
            require(text.size()<=32768&&text.size()<=16u*1024u*1024u-total,"Legacy transcript size limit");
            total+=text.size();
            try{observed=frames(result.protocol,hex(q),text);}
            catch(const std::invalid_argument&){
                require(!accepted&&declared.empty()&&saved.empty(),"Malformed legacy transcript accepted");return {};
            }
        }
        if(late){require(declared.empty(),"Late response accepted");return {};}
        require(saved==observed,"Saved legacy frame differs from original transcript");
        std::vector<Data> selected;
        for(const auto& f:observed){
            if(f.source==result.source)selected.push_back(f.payload);
            else result.other_sources.insert(f.source);
        }
        require(accepted?declared==selected:declared.empty()||declared==selected,"Accepted records differ from selected source");
        return accepted?selected:std::vector<Data>{};
    }
    std::vector<Data> selected;
    if(accepted)for(auto& f:observed)selected.push_back(std::move(f.payload));
    return selected;
}
bool page(Bytes q){return q.size()>1&&q[1]%32==0;}
bool single(Bytes q){return q[0]==1||q[0]==2||page(q)||(q[0]==9&&count_kind(q[1]));}
bool shape(Bytes q,Bytes r){
    if(r.empty()||r.size()>7||r[0]!=q[0]+0x40)return false;
    const unsigned m=q[0];
    if(m==3||m==7||m==10)return r.size()==7;
    if(r.size()<2||r[1]!=q[1])return false;
    if(page(q)){
        const unsigned offset=m==1?2:3;
        if(r.size()!=offset+4||(q[1]==224&&(r.back()&1)))return false;
        if(m==1)return true;
        const unsigned expected=m==6?255:m==9?1:m==5?q[2]:0;
        return r[2]==expected;
    }
    if(m==1||m==2){
        const auto s=pid_shape(q,r);
        if(m==1&&q[1]==0x50&&r.size()==6&&(r[3]||r[4]||r[5]))return false;
        return s==ApplicationShape::positive||s==ApplicationShape::raw;
    }
    if(m==5)return r.size()==(q[1]>=1&&q[1]<=4?4u:6u)&&r[2]==q[2];
    if(m==6)return r.size()==7;
    if(m==9)return r.size()==(count_kind(q[1])?3u:7u)&&r[2]!=0;
    return false;
}
struct Chain{Inventory inventory;std::optional<unsigned> next{0};};
using Key=std::pair<unsigned,unsigned>;
Data request(unsigned mode,unsigned pid,unsigned sensor){
    Data q{static_cast<std::uint8_t>(mode),static_cast<std::uint8_t>(pid)};
    if(mode==2||mode==5)q.push_back(static_cast<std::uint8_t>(sensor));
    return q;
}
std::string dtc(const std::uint8_t* b){
    char text[6]{};require(cd_j1979_native_dtc_code(b,text),"Invalid legacy DTC");return text;
}
}
HistoricalScan inspect_historical_scan(const Value& root,std::optional<std::string> expected){
    HistoricalScan out{};
    const auto& body=admission(root,out);
    if(expected){
        require(cd_j1979_native_vin(expected->data(),expected->size()),"Invalid independent inspection VIN");
        require(!out.declared_vin||out.declared_vin==expected,"Declared VIN differs from inspection scope");
    }
    out.completed=field(body,"completed").boolean();
    const auto& stop=optional(body,"stop_reason");
    if(!stop.null())out.stop=stop.string();
    require(out.completed==out.stop.empty()&&out.stop.size()<=256,"Legacy completion and stop disagree");
    out.duration=bounded(field(body,"duration_ms"),610000);
    std::set<Data> seen;
    std::map<Key,Chain> chains;
    std::map<unsigned,unsigned> counts;
    std::set<unsigned> sensors;
    std::uint64_t elapsed=0;
    std::size_t raw_total=0;
    for(const auto& v:array(field(body,"steps"),4096)){
        HistoricalStep step{};
        step.request=bytes(field(v,"request_hex"),7);
        const auto& q=step.request;
        require(read_allowed(q)&&seen.insert(q).second,"Duplicate, mutating or invalid historical request");
        const unsigned mode=q[0],pid=q.size()>1?q[1]:0,sensor=mode==5?q[2]:0;
        if(out.steps.empty())require(q==Data{1,0},"Historical legacy report lacks initial PID00");
        else{
            require(!out.steps.front().accepted.empty()&&out.probe_observed,"Legacy request before transport admission");
            if(out.declared_vin&&mode!=9)require(out.observed_vin==out.declared_vin,"Legacy request before VIN match");
        }
        step.status=field(v,"status").string();
        require(accepted_status(step.status)||step.status=="FAILED"||step.status=="NOT_TESTED"||
            step.status=="TIMEOUT"||step.status=="NEGATIVE","Invalid legacy status");
        step.duration=bounded(field(v,"duration_ms"),out.duration+2);elapsed+=step.duration;
        step.accepted=records(v,step,out,raw_total);
        if(accepted_status(step.status))require(!step.accepted.empty()&&(!single(q)||step.accepted.size()==1)&&
            std::all_of(step.accepted.begin(),step.accepted.end(),[&](const Data& r){return shape(q,r);}),
            "Invalid or ambiguous positive legacy record");
        if(mode==5)require(sensors.contains(sensor),"Oxygen monitor without advertised sensor");
        if(mode==2&&pid!=2)require(out.freeze_present==true,"Freeze PID without trigger");
        const Key key{mode,sensor};
        if(page(q)){
            auto& c=chains[key];c.inventory.mode=mode;c.inventory.sensor=sensor;
            require(c.next==pid&&!c.inventory.complete,"Unadvertised legacy continuation page");
            c.next.reset();
            if(!step.accepted.empty()){
                const auto& r=step.accepted.front();const auto data=Bytes(r).last(4);
                for(unsigned i=0;i<31;++i)if(data[i/8]&(0x80u>>(i%8)))c.inventory.advertised.insert(pid+i+1);
                ++c.inventory.pages;c.inventory.complete=!(data.back()&1);
                if(!c.inventory.complete)c.next=pid+32;
            }
        }else if(q.size()>1&&!(mode==2&&pid==2)){
            require(chains.contains(key)&&chains.at(key).inventory.advertised.contains(pid),"Legacy request absent from inventory");
        }
        out.verified_responses+=static_cast<unsigned>(step.accepted.size());
        if(!step.accepted.empty()){
            const auto& r=step.accepted.front();
            if(mode==9&&pid%32){
                if(count_kind(pid))counts[pid]=r[2];
                else{
                    const unsigned count=matching_count_kind(pid);
                    require(counts.contains(count),"Missing legacy identification count");
                    const auto info=decode_identification(pid,counts.at(count),step.accepted);
                    require(info.has_value(),"Invalid legacy identification fragments");
                    if(pid==2)out.observed_vin=info->values.front();
                    if(info->interpreted)out.identification.push_back(*info);
                }
            }else if(mode==3||mode==7||mode==10){
                auto& codes=out.dtcs[mode];
                for(const auto& row:step.accepted)for(unsigned i=1;i<7;i+=2)
                    if(row[i]||row[i+1])codes.insert(dtc(row.data()+i));
            }else if(mode==1&&(pid==0x13||pid==0x1d)){
                for(unsigned i=0;i<8;++i)if(r[2]&(1u<<i))sensors.insert(1u<<i);
            }else if(mode==2&&pid==2){
                out.freeze_present=r[3]||r[4];if(*out.freeze_present)out.freeze_dtc=dtc(r.data()+3);
            }
        }
        out.steps.push_back(std::move(step));
    }
    require(elapsed<=out.duration+2ull*out.steps.size(),"Legacy step durations exceed campaign");
    require(vin(optional(body,"observed_vin"))==out.observed_vin,"Reported legacy VIN differs from fragments");
    if(expected)require(out.observed_vin==expected,"Observed VIN differs from independent inspection scope");
    const std::string match=!out.declared_vin?"NOT_REQUESTED":!out.observed_vin?"UNVERIFIED":
        out.declared_vin==out.observed_vin?"MATCH":"MISMATCH";
    if(const auto* p=property(body,"vin_match"))require(p->string()==match,"Saved VIN match differs");
    if(out.completed){
        require(out.probe_observed&&!out.steps.empty()&&!out.steps.front().accepted.empty()&&
            (!out.declared_vin||out.declared_vin==out.observed_vin),"Legacy completion without admission");
        for(unsigned m:{1u,6u,8u,9u})require(chains.contains({m,0}),"Completed legacy report omits inventory");
        for(unsigned m:{3u,7u,10u})require(seen.contains(Data{static_cast<std::uint8_t>(m)}),"Completed legacy report omits DTC request");
        require(seen.contains(Data{2,2,0}),"Completed legacy report omits freeze trigger");
        if(out.freeze_present==true)require(chains.contains({2,0}),"Completed legacy report omits freeze inventory");
        for(auto mask:sensors)require(chains.contains({5,mask}),"Completed legacy report omits oxygen inventory");
        for(const auto& [k,c]:chains){
            require(!c.next||c.inventory.complete,"Completed legacy report omits continuation page");
            if(k.first==8)continue;
            for(auto id:c.inventory.advertised){
                if(k.first==2&&id==2)continue;
                require(seen.contains(request(k.first,id,k.second)),"Completed legacy report omits advertised request");
            }
        }
    }
    for(auto& [k,c]:chains){(void)k;out.inventories.push_back(std::move(c.inventory));}
    if(!out.driver){
        std::set<unsigned> actual;
        if(const auto* p=property(body,"other_response_sources"))for(const auto& v:array(*p,254))
            require(actual.insert(source(v)).second,"Duplicate other source");
        require(actual==out.other_sources,"Other source list differs");
    }
    verify_historical_scan_summary(body,out);
    return out;
}
std::string historical_scan_report(const HistoricalScan& s,std::string_view digest){
    const auto nullable=[](const std::optional<std::string>& v){return v?json::quote(*v):std::string("null");};
    const auto boolean=[](bool b){return b?"true":"false";};
    std::ostringstream o;
    o<<"{\"schema\":\"camarodiag-j1979-historical-legacy-inspection-linux-v1\","
        "\"verification_scope\":\"SELECTED_SOURCE_FRAMES_AND_READ_PLAN\","
        "\"consistent\":true,\"completed\":"<<boolean(s.completed)<<",\"protocol\":"<<static_cast<unsigned>(s.protocol)
        <<",\"selected_response_source\":"<<json::quote(address(s.source))
        <<",\"declared_vin\":"<<nullable(s.declared_vin)<<",\"observed_vin\":"<<nullable(s.observed_vin)
        <<",\"stop_reason\":"<<(s.stop.empty()?"null":json::quote(s.stop))
        <<",\"verified_response_count\":"<<s.verified_responses
        <<",\"link_checksum_verified\":"<<boolean(!s.driver&&s.verified_responses>0)
        <<",\"protocol_probe_replayed\":"<<boolean(s.probe_replayed)<<",\"steps\":[";
    bool first=true;
    for(const auto& r:s.steps){
        if(!first)o<<',';
        first=false;
        o<<"{\"request_hex\":"<<json::quote(hex(r.request))<<",\"status\":"<<json::quote(r.status)
            <<",\"duration_ms\":"<<r.duration<<",\"accepted_records\":[";
        bool comma=false;
        for(const auto& b:r.accepted){if(comma)o<<',';comma=true;o<<json::quote(hex(b));}
        o<<"]}";
    }
    o<<"],\"checked_summary_fields\":[";first=true;
    for(const auto& k:s.checked_summary_fields){if(!first)o<<',';first=false;o<<json::quote(k);}
    o<<"],\"source_sha256\":"<<(digest.empty()?"null":json::quote(digest))
        <<",\"physical_vehicle_verified\":false,\"physically_addressed\":false,"
          "\"evidence_authenticated\":false,\"whole_vehicle_coverage_verified\":false,"
          "\"current_authorization_verified\":false,\"permits_programming\":false}\n";
    return o.str();
}
}
