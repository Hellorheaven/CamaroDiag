#include "j1979_legacy_monitor.hpp"
#include <array>
#include <iomanip>
namespace cd::legacy {
namespace {
struct Definition{std::string_view name,unit;double factor;};
constexpr std::array<Definition,10> definitions{{
    {"rich-lean-threshold","V",.005},{"lean-rich-threshold","V",.005},
    {"switch-low-voltage","V",.005},{"switch-high-voltage","V",.005},
    {"rich-lean-switch-time","s",.004},{"lean-rich-switch-time","s",.004},
    {"cycle-minimum-voltage","V",.005},{"cycle-maximum-voltage","V",.005},
    {"transition-interval","s",.04},{"sensor-period","s",.04}
}};
unsigned be16(Bytes b,size_t p){return (unsigned(b[p])<<8)|b[p+1];}
void optional(std::ostream& out,const std::optional<unsigned>& value){
    if(value)out<<*value;else out<<"null";
}
}
std::optional<MonitorValue> decode_monitor(Bytes q,Bytes r){
    if(q.size()<2||q[1]%32==0)return {};
    MonitorValue m{};m.mode=q[0];m.test_id=q[1];
    if(m.mode==5){
        if(q.size()!=3||!q[2]||(q[2]&(q[2]-1))||r.size()!=(m.test_id<=4?4u:6u)||
            r[0]!=0x45||r[1]!=q[1]||r[2]!=q[2])return {};
        m.sensor_mask=q[2];m.value=r[3];
        m.encoded.assign(r.begin()+3,r.end());
        if(m.test_id>4){m.minimum=r[4];m.maximum=r[5];}
        if(m.test_id<=definitions.size()){
            const auto& d=definitions[m.test_id-1];
            const std::string_view relation=!m.minimum?"NOT_APPLICABLE":*m.minimum>*m.maximum?"INVERTED_LIMITS":
                m.value<*m.minimum?"BELOW":m.value>*m.maximum?"ABOVE":"WITHIN";
            m.scaled=MonitorValue::Scale{d.name,d.unit,d.factor,relation};
        }
    }else if(m.mode==6){
        if(q.size()!=2||r.size()!=7||r[0]!=0x46||r[1]!=q[1])return {};
        m.component=r[2];m.value=be16(r,3);m.limit=be16(r,5);
        m.limit_type=(m.component&128)?"MINIMUM":"MAXIMUM";
        m.encoded_relation=m.value<*m.limit?"BELOW":m.value>*m.limit?"ABOVE":"EQUAL";
        m.encoded.assign(r.begin()+2,r.end());
    }else return {};
    return m;
}
void write_monitor(std::ostream& out,const MonitorValue& m){
    out<<"{\"mode\":"<<m.mode<<",\"test_id\":"<<m.test_id
        <<",\"sensor_mask\":";
    if(m.mode==5)out<<m.sensor_mask;else out<<"null";
    out<<",\"encoded_data_hex\":\""<<hex(m.encoded)<<"\",\"value_encoded\":"<<m.value;
    if(m.mode==5){
        out<<",\"minimum_encoded\":";optional(out,m.minimum);
        out<<",\"maximum_encoded\":";optional(out,m.maximum);
    }else{
        out<<",\"component_and_limit_encoded\":"<<m.component<<",\"component_id\":"<<(m.component&127)
            <<",\"limit_encoded\":";optional(out,m.limit);
        out<<",\"limit_type\":\""<<m.limit_type<<"\",\"encoded_relation\":\""<<m.encoded_relation<<"\"";
    }
    out<<",\"scaled\":";
    if(m.scaled){
        const auto& s=*m.scaled;
        out<<"{\"definition\":\"ISO15031-5:2006-ANNEX-C\",\"name\":\""<<s.name
            <<"\",\"unit\":\""<<s.unit<<"\",\"value\":"<<m.value*s.factor<<",\"minimum\":";
        if(m.minimum)out<<*m.minimum*s.factor;else out<<"null";
        out<<",\"maximum\":";
        if(m.maximum)out<<*m.maximum*s.factor;else out<<"null";
        out<<",\"numeric_relation\":\""<<s.relation<<"\"}";
    }else out<<"null";
    out<<",\"scale_verified\":"<<(m.scaled?"true":"false")<<",\"monitor_verdict\":null,\"diagnostic_verdict\":null}";
}
}
