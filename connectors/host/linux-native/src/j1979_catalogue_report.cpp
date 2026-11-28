#include "j1979_catalogue_projection.hpp"
#include "native_json.hpp"
#include <algorithm>
#include <array>
#include <locale>
#include <sstream>
#include <stdexcept>
#include <tuple>
namespace cd::catalogue {
namespace {
const char* tf(bool b){return b?"true":"false";}
using json::quote;
template<class R,class F>void array(std::ostream& o,const R& rows,F emit){
    o<<'[';bool first=true;
    for(const auto& x:rows){if(!first)o<<',';first=false;emit(x);}
    o<<']';
}
template<class R>void numbers(std::ostream& o,const R& rows){array(o,rows,[&](unsigned v){o<<v;});}
void optional_text(std::ostream& o,const std::optional<std::string>& s){if(s)o<<quote(*s);else o<<"null";}
void raw(std::ostream& o,Bytes bytes){
    if(bytes.empty()){o<<"null";return;}
    constexpr char digits[]="0123456789ABCDEF";
    o<<'"';for(auto b:bytes)o<<digits[b>>4]<<digits[b&15];o<<'"';
}
void signal(std::ostream& o,const SignalResult& s){
    o<<"{\"name\":"<<quote(s.name)<<",\"unit\":"<<quote(s.unit)<<",\"reference\":"<<quote(s.reference)
      <<",\"status\":"<<quote(s.status)<<",\"raw\":";
    optional_text(o,s.raw);o<<",\"enumeration\":";optional_text(o,s.enumeration);
    o<<",\"value\":";
    if(s.value)o<<"{\"numerator\":"<<quote(s.value->numerator)<<",\"denominator\":"<<quote(s.value->denominator)<<'}';
    else o<<"null";
    o<<'}';
}
std::set<unsigned> difference(const std::set<unsigned>& a,const std::set<unsigned>& b){
    std::set<unsigned> out;
    std::set_difference(a.begin(),a.end(),b.begin(),b.end(),std::inserter(out,out.end()));return out;
}
std::set<unsigned> intersection(const std::set<unsigned>& a,const std::set<unsigned>& b){
    std::set<unsigned> out;
    std::set_intersection(a.begin(),a.end(),b.begin(),b.end(),std::inserter(out,out.end()));return out;
}
std::string bounded(std::ostringstream& o){
    if(!o||o.tellp()>static_cast<std::streamoff>(max_projection))throw std::invalid_argument("Projection output byte limit");
    return o.str();
}
}
std::string inventory(const Catalogue& c,std::string_view sha){
    if(!valid_digest(sha)||!valid_digest(c.reference_sha256))throw std::invalid_argument("Invalid catalogue digest");
    std::ostringstream o;o.imbue(std::locale::classic());
    o<<"{\"schema\":"<<quote(c.mode==Mode::live?"camarodiag-j1979-catalogue-inspection-v1":
         "camarodiag-j1979-freeze-catalogue-inspection-v1")
      <<",\"edition\":"<<quote(c.edition)<<",\"reference_name\":"<<quote(c.reference_name)
      <<",\"reference_sha256\":"<<quote(c.reference_sha256)<<",\"catalogue_sha256\":"<<quote(sha)
      <<",\"reference_authenticity_verified\":false,\"normative_compliance_verified\":false,"
        "\"permits_programming\":false,\"definitions\":";
    std::vector<const Definition*> sorted;
    for(const auto& d:c.definitions)sorted.push_back(&d);
    std::sort(sorted.begin(),sorted.end(),[](const auto* a,const auto* b){
        return std::tie(a->frame,a->pid,a->protocols)<std::tie(b->frame,b->pid,b->protocols);
    });
    array(o,sorted,[&](const Definition* d){
        o<<'{';if(d->frame)o<<"\"frame\":"<<*d->frame<<',';
        o<<"\"pid\":"<<d->pid<<",\"payload_bytes\":"<<d->width<<",\"protocols\":";
        array(o,d->protocols,[&](Transport t){o<<quote(t==Transport::can?"CAN":"LEGACY");});
        o<<",\"reference\":"<<quote(d->reference)<<",\"signals\":";
        array(o,d->signals,[&](const Signal& s){o<<quote(s.name);});o<<'}';
    });
    o<<"}\n";return bounded(o);
}
std::string report(const Catalogue& c,const Projection& p){
    if(c.mode!=p.mode||!valid_digest(p.catalogue_sha256)||!valid_digest(p.source_sha256))
        throw std::invalid_argument("Projection provenance mismatch");
    std::ostringstream o;o.imbue(std::locale::classic());
    o<<"{\"schema\":"<<quote(p.mode==Mode::freeze?"camarodiag-j1979-native-freeze-catalogue-projection-linux-v1":
          "camarodiag-j1979-native-catalogue-projection-linux-v1")
      <<",\"reference_edition\":"<<quote(c.edition)<<",\"reference_sha256\":"<<quote(c.reference_sha256)
      <<",\"catalogue_sha256\":"<<quote(p.catalogue_sha256)<<",\"source_capture_sha256\":"<<quote(p.source_sha256)
      <<",\"declared_vin\":"<<quote(p.vin)<<",\"expected_vin_independently_supplied\":true,"
        "\"source_replay_verified\":true,\"native_capture_replayed\":true,"
        "\"transport_frames_replayed\":"<<tf(p.transport==Transport::legacy)
      <<",\"source_completed\":"<<tf(p.completed)<<",\"final_identity_verified\":"<<tf(p.identity_rechecked)
      <<",\"reference_authenticity_verified\":false,\"normative_compliance_verified\":false,"
        "\"physical_vehicle_verified\":false,\"authenticated\":false,\"permits_programming\":false,"
        "\"diagnostic_verdict\":null,\"channel\":";
    if(p.transport==Transport::can)
        o<<"{\"kind\":\"can\",\"tx\":"<<p.tx<<",\"rx\":"<<p.rx<<",\"extended\":"<<tf(p.extended)<<'}';
    else o<<"{\"kind\":\"legacy\",\"protocol\":"<<p.legacy_protocol<<",\"source\":"<<p.legacy_source<<'}';
    std::map<std::string,unsigned> counts;
    for(const auto& row:p.rows)++counts[row.status];
    o<<",\"counts\":{";
    bool first=true;
    for(const auto& [status,n]:counts){if(!first)o<<',';first=false;o<<quote(status)<<':'<<n;}
    o<<"},\"rows\":";
    array(o,p.rows,[&](const ProjectionRow& row){
        o<<"{\"pid\":"<<row.pid;
        if(row.frame)o<<",\"frame\":"<<*row.frame<<",\"step_index\":"<<row.step_index;
        else o<<",\"cycle\":"<<row.cycle<<",\"elapsed_ms\":"<<row.elapsed_ms;
        o<<",\"source_status\":"<<quote(row.source_status)<<",\"response_hex\":";raw(o,row.response);
        o<<",\"projection_status\":"<<quote(row.status)<<",\"signals\":";
        array(o,row.signals,[&](const SignalResult& s){signal(o,s);});
        if(!row.reference.empty())o<<",\"reference\":"<<quote(row.reference);
        o<<'}';
        if(o.tellp()>static_cast<std::streamoff>(max_projection))throw std::invalid_argument("Projection output byte limit");
    });
    if(p.mode==Mode::freeze){
        std::set<unsigned> unrequested;
        for(const auto& d:c.definitions)if(d.frame&&std::none_of(p.coverage.begin(),p.coverage.end(),
            [&](const auto& f){return f.frame==*d.frame;}))unrequested.insert(*d.frame);
        o<<",\"catalogue_coverage\":{\"scope\":\"EXPLICIT_FREEZE_FRAMES_ONLY\","
            "\"normative_compliance_verified\":false,\"hardware_provenance_verified\":false,"
            "\"unrequested_catalogue_frames\":";
        numbers(o,unrequested);o<<",\"frames\":";
        array(o,p.coverage,[&](const FrameCoverage& f){
            o<<"{\"frame\":"<<f.frame<<",\"source_status\":"<<quote(f.state)
              <<",\"inventory_complete\":"<<tf(f.inventory_complete)<<",\"trigger_stable\":"<<tf(f.trigger_stable)
              <<",\"documented_pids\":";numbers(o,f.documented);
            o<<",\"advertised_measurement_pids\":";numbers(o,f.advertised);
            o<<",\"requested_measurement_pids\":";numbers(o,f.requested);
            o<<",\"responded_measurement_pids\":";numbers(o,f.responded);
            o<<",\"projected_measurement_pids\":";numbers(o,f.projected);
            o<<",\"advertised_without_documentation\":";numbers(o,difference(f.advertised,f.documented));
            o<<",\"documented_without_advertisement\":";
            if(f.inventory_complete)numbers(o,difference(f.documented,f.advertised));else o<<"null";
            o<<",\"advertised_not_requested\":";numbers(o,difference(f.advertised,f.requested));
            o<<",\"documented_responses_not_projected\":";numbers(o,difference(intersection(f.responded,f.documented),f.projected));
            o<<'}';
        });
        o<<'}';
    }
    o<<"}\n";return bounded(o);
}

namespace {
void csv_text(std::ostream& out,std::string_view value){
    out<<'"';
    for(char c:value){if(c=='"')out<<'"';out<<c;}
    out<<'"';
}
std::string bytes_hex(Bytes b){
    constexpr char digits[]="0123456789ABCDEF";
    std::string s;s.reserve(b.size()*2);
    for(auto x:b){s+=digits[x>>4];s+=digits[x&15];}
    return s;
}
}
std::string projection_csv(const Catalogue& c,const Projection& p){
    if(c.mode!=p.mode||!valid_digest(p.catalogue_sha256)||!valid_digest(p.source_sha256)||
        !valid_digest(c.reference_sha256))throw std::invalid_argument("Projection provenance mismatch");
    std::ostringstream o;o.imbue(std::locale::classic());
    o<<"pid_hex,frame,step_index,cycle,elapsed_ms,source_status,response_hex,projection_status,"
        "definition_reference,signal_name,unit,signal_reference,signal_status,raw,enumeration,numerator,denominator,"
        "vin,transport,tx,rx,legacy_protocol,legacy_source,source_capture_sha256,catalogue_sha256,reference_sha256,"
        "source_completed,final_identity_verified,normative_compliance_verified,physical_vehicle_verified\r\n";
    for(const auto& row:p.rows){
        if(!p.identity_rechecked&&!row.signals.empty())throw std::invalid_argument("Unverified identity carries catalogue values");
        const auto emit=[&](const SignalResult* signal){
            bool first=true;
            auto next=[&](){if(!first)o<<',';first=false;};
            auto field=[&](std::string_view v){next();csv_text(o,v);};
            auto integer=[&](unsigned v){next();o<<v;};
            auto blank=[&](){next();};
            const std::array<std::uint8_t,1> pid{static_cast<std::uint8_t>(row.pid)};
            if(!row.pid||row.pid>255)throw std::invalid_argument("Invalid catalogue row PID");
            field(bytes_hex(pid));
            if(row.frame){integer(*row.frame);integer(row.step_index);blank();blank();}
            else{blank();blank();integer(row.cycle);integer(row.elapsed_ms);}
            field(row.source_status);field(bytes_hex(row.response));field(row.status);field(row.reference);
            if(signal){
                field(signal->name);field(signal->unit);field(signal->reference);field(signal->status);
                if(signal->raw)field(*signal->raw);else blank();
                if(signal->enumeration)field(*signal->enumeration);else blank();
                if(signal->value){field(signal->value->numerator);field(signal->value->denominator);}
                else{blank();blank();}
            }else for(unsigned i=0;i<8;++i)blank();
            field(p.vin);field(p.transport==Transport::can?"CAN":"LEGACY");
            if(p.transport==Transport::can){integer(p.tx);integer(p.rx);blank();blank();}
            else{blank();blank();integer(p.legacy_protocol);integer(p.legacy_source);}
            field(p.source_sha256);field(p.catalogue_sha256);field(c.reference_sha256);
            field(tf(p.completed));field(tf(p.identity_rechecked));field("false");field("false");
            o<<"\r\n";
            if(!o||o.tellp()>static_cast<std::streamoff>(max_projection))
                throw std::invalid_argument("Catalogue CSV byte limit");
        };
        if(row.signals.empty())emit(nullptr);
        else for(const auto& signal:row.signals)emit(&signal);
    }
    return bounded(o);
}

}
