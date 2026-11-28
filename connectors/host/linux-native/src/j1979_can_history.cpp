#include "j1979_can_history.hpp"
#include "j1979_can.h"
#include "j1979_policy.h"
#include <algorithm>
#include <charconv>
namespace cd::canhistory {
namespace {
using V=json::Value;
using legacy::hex;
constexpr std::array<std::string_view,3> modes{"03","07","0A"};
constexpr std::string_view clear_schema="camarodiag-functional-j1979-clear-v1",
    evap_schema="camarodiag-functional-j1979-evap-v1",baseline_schema="camarodiag-j1979-can-clear-baseline-v1";
void require(bool condition,const char* why){if(!condition)throw std::invalid_argument(why);}
const V* alias(const V& v,std::string_view name){
    std::string camel;
    for(size_t i=0;i<name.size();++i){
        if(name[i]=='_'&&i+1<name.size()&&name[i+1]>='a'&&name[i+1]<='z')
            camel+=static_cast<char>(name[++i]-'a'+'A');
        else camel+=name[i];
    }
    const auto* a=v.find(name);
    if(camel==name)return a;
    const auto* b=v.find(camel);
    require(!a||!b,"Ambiguous historical report property");return a?a:b;
}
const V& field(const V& v,std::string_view name){
    const auto* p=alias(v,name);require(p,"Missing historical report field");return *p;
}
std::string nullable(const V* v,size_t max){
    if(!v||v->null())return {};
    const auto& s=v->string();
    require(s.size()<=max,"Historical report field exceeds bound");return s;
}
uint32_t identifier(const V& v){
    const auto& s=v.string();uint32_t id=0;
    const auto [end,error]=std::from_chars(s.data(),s.data()+s.size(),id,16);
    require(s.size()>=1&&s.size()<=8&&error==std::errc{}&&end==s.data()+s.size(),"Invalid CAN ID");
    return id;
}
const V& fallback(const V* a,const V* b,const V* c=nullptr){
    const auto* v=a?a:b?b:c;require(v,"Missing historical scope");return *v;
}
struct Document{
    const V* body;
    Scope scope;
    std::string platform;
};
Document scope(const V& root,bool baseline){
    const auto schema=root.at("schema").string();
    require(baseline?schema==baseline_schema:schema==clear_schema||schema==evap_schema,"Unsupported historical CAN schema");
    Scope p{};p.evap=schema==evap_schema;
    const auto* nested=root.find(p.evap?"evap":"clear");
    const auto* wrapped=root.find("result");
    require(!nested||!wrapped,"Ambiguous historical CAN wrapper");
    const V& body=wrapped?*wrapped:nested?*nested:root;
    p.vin=fallback(alias(root,"expected_vin"),alias(root,"selected_vin")).string();
    require(p.vin.size()==17&&j1979_can_valid_vin(p.vin.c_str()),"Invalid historical VIN");
    p.tx=identifier(fallback(alias(body,"request_can_id"),alias(root,"request_can_id"),alias(root,"tx_can_id")));
    p.rx=identifier(fallback(alias(body,"response_can_id"),alias(root,"response_can_id"),alias(root,"rx_can_id")));
    require((p.tx==0x7e0&&p.rx==0x7e8)||(p.tx==0x18da10f1&&p.rx==0x18daf110),"Not a primary ECM");
    const auto* protocol=alias(body,"elm_protocol");
    if(protocol&&!protocol->null()){
        const auto n=protocol->unsigned_integer();
        require(n>=6&&n<=9&&((n==7||n==9)==(p.tx>0x7ff)),"ELM profile and CAN ID disagree");
    }
    for(const auto* obj:{&root,&body}){
        for(auto key:{"physical_vehicle_verified","monitor_completion_verified","whole_vehicle_coverage_verified",
                      "evidence_authenticated","permits_automatic_retry"}){
            const auto* v=alias(*obj,key);require(!v||!v->boolean(),"Unsupported historical verification claim");
        }
        for(auto key:{"request_can_id","tx_can_id"}){
            const auto* v=alias(*obj,key);require(!v||v->null()||identifier(*v)==p.tx,"Conflicting TX scope");
        }
        for(auto key:{"response_can_id","rx_can_id"}){
            const auto* v=alias(*obj,key);require(!v||v->null()||identifier(*v)==p.rx,"Conflicting RX scope");
        }
    }
    return {&body,std::move(p),wrapped?"J2534":nested||(protocol&&!protocol->null())?"ANDROID":"SOCKETCAN"};
}
Data bytes(std::string_view s,size_t max,bool canonical){
    require(s.size()<=2*max&&s.size()%2==0&&std::all_of(s.begin(),s.end(),[](char c){
        return (c>='0'&&c<='9')||(c>='A'&&c<='F')||(c>='a'&&c<='f');
    }),"Invalid historical hex");
    auto nibble=[](char c)->unsigned{return c<='9'?unsigned(c-'0'):c<='F'?unsigned(c-'A'+10):unsigned(c-'a'+10);};
    Data data;data.reserve(s.size()/2);
    for(size_t i=0;i<s.size();i+=2)data.push_back(static_cast<uint8_t>((nibble(s[i])<<4)|nibble(s[i+1])));
    require(!canonical||hex(data)==s,"Noncanonical request");return data;
}
Row read_row(const V& row,bool evap){
    Row r{};
    if(!evap)r.stage=row.at("stage").string();
    r.command=field(row,"request_hex").string();
    require(!bytes(r.command,2,true).empty(),"Empty historical request");
    const auto* response=alias(row,"response_hex");
    if(response&&!response->null())r.response=bytes(response->string(),4095,false);
    r.error=nullable(row.find("error"),256);
    return r;
}
bool reply(const Row& row,Data expected){return row.response&&*row.response==expected;}
std::string vin(const Row& row){
    if(!row.response||row.response->size()!=20||!std::equal(row.response->begin(),row.response->begin()+3,Data{0x49,2,1}.begin()))return {};
    std::string s(row.response->begin()+3,row.response->end());
    if(!std::all_of(s.begin(),s.end(),[](unsigned char c){return c<128;}))return {};
    return s;
}
std::optional<std::vector<std::string>> codes(unsigned mode,const Row& row){
    if(!row.response)return {};
    const auto& r=*row.response;
    if(r.size()<2||r[0]!=mode+64||r.size()!=2+2u*r[1])return {};
    std::vector<std::string> out;
    for(size_t i=2;i<r.size();i+=2){
        char code[6]{};
        if((!r[i]&&!r[i+1])||!cd_j1979_native_dtc_code(r.data()+i,code))return {};
        out.emplace_back(code);
    }
    return out;
}
std::string nrc(const Row& row,unsigned mode){
    if(!row.response)return {};
    const auto& r=*row.response;
    return r.size()==3&&r[0]==0x7f&&r[1]==mode&&r[2]&&r[2]!=0x78?
        "NRC_"+hex(legacy::Bytes(r).subspan(2)):std::string{};
}
void dtc_claim(const V& body,std::string_view name,const Dtc& values){
    const auto& obj=field(body,name);
    require(obj.object().size()==3,"Historical DTC field count");
    for(size_t i=0;i<modes.size();++i){
        const auto& actual=obj.at(modes[i]);
        if(!values[i]){require(actual.null(),"Missing DTC list promoted to known");continue;}
        require(actual.array().size()==values[i]->size(),"DTC list size differs");
        for(size_t n=0;n<values[i]->size();++n)
            require(actual.array()[n].string()==values[i]->at(n),"DTC list differs from response");
    }
}
using Step=std::pair<std::string_view,std::string_view>;
std::vector<Step> clear_plan(bool recheck){
    std::vector<Step> plan{{"PRECONDITION","0902"}};
    for(auto q:{"03","07","0A","0101"})plan.emplace_back("BEFORE",q);
    plan.insert(plan.end(),{{"PRECONDITION","010D"},{"PRECONDITION","010C"}});
    if(recheck)plan.insert(plan.end(),{{"RECHECK","0902"},{"RECHECK","010D"},{"RECHECK","010C"}});
    plan.emplace_back("RESET","04");
    for(auto q:{"03","07","0A","0101"})plan.emplace_back("AFTER",q);
    return plan;
}
Inspection replay_clear(const V& body,const Scope& scope,bool baseline){
    Inspection r{};r.scope=scope;
    const auto& rows=body.at("observations").array();require(rows.size()<=32,"Historical observation limit");
    for(const auto& row:rows)r.rows.push_back(read_row(row,false));
    constexpr size_t boundary=7;
    const bool recheck=r.rows.size()>boundary&&r.rows[boundary].stage=="RECHECK";
    auto plan=clear_plan(recheck);
    if(baseline)plan.resize(boundary);
    require(r.rows.size()<=plan.size(),"Extra CAN reset or exchange");
    bool blocked=false;std::string reset_nrc;
    for(size_t i=0;i<r.rows.size();++i){
        const auto& row=r.rows[i];const auto& [stage,q]=plan[i];
        require(!blocked&&row.stage==stage&&row.command==q,"Unexpected historical reset sequence");
        if(stage=="BEFORE"||stage=="AFTER"){
            if(row.error.empty()){
                const auto idx=std::find(modes.begin(),modes.end(),q);
                if(idx!=modes.end()){
                    const auto n=static_cast<size_t>(idx-modes.begin());
                    (stage=="BEFORE"?r.before:r.after)[n]=codes(std::array<unsigned,3>{3,7,10}[n],row);
                }else if(row.response&&row.response->size()==6&&(*row.response)[0]==0x41&&(*row.response)[1]==1){
                    const auto& b=*row.response;
                    r.readiness[stage=="BEFORE"?0:1]=Readiness{bool(b[2]&128),unsigned(b[2]&127),hex(legacy::Bytes(b).subspan(3))};
                }
            }
            if(stage=="BEFORE"&&q=="0101")blocked=!r.before[0];
        }else if(stage=="RESET"){
            r.attempted=true;r.acknowledged=reply(row,{0x44})&&row.error.empty();
            reset_nrc=nrc(row,4);blocked=!r.acknowledged;
        }else if(!row.error.empty())blocked=true;
        else if(q=="0902"){
            const auto found=vin(row);
            if(stage=="PRECONDITION")r.observed=found;
            blocked=found!=scope.vin;
        }else if(q=="010D")blocked=!reply(row,{0x41,0x0d,0});
        else if(q=="010C")blocked=!reply(row,{0x41,0x0c,0,0});
        if(recheck&&i==boundary+2&&!blocked)r.fresh=true;
    }
    require(field(body,"command_attempted").boolean()==r.attempted&&body.at("acknowledged").boolean()==r.acknowledged,
        "CAN reset flags differ from evidence");
    if(const auto* v=alias(body,"observed_vin"))require(nullable(v,17)==r.observed,"VIN claim differs");
    dtc_claim(body,"before_dtcs",r.before);dtc_claim(body,"after_dtcs",r.after);
    r.outcome=body.at("outcome").string();r.reason=nullable(body.find("reason"),256);
    const bool full=r.rows.size()==plan.size();
    if(baseline)require(full&&!blocked&&!r.attempted&&r.outcome=="BASELINE_SAVED"&&r.reason.empty(),"Invalid historical baseline");
    else if(!r.attempted)require(r.outcome=="NOT_TESTED","Unattempted reset claims outcome");
    else if(!r.acknowledged){
        require(r.outcome==(reset_nrc.empty()?"UNCERTAIN":"REFUSED"),"Reset outcome differs from acknowledgment");
        if(!reset_nrc.empty())require(r.reason==reset_nrc,"Reset refusal differs");
    }else if(r.outcome=="ACKNOWLEDGED_READBACK"){
        require(full&&r.after[0]&&r.after[1]&&r.reason==(!r.after[0]->empty()||!r.after[1]->empty()?"DTCS_STILL_PRESENT":""),
            "Historical readback claim exceeds evidence");
    }else require(r.outcome=="ACKNOWLEDGED_PARTIAL","Invalid historical reset outcome");
    return r;
}
Inspection replay_evap(const V& body,const Scope& scope){
    Inspection r{};r.scope=scope;
    constexpr std::array<std::string_view,5> plan{"0902","0800","010D","010C","0801"};
    const auto& rows=body.at("exchanges").array();require(rows.size()<=plan.size(),"Extra EVAP request");
    bool blocked=false;std::string refusal;
    for(size_t i=0;i<rows.size();++i){
        auto row=read_row(rows[i],true);
        require(!blocked&&row.command==plan[i],"Unexpected historical EVAP sequence");
        if(i==4){
            r.attempted=true;r.acknowledged=reply(row,{0x48,1})&&row.error.empty();refusal=nrc(row,8);
        }else if(!row.error.empty())blocked=true;
        else if(i==0){r.observed=vin(row);blocked=r.observed!=scope.vin;}
        else if(i==1)blocked=!row.response||row.response->size()!=6||(*row.response)[0]!=0x48||
            (*row.response)[1]!=0||!((*row.response)[2]&128);
        else if(i==2)blocked=!reply(row,{0x41,0x0d,0});
        else blocked=!reply(row,{0x41,0x0c,0,0});
        r.rows.push_back(std::move(row));
    }
    require(field(body,"command_attempted").boolean()==r.attempted,"EVAP attempted flag differs");
    if(const auto* ack=body.find("acknowledged"))require(ack->boolean()==r.acknowledged,"EVAP acknowledgment flag differs");
    if(const auto* v=alias(body,"observed_vin"))require(nullable(v,17)==r.observed,"EVAP VIN claim differs");
    r.outcome=body.at("outcome").string();r.reason=nullable(body.find("reason"),256);
    require(r.outcome==(!r.attempted?"NOT_TESTED":r.acknowledged?"ACKNOWLEDGED":refusal.empty()?"UNCERTAIN":"REFUSED"),
        "Historical EVAP outcome differs");
    if(r.acknowledged)require(r.reason.empty()||r.reason=="LEAK_TEST_NOT_PERFORMED","EVAP is not a leak test");
    if(!refusal.empty())require(r.reason==refusal,"EVAP refusal differs");
    return r;
}
}
Inspection inspect(std::string_view raw,std::string_view baseline,std::string_view expected){
    const auto root=json::parse(raw,max_report);
    const auto doc=scope(root,false);
    require(doc.scope.vin==expected,"Historical CAN report differs from independent VIN");
    auto result=doc.scope.evap?replay_evap(*doc.body,doc.scope):replay_clear(*doc.body,doc.scope,false);
    result.platform=doc.platform;
    auto compare=[&](const V& base){
        require(!doc.scope.evap,"EVAP cannot carry a reset baseline");
        const auto initial=replay_clear(base,doc.scope,true);
        require(result.rows.size()>=initial.rows.size()&&
            std::equal(initial.rows.begin(),initial.rows.end(),result.rows.begin()),"CAN baseline prefix differs");
        result.baseline_consistent=true;
    };
    if(!baseline.empty()){
        const auto base=json::parse(baseline,max_report);const auto declared=scope(base,true);
        require(declared.scope==doc.scope&&declared.platform==doc.platform,"CAN baseline scope differs");
        compare(*declared.body);result.baseline_origin="SEPARATE_FILE";
    }else{
        const auto* clear=root.find("clearBaseline");const auto* generic=root.find("baseline");
        require(!clear||!generic,"Ambiguous embedded baseline");
        if(const auto* embedded=clear?clear:generic;embedded&&!embedded->null()){
            for(auto key:{"request_can_id","tx_can_id"})if(const auto* v=alias(*embedded,key))require(identifier(*v)==doc.scope.tx,"Embedded baseline TX differs");
            for(auto key:{"response_can_id","rx_can_id"})if(const auto* v=alias(*embedded,key))require(identifier(*v)==doc.scope.rx,"Embedded baseline RX differs");
            if(const auto* v=alias(*embedded,"expected_vin"))require(v->string()==doc.scope.vin,"Embedded baseline VIN differs");
            compare(*embedded);result.baseline_origin="EMBEDDED_COPY";
        }
    }
    return result;
}
}
