#include "j1979_cm1.hpp"
#include <algorithm>
#include <array>
#include <charconv>
#include <stdexcept>
#include <utility>

namespace cd::cm1 {
namespace {
constexpr std::array<std::string_view,5> kinds{"","OPEN","BASELINE","INTENT","TERMINAL"};
constexpr std::array<std::string_view,7> outcomes{"RUNNING","NOT_TESTED","REFUSED","UNCERTAIN",
    "ACKNOWLEDGED_PARTIAL","ACKNOWLEDGED_READBACK","ACKNOWLEDGED_EVAP"};
constexpr std::array<std::string_view,14> reasons{"NONE","INVALID_SCOPE","CONTEXT","DEADLINE","SEQUENCE","IDENTITY",
    "STATIONARY","UNSUPPORTED","BASELINE","JOURNAL","TRANSPORT","NEGATIVE","READBACK","DTC_REMAINING"};
constexpr std::array<std::string_view,8> flags_a{"baseline_persisted","intent_persisted","attempted","acknowledged",
    "initial_vin","rechecked_vin","final_vin","before_confirmed"};
constexpr std::array<std::string_view,6> flags_b{"after_confirmed","after_pending","after_confirmed_empty","after_pending_empty",
    "physical_stop_declared","recovery_required"};
constexpr std::array<std::string_view,16> clear_plan{"0902","03","07","0A","0101","010D","010C","0902","010D","010C","04",
    "03","07","0A","0101","0902"};
constexpr std::array<unsigned,16> clear_stages{1,2,2,2,2,2,2,4,4,4,6,7,7,7,7,7};
constexpr std::array<std::string_view,9> evap_plan{"0902","0800","010D","010C","0902","0800","010D","010C","0801"};
constexpr std::array<unsigned,9> evap_stages{1,2,2,2,4,4,4,4,6};

void require(bool condition,const char* message) {
    if (!condition) throw std::invalid_argument(message);
}
unsigned u16(Bytes b,std::size_t p) {
    return unsigned(b[p]) | unsigned(b[p+1])<<8;
}
std::uint32_t u32(Bytes b,std::size_t p) {
    return std::uint32_t(b[p]) | std::uint32_t(b[p+1])<<8 |
        std::uint32_t(b[p+2])<<16 | std::uint32_t(b[p+3])<<24;
}
std::uint32_t crc(Bytes b) {
    std::uint32_t v=0xffffffffu;
    for (auto byte:b) {
        v^=byte;
        for (unsigned i=0;i<8;++i) v=(v>>1)^((v&1u)?0xedb88320u:0u);
    }
    return v^0xffffffffu;
}
bool zero(Bytes b) {return std::all_of(b.begin(),b.end(),[](auto x){return x==0;});}
bool valid_vin(std::string_view v) {
    return v.size()==17 && std::all_of(v.begin(),v.end(),[](char c){
        return (c>='0'&&c<='9')||(c>='A'&&c<='Z'&&c!='I'&&c!='O'&&c!='Q');
    });
}
std::string hex(Bytes data) {
    constexpr std::string_view alphabet="0123456789ABCDEF";
    std::string out;
    out.reserve(data.size()*2);
    for(auto b:data){out+=alphabet[b>>4];out+=alphabet[b&15];}
    return out;
}
std::string can_hex(std::uint32_t n) {
    std::array<char,9> buf{};
    auto result=std::to_chars(buf.data(),buf.data()+buf.size(),n,16);
    require(result.ec==std::errc{},"Invalid CAN identifier");
    std::string out(buf.data(),result.ptr);
    for(char& c:out) if(c>='a'&&c<='f')c=char(c-'a'+'A');
    return out;
}
std::string_view stage(unsigned n) {
    switch(n){case 1:return "INITIAL";case 2:return "BASELINE";case 4:return "RECHECK";
        case 6:return "COMMAND";case 7:return "READBACK";default:throw std::invalid_argument("Invalid stage");}
}
std::string_view status(unsigned n) {
    switch(n){case 1:return "POSITIVE";case 2:return "NEGATIVE";case 3:return "TIMEOUT";
        case 4:return "FAULT";default:throw std::invalid_argument("Invalid status");}
}
Scope parse_scope(Bytes data) {
    require(data.size()==scope_size && (data[0]==1||data[0]==2)&&data[1]<=1,"Invalid CM/1 scope");
    Scope s{data[0],data[1]==1,u32(data,2),u32(data,6),u32(data,10),
        std::string(data.begin()+14,data.end())};
    require((s.extended?s.tx==0x18da10f1u&&s.rx==0x18daf110u:s.tx==0x7e0u&&s.rx==0x7e8u)
        && s.budget>=1000 && s.budget<=60000 && valid_vin(s.vin),"Invalid maintenance target");
    return s;
}
Summary parse_summary(Bytes data,unsigned count) {
    require(data.size()==summary_size,"Truncated CM/1 summary");
    Summary s{data[0],data[1],data[2],data[3],data[4],data[5],u32(data,8)};
    require(s.outcome<outcomes.size()&&s.reason<reasons.size()&&s.count==count&&(s.b&0xc0)==0
        &&zero(data.subspan(6,2))&&zero(data.subspan(12,8)),"Invalid CM/1 summary");
    return s;
}
Event parse_event(Bytes data) {
    require(data.size()>=row_size,"Truncated CM/1 observation");
    auto n=data[2];
    auto size=u16(data,6);
    require((n==1||n==2)&&data[5]==0&&(n==2||data[4]==0)&&size<=max_reply&&data.size()==row_size+size,
        "Malformed CM/1 observation");
    Event e{};
    e.stage=data[0];e.status=data[1];e.start=u32(data,8);e.end=u32(data,12);e.timeout=u16(data,16);
    (void)stage(e.stage);(void)status(e.status);
    require(e.start<=e.end&&e.timeout>=1&&e.timeout<=1800,"Invalid CM/1 observation timing");
    e.request.assign(data.begin()+3,data.begin()+3+n);
    e.response.assign(data.begin()+row_size,data.end());
    e.wire.assign(data.begin(),data.end());
    return e;
}
unsigned shape(const Event& e) {
    const Bytes r=e.response, q=e.request;
    const auto cmd=hex(q);
    if(r.empty())return e.status==3||e.status==4?e.status:0;
    if(r[0]==0x7f)return r.size()==3&&r[1]==q[0]&&r[2]!=0&&r[2]!=0x78?2:0;
    if(cmd=="04")return r.size()==1&&r[0]==0x44?1:0;
    if(cmd=="0801")return r.size()==2&&r[0]==0x48&&r[1]==1?1:0;
    if(q[0]==3||q[0]==7||q[0]==10){
        if(r.size()<2||r[0]!=q[0]+64u||r.size()!=2u+2u*r[1])return 0;
        for(std::size_t i=2;i<r.size();i+=2)if(r[i]==0&&r[i+1]==0)return 0;
        return 1;
    }
    if(cmd=="0902")return r.size()==20&&r[0]==0x49&&r[1]==2&&r[2]==1&&
        valid_vin(std::string(r.begin()+3,r.end()))?1:0;
    const std::size_t size=cmd=="0101"||cmd=="0800"?6:cmd=="010D"?3:cmd=="010C"?4:0;
    return size&&r.size()==size&&r[0]==q[0]+64u&&r[1]==q[1]?1:0;
}
void abort(Summary& s,unsigned why) {
    s.reason=why;
    s.outcome=(s.a&8)?4:(s.a&6)?3:1;
}
std::size_t baseline_boundary(const Scope& s){return s.operation==1?7:4;}
std::size_t intent_boundary(const Scope& s){return s.operation==1?10:8;}

/* Independent replay, not the vehicle-side sequence engine. Recompute every
 * terminal field and never accept a caller-supplied observation order. */
Summary replay(const Scope& scope,const std::vector<Event>& rows,bool baseline,bool intent) {
    Summary s{};
    std::uint32_t now=0;
    std::optional<std::uint32_t> identity,speed,rpm;
    const std::span<const std::string_view> plan=scope.operation==1?
        std::span<const std::string_view>(clear_plan):std::span<const std::string_view>(evap_plan);
    const std::span<const unsigned> stages=scope.operation==1?
        std::span<const unsigned>(clear_stages):std::span<const unsigned>(evap_stages);
    require(rows.size()<=plan.size(),"Too many observations");
    for(std::size_t i=0;i<rows.size();++i){
        const auto& e=rows[i];
        const auto cmd=hex(e.request);
        require(s.outcome==0&&e.stage==stages[i]&&cmd==plan[i],"Unexpected maintenance request");
        require(e.start>=now&&e.start<scope.budget&&e.timeout<=scope.budget-e.start,"Invalid deadline");
        if(i==baseline_boundary(scope)){require(baseline,"Missing baseline");s.a|=1;}
        if(i==intent_boundary(scope)){
            const auto fresh=[&](const auto& time){
                return time && e.start>=*time && std::uint64_t(e.start-*time)+e.timeout<=5000u;
            };
            require(intent&&baseline&&fresh(identity)&&fresh(speed)&&fresh(rpm),
                "Command without fresh checks and intent");
            s.a|=6;
        }
        now=e.end;++s.count;s.duration=now;
        if(now>=scope.budget||now-e.start>e.timeout){abort(s,3);continue;}
        if(e.status==4){require(e.response.empty(),"Fault carries data");abort(s,10);continue;}
        if(shape(e)!=e.status){abort(s,10);continue;}
        const bool positive=e.status==1;
        if(e.stage==6){
            if(e.status==2){s.outcome=2;s.reason=11;s.nrc=e.response[2];}
            else if(!positive)abort(s,10);
            else{s.a|=8;if(scope.operation==2)s.outcome=6;}
        }else if(cmd=="0902"){
            if(!positive||std::string(e.response.begin()+3,e.response.end())!=scope.vin)abort(s,5);
            else if(e.stage==1)s.a|=16;
            else if(e.stage==4){s.a|=32;identity=now;}
            else s.a|=64;
        }else if(cmd=="010D"||cmd=="010C"){
            if(!positive||!zero(Bytes(e.response).subspan(2)))abort(s,6);
            else if(e.stage==4){if(cmd=="010D")speed=now;else rpm=now;}
        }else if(cmd=="0800"){
            if(!positive||(e.response[2]&0x80)==0)abort(s,7);
        }else if(e.stage==2&&cmd=="03"){
            if(!positive)abort(s,8);else s.a|=128;
        }else if(e.stage==7&&(cmd=="03"||cmd=="07")){
            if(positive){const unsigned bit=cmd=="03"?1:2;s.b|=bit;if(e.response[1]==0)s.b|=bit<<2;}
        }
        if(i==plan.size()-1&&scope.operation==1&&s.outcome==0){
            if((s.b&3)==3&&(s.a&64)!=0){s.outcome=5;s.reason=(s.b&12)==12?0:13;}
            else abort(s,12);
        }
    }
    return s;
}
struct Entry {
    unsigned kind{};
    Scope scope;
    std::optional<Summary> summary;
    std::vector<Event> rows;
};
bool next_kind(unsigned previous,unsigned kind){
    return kind>=1&&kind<=4&&previous!=4&&(previous==0?kind==1:kind==previous+1||kind==4);
}
std::vector<Entry> records(Bytes data) {
    require(!data.empty()&&data.size()<=max_journal,"Invalid CM/1 input size");
    std::vector<Entry> result;
    std::size_t pos=0;
    unsigned prior=0;
    while(pos<data.size()){
        require(result.size()<4&&data.size()-pos>=header_size,"Truncated or excessive CM/1 records");
        const auto h=data.subspan(pos,header_size);
        const unsigned kind=h[3],count=h[5];
        const auto len=u32(h,8);
        require(h[0]=='C'&&h[1]=='M'&&h[2]==1&&h[4]==result.size()&&u16(h,6)==0
            &&count<=max_rows&&next_kind(prior,kind),"Unexpected CM/1 header");
        require(len>=scope_size&&len<=max_record-header_size-4&&len+header_size+4<=data.size()-pos,
            "CM/1 body size");
        const auto end=pos+header_size+len;
        require(crc(data.subspan(pos,end-pos))==u32(data,end),"CM/1 CRC mismatch");
        auto cursor=pos+header_size;
        Entry entry{};entry.kind=kind;entry.scope=parse_scope(data.subspan(cursor,scope_size));cursor+=scope_size;
        if(kind==1)require(count==0&&cursor==end,"Unexpected open payload");
        else{
            require(cursor+summary_size<=end,"Truncated summary");
            entry.summary=parse_summary(data.subspan(cursor,summary_size),count);cursor+=summary_size;
            for(unsigned i=0;i<count;++i){
                require(cursor+row_size<=end,"Truncated observation header");
                const unsigned size=u16(data,cursor+6);
                require(size<=max_reply&&cursor+row_size+size<=end,"Truncated observation");
                entry.rows.push_back(parse_event(data.subspan(cursor,row_size+size)));
                cursor+=row_size+size;
            }
            require(cursor==end,"Trailing CM/1 record bytes");
        }
        result.push_back(std::move(entry));prior=kind;pos=end+4;
    }
    return result;
}
Inspection complete(Bytes raw,std::string_view vin) {
    const auto entries=records(raw);
    Inspection result{};result.scope=entries.front().scope;
    result.verified_bytes=result.total_bytes=raw.size();result.records.push_back(1);
    require(result.scope.vin==vin,"CM/1 differs from independent VIN");
    for(std::size_t i=1;i<entries.size();++i){
        const auto& e=entries[i];
        result.records.push_back(e.kind);
        require(e.scope==result.scope&&e.rows.size()>=result.observations.size(),"CM/1 prior evidence changed");
        for(std::size_t n=0;n<result.observations.size();++n)
            require(result.observations[n].wire==e.rows[n].wire,"CM/1 prior evidence changed");
        auto state=replay(result.scope,e.rows,result.baseline,result.intent);
        require(e.summary.has_value(),"Missing CM/1 summary");
        const auto& claimed=*e.summary;
        if(e.kind==2){
            require(e.rows.size()==baseline_boundary(result.scope)&&state.outcome==0&&state==claimed,"Invalid baseline");
            result.baseline=true;
        }else if(e.kind==3){
            require(result.baseline&&e.rows.size()==intent_boundary(result.scope)&&state.outcome==0&&state==claimed,
                "Invalid intent");result.intent=true;
        }else{
            require((claimed.b&16)!=0,"Terminal missing shutdown declaration");
            if(state.outcome==0){
                const unsigned why=claimed.reason;
                require((why==2||why==3||why==4||why==6||why==9||why==10)&&claimed.duration>=state.duration
                    &&(why!=3||claimed.duration>=result.scope.budget),"Invalid external terminal declaration");
                if(e.rows.size()==baseline_boundary(result.scope)&&result.baseline)state.a=(state.a&~1u)|(claimed.a&1u);
                if(e.rows.size()==intent_boundary(result.scope)&&result.intent)state.a=(state.a&~2u)|(claimed.a&2u);
                abort(state,why);state.duration=claimed.duration;
            }
            state.b|=16|(claimed.b&32);
            require((state.outcome!=3||(state.b&32)!=0)&&state==claimed,"Terminal summary differs from replay");
            result.terminal=state;
        }
        result.observations=e.rows;
    }
    return result;
}
Inspection incomplete(Bytes raw,std::string_view vin) {
    std::size_t pos=0;
    unsigned number=0,prior=0,declared=0;
    std::optional<Scope> first;
    constexpr std::array<std::uint8_t,3> magic{'C','M',1};
    while(pos<raw.size()){
        require(number<4&&prior!=4,"Trailing bytes after CM/1 terminal");
        const auto h=raw.subspan(pos,std::min(header_size,raw.size()-pos));
        for(std::size_t i=0;i<std::min(magic.size(),h.size());++i)
            require(h[i]==magic[i],"Invalid CM/1 prefix magic/version");
        if(h.size()>=4){require(next_kind(prior,h[3]),"Unexpected prefix record");declared=h[3];}
        if(h.size()>=5)require(h[4]==number,"Invalid prefix sequence");
        if(h.size()>=6)require(h[5]<=max_rows&&(number!=0||h[5]==0),"Invalid prefix count");
        for(std::size_t i=6;i<std::min<std::size_t>(8,h.size());++i)require(h[i]==0,"Invalid prefix reserved field");
        if(h.size()<header_size)break;
        const unsigned kind=h[3],count=h[5];const auto len=u32(h,8);
        require(len>=scope_size&&len<=max_record-header_size-4&&
            (kind==1?len==scope_size&&count==0:len>=scope_size+summary_size+count*row_size),
            "Invalid prefix body size");
        const auto end=pos+header_size+len+4;
        if(raw.size()-pos>=header_size+scope_size){
            const auto scope=parse_scope(raw.subspan(pos+header_size,scope_size));
            require(scope.vin==vin&&(!first||*first==scope),"CM/1 prefix scope changed");
            if(!first)first=scope;
        }
        if(end>raw.size())break;
        require(crc(raw.subspan(pos,end-pos-4))==u32(raw,end-4),"Complete prefix record has bad CRC");
        pos=end;++number;prior=kind;declared=0;
    }
    require(number>0&&prior!=4,"Interrupted CM/1 requires verified open and no terminal");
    auto result=complete(raw.first(pos),vin);
    require(!result.terminal&&result.recovery_required(),"Invalid prefix state");
    result.prefix=true;result.total_bytes=raw.size();result.next_kind=declared;
    return result;
}

/* Only fixed field names, validated VIN and hexadecimal evidence are
 * serialized. The writer nevertheless escapes strings, and owns all data. */
class Json {
    std::string out_;
public:
    void literal(std::string_view s){out_+=s;}
    void str(std::string_view s){
        out_+='"';
        for(char c:s){require(static_cast<unsigned char>(c)>=32&&c!=127,"Invalid JSON text");
            if(c=='"'||c=='\\')out_+='\\';
            out_+=c;}
        out_+='"';
    }
    void key(std::string_view s){str(s);out_+=':';}
    void num(std::uint64_t n){out_+=std::to_string(n);}
    void boolean(bool b){literal(b?"true":"false");}
    void field(std::string_view name,std::string_view value){literal(",");key(name);str(value);}
    void field(std::string_view name,bool value){literal(",");key(name);boolean(value);}
    void numeric(std::string_view name,std::uint64_t n){literal(",");key(name);num(n);}
    std::string finish(){require(out_.size()<=max_report,"CM/1 report size");return std::move(out_);}
};
void scope_json(Json& j,const Scope& s) {
    j.literal("{");j.key("operation");j.str(s.operation==1?"CLEAR":"EVAP");
    j.field("extended",s.extended);j.field("request_can_id",can_hex(s.tx));j.field("response_can_id",can_hex(s.rx));
    j.numeric("budget_ms",s.budget);j.field("expected_vin",s.vin);j.literal("}");
}
void summary_json(Json& j,const Summary& s) {
    j.literal("{");j.key("outcome");j.str(outcomes.at(s.outcome));j.field("reason",reasons.at(s.reason));
    j.numeric("observations",s.count);j.numeric("nrc",s.nrc);j.numeric("duration_ms",s.duration);
    for(unsigned i=0;i<flags_a.size();++i)j.field(flags_a[i],(s.a&(1u<<i))!=0);
    for(unsigned i=0;i<flags_b.size();++i)j.field(flags_b[i],(s.b&(1u<<i))!=0);
    j.literal("}");
}
void observations_json(Json& j,const std::vector<Event>& rows) {
    j.literal("[");bool first=true;
    for(const auto& e:rows){
        if(!first)j.literal(",");
        first=false;j.literal("{");j.key("stage");j.str(stage(e.stage));j.field("status",status(e.status));
        j.field("request_hex",hex(e.request));j.literal(",");j.key("response_hex");
        if(e.response.empty())j.literal("null");else j.str(hex(e.response));
        j.numeric("started_ms",e.start);j.numeric("ended_ms",e.end);j.numeric("timeout_ms",e.timeout);j.literal("}");
    }
    j.literal("]");
}
}
Inspection inspect(Bytes raw,std::string_view vin,bool prefix) {
    require(valid_vin(vin),"Independent VIN required");
    require(!raw.empty()&&raw.size()<=max_journal,"Invalid CM/1 input size");
    return prefix?incomplete(raw,vin):complete(raw,vin);
}
std::string report(const Inspection& e,std::string_view digest) {
    require(digest.size()==64&&std::all_of(digest.begin(),digest.end(),[](char c){return (c>='0'&&c<='9')||(c>='a'&&c<='f');}),
        "Invalid journal digest");
    Json j;j.literal("{");j.key("schema");j.str(e.prefix?"camarodiag-j1979-native-maintenance-prefix-v1":
        "camarodiag-j1979-native-maintenance-inspection-v1");
    j.literal(",");j.key("protocol");j.str("SAE-J1979");j.literal(",");j.key("scope");scope_json(j,e.scope);
    if(e.prefix)j.field("prefix_integrity_verified",true);
    j.field("record_consistency_verified",!e.prefix);j.field("terminal_present",e.terminal.has_value());
    if(e.prefix){
        j.numeric("verified_prefix_bytes",e.verified_bytes);j.numeric("unverified_trailing_bytes",e.total_bytes-e.verified_bytes);
        j.numeric("journal_bytes_received",e.total_bytes);j.literal(",");j.key("next_record_kind_declared");
        if(e.next_kind)j.str(kinds.at(e.next_kind));else j.literal("null");
    }else{
        j.literal(",");j.key("terminal");if(e.terminal)summary_json(j,*e.terminal);else j.literal("null");
    }
    j.literal(",");j.key(e.prefix?"verified_records":"records");j.literal("[");
    bool first=true;for(unsigned k:e.records){if(!first)j.literal(",");first=false;j.str(kinds.at(k));}
    j.literal("]");j.field("baseline_record_present",e.baseline);j.field("intent_record_present",e.intent);
    j.literal(",");j.key("observations");observations_json(j,e.observations);
    j.field("recovery_required",e.recovery_required());
    if(e.prefix)j.field("command_outcome_unknown",true);
    for(const auto key:{"physical_vehicle_verified","physical_stop_independently_verified","durable_before_tx_independently_verified",
        "operator_consent_verified","monitor_completion_verified","evidence_authenticated","permits_automatic_retry","permits_programming"})
        j.field(key,false);
    j.field("journal_sha256",digest);j.literal("}\n");return j.finish();
}
}
