#include "j1979_live_history.hpp"
#include "j1979_historical_freeze.hpp"
#include "j1979_native_report_detail.hpp"
#include "j1979_policy.h"
#include "j1979_measurement.h"
#include "j1979_multi.hpp"
#include "j1979_status.hpp"
#include "j1979_context.hpp"
#include <algorithm>
#include <array>
#include <iomanip>
#include <locale>
#include <map>
#include <set>
#include <sstream>
#include <tuple>

namespace cd::network::history {
namespace {
using namespace detail;
using Object=Value::Object;
using Array=Value::Array;
constexpr unsigned max_rows=10000,max_admission=256,max_time=0x7fffffff;
Value obj(Object x){return Value{std::move(x)};}
Value arr(Array x){return Value{std::move(x)};}
Value str(std::string_view s){return Value{std::string(s)};}
Value num(std::uint64_t n){return Value{json::Integer{std::to_string(n)}};}
Value flag(bool b){return Value{b};}
Value real(double n){
    require(std::isfinite(n),"Nonfinite live value");
    std::ostringstream o;o.imbue(std::locale::classic());o<<std::scientific<<std::setprecision(16)<<n;
    return Value{json::Decimal{o.str()}};
}
template<class T> Value numbers(const T& values){
    Array out;for(auto n:values)out.push_back(num(n));return arr(std::move(out));
}
const Value* property(const Value& v,std::string_view k){
    std::string camel;bool upper=false;
    for(char c:k){
        if(c=='_'){upper=true;continue;}
        if(upper&&c>='a'&&c<='z')c=static_cast<char>(c-'a'+'A');
        upper=false;camel+=c;
    }
    const auto* a=v.find(k);const auto* b=camel==k?nullptr:v.find(camel);
    require(!a||!b,"Ambiguous historical live property");return a?a:b;
}
const Value& optional(const Value& v,std::string_view k){
    static const Value nil{};const auto* p=property(v,k);return p?*p:nil;
}
const Value& field(const Value& v,std::string_view k){
    const auto* p=property(v,k);require(p,"Missing historical live property");return *p;
}
bool boolean(const Value& v,std::string_view k,bool fallback=false){
    const auto* p=property(v,k);return p?p->boolean():fallback;
}
unsigned bounded(const Value& v,unsigned low,unsigned high){
    const auto n=v.unsigned_integer();require(n>=low&&n<=high,"Historical live integer out of range");
    return static_cast<unsigned>(n);
}
const Array& array(const Value& v,std::size_t bound){
    const auto& a=v.array();require(a.size()<=bound,"Historical live array exceeds bound");return a;
}
std::string text(const Value& v,std::size_t bound){
    if(v.null())return {};
    const auto& s=v.string();require(!s.empty()&&s.size()<=bound,"Historical live text limit");return s;
}
bool schema(const Value& v){
    const auto* p=v.find("schema");if(!p)return false;
    return p->string()=="camarodiag-j1979-live-v1"||p->string()=="camarodiag-j1979-legacy-live-v1";
}
const Value& unwrap(const Value& v){
    if(schema(v))return v;
    const Value* found=nullptr;
    for(auto k:{"j1979Live","j1979_live","liveReport","live"}){
        const auto* p=v.find(k);
        if(p&&!p->null()&&schema(*p)){require(!found,"Ambiguous historical live wrapper");found=p;}
    }
    require(found,"Missing historical live report schema");return *found;
}
void no_claims(const Value& v){
    for(auto k:{"physical_vehicle_verified","whole_vehicle_coverage_verified","authenticated","permits_programming"})
        require(!boolean(v,k),"Unsupported historical live provenance assertion");
}
std::vector<unsigned> pids(const Value& v){
    std::vector<unsigned> out;
    if(const auto* s=std::get_if<std::string>(&v.data)){
        constexpr std::string_view alphabet="ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        require(s->size()<=44&&s->size()%4==0,"Invalid historical driver PID vector");
        for(std::size_t i=0;i<s->size();i+=4){
            unsigned word=0,padding=0;
            for(unsigned j=0;j<4;++j){
                word<<=6;
                if((*s)[i+j]=='='){require(i+4==s->size()&&j>=2,"Invalid driver PID padding");++padding;}
                else{const auto n=alphabet.find((*s)[i+j]);require(n!=std::string_view::npos&&!padding,"Invalid driver PID base64");word|=static_cast<unsigned>(n);}
            }
            require(!padding||!(word&((1u<<(padding*8))-1)),"Noncanonical driver PID padding");
            for(unsigned j=0;j<3-padding;++j)out.push_back((word>>(16-8*j))&255);
        }
    }else for(const auto& n:array(v,32))out.push_back(bounded(n,1,255));
    require(!out.empty()&&out.size()<=32,"Historical live PID selection limit");
    std::set<unsigned> seen;
    for(auto p:out)require(p&&p%32!=0&&seen.insert(p).second,"Duplicate or nondata live PID");
    return out;
}
HistoricalLiveRow row(const Value& v,bool sample){
    HistoricalLiveRow r{};
    r.original=&v;r.sample=sample;
    r.phase=field(v,"phase").string();
    r.status=field(v,"status").string();
    require(r.status=="PASSED"||r.status=="RAW"||r.status=="NEGATIVE"||r.status=="TIMEOUT"||
        r.status=="FAILED"||r.status=="NOT_TESTED","Invalid historical live observation status");
    r.detail=text(optional(v,"detail"),1024);
    r.start=bounded(field(v,"started_elapsed_ms"),0,max_time);
    r.end=bounded(field(v,"elapsed_ms"),r.start,max_time);
    r.duration=bounded(field(v,"duration_ms"),0,max_time);
    require(std::abs(static_cast<std::int64_t>(r.end-r.start)-r.duration)<=2,"Inconsistent live exchange duration");
    r.request=bytes(field(v,"request_hex"),2);
    require(r.request.size()==2&&(r.request[0]==1||r.request==Bytes{9,2}),"Historical live contains non-read request");
    const auto& response=optional(v,"response_hex");
    if(!response.null()){
        r.response=bytes(response,4095);require(!r.response.empty(),"Empty historical live response");
    }
    if(sample){
        r.cycle=bounded(field(v,"cycle"),1,1000);
        r.pid=bounded(field(v,"pid"),1,255);
    }
    return r;
}
unsigned priority(std::string_view phase){
    if(phase=="FINAL_VIN")return 2;
    if(phase=="SAMPLE")return 1;
    return 0;
}
bool accepted(const HistoricalLiveRow& r){return r.status=="PASSED"||r.status=="RAW";}
cd::j1979::Observation observation(const HistoricalLiveRow& r){
    return {r.request,r.response,accepted(r),!r.sample};
}
std::string application(const HistoricalLiveRow& r,bool raw_only,bool legacy){
    const auto& q=r.request;const auto& b=r.response;
    if(b.size()==3&&b[0]==0x7f&&b[1]==q[0]&&b[2]&&b[2]!=0x78)return "NEGATIVE";
    if(b.size()<3||b[0]!=q[0]+0x40||b[1]!=q[1])return {};
    if(raw_only&&r.sample)return b.size()<=(legacy?7u:4095u)?"RAW":"";
    if(q[0]==9)return b.size()==20&&b[2]==1&&cd_j1979_native_vin(
        reinterpret_cast<const char*>(b.data()+3),17)?"PASSED":"";
    if(legacy&&b.size()>7)return {};
    const unsigned pid=q[1];const auto size=b.size()-2;
    if(pid%32==0)return size==4&&!(pid==224&&(b.back()&1))?"PASSED":"";
    if(cd::j1979::trim_pid(pid)){
        if(size!=1&&size!=2)return {};
        return pid<0x55&&size==1?"PASSED":"RAW";
    }
    const auto width=pid==1?4u:pid==0x13||pid==0x1d?1u:
        j1979_measurement_width(static_cast<std::uint8_t>(pid));
    return width?(size==width?"PASSED":""):"RAW";
}
struct Signal {unsigned pid;std::string name,unit;std::uint64_t encoded;double number;};
std::optional<Signal> scalar(const HistoricalLiveRow& r,const cd::j1979::ScalingContext* ctx){
    j1979_measurement m{};
    if(!j1979_measurement_decode(r.request.data(),r.request.size(),r.response.data(),r.response.size(),&m))return {};
    const unsigned pid=r.request[1];double n=m.number;
    if(cd::j1979::dependency(pid)&&ctx){
        const auto s=cd::j1979::scale(observation(r),*ctx);
        if(!s||s->signals.size()!=1||!s->signals[0].number)return {};
        n=*s->signals[0].number;
    }else if(!m.interpreted){
        const auto a=r.response[2];
        if(pid==0x0b)n=a;
        else if(pid==0x10)n=(unsigned(a)*256+r.response[3])/100.0;
        else if(pid==0x44)n=(unsigned(a)*256+r.response[3])/32768.0;
        else if(pid>=6&&pid<=9)n=(int(a)-128)*100.0/128;
        else return {};
    }
    return Signal{pid,m.name,m.unit,0,n};
}
std::optional<std::vector<Signal>> multi(const HistoricalLiveRow& r,const cd::j1979::ScalingContext* ctx,
    const cd::j1979::Layout* layout){
    std::vector<Signal> out;
    if(layout&&cd::j1979::trim_pid(r.pid)){
        const auto t=cd::j1979::trims(observation(r),*layout);
        if(!t)return {};
        for(const auto& s:t->signals)if(s.number)out.push_back({r.pid,s.name,"%",s.raw,*s.number});
        return out;
    }
    const auto decoded=ctx?cd::j1979::multi_signal(observation(r),ctx):
        cd::j1979::historical_multi_signal(observation(r));
    if(!decoded)return {};
    if(cd::j1979::status_width(r.pid)&&!cd::j1979::status_record(observation(r)))return {};
    for(const auto& s:decoded->signals){
        if(!s.number)return {};
        out.push_back({r.pid,std::string(s.name),std::string(s.unit),s.raw,*s.number});
    }
    return out;
}
Value signal_json(const Signal& s){
    return obj({{"pid",num(s.pid)},{"name",str(s.name)},{"unit",str(s.unit)},
        {"raw_encoded",num(s.encoded)},{"number",real(s.number)}});
}
void exact(const Value& a,const Value& b,std::string_view k={}){
    require(compare(a,b,k),"Historical live declared field differs from replay");
}
void fields_equal(const Value& saved,const Value& rebuilt){
    for(const auto& [k,v]:rebuilt.object())exact(optional(saved,k),v,k);
}
bool absent_signals(const Value& v){return v.null()||v.array().empty();}
struct Statistics {
    std::string name,unit;
    unsigned count{},last_ms{};
    double minimum{},maximum{},last{};
    long double sum{};
    void add(const Signal& s,unsigned elapsed){
        require(std::isfinite(s.number)&&(!count||(name==s.name&&unit==s.unit)),"Historical live signal changes definition");
        name=s.name;unit=s.unit;
        minimum=count?std::min(minimum,s.number):s.number;maximum=count?std::max(maximum,s.number):s.number;
        ++count;sum+=s.number;last=s.number;last_ms=elapsed;
    }
    Object fields()const{
        return {{"name",count?str(name):Value{}},{"unit",count?str(unit):Value{}},
            {"minimum",count?real(minimum):Value{}},{"maximum",count?real(maximum):Value{}},
            {"mean",count?real(static_cast<double>(sum/count)):Value{}},{"last_verified_value",count?real(last):Value{}},
            {"last_verified_elapsed_ms",count?num(last_ms):Value{}}};
    }
};
struct PidStats{
    std::map<std::string,unsigned> counts;
    Statistics stat;
    unsigned attempts{};
    Value json(unsigned pid)const{
        auto fields=stat.fields();Object c;
        for(const auto& [k,n]:counts)c.emplace(k,num(n));
        fields.emplace("pid",num(pid));fields.emplace("attempted",num(attempts));
        fields.emplace("status_counts",obj(std::move(c)));return obj(std::move(fields));
    }
};
Value scale_json(const cd::j1979::ScalingContext& ctx){
    Array out;
    for(const auto& [pid,r]:std::array<std::pair<unsigned,cd::j1979::Range>,2>{{{0x4f,ctx.pid4f},{0x50,ctx.pid50}}}){
        const auto state=r.state==cd::j1979::RangeState::ecu_configuration?"ECU_CONFIGURATION":
            r.state==cd::j1979::RangeState::default_unsupported?"DEFAULT_UNSUPPORTED":"UNVERIFIED";
        out.push_back(obj({{"pid",num(pid)},{"state",str(state)},
            {"data_hex",r.state==cd::j1979::RangeState::ecu_configuration?str(legacy::hex(r.data)):Value{}}}));
    }
    return arr(std::move(out));
}
Value layout_json(const cd::j1979::Layout& l){
    const auto state=l.state==cd::j1979::LayoutState::verified?"VERIFIED":
        l.state==cd::j1979::LayoutState::not_advertised?"NOT_ADVERTISED_BY_SELECTED_ECU":"UNVERIFIED";
    return obj({{"state",str(state)},{"raw_encoded",l.value?num(*l.value):Value{}}});
}
class Replay {
    HistoricalLive& out_;
    const Value& body_;
    std::vector<HistoricalLiveRow>& rows_;
    std::map<unsigned,PidStats> stats_;
    std::map<std::pair<unsigned,std::string>,Statistics> channels_;
    std::set<unsigned> advertised_,required_configs_;
    std::vector<cd::j1979::Observation> context_rows_;
    std::vector<unsigned> configs_;
    Array clean_,pages_;
    bool scaling_{},layout_{},layout_attempted_{},inventory_complete_{},terminal_{},final_{};
    unsigned sample_index_{},next_page_{},last_end_{},identity_count_{};
    std::optional<unsigned> last_start_,last_identity_;
    std::string identity_,initial_;
    cd::j1979::Context context()const{return cd::j1979::derive_context(context_rows_);}
    bool scaling_verified()const{
        const auto c=context();
        for(auto pid:required_configs_)if((pid==0x4f?c.scaling.pid4f:c.scaling.pid50).state==cd::j1979::RangeState::unverified)
            return false;
        return true;
    }
    bool selected_supported()const{
        return std::all_of(out_.pids.begin(),out_.pids.end(),[&](auto p){return advertised_.contains(p);});
    }
    void sample(const HistoricalLiveRow& r){
        require(r.phase=="SAMPLE"&&identity_==out_.vin&&selected_supported()&&!final_,
            "Historical sample before identity or PID admission");
        const unsigned cycle=sample_index_/out_.pids.size()+1,pid=out_.pids[sample_index_%out_.pids.size()];
        require(cycle<=out_.cycles&&r.cycle==cycle&&r.pid==pid&&r.request==Bytes{1,static_cast<std::uint8_t>(pid)},
            "Historical sample order differs");
        if(sample_index_%out_.pids.size()==0){
            require(!last_start_||r.start+2>=*last_start_+out_.interval,"Historical cycle starts too early");
            require(last_identity_&&r.start>=*last_identity_&&r.start-*last_identity_<=30002,"Historical periodic VIN missing");
            last_start_=r.start;
        }
        if(layout_)require(!advertised_.contains(0x1d)||layout_attempted_,"Sample before trim admission");
        if(scaling_)require(scaling_verified(),"Sample before scaling admission");
        auto& st=stats_.at(pid);++st.attempts;++st.counts[r.status];
        const auto& saved=*r.original;
        const auto& measured=optional(saved,"measurement");
        const auto& signals=optional(saved,"multi_signals");
        std::optional<Signal> m;std::optional<std::vector<Signal>> channels;
        const auto c=context();
        if(out_.raw_only){
            require(measured.null()&&absent_signals(signals)&&r.status!="PASSED","Raw-only sample has interpreted value");
            if(r.status=="RAW")require(r.detail=="RAW_UNINTERPRETED","Missing historical raw interpretation marker");
        }else if(accepted(r)&&(cd::j1979::multi_width(pid)||(layout_&&cd::j1979::trim_pid(pid)))){
            channels=multi(r,scaling_?&c.scaling:nullptr,layout_?&c.layout:nullptr);
            require(channels&&measured.null()&&!signals.null(),"Invalid historical multichannel sample");
            require(array(signals,10).size()==channels->size(),"Historical signal count differs");
            for(std::size_t i=0;i<channels->size();++i){
                const auto& signal=channels->at(i);
                fields_equal(signals.array()[i],signal_json(signal));
                channels_[{pid,signal.name}].add(signal,r.end);
            }
        }else if(r.status=="PASSED"){
            m=scalar(r,scaling_?&c.scaling:nullptr);
            require(m&&!measured.null()&&absent_signals(signals),"Invalid historical scalar sample");
            fields_equal(measured,obj({{"name",str(m->name)},{"unit",str(m->unit)},{"number",real(m->number)}}));
            st.stat.add(*m,r.end);
        }else require(r.status!="RAW"&&measured.null()&&absent_signals(signals),"Missing sample claims engineering value");
        Array projected;
        if(channels)for(const auto& s:*channels)projected.push_back(signal_json(s));
        Object item{{"cycle",num(cycle)},{"pid",num(pid)},{"elapsed_ms",num(r.end)},{"status",str(r.status)},
            {"number",m?real(m->number):Value{}},{"unit",m?str(m->unit):Value{}},
            {"multi_signals",channels?arr(std::move(projected)):Value{}}};
        if(out_.raw_only)item.emplace("response_hex",r.response.empty()?Value{}:str(legacy::hex(r.response)));
        clean_.push_back(obj(std::move(item)));++sample_index_;
    }
    void admission(const HistoricalLiveRow& r,std::size_t index){
        const auto& saved=*r.original;
        require(optional(saved,"measurement").null()&&absent_signals(optional(saved,"multi_signals")),
            "Historical live admission claims measurement");
        require(r.status!="RAW"||(r.phase=="SCALING"&&scaling_),"Unexpected raw admission");
        const auto& q=r.request;
        if(r.phase=="INITIAL_VIN"||r.phase=="PERIODIC_VIN"||r.phase=="FINAL_VIN"){
            require(q==Bytes{9,2},"Unexpected historical VIN request");
            if(r.phase=="INITIAL_VIN")require(index==0&&!identity_count_,"Duplicate or late initial VIN");
            else{
                require(identity_==out_.vin&&selected_supported()&&sample_index_%out_.pids.size()==0&&!final_,
                    "Historical identity recheck outside cycle boundary");
                if(r.phase=="FINAL_VIN"){require(sample_index_==out_.cycles*out_.pids.size(),"Early final VIN");final_=true;}
            }
            identity_=r.status=="PASSED"?std::string(r.response.begin()+3,r.response.end()):"";
            if(r.phase=="INITIAL_VIN")initial_=identity_;
            ++identity_count_;
            if(identity_!=out_.vin)terminal_=true;else last_identity_=r.end;
        }else if(r.phase=="INVENTORY"){
            require(identity_==out_.vin&&!sample_index_&&!final_&&!inventory_complete_&&next_page_<=224&&
                q==Bytes{1,static_cast<std::uint8_t>(next_page_)}&&configs_.empty()&&!layout_attempted_,
                "Historical inventory page outside admitted sequence");
            context_rows_.push_back(observation(r));
            if(r.status=="PASSED"){
                const auto data=std::span(r.response).subspan(2);
                std::vector<unsigned> ids;
                for(unsigned i=0;i<31;++i)if(data[i/8]&(0x80u>>(i%8))){ids.push_back(next_page_+i+1);advertised_.insert(next_page_+i+1);}
                inventory_complete_=!(data.back()&1);
                pages_.push_back(obj({{"base",num(next_page_)},{"supported_ids",numbers(ids)},{"has_next_page",flag(!inventory_complete_)}}));
                next_page_+=32;
            }else terminal_=true;
        }else if(r.phase=="SCALING"){
            require(scaling_&&!layout_attempted_&&identity_==out_.vin&&!sample_index_&&!final_&&selected_supported()&&
                q[0]==1&&required_configs_.contains(q[1])&&advertised_.contains(q[1])&&
                (configs_.empty()||configs_.back()<q[1]),"Unexpected historical scaling request");
            if(accepted(r))require(r.response.size()==6&&(q[1]!=0x50||!(r.response[3]||r.response[4]||r.response[5])),
                "Malformed historical ECU scaling configuration");
            configs_.push_back(q[1]);context_rows_.push_back(observation(r));
        }else if(r.phase=="TRIM_LAYOUT"){
            require(layout_&&!layout_attempted_&&identity_==out_.vin&&!sample_index_&&!final_&&selected_supported()&&
                advertised_.contains(0x1d)&&q==Bytes{1,0x1d},"Unexpected historical trim layout request");
            layout_attempted_=true;context_rows_.push_back(observation(r));
        }else throw std::invalid_argument("Unexpected historical live admission phase");
    }
public:
    Replay(HistoricalLive& out,const Value& body,std::vector<HistoricalLiveRow>& rows):out_(out),body_(body),rows_(rows){
        scaling_=!optional(body,"scaling_configuration").null();layout_=!optional(body,"trim_layout").null();
        bool trims=false,secondary=false;
        for(auto pid:out_.pids){
            stats_.emplace(pid,PidStats{});
            if(!out_.raw_only){
                if(const auto d=cd::j1979::dependency(pid))required_configs_.insert(*d);
                trims=trims||cd::j1979::trim_pid(pid);secondary=secondary||(pid>=0x55&&pid<=0x58);
            }
        }
        require(!scaling_||!required_configs_.empty(),"Unexpected historical scaling configuration");
        require((!layout_||trims)&&(!secondary||layout_),"Missing or unexpected historical trim layout");
        context_rows_.reserve(rows.size());
    }
    Value run(){
        std::size_t bytes=0;
        for(std::size_t i=0;i<rows_.size();++i){
            const auto& r=rows_[i];
            require(!terminal_&&r.start>=last_end_,"Historical live observation after terminal condition or overlap");
            last_end_=r.end;bytes+=r.response.size();
            require(bytes<=16u*1024u*1024u,"Historical live payload bound");
            if(accepted(r)||r.status=="NEGATIVE")
                require(application(r,out_.raw_only,out_.protocol.has_value())==r.status&&r.end<=out_.budget+1,
                    "Historical live response status or budget differs");
            if(r.sample)sample(r);else admission(r,i);
            if(r.status=="FAILED"||r.status=="NOT_TESTED")terminal_=true;
        }
        const auto c=context();
        Value scaling=scaling_?scale_json(c.scaling):Value{},layout=layout_?layout_json(c.layout):Value{};
        if(scaling_){
            const auto& saved=array(field(body_,"scaling_configuration"),2);require(saved.size()==2,"Missing historical scaling rows");
            for(std::size_t i=0;i<2;++i)fields_equal(saved[i],scaling.array()[i]);
        }
        if(layout_)fields_equal(field(body_,"trim_layout"),layout);
        exact(optional(body_,"observed_vin"),initial_.empty()?Value{}:str(initial_));
        exact(field(body_,"advertised_pids"),numbers(advertised_));
        require(field(body_,"inventory_complete").boolean()==inventory_complete_,"Historical inventory completion differs");
        require(out_.duration+2>=last_end_,"Historical recording ends before observation");
        const auto possible=sample_index_/out_.pids.size();
        require(out_.completed_cycles==possible||(!out_.completed&&terminal_&&sample_index_%out_.pids.size()==0&&
            possible>0&&out_.completed_cycles==possible-1),"Historical completed cycle count differs");
        Array summary,channel_summary;
        const auto& saved=array(field(body_,"summary"),32);require(saved.size()==out_.pids.size(),"Missing historical PID summaries");
        bool has_measurement=false,all_raw=true;
        for(std::size_t i=0;i<out_.pids.size();++i){
            auto pid=out_.pids[i];auto& st=stats_.at(pid);
            auto r=st.json(pid);fields_equal(saved[i],r);summary.push_back(std::move(r));
            has_measurement=has_measurement||st.stat.count;
            all_raw=all_raw&&st.counts.contains("RAW")&&st.counts.at("RAW")>0;
        }
        for(const auto& [k,st]:channels_){
            auto fields=st.fields();const auto attempts=stats_.at(k.first).attempts;
            fields.emplace("pid",num(k.first));fields.emplace("count",num(st.count));fields.emplace("attempted",num(attempts));
            fields.emplace("missing",num(attempts-st.count));channel_summary.push_back(obj(std::move(fields)));
        }
        const auto& claimed=optional(body_,"multi_signal_summary");
        if(!claimed.null()||layout_||!channels_.empty()){
            const auto& rows=array(claimed,320);require(rows.size()==channel_summary.size(),"Historical channel summary count differs");
            for(std::size_t i=0;i<rows.size();++i)fields_equal(rows[i],channel_summary[i]);
        }
        out_.final_identity=final_&&identity_==out_.vin;
        if(out_.completed)require(out_.final_identity&&!terminal_&&out_.completed_cycles==out_.cycles&&
            (out_.raw_only?all_raw:has_measurement||!channels_.empty())&&out_.stop.empty(),"Historical completion not established");
        else require(!out_.stop.empty(),"Incomplete historical live report lacks stop reason");
        return obj({{"schema",str("camarodiag-j1979-live-inspection-v1")},{"verified",flag(true)},
            {"completed",flag(out_.completed)},{"declared_vin",str(out_.vin)},
            {"initial_vin",initial_.empty()?Value{}:str(initial_)},{"final_identity_verified",flag(out_.final_identity)},
            {"channel",out_.channel},{"requested_cycles",num(out_.cycles)},{"completed_cycles",num(out_.completed_cycles)},
            {"raw_only",flag(out_.raw_only)},{"selected_pids",numbers(out_.pids)},{"advertised_pids",numbers(advertised_)},
            {"inventory_complete",flag(inventory_complete_)},{"summary",arr(std::move(summary))},
            {"multi_signal_summary",arr(std::move(channel_summary))},{"samples",arr(std::move(clean_))},
            {"trim_layout",layout},{"scaling_configuration",scaling},
            {"scaling_context_verified",flag(required_configs_.empty()||(scaling_&&scaling_verified()))},
            {"physical_vehicle_verified",flag(false)},{"authenticated",flag(false)},{"whole_vehicle_coverage_verified",flag(false)},
            {"stop_reason",out_.stop.empty()?Value{}:str(out_.stop)}});
    }
};
}
HistoricalLive inspect_historical_live(const json::Value& source,std::optional<std::string> expected){
    no_claims(source);
    const auto& root=unwrap(source);no_claims(root);
    const auto* inner=property(root,"result");const auto& body=inner?*inner:root;no_claims(body);
    HistoricalLive out{};
    out.vin=field(body,"declared_vin").string();
    require(cd_j1979_native_vin(out.vin.data(),out.vin.size())&&(!expected||*expected==out.vin),"Historical live VIN differs from target");
    out.raw_only=boolean(body,"raw_only");
    out.pids=pids(field(body,"selected_pids"));
    out.cycles=bounded(field(body,"requested_cycles"),1,1000);
    require(out.pids.size()*out.cycles<=max_rows,"Historical live sample limit");
    out.completed_cycles=bounded(field(body,"completed_cycles"),0,out.cycles);
    out.interval=bounded(field(body,"interval_ms"),100,60000);
    out.budget=bounded(field(body,"budget_ms"),1,600000);
    out.duration=bounded(field(body,"duration_ms"),0,max_time);
    out.completed=field(body,"completed").boolean();
    out.stop=text(optional(body,"stop_reason"),1024);
    const bool legacy_format=root.at("schema").string()=="camarodiag-j1979-legacy-live-v1";
    if(legacy_format){
        require(!boolean(root,"physically_addressed"),"Legacy live falsely claims physical addressing");
        const auto& profile=optional(root,"expected_protocol");
        out.protocol=static_cast<legacy::Protocol>(bounded(profile.null()?field(root,"profile"):profile,1,5));
        out.source=address(field(root,"selected_response_source"));
        require(legacy::valid_source(out.source),"Invalid legacy selected source");
        out.legacy_driver=!optional(root,"legacy_variant").null();
        out.channel=obj({{"kind",str("legacy")},{"protocol",num(static_cast<unsigned>(*out.protocol))},
            {"source",num(out.source)},{"link_checksum_verified",flag(!out.legacy_driver)}});
    }else{
        out.pair={address(field(root,"tx_can_id")),address(field(root,"rx_can_id"))};
        require(cd_j1979_native_physical_pair(out.pair.tx,out.pair.rx,out.pair.tx>0x7ff),"Invalid historical live CAN pair");
        out.channel=obj({{"kind",str("can")},{"tx",num(out.pair.tx)},{"rx",num(out.pair.rx)}});
    }
    if(!out.raw_only)for(auto p:out.pids){
        const auto width=cd::j1979::multi_width(p);
        require(cd::j1979::trim_pid(p)||width||j1979_measurement_width(static_cast<std::uint8_t>(p)),
            "Unknown numeric historical live PID");
        if(legacy_format)require(!width||*width<=5,"Historical numeric PID exceeds legacy width");
    }
    std::vector<HistoricalLiveRow> rows;
    const auto append=[&](const Array& source_rows,bool sample){
        unsigned previous=0;
        for(const auto& v:source_rows){
            auto r=row(v,sample);require(r.start>=previous,"Historical live group reordered");
            previous=r.end;rows.push_back(std::move(r));
        }
    };
    append(array(field(body,"admission"),max_admission),false);append(array(field(body,"observations"),max_rows),true);
    std::stable_sort(rows.begin(),rows.end(),[](const auto& a,const auto& b){
        return std::tuple(a.start,priority(a.phase),a.end)<std::tuple(b.start,priority(b.phase),b.end);
    });
    out.proof=Replay(out,body,rows).run();
    verify_live_transport(root,out,rows);
    auto fields=out.proof.object();fields.emplace("adapter_transcript_replayed",flag(out.adapter_replayed));
    out.proof=obj(std::move(fields));return out;
}
std::string historical_live_report(const HistoricalLive& s,std::string_view digest){
    auto fields=s.proof.object();
    fields.emplace("source_sha256",digest.empty()?Value{}:str(digest));
    return encode_historical_json(obj(std::move(fields)))+"\n";
}
}
