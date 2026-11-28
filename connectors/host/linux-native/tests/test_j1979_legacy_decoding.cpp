#include "j1979_legacy_info.hpp"
#include "j1979_legacy_monitor.hpp"
#include <cassert>
#include <cmath>
#include <sstream>
namespace {
using namespace cd::legacy;
std::vector<Data> fragments(unsigned kind,Data data){
    assert(data.size()%4==0);
    std::vector<Data> rows;
    for(size_t i=0;i<data.size();i+=4){
        Data row{0x49,static_cast<uint8_t>(kind),static_cast<uint8_t>(i/4+1)};
        row.insert(row.end(),data.begin()+i,data.begin()+i+4);rows.push_back(std::move(row));
    }
    return rows;
}
std::optional<Identification> info(unsigned kind,const Data& data){
    const auto rows=fragments(kind,data);
    return decode_identification(kind,static_cast<unsigned>(rows.size()),rows);
}
void monitor(){
    for(unsigned tid=1;tid<=255;++tid){
        if(tid%32==0)continue;
        for(unsigned mask:{1u,2u,4u,8u,16u,32u,64u,128u}){
            const Data q{5,static_cast<uint8_t>(tid),static_cast<uint8_t>(mask)};
            Data r{0x45,q[1],q[2],50};
            if(tid>4){r.push_back(10);r.push_back(100);}
            const auto m=decode_monitor(q,r);
            assert(m&&m->mode==5&&m->sensor_mask==mask&&m->value==50);
            assert(bool(m->scaled)==(tid<=10));
            if(m->scaled){
                const auto& s=*m->scaled;
                const double factor=tid<=4||(tid>=7&&tid<=8)?.005:tid<=6?.004:.04;
                assert(std::abs(s.factor-factor)<1e-12);
                assert(s.relation==(tid<=4?"NOT_APPLICABLE":"WITHIN"));
            }
            auto bad=r;bad[2]^=1;assert(!decode_monitor(q,bad));
            bad=r;bad.pop_back();assert(!decode_monitor(q,bad));
        }
    }
    const Data q{5,5,1};
    for(const auto& [limits,relation]:std::vector<std::pair<Data,std::string>>{
        {{5,10},"ABOVE"},{{100,110},"BELOW"},{{100,1},"INVERTED_LIMITS"},{{50,50},"WITHIN"}}){
        Data r{0x45,5,1,50};r.insert(r.end(),limits.begin(),limits.end());
        auto decoded=decode_monitor(q,r);assert(decoded&&decoded->scaled->relation==relation);
        std::ostringstream out;write_monitor(out,*decoded);
        assert(out.str().find("\"monitor_verdict\":null")!=std::string::npos);
    }
    for(unsigned component:{1u,127u,129u,255u}){
        const Data r{0x46,3,static_cast<uint8_t>(component),0xff,0xff,0,1};
        const auto decoded=decode_monitor(Data{6,3},r);
        assert(decoded&&decoded->value==65535&&decoded->limit==1&&!decoded->scaled);
        assert(decoded->encoded_relation=="ABOVE"&&decoded->limit_type==(component&128?"MINIMUM":"MAXIMUM"));
        std::ostringstream out;write_monitor(out,*decoded);
        assert(out.str().find("\"scaled\":null")!=std::string::npos);
    }
    assert(!decode_monitor(Data{5,1,3},Data{0x45,1,3,0}));
    assert(!decode_monitor(Data{6,0},Data{0x46,0,0,0,0,0,0}));
}
void identity(){
    constexpr std::string_view vin="1G1FA1RXXR0123456";
    Data data(3,0);data.insert(data.end(),vin.begin(),vin.end());
    auto v=info(2,data);assert(v&&v->values==std::vector<std::string>{std::string(vin)});
    data[0]=1;assert(!info(2,data));
    Data calid(32,0);
    const std::string a="CAL-A",b="CAL-B";
    std::copy(a.begin(),a.end(),calid.begin());std::copy(b.begin(),b.end(),calid.begin()+16);
    auto cal=info(4,calid);assert(cal&&cal->values==std::vector<std::string>({a,b}));
    calid[3]=0;assert(!info(4,calid));
    const auto cvn=info(6,{1,2,3,4,0xff,0,0,0});
    assert(cvn&&cvn->values==std::vector<std::string>({"01020304","FF000000"}));
    Data name(20,0);name[0]='E';name[1]='C';name[2]='M';name[4]='-';name[5]='A';
    auto ecu=info(10,name);assert(ecu&&ecu->values==std::vector<std::string>{"ECM-A"});
    name[4]=':';assert(!info(10,name));
    for(unsigned kind:{8u,11u}){
        for(unsigned len:{32u,kind==8?40u:36u}){
            Data raw(len,0);raw[1]=10;raw[3]=20;raw[5]=3;raw[7]=6;raw[9]=2;
            const auto ipt=info(kind,raw);
            assert(ipt&&ipt->counters&&ipt->counters->general_denominator==10&&ipt->counters->ignition_cycles==20);
            assert(ipt->counters->monitors[0].ratio_valid&&ipt->counters->monitors[0].ratio==0.5);
            assert(!ipt->counters->monitors[1].ratio_valid&&ipt->counters->counter_count==len/2);
        }
    }
    Data id(20,0);id[18]='X';id[19]='9';
    for(unsigned kind:{13u,15u}){
        const auto value=info(kind,id);assert(value&&value->values==std::vector<std::string>{"X9"});
        auto bad=id;bad[0]=1;assert(!info(kind,bad));
        auto rows=fragments(kind,id);rows[1][2]=1;assert(!decode_identification(kind,5,rows));
        rows=fragments(kind,id);rows.pop_back();assert(!decode_identification(kind,5,rows));
    }
    const auto unknown=info(16,{1,2,3,4});
    assert(unknown&&!unknown->interpreted&&unknown->values.empty());
}
}
int main(){monitor();identity();}
