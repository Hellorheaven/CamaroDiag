#include "j1979_historical_can_plan.hpp"
#include "j1979_measurement.h"
#include "j1979_policy.h"
#include <algorithm>
#include <cmath>
#include <iomanip>
#include <locale>
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
        if(const auto* evidence=get(source[i],"application_evidence");evidence&&!evidence->null()){
            if(const auto* p=get(*evidence,"measurement"))compare_scalar(*p,m);
            if(const auto* p=get(*evidence,"dtc_codes"))compare_codes(*p,codes);
            if(const auto* p=get(*evidence,"observed_vin");p&&!p->null()){
                require(success(s)&&s.request==Bytes{9,2}&&s.response->size()==20&&
                    p->string()==std::string(s.response->begin()+3,s.response->end()),"Application VIN differs");
            }
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
        <<(p.basic_summary_checked?"true":"false")<<",\"measurements\":[";
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
