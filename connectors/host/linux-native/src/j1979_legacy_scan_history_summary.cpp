#include "j1979_legacy_scan_history.hpp"
#include "j1979_native_report_detail.hpp"
#include "j1979_measurement.h"
#include "j1979_historical_can_plan.hpp"
#include "j1979_legacy_monitor.hpp"
#include <sstream>
#include <iomanip>
#include <locale>
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
Value normalize(const Value& v){
    if(const auto* o=std::get_if<Value::Object>(&v.data)){
        Value::Object out;
        for(const auto& [k,x]:*o){
            std::string key;
            for(char c:k){
                if(c>='A'&&c<='Z'){key+='_';key+=static_cast<char>(c-'A'+'a');}
                else key+=c;
            }
            require(out.emplace(key,normalize(x)).second,"Ambiguous legacy summary key");
        }
        return Value{std::move(out)};
    }
    if(const auto* a=std::get_if<Value::Array>(&v.data)){
        Value::Array out;for(const auto& x:*a)out.push_back(normalize(x));return Value{std::move(out)};
    }
    return v;
}
Value reduce(const Value& v,std::initializer_list<std::string_view> keys,unsigned source){
    Value::Array result;
    require(v.array().size()<=4096u*128u,"Legacy summary count exceeds limit");
    for(const auto& row:v.array()){
        const auto n=normalize(row);
        if(const auto* p=n.find("source")){
            const auto b=cd::network::history::detail::bytes(*p,1);
            require(b.size()==1&&b[0]==source,"Legacy summary names a foreign source");
        }
        Value::Object fields;
        for(const auto key:keys){
            const auto* p=n.find(key);
            fields.emplace(key,p?*p:Value{});
        }
        result.push_back(Value{std::move(fields)});
    }
    return Value{std::move(result)};
}
void check(const Value& actual,const Value& expected){
    require(cd::network::history::detail::compare(actual,expected),"Legacy derived projection differs");
}
std::string scalar_rows(const HistoricalScan& s){
    std::ostringstream o;o.imbue(std::locale::classic());o<<std::setprecision(15)<<'[';bool first=true;
    for(const auto& step:s.steps){
        if(step.accepted.size()!=1||step.request.size()<2||(step.request[0]!=1&&step.request[0]!=2))continue;
        const auto& q=step.request;const auto& r=step.accepted.front();
        j1979_measurement m{};
        if(!j1979_measurement_decode(q.data(),q.size(),r.data(),r.size(),&m))continue;
        if(q[1]>=6&&q[1]<=9&&r.size()!=q.size()+1)continue;
        double number=m.number;
        if(!m.interpreted){
            const auto off=q.size();
            if(q[1]==0x0b)number=r[off];
            else if(q[1]==0x10)number=(unsigned(r[off])*256+r[off+1])/100.0;
            else if(q[1]==0x44)number=(unsigned(r[off])*256+r[off+1])/32768.0;
            else if(q[1]>=6&&q[1]<=9)number=(int(r[off])-128)*100.0/128;
            else continue;
        }
        if(!first)o<<',';
        first=false;
        o<<"{\"mode\":"<<unsigned(m.mode)<<",\"pid\":"<<unsigned(m.pid)<<",\"name\":"<<json::quote(m.name)
            <<",\"unit\":"<<json::quote(m.unit)<<",\"number\":"<<number<<'}';
    }
    o<<']';return o.str();
}
std::string readiness(const HistoricalScan& s){
    const auto it=std::find_if(s.steps.begin(),s.steps.end(),[](const HistoricalStep& r){
        return r.request==Data{1,1}&&r.accepted.size()==1;
    });
    if(it==s.steps.end())return "null";
    const auto& r=it->accepted.front();
    require(r.size()==6,"Invalid legacy readiness bytes");
    const unsigned a=r[2],b=r[3],c=r[4],d=r[5];
    const bool compression=(b&8)!=0;
    constexpr std::array<std::string_view,3> continuous{"misfire","fuel-system","components"};
    constexpr std::array<std::string_view,8> spark{"catalyst","heated-catalyst","evaporative-system","secondary-air","",
        "oxygen-sensor","oxygen-sensor-heater","egr-vvt"};
    constexpr std::array<std::string_view,8> diesel{"nmhc-catalyst","nox-aftertreatment","","boost-pressure","",
        "exhaust-gas-sensor","particulate-filter","egr-vvt"};
    std::ostringstream o;
    o<<"{\"mil_on\":"<<((a&128)?"true":"false")<<",\"confirmed_dtc_count\":"<<(a&127)
        <<",\"compression_ignition\":"<<(compression?"true":"false")<<",\"byte_b\":"<<b<<",\"byte_c\":"<<c
        <<",\"byte_d\":"<<d<<",\"supported_monitors\":{";
    bool first=true;
    auto emit=[&](auto names,unsigned support,unsigned incomplete){
        for(unsigned i=0;i<names.size();++i)if(!names[i].empty()&&(support&(1u<<i))){
            if(!first)o<<',';
            first=false;
            o<<json::quote(names[i])<<':'<<((incomplete&(1u<<i))?"false":"true");
        }
    };
    emit(continuous,b,b>>4);emit(compression?diesel:spark,c,d);
    o<<"}}";return o.str();
}
Value monitor_rows(const HistoricalScan& s){
    Value::Array rows;
    for(const auto& step:s.steps){
        if(step.status!="PASSED"||step.request.size()<2||step.request[1]%32||
            (step.request[0]!=5&&step.request[0]!=6))continue;
        for(std::size_t i=0;i<step.accepted.size();++i){
            auto record=decode_monitor(step.request,step.accepted[i]);
            require(record.has_value(),"Invalid legacy monitor record");
            std::ostringstream o;o.imbue(std::locale::classic());o<<std::setprecision(15);write_monitor(o,*record);
            auto fields=json::parse_report(o.str()).object();
            fields.erase("scale_verified");fields.erase("diagnostic_verdict");
            fields.emplace("request_hex",Value{hex(step.request)});
            fields.emplace("record_index",Value{json::Integer{std::to_string(i)}});
            rows.push_back(Value{std::move(fields)});
        }
    }
    return Value{std::move(rows)};
}
Value number(std::uint64_t n){return Value{json::Integer{std::to_string(n)}};}
Value str(std::string_view s){return Value{std::string(s)};}
Value flag(bool b){return Value{b};}
Value text(const std::optional<std::string>& s){return s?str(*s):Value{};}
Value array(Value::Array a){return Value{std::move(a)};}
Value object(Value::Object o){return Value{std::move(o)};}
Value identity_rows(const HistoricalScan& s){
    Value::Array rows;
    for(const auto& id:s.identification){
        Value::Object fields{{"info_type",number(id.kind)}};
        if(id.counters){
            const auto& info=*id.counters;
            fields.emplace("counter_count",number(info.counter_count));
            fields.emplace("general_denominator",number(info.general_denominator));
            fields.emplace("ignition_cycles",number(info.ignition_cycles));
            fields.emplace("regulatory_verdict",Value{});
            Value::Array monitors;
            for(unsigned i=0;i<info.monitor_count;++i){
                const auto& m=info.monitors[i];
                Value ratio;
                if(m.ratio_valid){
                    std::ostringstream n;n.imbue(std::locale::classic());n<<std::scientific<<std::setprecision(16)<<m.ratio;
                    ratio=Value{json::Decimal{n.str()}};
                }
                monitors.push_back(object({{"monitor",str(m.name)},{"numerator",number(m.numerator)},
                    {"denominator",number(m.denominator)},{"ratio",std::move(ratio)}}));
            }
            fields.emplace("monitors",array(std::move(monitors)));
        }else{
            Value::Array values;
            for(const auto& v:id.values)values.push_back(str(v));
            fields.emplace("values",array(std::move(values)));
        }
        rows.push_back(object(std::move(fields)));
    }
    return array(std::move(rows));
}
Value inventory_rows(const HistoricalScan& s){
    Value::Array rows;
    for(const auto& i:s.inventories){
        Value::Array advertised;
        for(auto p:i.advertised)advertised.push_back(number(p));
        rows.push_back(object({{"mode",number(i.mode)},{"sensor_mask",i.sensor?number(i.sensor):Value{}},
            {"state",str(i.complete?"COMPLETE":i.pages?"PARTIAL":"UNAVAILABLE")},
            {"verified_pages",number(i.pages)},{"advertised_ids",array(std::move(advertised))}}));
    }
    return array(std::move(rows));
}
Value dtc_rows(const HistoricalScan& s){
    Value::Array rows;
    for(auto mode:{3u,7u,10u}){
        Value value;
        const auto found=s.dtcs.find(mode);
        if(found!=s.dtcs.end()){
            Value::Array codes;for(const auto& c:found->second)codes.push_back(str(c));
            value=array(std::move(codes));
        }
        rows.push_back(object({{"mode",number(mode)},{"codes",std::move(value)}}));
    }
    return array(std::move(rows));
}
Value raw_monitors(const HistoricalScan& s){
    Value::Array records;
    for(const auto& step:s.steps){
        if(step.request.size()<2||step.request[1]%32||(step.request[0]!=5&&step.request[0]!=6))continue;
        for(std::size_t i=0;i<step.accepted.size();++i){
            const auto value=decode_monitor(step.request,step.accepted[i]);
            require(value.has_value(),"Invalid historical monitor record");
            const auto& m=*value;
            Value::Object fields{{"mode",number(m.mode)},{"test_id",number(m.test_id)},
                {"sensor_mask",m.mode==5?number(m.sensor_mask):Value{}},{"response_index",number(i)},
                {"request_hex",str(hex(step.request))},{"encoded_data_hex",str(hex(m.encoded))}};
            if(m.mode==6){
                fields.emplace("component_and_limit_encoded",number(m.component));
                fields.emplace("value_encoded",number(m.value));
                fields.emplace("limit_encoded",number(*m.limit));
            }
            records.push_back(object(std::move(fields)));
        }
    }
    return array(std::move(records));
}
void verify_projections(const Value& body,HistoricalScan& s){
    std::vector<cd::j1979::Observation> observations;
    observations.reserve(s.steps.size());
    for(const auto& r:s.steps){
        const bool accepted=r.accepted.size()==1;
        observations.push_back({r.request,accepted?cd::j1979::Bytes(r.accepted.front()):cd::j1979::Bytes{},accepted,true});
    }
    const auto p=cd::network::history::historical_projections(observations);
    s.derived=object({{"measurements",json::parse_report(scalar_rows(s))},{"multi_signal_records",json::parse_report(p.multi)},
        {"status_records",json::parse_report(p.status)},{"contextual_measurements",json::parse_report(p.context)},
        {"operating_context",json::parse_report(p.operating)},{"readiness",json::parse_report(readiness(s))},
        {"legacy_monitor_results",monitor_rows(s)},{"monitor_records",raw_monitors(s)},{"identification",identity_rows(s)},
        {"inventory",inventory_rows(s)},{"dtc_modes",dtc_rows(s)},
        {"freeze_trigger",s.freeze_present?object({{"present",flag(*s.freeze_present)},{"dtc",text(s.freeze_dtc)}}):Value{}}});
    if(const auto* v=claim(body,"identification")){
        check(normalize(*v),s.derived.at("identification"));s.checked_summary_fields.emplace_back("identification");
    }
    if(const auto* v=claim(body,"monitor_records")){
        check(normalize(*v),s.derived.at("monitor_records"));s.checked_summary_fields.emplace_back("monitor_records");
    }
    if(const auto* v=claim(body,"measurements")){
        check(reduce(*v,{"mode","pid","name","number","unit"},s.source),json::parse_report(scalar_rows(s)));
        s.checked_summary_fields.emplace_back("measurements");
    }
    if(const auto* v=claim(body,"multi_signal_records")){
        const auto actual=reduce(*v,{"request_hex","signals"},s.source);
        using cd::network::history::detail::compare;
        require(compare(actual,json::parse_report(p.multi))||compare(actual,json::parse_report(p.old_multi)),
            "Legacy multi-signal projection differs");
        s.checked_summary_fields.emplace_back("multi_signal_records");
    }
    if(const auto* v=claim(body,"status_records")){
        check(reduce(*v,{"request_hex","states"},s.source),json::parse_report(p.status));
        s.checked_summary_fields.emplace_back("status_records");
    }
    for(const auto& [key,encoded]:std::array<std::pair<std::string_view,std::string_view>,2>{{
        {"contextual_measurements",p.context},{"operating_context",p.operating}}})
        if(const auto* v=claim(body,key)){check(normalize(*v),json::parse_report(encoded));s.checked_summary_fields.emplace_back(key);}
    if(const auto* v=claim(body,"readiness")){
        check(normalize(*v),json::parse_report(readiness(s)));s.checked_summary_fields.emplace_back("readiness");
    }
    if(const auto* v=claim(body,"legacy_monitor_results")){
        check(normalize(*v),monitor_rows(s));s.checked_summary_fields.emplace_back("legacy_monitor_results");
    }
}
void freeze(const Value& v,const HistoricalScan& s){
    require(s.freeze_present.has_value()&&required(v,"present").boolean()==*s.freeze_present,"Legacy freeze presence differs");
    const auto& code=required(v,"dtc");
    require(s.freeze_dtc?(!code.null()&&code.string()==*s.freeze_dtc):code.null(),"Legacy freeze DTC differs");
}
}
void verify_historical_scan_summary(const Value& body,HistoricalScan& s){
    verify_projections(body,s);
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
