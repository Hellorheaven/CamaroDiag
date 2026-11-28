#include "j1979_multi.hpp"
#include "j1979_status.hpp"
#include <algorithm>
#include <cassert>
#include <cmath>
#include <set>
#include <sstream>
#include <string>
#include <vector>

using namespace cd::j1979;
namespace {
using Data=std::vector<std::uint8_t>;
Data unhex(std::string_view s){
    assert(s.size()%2==0);
    auto digit=[](char c)->unsigned{
        if(c>='0'&&c<='9')return unsigned(c-'0');
        assert(c>='A'&&c<='F');return unsigned(c-'A'+10);
    };
    Data result;
    for(std::size_t i=0;i<s.size();i+=2)result.push_back(std::uint8_t(digit(s[i])*16+digit(s[i+1])));
    return result;
}
struct Row {
    Data request,response;
    bool accepted{true};
    Observation view() const{return {request,response,accepted,true};}
};
Row sample(unsigned pid,Data payload){
    Row row{{1,std::uint8_t(pid)},{0x41,std::uint8_t(pid)},true};
    row.response.insert(row.response.end(),payload.begin(),payload.end());
    return row;
}
Row sample(unsigned pid,std::string_view payload){return sample(pid,unhex(payload));}
void near(std::optional<double> v,double expected){assert(v&&std::abs(*v-expected)<1e-9);}
void numbers(unsigned pid,std::string_view payload,std::initializer_list<double> expected){
    const auto row=sample(pid,payload);
    const auto decoded=multi_signal(row.view());
    assert(decoded&&decoded->signals.size()==expected.size());
    std::size_t i=0;
    for(auto value:expected)near(decoded->signals[i++].number,value);
}
void multi_catalogue(){
    struct Definition {unsigned pid,width,valid_mask,support_mask,fields;};
    constexpr Definition definitions[]{
    {0x14,2,0x00,0x00,2},
    {0x15,2,0x00,0x00,2},
    {0x16,2,0x00,0x00,2},
    {0x17,2,0x00,0x00,2},
    {0x18,2,0x00,0x00,2},
    {0x19,2,0x00,0x00,2},
    {0x1A,2,0x00,0x00,2},
    {0x1B,2,0x00,0x00,2},
    {0x24,4,0x00,0x00,2},
    {0x25,4,0x00,0x00,2},
    {0x26,4,0x00,0x00,2},
    {0x27,4,0x00,0x00,2},
    {0x28,4,0x00,0x00,2},
    {0x29,4,0x00,0x00,2},
    {0x2A,4,0x00,0x00,2},
    {0x2B,4,0x00,0x00,2},
    {0x34,4,0x00,0x00,2},
    {0x35,4,0x00,0x00,2},
    {0x36,4,0x00,0x00,2},
    {0x37,4,0x00,0x00,2},
    {0x38,4,0x00,0x00,2},
    {0x39,4,0x00,0x00,2},
    {0x3A,4,0x00,0x00,2},
    {0x3B,4,0x00,0x00,2},
    {0x64,5,0x00,0x00,5},
    {0x66,5,0x03,0x03,2},
    {0x67,3,0x03,0x03,2},
    {0x68,7,0x3F,0x3F,6},
    {0x69,7,0x3F,0x3F,6},
    {0x6A,5,0x0F,0x0F,4},
    {0x6C,5,0x0F,0x0F,4},
    {0x6D,11,0x3F,0x3F,6},
    {0x6E,9,0x0F,0x0F,4},
    {0x70,10,0x3F,0x3F,4},
    {0x71,6,0x3F,0x3F,4},
    {0x72,5,0x0F,0x0F,4},
    {0x73,5,0x03,0x03,2},
    {0x74,5,0x03,0x03,2},
    {0x75,7,0x0F,0x0F,4},
    {0x76,7,0x0F,0x0F,4},
    {0x77,5,0x0F,0x0F,4},
    {0x78,9,0x0F,0x0F,4},
    {0x79,9,0x0F,0x0F,4},
    {0x7A,7,0x07,0x07,3},
    {0x7B,7,0x07,0x07,3},
    {0x7C,9,0x0F,0x0F,4},
    {0x7F,13,0x07,0x07,3},
    {0x98,9,0x0F,0x0F,4},
    {0x99,9,0x0F,0x0F,4},
    {0x6B,5,0x0f,0xFF,4},
    {0x6F,3,0x03,0x0F,2},
    {0x81,41,0x1F,0x1F,10},
    {0x82,41,0x1F,0x1F,10},
    {0x83,9,0x0F,0x0F,4},
    {0x84,1,0x00,0x00,1},
    {0x85,10,0x0F,0x0F,4},
    {0x87,5,0x03,0x03,2},
    {0x89,41,0x1F,0x1F,10},
    {0x8A,41,0x1F,0x1F,10},
    {0x8B,7,0x7F,0x7F,3},
    {0x8C,17,0xFF,0xFF,8},
    {0x8D,1,0x00,0x00,1},
    {0x8E,1,0x00,0x00,1},
    {0x8F,7,0x0F,0x0F,2},
    {0x93,3,0x01,0x01,1},
    {0x94,12,0x3F,0x3F,5},
    {0x9C,17,0xFF,0xFF,8}
    };
    static_assert(sizeof(definitions)/sizeof(definitions[0])==67);
    unsigned total=0;
    for(const auto& d:definitions){
        total+=d.fields;
        assert(multi_width(d.pid)==d.width);
        Data raw(d.width);raw[0]=std::uint8_t(d.valid_mask);
        auto row=sample(d.pid,raw);
        const auto decoded=multi_signal(row.view());
        assert(decoded&&decoded->signals.size()==d.fields);
        std::set<std::string_view> names;
        for(const auto& f:decoded->signals)assert(names.insert(f.name).second);
        raw.pop_back();assert(!multi_signal(sample(d.pid,raw).view()));
        raw.resize(d.width+1);assert(!multi_signal(sample(d.pid,raw).view()));
        if(d.support_mask){
            raw.assign(d.width,0);
            const auto empty=multi_signal(sample(d.pid,raw).view());assert(empty&&empty->signals.empty());
            const auto forbidden=(~d.support_mask)&255;
            if(forbidden){raw[0]=std::uint8_t(forbidden);assert(!multi_signal(sample(d.pid,raw).view()));}
        }
    }
    assert(total==230);
    assert(!multi_width(0xff));
}
void measurements(){
    numbers(0x66,"010400FFFF",{32});
    numbers(0x67,"035A28",{50,0});
    numbers(0x67,"00FFFF",{});
    assert(!multi_signal(sample(0x67,"045A28").view()));
    numbers(0x14,"C8FF",{1});
    numbers(0x14,"0080",{0,0});
    const auto wb=sample(0x34,"80007F00");
    const auto unknown=multi_signal(wb.view());
    assert(unknown&&unknown->signals.size()==2&&!unknown->signals[0].number&&!unknown->signals[1].number);
    const ScalingContext defaults{{RangeState::default_unsupported,{}},{RangeState::default_unsupported,{}}};
    const auto verified=multi_signal(wb.view(),&defaults);
    assert(verified&&verified->signals.size()==2);
    near(verified->signals[0].number,1);near(verified->signals[1].number,-1);
    numbers(0x7a,"07FF9C006400C8",{-1,1,2});
    numbers(0x7f,"01FFFFFFFF0000000000000000",{4294967295.0});
    numbers(0x6b,"2164640000",{60,360});
    assert(!multi_signal(sample(0x6b,"1164640000").view()));
    numbers(0x6f,"090A14",{10,160});
    assert(!multi_signal(sample(0x6f,"050A14").view()));
    numbers(0x85,"0F00C80190FFFFFFFFFF",{1,2,100,4294967295.0});
    assert(!multi_signal(sample(0x85,"1000C80190FFFFFFFFFF").view()));
    for(const auto pid:{0x81u,0x82u,0x89u,0x8au}){
        Data raw{0x11,0xff,0xff,0xff,0xff,0x80,0,0,0};
        raw.resize(33);const Data tail{0,0,0,1,0,0,0,2};raw.insert(raw.end(),tail.begin(),tail.end());
        const auto decoded=multi_signal(sample(pid,raw).view());assert(decoded&&decoded->signals.size()==4);
        near(decoded->signals[0].number,4294967295.0);near(decoded->signals[1].number,2147483648.0);
        near(decoded->signals[2].number,1);near(decoded->signals[3].number,2);
        raw[0]=0x20;assert(!multi_signal(sample(pid,raw).view()));
    }
    for(const auto pid:{0x8cu,0x9cu}){
        Data raw{0x11,0x27,0x10};raw.resize(9);raw.push_back(0x20);raw.push_back(0);raw.resize(17);
        auto decoded=multi_signal(sample(pid,raw).view());assert(decoded&&decoded->signals.size()==2);
        near(decoded->signals[0].number,15.26);near(decoded->signals[1].number,.999424);
    }
}
void states(unsigned pid,std::string_view payload,std::initializer_list<std::string_view> expected){
    const auto decoded=status_record(sample(pid,payload).view());
    assert(decoded&&decoded->states.size()==expected.size());
    std::size_t i=0;for(auto value:expected)assert(decoded->states[i++].state==value);
}
void statuses(){
    struct Definition{unsigned pid,width;std::vector<std::pair<unsigned,unsigned>> reserved;};
    const Definition definitions[]={
        {0x03,2,{}},{0x12,1,{}},{0x13,1,{}},{0x1c,1,{}},{0x1d,1,{}},{0x1e,1,{{0,0xfe}}},
        {0x51,1,{}},{0x70,10,{{0,0xc0},{9,0xf0}}},{0x71,6,{{0,0xc0},{5,0xf0}}},
        {0x7d,1,{{0,0xf0}}},{0x7e,1,{{0,0xf0}}},{0x8b,7,{{0,0x80},{1,0xf0}}},
        {0x8f,7,{{0,0xf0},{1,0xfc},{4,0xfc}}},{0x90,3,{{0,0x80}}},
        {0x91,5,{{0,0xf0}}},{0x92,2,{}},{0x94,12,{{0,0xc0},{1,0x80}}}
    };
    for(const auto& d:definitions){
        assert(status_width(d.pid)==d.width);
        assert(status_record(sample(d.pid,Data(d.width)).view()));
        assert(!status_record(sample(d.pid,Data(d.width-1)).view()));
        assert(!status_record(sample(d.pid,Data(d.width+1)).view()));
        for(const auto& [offset,mask]:d.reserved){
            Data raw(d.width);raw[offset]=std::uint8_t(mask);assert(!status_record(sample(d.pid,raw).view()));
        }
    }
    states(3,"0203",{"CLOSED_FEEDBACK","UNKNOWN"});
    states(0x51,"16",{"HYBRID_REGENERATION"});
    states(0x51,"FF",{"UNKNOWN"});
    const auto two=status_record(sample(0x13,"10").view());
    const auto four=status_record(sample(0x1d,"10").view());
    assert(two&&four&&two->states[4].name=="oxygen-bank-2-sensor-1"&&four->states[4].name=="oxygen-bank-3-sensor-1");
    assert(two->states[4].state=="PRESENT"&&four->states[4].state=="PRESENT");
    states(0x70,"0400000000000000000E",{"CLOSED_LOOP"});
    states(0x71,"04000000000E",{"CLOSED_LOOP"});
    states(0x8b,"07070000000000",{"ACTIVE","ACTIVE","ACTIVE"});
    states(0x8b,"000F0000000000",{});
    states(0x92,"8180",{"NOT_CLOSED_LOOP","CLOSED_LOOP"});
    states(0x94,"017300000000000000000000",{"ACTIVE","ENABLED","ACTIVE","UNSUPPORTED"});
}
void correlation(){
    auto a=sample(0x67,"035A28"),b=sample(0x51,"04");
    a.accepted=false;b.accepted=false;
    assert(!multi_signal(a.view())&&!status_record(b.view()));
    a.accepted=true;b.accepted=true;a.response[1]=0x68;b.response[1]=0x52;
    assert(!multi_signal(a.view())&&!status_record(b.view()));
    a.response[1]=0x67;b.response[1]=0x51;
    a.request[0]=2;a.request.push_back(0);b.request[0]=2;b.request.push_back(0);
    assert(!multi_signal(a.view())&&!status_record(b.view()));
    const auto first=sample(0x67,"035A28"),second=sample(0x51,"04");
    const std::array rows{first.view(),second.view()};
    std::ostringstream out;write_multi_json(out,rows);write_status_json(out,rows);
    assert(out.str().find("\"number\":50")!=std::string::npos);
    assert(out.str().find("\"state\":\"DIESEL\"")!=std::string::npos);
}
}
int main(){multi_catalogue();measurements();statuses();correlation();}
