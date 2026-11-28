#include "j1979_cj1_history.hpp"
#include "j1979_historical_can_plan.hpp"
#include "j1979_historical_freeze.hpp"
#include "j1979_native_report_detail.hpp"
#include "j1979_measurement.h"
#include "j1979_policy.h"
#include <algorithm>
#include <cmath>
#include <iomanip>
#include <locale>
#include <sstream>

namespace cd::cj1 {
namespace {
using json::Value;
using Object=Value::Object;
using Array=Value::Array;
using cd::network::history::detail::require;
using cd::network::history::detail::compare;
Value obj(Object x){return Value{std::move(x)};}
Value arr(Array x){return Value{std::move(x)};}
Value str(std::string_view s){return Value{std::string(s)};}
Value num(std::size_t n){return Value{json::Integer{std::to_string(n)}};}
Value flag(bool b){return Value{b};}
Value nullable(std::string_view s){return s.empty()?Value{}:str(s);}
Value real(double n){
    require(std::isfinite(n),"Nonfinite historical CJ/1 measurement");
    std::ostringstream o;o.imbue(std::locale::classic());o<<std::scientific<<std::setprecision(16)<<n;
    return Value{json::Decimal{o.str()}};
}
const Value& optional(const Value& v,std::string_view k){
    static const Value nil{};const auto* p=v.find(k);return p?*p:nil;
}
bool valid_vin(std::string_view s){return cd_j1979_native_vin(s.data(),s.size());}
bool digest_valid(std::string_view s){
    return s.size()==64&&std::all_of(s.begin(),s.end(),[](char c){return (c>='0'&&c<='9')||(c>='a'&&c<='f');});
}
std::string vin_match(const Evidence& e,const Value& vin,bool allowed){
    if(vin.null())return "NOT_REQUESTED";
    if(!allowed||!e.integrity_verified||!valid_vin(e.vin))return "UNVERIFIED";
    return vin.string()==e.vin?"MATCH":"MISMATCH";
}
Value historical_measurement(const Step& s){
    if(s.state!=1||s.request.size()<2||(s.request[0]!=1&&s.request[0]!=2))return {};
    const auto& q=s.request;const auto& b=s.response;
    j1979_measurement m{};
    if(!j1979_measurement_decode(q.data(),q.size(),b.data(),b.size(),&m))return {};
    double n=m.number;
    const auto offset=q.size();
    if(!m.interpreted){
        if(q[1]==0x0b)n=b[offset];
        else if(q[1]==0x10)n=(unsigned(b[offset])*256+b[offset+1])/100.0;
        else if(q[1]==0x44)n=(unsigned(b[offset])*256+b[offset+1])/32768.0;
        else if(q[1]>=6&&q[1]<=9)n=(int(b[offset])-128)*100.0/128;
        else return {};
    }
    require(m.name&&m.unit,"Missing historical CJ/1 scalar metadata");
    return obj({{"mode",num(m.mode)},{"pid",num(m.pid)},{"name",str(m.name)},{"unit",str(m.unit)},{"number",real(n)}});
}
Value historical_monitors(const Value& row){
    if(row.null())return {};
    Array out;
    for(const auto& record:row.array()){
        auto fields=record.object();fields.erase("scaled");out.push_back(obj(std::move(fields)));
    }
    return arr(std::move(out));
}
Value normalize_step(const Step& raw,const Value& native){
    auto fields=native.object();
    fields["measurement"]=historical_measurement(raw);
    fields["monitor_records"]=historical_monitors(native.at("monitor_records"));
    return obj(std::move(fields));
}
constexpr std::array<std::string_view,6> states{"","PASSED","RAW","NEGATIVE","TIMEOUT","FAILED"};
struct Differences{
    Array values;
    void check(bool ok,std::string_view name){if(!ok)values.push_back(str(name));}
    void field(const Value& actual,std::string_view name,const Value& expected,std::string_view path={}){
        const auto* saved=actual.find(name);
        check(saved&&compare(*saved,expected,name),path.empty()?name:path);
    }
};
}
HistoricalInspection verify_historical(Bytes capture,const json::Value& saved,std::string_view capture_digest,
    std::string_view report_digest,std::optional<std::string> independent){
    namespace historical=cd::network::history;
    require(digest_valid(capture_digest)&&digest_valid(report_digest),"Invalid CJ/1 input digest");
    require(!independent||valid_vin(*independent),"Invalid independent CJ/1 VIN");
    const auto& schema=saved.at("schema").string();
    const bool native=schema=="camarodiag-functional-j1979-native-usb-v1";
    require(native||schema=="camarodiag-functional-j1979-embedded-evidence-v1","Unsupported historical CJ/1 report");
    const auto e=inspect(capture);
    const auto canonical=json::parse_report(report(e,"",capture_digest),max_report);
    Differences diff;
    diff.field(saved,"protocol",str("SAE-J1979"));
    const auto& profile=canonical.at("profile");
    Object profile_fields;
    for(auto k:{"request_can_id","response_can_id","extended","budget_ms"}){
        const auto value=profile.null()?Value{}:profile.at(k);
        if(!profile.null())diff.field(saved,k,value);
        else diff.check(optional(saved,k).null(),k);
        profile_fields.emplace(k,value);
    }
    for(auto k:{"physical_vehicle_verified","evidence_authenticated","hardware_provenance_verified","write_authorized","permits_programming"}){
        if(k==std::string_view("physical_vehicle_verified")||saved.find(k))diff.field(saved,k,flag(false));
    }
    diff.field(saved,"observed_vin",nullable(e.vin));
    const auto& integrity=optional(saved,"capture_integrity_verified");
    const auto& cancelled=optional(saved,"cancelled");
    const bool valid_integrity=std::holds_alternative<bool>(integrity.data);
    const bool valid_cancelled=cancelled.null()||std::holds_alternative<bool>(cancelled.data);
    const bool host_integrity=valid_integrity&&integrity.boolean();
    const bool host_cancelled=valid_cancelled&&!cancelled.null()&&cancelled.boolean();
    diff.check(valid_integrity&&valid_cancelled,"host_status");
    diff.check(!host_integrity||e.integrity_verified,"capture_integrity_verified");
    diff.check(!host_integrity||!host_cancelled,"cancelled");
    diff.check(!host_integrity||optional(saved,"error").null(),"error");
    if(native&&host_integrity)diff.field(saved,"records",num(e.steps.size()+2));
    const auto& source=optional(saved,"source");
    const bool live=std::holds_alternative<std::string>(source.data)&&source.string()=="LIVE_USB_CU1";
    diff.check(live||(std::holds_alternative<std::string>(source.data)&&source.string()=="OFFLINE_CAPTURE"),"source");
    const auto& auth=optional(saved,"device_credential_authenticated");
    const bool auth_valid=std::holds_alternative<bool>(auth.data);
    const bool auth_claim=auth_valid&&auth.boolean();
    if(live)diff.check(auth_valid&&(!host_integrity||auth_claim),"device_credential_authenticated");
    if(native){
        diff.check(live,"source");
        if(host_integrity)diff.field(saved,"frame_integrity_verified",flag(true));
    }
    const auto& declared=optional(saved,"declared_vin");
    const bool declared_valid=declared.null()||(std::holds_alternative<std::string>(declared.data)&&valid_vin(declared.string()));
    diff.check(declared_valid,"declared_vin");
    if(declared_valid){
        diff.field(saved,"vin_match",str(vin_match(e,declared,host_integrity&&!host_cancelled&&(!live||auth_claim))));
        if(independent&&!declared.null())diff.check(declared.string()==*independent,"expected_vin");
    }
    const auto* saved_steps=saved.find("steps");
    const auto& native_steps=canonical.at("steps").array();
    if(!saved_steps||!std::holds_alternative<Array>(saved_steps->data)||saved_steps->array().size()!=e.steps.size()){
        diff.check(false,"steps.length");
    }else{
        for(std::size_t i=0;i<e.steps.size();++i){
            const auto wanted=normalize_step(e.steps[i],native_steps[i]);
            const auto& actual=saved_steps->array()[i];
            if(!std::holds_alternative<Object>(actual.data)){diff.check(false,"steps["+std::to_string(i)+"]");continue;}
            for(const auto& [k,v]:wanted.object())diff.field(actual,k,v,"steps["+std::to_string(i)+"]."+k);
        }
    }
    diff.field(saved,"service_observations",canonical.at("service_observations"));
    const auto& summary=canonical.at("summary");
    if(summary.null())diff.field(saved,"summary",summary);
    else{
        const auto& actual=optional(saved,"summary");
        if(!std::holds_alternative<Object>(actual.data))diff.check(false,"summary");
        else for(const auto& [k,v]:summary.object()){
            if(k=="counts"&&native&&!actual.find(k))continue;
            diff.field(actual,k,v,"summary."+k);
        }
    }
    historical::PlanInspection projected{};
    for(const auto& s:e.steps)projected.steps.push_back({s.request,s.response.empty()?std::nullopt:std::optional{s.response},
        std::string(states.at(s.state)),{},s.duration});
    // One shared historical arithmetic engine, independently applied to the
    // replayed bytes. Compare each optional group so a failure identifies it.
    historical::verify_derived_summary(obj({}),projected);
    auto derived=json::parse_report(projected.derived_summary,max_report).object();
    std::string catalogue="NOT_REPORTED";
    for(auto k:{"status_records","contextual_measurements","operating_context","scaled_monitor_records","extended_vehicle_info","multi_signal_records"}){
        if(const auto* claim=saved.find(k)){
            historical::PlanInspection check=projected;
            try{
                historical::verify_derived_summary(obj({{k,*claim}}),check);
                require(check.derived_groups_checked==1,"Missing historical projection");
                if(std::string_view(k)=="multi_signal_records"){
                    const auto parsed=json::parse_report(check.derived_summary,max_report);
                    catalogue=parsed.at("multi_signal_report_catalog").string();
                }
            }catch(const std::invalid_argument&){diff.check(false,k);}
        }
    }
    const Value target=independent?str(*independent):declared_valid?declared:Value{};
    const auto match=vin_match(e,target,true);
    const bool consistent=diff.values.empty();
    const bool verified=consistent&&host_integrity&&!host_cancelled&&e.integrity_verified&&e.end==1&&
        (match=="MATCH"||match=="NOT_REQUESTED");
    Object proof{{"schema",str("camarodiag-j1979-capture-inspection-v1")},{"verified",flag(verified)},
        {"report_consistent",flag(consistent)},{"differences",arr(std::move(diff.values))},{"source_schema",str(schema)},
        {"host_platform",str(native?(saved.find("app_version")?"ANDROID":"WINDOWS"):"PYTHON")},
        {"capture_sha256",str(capture_digest)},{"report_sha256",str(report_digest)},{"capture_bytes",num(capture.size())},
        {"capture_integrity_verified",flag(e.integrity_verified)},{"host_capture_integrity_claim",integrity},
        {"host_cancelled",flag(host_cancelled)},{"declared_vin",declared},{"expected_vin",independent?str(*independent):Value{}},
        {"observed_vin",nullable(e.vin)},{"vin_match",str(match)},{"profile",obj(std::move(profile_fields))},
        {"summary",summary},{"verified_prefix_steps",num(e.steps.size())},
        {"service_observations",canonical.at("service_observations")},{"multi_signal_report_catalog",str(catalogue)},
        {"capture_error",nullable(e.error)},{"host_error",optional(saved,"error")},
        {"device_credential_independently_verified",flag(false)},{"evidence_authenticated",flag(false)},
        {"physical_vehicle_verified",flag(false)},{"write_authorized",flag(false)}};
    for(auto k:{"status_records","contextual_measurements","operating_context","scaled_monitor_records","extended_vehicle_info","multi_signal_records"})
        proof.emplace(k,derived.at(k));
    return {obj(std::move(proof)),consistent,verified};
}
}
