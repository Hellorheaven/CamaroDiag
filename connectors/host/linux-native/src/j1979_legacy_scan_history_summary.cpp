#include "j1979_legacy_scan_history.hpp"
#include "j1979_native_report_detail.hpp"
#include "j1979_measurement.h"
#include <algorithm>
#include <cmath>
namespace cd::legacy::history {
namespace {
using json::Value;
using cd::network::history::detail::require;
const Value* field(const Value& v,std::string_view key){
    std::string camel;bool upper=false;
    for(char c:key){
        if(c=='_'){upper=true;continue;}
        if(upper&&c>='a'&&c<='z')c=static_cast<char>(c-'a'+'A');
        upper=false;camel+=c;
    }
    const auto* a=v.find(key);const auto* b=camel==key?nullptr:v.find(camel);
    require(!a||!b,"Ambiguous legacy summary property");return a?a:b;
}
const Value& required(const Value& v,std::string_view k){
    const auto* p=field(v,k);require(p,"Missing legacy summary property");return *p;
}
const Value* claim(const Value& v,std::string_view k){const auto* p=field(v,k);return p&&!p->null()?p:nullptr;}
std::vector<unsigned> ids(const Value& v){
    std::vector<unsigned> out;
    if(std::holds_alternative<std::string>(v.data)){
        constexpr std::string_view chars="ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        const auto& s=v.string();require(s.size()%4==0&&s.size()<=344,"Invalid driver bitmap base64");
        for(std::size_t i=0;i<s.size();i+=4){
            unsigned word=0,padding=0;
            for(unsigned j=0;j<4;++j){
                word<<=6;
                if(s[i+j]=='='){require(i+4==s.size()&&j>=2,"Invalid base64 padding");++padding;}
                else{const auto n=chars.find(s[i+j]);require(n!=std::string_view::npos&&!padding,"Invalid base64 byte");word|=static_cast<unsigned>(n);}
            }
            require(!padding||!(word&((1u<<(padding*8))-1)),"Noncanonical base64 padding");
            for(unsigned j=0;j<3-padding;++j)out.push_back((word>>(16-8*j))&255);
        }
    }else{
        require(v.array().size()<=255,"Legacy inventory size limit");
        for(const auto& p:v.array()){const auto n=p.unsigned_integer();require(n>0&&n<256,"Invalid advertised PID");out.push_back(static_cast<unsigned>(n));}
    }
    require(out.size()<=255&&std::is_sorted(out.begin(),out.end())&&std::adjacent_find(out.begin(),out.end())==out.end(),"Invalid inventory order");
    return out;
}
void inventory(const Value& v,const HistoricalScan& s){
    std::set<std::pair<unsigned,unsigned>> seen;
    require(v.array().size()<=16,"Legacy inventory count");
    for(const auto& row:v.array()){
        const auto mode=required(row,"mode").unsigned_integer();
        const auto& mask=required(row,"sensor_mask");
        const auto sensor=mask.null()?0:mask.unsigned_integer();
        require(mode<=10&&sensor<=128&&(mode==5?(!mask.null()&&sensor&&(sensor&(sensor-1))==0):mask.null()),"Invalid inventory scope");
        const std::pair<unsigned,unsigned> key{static_cast<unsigned>(mode),static_cast<unsigned>(sensor)};
        require(seen.insert(key).second,"Duplicate legacy inventory summary");
        const auto it=std::find_if(s.inventories.begin(),s.inventories.end(),[&](const Inventory& i){return i.mode==mode&&i.sensor==sensor;});
        std::string state;unsigned pages=0;std::vector<unsigned> expected;
        if(it!=s.inventories.end()){
            pages=it->pages;state=it->complete?"COMPLETE":pages?"PARTIAL":"UNAVAILABLE";
            expected.assign(it->advertised.begin(),it->advertised.end());
        }else if(mode==2&&!sensor&&s.freeze_present!=true)state=s.freeze_present?"NOT_APPLICABLE":"UNAVAILABLE";
        else if(!s.completed)state="UNAVAILABLE";
        else throw std::invalid_argument("Unexpected completed legacy inventory");
        require(required(row,"state").string()==state&&required(row,"verified_pages").unsigned_integer()==pages&&
            ids(required(row,"advertised_ids"))==expected,"Legacy inventory summary differs");
    }
    const bool dtc_seen=std::any_of(s.steps.begin(),s.steps.end(),[](const HistoricalStep& x){return x.request.size()==1;});
    for(const auto& i:s.inventories)if(s.completed||dtc_seen||i.mode!=1)
        require(seen.contains({i.mode,i.sensor}),"Missing legacy inventory summary");
}
void dtcs(const Value& v,const HistoricalScan& s){
    require(v.array().size()==3,"Legacy DTC summary count");
    unsigned index=0;
    for(auto mode:{3u,7u,10u}){
        const auto& row=v.array()[index++];
        require(required(row,"mode").unsigned_integer()==mode,"Legacy DTC summary mode differs");
        const auto& codes=required(row,"codes");
        const auto it=s.dtcs.find(mode);
        if(it==s.dtcs.end()){require(codes.null(),"Unobserved legacy DTC summary");continue;}
        std::set<std::string> actual;
        for(const auto& code:codes.array())require(actual.insert(code.string()).second,"Duplicate legacy DTC");
        require(actual==it->second,"Legacy DTC summary differs");
    }
}
void freeze(const Value& v,const HistoricalScan& s){
    require(s.freeze_present.has_value()&&required(v,"present").boolean()==*s.freeze_present,"Legacy freeze presence differs");
    const auto& code=required(v,"dtc");
    require(s.freeze_dtc?(!code.null()&&code.string()==*s.freeze_dtc):code.null(),"Legacy freeze DTC differs");
}
}
void verify_historical_scan_summary(const Value& body,HistoricalScan& s){
    if(const auto* p=claim(body,"inventory")){inventory(*p,s);s.checked_summary_fields.emplace_back("inventory");}
    if(const auto* p=claim(body,"dtc_modes")){dtcs(*p,s);s.checked_summary_fields.emplace_back("dtc_modes");}
    for(auto k:{"freeze_trigger","freeze_frame_trigger"})if(const auto* p=claim(body,k)){
        freeze(*p,s);s.checked_summary_fields.emplace_back(k);
    }
    if(const auto* p=claim(body,"freeze_triggers")){
        require(p->array().size()==1,"Unexpected freeze trigger count");freeze(p->array().front(),s);
        s.checked_summary_fields.emplace_back("freeze_triggers");
    }
    if(const auto* p=claim(body,"advertised_control_tests")){
        std::vector<unsigned> expected;
        for(const auto& i:s.inventories)if(i.mode==8)expected.assign(i.advertised.begin(),i.advertised.end());
        require(ids(*p)==expected,"Legacy advertised control test list differs");
        s.checked_summary_fields.emplace_back("advertised_control_tests");
    }
}
}
