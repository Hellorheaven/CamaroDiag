#include "j1979_historical_can_plan.hpp"
#include "j1979_measurement.h"
#include "j1979_policy.h"
#include <algorithm>
#include <cmath>
#include <iomanip>
#include <locale>
#include <map>
#include <sstream>
#include <stdexcept>

namespace cd::network::history {
namespace {
using json::Value;
void require(bool b,const char* why){if(!b)throw std::invalid_argument(why);}
const Value* get(const Value& v,std::string_view k){
    std::string camel;bool up=false;
    for(char c:k){
        if(c=='_'){up=true;continue;}
        if(up&&c>='a'&&c<='z')c=static_cast<char>(c-'a'+'A');
        up=false;camel+=c;
    }
    const auto* a=v.find(k);const auto* b=camel==k?nullptr:v.find(camel);
    require(!a||!b,"Ambiguous summary field");return a?a:b;
}
const Value& field(const Value& v,std::string_view k){
    const auto* p=get(v,k);require(p,"Missing summary field");return *p;
}
bool success(const Step& s){return s.status=="PASSED"&&s.response.has_value();}
std::optional<Scalar> scalar(const Step& s){
    if(!success(s)||s.request.size()<2||(s.request[0]!=1&&s.request[0]!=2))return {};
    const auto& q=s.request;const auto& r=*s.response;
    j1979_measurement m{};
    if(!j1979_measurement_decode(q.data(),q.size(),r.data(),r.size(),&m))return {};
    double number=m.number;
    if(!m.interpreted){
        // These are only the exact historical arithmetic projections, used
        // to audit an old report. They are not current scale qualifications.
        const auto offset=q.size();
        const auto a=static_cast<double>(r[offset]);
        if(q[1]==0x0b)number=a;
        else if(q[1]==0x10)number=(a*256+r[offset+1])/100;
        else if(q[1]==0x44)number=(a*256+r[offset+1])/32768;
        else if(q[0]==2&&q[1]>=6&&q[1]<=9)number=a*100/128-100;
        else return {};
    }
    require(std::isfinite(number)&&m.name&&m.unit,"Invalid historical scalar");
    return Scalar{m.mode,m.pid,m.name,m.unit,number};
}
std::optional<std::vector<std::string>> dtc(const Step& s){
    if(!success(s)||s.request.size()!=1)return {};
    if(s.request[0]!=3&&s.request[0]!=7&&s.request[0]!=10)return {};
    const auto& r=*s.response;
    require(r.size()>=2&&r.size()==2u+2u*r[1],"Invalid historical DTC list");
    std::vector<std::string> out;
    for(std::size_t i=2;i<r.size();i+=2){
        char code[6]{};
        require(cd_j1979_native_dtc_code(r.data()+i,code),"Invalid historical DTC code");
        out.emplace_back(code);
    }
    return out;
}
void compare_scalar(const Value& v,const std::optional<Scalar>& m){
    if(!m){require(v.null(),"Unexpected historical scalar");return;}
    require(!v.null()&&field(v,"mode").unsigned_integer()==m->mode&&field(v,"pid").unsigned_integer()==m->pid&&
        field(v,"name").string()==m->name&&field(v,"unit").string()==m->unit,"Historical scalar metadata differs");
    const double n=field(v,"number").finite_number();
    require(std::abs(n-m->number)<=std::max(1e-9,1e-9*std::abs(m->number)),"Historical scalar number differs");
}
void compare_codes(const Value& v,const std::optional<std::vector<std::string>>& expected){
    if(!expected){require(v.null(),"Unexpected historical DTC list");return;}
    const auto& a=v.array();require(a.size()==expected->size(),"Historical DTC count differs");
    for(std::size_t i=0;i<a.size();++i)require(a[i].string()==expected->at(i),"Historical DTC differs");
}
void compare_status(const Value& v,const std::optional<std::string>& expected){
    require(expected?!v.null()&&v.string()==*expected:v.null(),"Historical DTC status differs");
}
void compare_inventory(const Value& v,const Inventory& inv){
    require(field(v,"mode").unsigned_integer()==inv.mode&&field(v,"state").string()==inv.state&&
        field(v,"verified_pages").unsigned_integer()==inv.pages,"Historical inventory metadata differs");
    const auto& a=field(v,"advertised_ids").array();require(a.size()==inv.ids.size(),"Historical advertised count differs");
    for(std::size_t i=0;i<a.size();++i)require(a[i].unsigned_integer()==inv.ids[i],"Historical advertised PID differs");
}
void compare_uint(const Value& v,std::string_view k,unsigned expected){
    require(field(v,k).unsigned_integer()==expected,"Historical application integer differs");
}
void compare_bool(const Value& v,std::string_view k,bool expected){
    require(field(v,k).boolean()==expected,"Historical application flag differs");
}
const Value& nullable_field(const Value& v,std::string_view key){
    static const Value nil{};
    const auto* p=get(v,key);return p?*p:nil;
}
void compare_page(const Value& v,const Step& s){
    const bool expected=success(s)&&s.request.size()>=2&&s.request[1]%32==0;
    if(!expected){require(v.null(),"Unexpected support page evidence");return;}
    require(!v.null(),"Missing support page evidence");
    const auto& q=s.request;const auto& r=*s.response;
    const auto off=q.size();
    require(r.size()==off+4,"Invalid support page width");
    compare_uint(v,"mode",q[0]);compare_uint(v,"base",q[1]);
    compare_bool(v,"has_next_page",(r.back()&1)!=0);
    std::vector<unsigned> ids;
    for(unsigned i=0;i<31;++i)if(r[off+i/8]&(128u>>(i%8)))ids.push_back(q[1]+i+1);
    const auto& a=field(v,"supported_ids").array();
    require(a.size()==ids.size(),"Support page identifier count differs");
    for(std::size_t i=0;i<a.size();++i)require(a[i].unsigned_integer()==ids[i],"Support page identifier differs");
}
void compare_trigger(const Value& v,const Step& s){
    const bool expected=success(s)&&s.request==Bytes{2,2,0};
    if(!expected){require(v.null(),"Unexpected freeze trigger evidence");return;}
    require(!v.null()&&s.response->size()==5,"Missing freeze trigger evidence");
    const auto& r=*s.response;const bool present=r[3]||r[4];
    compare_bool(v,"present",present);
    const auto& code=nullable_field(v,"dtc");
    if(!present)require(code.null(),"Unexpected freeze trigger code");
    else{
        char dtc_code[6]{};
        require(cd_j1979_native_dtc_code(r.data()+3,dtc_code)&&code.string()==dtc_code,"Freeze trigger DTC differs");
    }
}
void compare_readiness(const Value& v,const Step& s){
    if(!success(s)||s.request!=Bytes{1,1}){require(v.null(),"Unexpected readiness evidence");return;}
    require(!v.null()&&s.response->size()==6,"Missing readiness evidence");
    const auto& r=*s.response;
    const unsigned a=r[2],b=r[3],c=r[4],d=r[5];
    const bool compression=(b&8)!=0;
    compare_bool(v,"mil_on",(a&128)!=0);compare_uint(v,"confirmed_dtc_count",a&127);
    compare_bool(v,"compression_ignition",compression);
    compare_uint(v,"byte_b",b);compare_uint(v,"byte_c",c);compare_uint(v,"byte_d",d);
    constexpr std::array<std::string_view,3> continuous{"misfire","fuel-system","components"};
    constexpr std::array<std::string_view,8> spark{"catalyst","heated-catalyst","evaporative-system","secondary-air","",
        "oxygen-sensor","oxygen-sensor-heater","egr-vvt"};
    constexpr std::array<std::string_view,8> diesel{"nmhc-catalyst","nox-aftertreatment","","boost-pressure","",
        "exhaust-gas-sensor","particulate-filter","egr-vvt"};
    std::map<std::string_view,bool> wanted;
    const auto add=[&](const auto& names,unsigned support,unsigned incomplete){
        for(std::size_t i=0;i<names.size();++i)
            if(!names[i].empty()&&(support&(1u<<i)))wanted.emplace(names[i],!(incomplete&(1u<<i)));
    };
    add(continuous,b,b>>4);add(compression?diesel:spark,c,d);
    const auto& monitors=field(v,"supported_monitors").object();
    require(monitors.size()==wanted.size(),"Readiness monitor count differs");
    for(const auto& [name,done]:wanted){
        const auto i=monitors.find(name);
        require(i!=monitors.end()&&i->second.boolean()==done,"Readiness monitor differs");
    }
}
void compare_monitors(const Value& v,const Step& s){
    const bool expected=success(s)&&s.request.size()==2&&s.request[0]==6&&s.request[1]%32!=0;
    if(!expected){require(v.null(),"Unexpected Mode06 record evidence");return;}
    const auto& r=*s.response;
    require(!v.null()&&r.size()>=10&&(r.size()-1)%9==0,"Missing Mode06 record evidence");
    const auto& a=v.array();
    require(a.size()==(r.size()-1)/9,"Mode06 record count differs");
    const auto word=[&](std::size_t i){return unsigned(r[i])*256+r[i+1];};
    for(std::size_t j=0;j<a.size();++j){
        const auto i=1+j*9;
        compare_uint(a[j],"monitor_id",r[i]);compare_uint(a[j],"test_id",r[i+1]);
        compare_uint(a[j],"unit_scaling_id",r[i+2]);compare_uint(a[j],"value_encoded",word(i+3));
        compare_uint(a[j],"minimum_encoded",word(i+5));compare_uint(a[j],"maximum_encoded",word(i+7));
    }
}
std::optional<std::string> ascii(const Bytes& r,std::size_t begin,std::size_t count){
    if(begin>r.size()||count>r.size()-begin)return {};
    auto end=begin+count;
    while(end>begin&&!r[end-1])--end;
    if(end==begin)return {};
    for(auto i=begin;i<end;++i)if(r[i]<32||r[i]>126)return {};
    return std::string(r.begin()+static_cast<std::ptrdiff_t>(begin),r.begin()+static_cast<std::ptrdiff_t>(end));
}
std::string hex_bytes(std::span<const std::uint8_t> b){
    constexpr char digits[]="0123456789ABCDEF";std::string o;
    for(auto c:b){o+=digits[c>>4];o+=digits[c&15];}
    return o;
}
std::optional<std::vector<std::string>> identification(const Step& s){
    if(!success(s)||s.request.size()!=2||s.request[0]!=9)return {};
    const auto kind=s.request[1];const auto& r=*s.response;
    std::vector<std::string> values;
    if(kind==2)values.emplace_back(r.begin()+3,r.end());
    else if(kind==4){
        for(std::size_t i=3;i<r.size();i+=16){
            auto v=ascii(r,i,16);if(!v)return {};values.push_back(std::move(*v));
        }
    }else if(kind==6){
        for(std::size_t i=3;i<r.size();i+=4)values.push_back(hex_bytes(std::span(r).subspan(i,4)));
    }else if(kind==10){
        if(r.size()!=23||r[2]!=1||r[7]!='-')return {};
        const auto a=ascii(r,3,4),b=ascii(r,8,15);
        if(!a||!b)return {};
        values.push_back(*a+"-"+*b);
    }else return {};
    return values;
}
void compare_identity(const Value& v,const Step& s){
    const auto wanted=identification(s);
    if(!wanted){require(v.null(),"Unexpected identification evidence");return;}
    require(!v.null(),"Missing identification evidence");
    compare_uint(v,"info_type",s.request[1]);
    const auto& a=field(v,"values").array();
    require(a.size()==wanted->size(),"Identification value count differs");
    for(std::size_t i=0;i<a.size();++i)require(a[i].string()==wanted->at(i),"Identification value differs");
}
void compare_application(const Value& v,const Step& s,const std::optional<Scalar>& m,
                         const std::optional<std::vector<std::string>>& codes){
    const bool negative=s.status=="NEGATIVE"||(s.status=="NOT_TESTED"&&s.detail.starts_with("NRC_"));
    const bool expected=success(s)||s.status=="RAW"||negative;
    if(!expected){require(v.null(),"Unexpected application evidence on failed observation");return;}
    require(!v.null(),"Missing application evidence");
    require(field(v,"shape").string()==(negative?"NEGATIVE":s.status),"Application shape differs");
    // Unknown non-null fields cannot be smuggled into a historical proof.
    for(const auto& [k,value]:v.object()){
        if(value.null())continue;
        bool known=false;
        for(auto name:{"shape","measurement","dtc_codes","negative_response_code","monitor_records",
            "vehicle_info","readiness","supported_page","freeze_trigger","observed_vin"})
            if(get(v,name)==&value)known=true;
        require(known,"Unknown application evidence property");
    }
    compare_scalar(nullable_field(v,"measurement"),m);
    compare_codes(nullable_field(v,"dtc_codes"),codes);
    const auto& nrc=nullable_field(v,"negative_response_code");
    if(negative)require(s.response&&s.response->size()==3&&nrc.unsigned_integer()==s.response->at(2),"Application NRC differs");
    else require(nrc.null(),"Unexpected application NRC");
    compare_monitors(nullable_field(v,"monitor_records"),s);
    compare_identity(nullable_field(v,"vehicle_info"),s);
    compare_readiness(nullable_field(v,"readiness"),s);
    compare_page(nullable_field(v,"supported_page"),s);
    compare_trigger(nullable_field(v,"freeze_trigger"),s);
    const auto& vin=nullable_field(v,"observed_vin");
    if(success(s)&&s.request==Bytes{9,2})
        require(s.response->size()==20&&vin.string()==std::string(s.response->begin()+3,s.response->end()),"Application VIN differs");
    else require(vin.null(),"Unexpected application VIN");
}
void scalar_json(std::ostream& o,const Scalar& s){
    o<<"{\"mode\":"<<s.mode<<",\"pid\":"<<s.pid<<",\"name\":"<<json::quote(s.name)
        <<",\"unit\":"<<json::quote(s.unit)<<",\"number\":"<<s.number<<'}';
}
void codes_json(std::ostream& o,const std::optional<std::vector<std::string>>& codes){
    if(!codes){o<<"null";return;}
    o<<'[';for(std::size_t i=0;i<codes->size();++i){if(i)o<<',';o<<json::quote(codes->at(i));}o<<']';
}
}
void verify_basic_summary(const Value& body,PlanInspection& plan){
    const auto& source=field(body,"steps").array();
    require(source.size()==plan.steps.size(),"Physical step count differs");
    const std::array<unsigned,3> modes{3,7,10};
    for(std::size_t i=0;i<modes.size();++i)plan.dtcs[i].mode=modes[i];
    for(std::size_t i=0;i<plan.steps.size();++i){
        const auto& s=plan.steps[i];
        const auto m=scalar(s);const auto codes=dtc(s);
        if(m)plan.measurements.push_back(*m);
        if(s.request.size()==1){
            const auto j=std::find(modes.begin(),modes.end(),s.request.front());
            if(j!=modes.end()){
                auto& row=plan.dtcs[static_cast<std::size_t>(j-modes.begin())];
                row.status=s.status;row.codes=codes;
            }
        }
        if(const auto* p=get(source[i],"measurement"))compare_scalar(*p,m);
        if(const auto* p=get(source[i],"dtc_codes"))compare_codes(*p,codes);
        if(const auto* evidence=get(source[i],"application_evidence")){
            compare_application(*evidence,s,m,codes);
            if(!evidence->null())++plan.application_evidence_checked;
        }
    }
    // Python and J2534 export these summaries; Android older schemas do
    // not necessarily export all of them. Missing is not a positive claim.
    if(const auto* p=get(body,"inventory");p&&!p->null()){
        const auto& a=p->array();require(a.size()==plan.inventories.size(),"Historical inventory count differs");
        for(std::size_t i=0;i<a.size();++i)compare_inventory(a[i],plan.inventories[i]);
    }
    if(const auto* p=get(body,"dtc_modes");p&&!p->null()){
        const auto& a=p->array();require(a.size()==plan.dtcs.size(),"Historical DTC mode count differs");
        for(std::size_t i=0;i<a.size();++i){
            const auto& d=plan.dtcs[i];
            require(field(a[i],"mode").unsigned_integer()==d.mode,"Historical DTC mode differs");
            compare_codes(field(a[i],"codes"),d.codes);compare_status(field(a[i],"status"),d.status);
        }
    }
    if(const auto* p=get(body,"measurements");p&&!p->null()){
        const auto& a=p->array();require(a.size()==plan.measurements.size(),"Historical measurement count differs");
        for(std::size_t i=0;i<a.size();++i)compare_scalar(a[i],plan.measurements[i]);
    }
    plan.basic_summary_checked=true;
}
std::string basic_summary_json(const PlanInspection& p){
    std::ostringstream o;o.imbue(std::locale::classic());o<<std::setprecision(15);
    o<<"{\"catalogue\":\"HISTORICAL_SCALAR_ARITHMETIC_ONLY\",\"saved_basic_summary_checked\":"
        <<(p.basic_summary_checked?"true":"false")<<",\"application_evidence_records_checked\":"<<p.application_evidence_checked<<",\"measurements\":[";
    for(std::size_t i=0;i<p.measurements.size();++i){if(i)o<<',';scalar_json(o,p.measurements[i]);}
    o<<"],\"dtc_modes\":[";
    for(std::size_t i=0;i<p.dtcs.size();++i){
        if(i)o<<',';
        const auto& d=p.dtcs[i];
        o<<"{\"mode\":"<<d.mode<<",\"status\":"<<(d.status?json::quote(*d.status):"null")<<",\"codes\":";
        codes_json(o,d.codes);o<<'}';
    }
    o<<"],\"current_scaling_verified\":false}";return o.str();
}
}
