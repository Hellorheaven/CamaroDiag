#include "j1979_status.hpp"
#include <array>
#include <span>
#include <utility>

namespace cd::j1979 {
namespace {
using Code=std::pair<unsigned,std::string_view>;
constexpr Code loop[]={{0,"RESERVED"},{1,"OPEN_LOOP"},{2,"CLOSED_LOOP"},{3,"FAULT"}};
constexpr Code fuel_loop[]={{0,"UNAVAILABLE"},{1,"OPEN_WARMUP"},{2,"CLOSED_FEEDBACK"},
    {4,"OPEN_DRIVING"},{8,"OPEN_FAULT"},{16,"CLOSED_SENSOR_FAULT"}};
constexpr Code on[]={{0,"INACTIVE"},{1,"ACTIVE"}};
constexpr Code present[]={{0,"ABSENT"},{1,"PRESENT"}};
constexpr Code induce[]={{0,"INACTIVE"},{1,"ENABLED"},{2,"ACTIVE"},{3,"UNSUPPORTED"}};
constexpr Code air[]={{1,"UPSTREAM"},{2,"DOWNSTREAM"},{4,"ATMOSPHERE_OR_OFF"},{8,"DIAGNOSTIC"}};
constexpr Code regen[]={{0,"PASSIVE"},{1,"ACTIVE"}};
constexpr Code ready[]={{0,"COMPLETE"},{1,"INCOMPLETE"}};
constexpr Code display[]={{0,"NONDISCRIMINATORY"},{1,"DISCRIMINATORY"},{3,"UNAVAILABLE"}};
constexpr Code closed[]={{0,"NOT_CLOSED_LOOP"},{1,"CLOSED_LOOP"}};
constexpr Code mi[]={{0,"OFF"},{1,"ON_DEMAND"},{2,"SHORT"},{3,"CONTINUOUS"},{14,"ERROR"},{15,"UNAVAILABLE"}};
constexpr Code obd[]={{1,"OBD_II"},{2,"OBD"},{3,"OBD_AND_OBD_II"},{4,"OBD_I"},{5,"NOT_COMPLIANT"},
    {6,"EOBD"},{7,"EOBD_OBD_II"},{8,"EOBD_OBD"},{9,"EOBD_OBD_OBD_II"},{10,"JOBD"},
    {11,"JOBD_OBD_II"},{12,"JOBD_EOBD"},{13,"JOBD_EOBD_OBD_II"},{17,"EMD"},{18,"EMD_PLUS"},
    {19,"HD_OBD_PARTIAL"},{20,"HD_OBD"},{21,"WWH_OBD"},{23,"HD_EOBD_I_NO_NOX"},{24,"HD_EOBD_I_NOX"},
    {25,"HD_EOBD_II_NO_NOX"},{26,"HD_EOBD_II_NOX"},{28,"BR_OBD_I"},{29,"BR_OBD_II"},{30,"KOBD"},
    {31,"INDIA_OBD_I"},{32,"INDIA_OBD_II"},{33,"HD_EOBD_VI"}};
constexpr std::array<std::string_view,24> fuel{"UNAVAILABLE","GASOLINE","METHANOL","ETHANOL","DIESEL",
    "LPG","CNG","PROPANE","ELECTRIC","BIFUEL_GASOLINE","BIFUEL_METHANOL","BIFUEL_ETHANOL",
    "BIFUEL_LPG","BIFUEL_CNG","BIFUEL_PROPANE","BIFUEL_ELECTRIC","BIFUEL_COMBINED",
    "HYBRID_GASOLINE","HYBRID_ETHANOL","HYBRID_DIESEL","HYBRID_ELECTRIC","HYBRID_COMBINED",
    "HYBRID_REGENERATION","BIFUEL_DIESEL"};
constexpr auto fuel_codes=[]{
    std::array<Code,fuel.size()> result{};
    for(unsigned i=0;i<fuel.size();++i)result[i]={i,fuel[i]};
    return result;
}();
struct Width {unsigned pid,width;};
constexpr Width widths[]={{0x03,2},{0x12,1},{0x13,1},{0x1c,1},{0x1d,1},{0x1e,1},{0x51,1},
    {0x70,10},{0x71,6},{0x7d,1},{0x7e,1},{0x8b,7},{0x8f,7},{0x90,3},{0x91,5},{0x92,2},{0x94,12}};
std::string_view name_for(std::span<const Code> codes,unsigned raw) {
    for(const auto& [value,label]:codes)if(value==raw)return label;
    return "UNKNOWN";
}
struct Reader {
    Bytes data;
    StatusRecord result;
    void field(std::string name,unsigned offset,unsigned shift,unsigned mask,std::span<const Code> codes,int support=-1) {
        if(support>=0&&!(data[0]&(1u<<support)))return;
        const unsigned raw=(data[offset]>>shift)&mask;
        result.states.push_back({std::move(name),raw,name_for(codes,raw)});
    }
    bool reserved(std::initializer_list<std::pair<unsigned,unsigned>> bits) const {
        for(const auto& [offset,mask]:bits)if(data[offset]&mask)return true;
        return false;
    }
};
void quoted(std::ostream& out,std::string_view s) {
    out<<'"';for(char c:s){if(c=='"'||c=='\\')out<<'\\';out<<c;}out<<'"';
}
}
std::optional<unsigned> status_width(unsigned pid) {
    for(const auto& w:widths)if(w.pid==pid)return w.width;
    return {};
}
std::optional<StatusRecord> status_record(const Observation& row) {
    if(row.request.size()!=2||row.request[0]!=1)return {};
    const auto data=payload(row);
    const unsigned pid=row.request[1];
    const auto width=status_width(pid);
    if(!data||!width||data->size()!=*width)return {};
    Reader r{*data,{pid,{}}};
    switch(pid){
    case 0x03:
        r.field("fuel-system-1",0,0,255,fuel_loop);
        r.field("fuel-system-2",1,0,255,fuel_loop);break;
    case 0x12:r.field("secondary-air",0,0,255,air);break;
    case 0x13:case 0x1d:{
        const unsigned slots=pid==0x13?4:2;
        for(unsigned i=0;i<8;++i)
            r.field("oxygen-bank-"+std::to_string(i/slots+1)+"-sensor-"+std::to_string(i%slots+1),0,i,1,present);
        break;}
    case 0x1c:r.field("declared-obd-standard",0,0,255,obd);break;
    case 0x1e:
        if(r.reserved({{0,0xfe}}))return {};
        r.field("power-take-off",0,0,1,on);break;
    case 0x51:r.field("current-fuel-type",0,0,255,fuel_codes);break;
    case 0x70:case 0x71:{
        const unsigned at=pid==0x70?9:5;
        if(r.reserved({{0,0xc0},{at,0xf0}}))return {};
        const std::string prefix=pid==0x70?"boost-control-":"vgt-control-";
        r.field(prefix+"a",at,0,3,loop,2);
        r.field(prefix+"b",at,2,3,loop,5);break;}
    case 0x7d:case 0x7e:{
        if(r.reserved({{0,0xf0}}))return {};
        constexpr std::array names{"inside","outside","carve-out","deficiency"};
        const std::string prefix=pid==0x7d?"nox-nte-":"pm-nte-";
        for(unsigned i=0;i<names.size();++i)r.field(prefix+names[i],0,i,1,on);
        break;}
    case 0x8b:
        if(r.reserved({{0,0x80},{1,0xf0}}))return {};
        r.field("dpf-regeneration",1,0,1,on,0);
        r.field("dpf-regeneration-type",1,1,1,regen,1);
        r.field("nox-adsorber-regeneration",1,2,1,on,2);
        r.field("nox-adsorber-desulfurization",1,3,1,on,3);break;
    case 0x8f:
        if(r.reserved({{0,0xf0},{1,0xfc},{4,0xfc}}))return {};
        for(unsigned bank=1;bank<=2;++bank){
            const auto prefix="pm-sensor-bank-"+std::to_string(bank)+"-";
            const unsigned offset=bank==1?1:4;
            const int support=bank==1?0:2;
            r.field(prefix+"measuring",offset,0,1,on,support);
            r.field(prefix+"regenerating",offset,1,1,on,support);
        }break;
    case 0x90:
        if(r.reserved({{0,0x80}}))return {};
        r.field("vehicle-mi",0,2,15,mi);
        r.field("emissions-readiness",0,6,1,ready);
        r.field("mi-display-strategy",0,0,3,display);break;
    case 0x91:
        if(r.reserved({{0,0xf0}}))return {};
        r.field("ecu-mi",0,0,15,mi);break;
    case 0x92:{
        constexpr std::array names{"pressure","quantity","timing","idle-balance"};
        for(unsigned bank=1;bank<=2;++bank)for(unsigned i=0;i<names.size();++i){
            const unsigned bit=(bank-1)*4+i;
            r.field(std::string("fuel-")+names[i]+"-"+std::to_string(bank),1,bit,1,closed,static_cast<int>(bit));
        }break;}
    case 0x94:
        if(r.reserved({{0,0xc0},{1,0x80}}))return {};
        r.field("nox-warning",1,0,1,on,0);
        for(unsigned i=1;i<=3;++i)r.field("inducement-level-"+std::to_string(i),1,2*i-1,3,induce,0);
        break;
    default:return {};
    }
    return std::move(r.result);
}
void write_status_json(std::ostream& out,std::span<const Observation> rows) {
    constexpr std::string_view digits="0123456789ABCDEF";
    bool first=true;
    out<<'[';
    for(const auto& row:rows){
        const auto decoded=status_record(row);
        if(!decoded)continue;
        if(!first)out<<',';
        first=false;
        out<<"{\"request_hex\":\"";
        for(auto b:row.request)out<<digits[b>>4]<<digits[b&15];
        out<<"\",\"states\":[";
        bool first_state=true;
        for(const auto& value:decoded->states){
            if(!first_state)out<<',';
            first_state=false;
            out<<"{\"pid\":"<<decoded->pid<<",\"name\":";quoted(out,value.name);
            out<<",\"raw_encoded\":"<<value.raw<<",\"state\":";quoted(out,value.state);out<<'}';
        }
        out<<"]}";
    }
    out<<']';
}
}
