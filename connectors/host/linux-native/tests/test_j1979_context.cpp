#include "j1979_context.hpp"
#include <cassert>
#include <cmath>
#include <cstdio>
#include <string>
#include <vector>

using namespace cd::j1979;
namespace {
using Owned=std::vector<std::uint8_t>;
Owned unhex(std::string_view hex) {
    assert(hex.size()%2==0);
    const auto digit=[](char c)->unsigned {
        if (c>='0'&&c<='9') return unsigned(c-'0');
        assert(c>='A'&&c<='F');
        return unsigned(c-'A'+10);
    };
    Owned out;
    for (size_t i=0;i<hex.size();i+=2)
        out.push_back(static_cast<std::uint8_t>(16*digit(hex[i])+digit(hex[i+1])));
    return out;
}
struct Row {
    Owned request;
    Owned response;
    bool good{true};
    bool admission{true};
    Observation view() const {return {request,response,good,admission};}
};
Row row(std::string_view req,std::string_view res,bool good=true,bool admission=true) {
    return {unhex(req),unhex(res),good,admission};
}
struct Campaign {
    std::vector<Row> rows;
    std::vector<Observation> views() const {
        std::vector<Observation> out;
        for (const auto& item:rows) out.push_back(item.view());
        return out;
    }
    Context context() const {const auto items=views(); return derive_context(items);}
};
Campaign configured() {
    return {{row("0100","410000210001"),row("0120","412010001001"),row("0140","414010030000"),
        row("014F","414F0410004D"),row("0150","415064000000")}};
}
void near(std::optional<double> got,double expected) {
    assert(got && std::abs(*got-expected)<1e-9);
}
void custom_ranges() {
    auto c=configured().context();
    assert(c.scaling.pid4f.state==RangeState::ecu_configuration);
    assert(c.scaling.pid50.state==RangeState::ecu_configuration);
    struct Case {std::string_view req,res;unsigned index;double expected;};
    for (const auto& t:std::vector<Case>{
        {"010B","410BFF",0,770},{"0110","4110FFFF",0,1000},
        {"0144","4144FFFF",0,4},{"0124","4124FFFFFFFF",0,4},
        {"0124","4124FFFFFFFF",1,16},{"0134","4134FFFF8000",1,0},
        {"020B00","420B00FF",0,770}}) {
        const auto r=row(t.req,t.res);
        const auto decoded=scale(r.view(),c.scaling);
        assert(decoded && decoded->signals.size()>t.index);
        near(decoded->signals[t.index].number,t.expected);
    }
    auto a=configured();
    a.rows[3]=row("014F","414F0400404D");
    c=a.context();
    for (const auto& t:std::vector<Case>{
        {"0134","41347D000000",1,0},{"0134","41347D007FFF",1,63.998046875},
        {"0134","41347D008000",1,-64},{"0134","41347D009C40",1,-49.875},
        {"0134","41347D00FFFF",1,-0.001953125}}) {
        auto r=row(t.req,t.res);
        const auto decoded=scale(r.view(),c.scaling);
        assert(decoded && decoded->signals.size()==2);
        near(decoded->signals[1].number,t.expected);
        assert(decoded->signals[1].scale_source=="ECU_CONFIGURATION");
    }
}
void unverified_and_absent() {
    const auto sample=row("0110","41101234");
    auto c=configured();
    c.rows.pop_back();
    auto ctx=c.context();
    assert(ctx.scaling.pid50.state==RangeState::unverified);
    auto value=scale(sample.view(),ctx.scaling);
    assert(value&&!value->signals[0].number);
    c.rows.push_back(row("0150","415064010000"));
    assert(c.context().scaling.pid50.state==RangeState::unverified);
    c.rows={row("0100","410000210000")};
    ctx=c.context();
    assert(ctx.scaling.pid50.state==RangeState::default_unsupported);
    near(scale(sample.view(),ctx.scaling)->signals[0].number,46.6);
    c=configured();
    c.rows.push_back(c.rows[3]);
    assert(c.context().scaling.pid4f.state==RangeState::unverified);
    c=configured();
    c.rows.push_back(c.rows[0]);
    assert(c.context().scaling.pid4f.state==RangeState::unverified);
    c=configured();
    c.rows[3].good=false;
    assert(c.context().scaling.pid4f.state==RangeState::unverified);
    c=configured();
    c.rows.push_back(row("014F","414F0410004D",true,false));
    assert(c.context().scaling.pid4f.state==RangeState::ecu_configuration);
    for (const auto& invalid:std::vector<Row>{
        row("020B00","420B01FF"),row("010B","410CFF"),
        row("010B","410BFFFF"),row("020B01","420B01FF"),
        row("010B","410BFF",false)}) assert(!scale(invalid.view(),ctx.scaling));
}
void fuel_trim_context() {
    Campaign c{{row("0100","410004000008"),row("011D","411D30")}};
    const auto layout=c.context().layout;
    assert(layout.state==LayoutState::verified && layout.value==48);
    auto t=row("0106","41068090");
    const auto first=trims(t.view(),layout);
    assert(first&&first->upper_bank_verified==true&&first->signals.size()==2);
    near(first->signals[0].number,0);
    near(first->signals[1].number,12.5);
    auto second=row("0108","41088090");
    const auto other=trims(second.view(),layout);
    assert(other&&other->upper_bank_verified==false&&!other->signals[1].number);
    for (const unsigned pid:std::array<unsigned,8>{6,7,8,9,0x55,0x56,0x57,0x58}) {
        for (const unsigned mode:std::array<unsigned,2>{1,2}) {
            Row r{{static_cast<uint8_t>(mode),static_cast<uint8_t>(pid)},
                {static_cast<uint8_t>(mode+0x40),static_cast<uint8_t>(pid)},true,true};
            if (mode==2) {r.request.push_back(0);r.response.push_back(0);}
            r.response.push_back(0);r.response.push_back(255);
            const auto decoded=trims(r.view(),{LayoutState::verified,255});
            assert(decoded&&decoded->signals.size()==2);
            near(decoded->signals[0].number,-100);
            near(decoded->signals[1].number,99.21875);
            assert(decoded->frame.has_value()==(mode==2));
        }
    }
    c.rows.push_back(c.rows[1]);
    assert(c.context().layout.state==LayoutState::unverified);
    c.rows={row("0100","410004000000")};
    assert(c.context().layout.state==LayoutState::not_advertised);
    c.rows.push_back(row("011D","411D30"));
    assert(c.context().layout.state==LayoutState::unverified);
    auto bad=row("020600","4206018090");
    assert(!trims(bad.view(),layout));
}
const Monitor& find(const DriveCycle& cycle,std::string_view name) {
    for (const auto& m:cycle.monitors) if (m.name==name) return m;
    assert(false);
    return cycle.monitors.front();
}
void drive_cycle_context() {
    const auto baseline=unhex("80070100"), raw=unhex("00110101");
    auto cycle=drive_cycle(raw,Bytes(baseline));
    assert(cycle&&cycle->consistent&&cycle->support_verified);
    assert(find(*cycle,"misfire").enabled==true&&find(*cycle,"misfire").complete==false);
    assert(find(*cycle,"fuel-system").enabled==false&&find(*cycle,"fuel-system").complete==true);
    assert(find(*cycle,"heated-catalyst").supported==false&&!find(*cycle,"heated-catalyst").complete);
    cycle=drive_cycle(raw,{});
    assert(cycle&&!cycle->support_verified);
    for (const auto& m:cycle->monitors) assert(!m.supported&&!m.complete&&!m.enabled);
    const auto contradictory=unhex("00000200"),other=unhex("00000100");
    cycle=drive_cycle(contradictory,Bytes(other));
    assert(cycle&&!cycle->consistent);
    for (const auto invalid:{"01000000","00800000","00001000","00080400","00080014"})
        assert(!drive_cycle(unhex(invalid),{}));
    Campaign c{{row("0101","410100010100"),row("0101","410100"),
        row("0141","414100010100"),row("011D","411D30"),row("011D","411D3030")}};
    const auto ctx=c.context();
    assert(ctx.cycle&&!ctx.cycle->support_verified&&ctx.layout.state==LayoutState::unverified);
}
void report() {
    auto c=configured();
    c.rows.push_back(row("010B","410BFF"));
    c.rows.push_back(row("0110","4110FFFF",false));
    const auto observations=c.views();
    for (const bool identity:{true,false}) {
        std::FILE* f=std::tmpfile();
        assert(f&&write_context_json(f,observations,identity));
        std::rewind(f);
        char data[8192]{};
        assert(std::fread(data,1,sizeof(data)-1,f)>0);
        const std::string json(data);
        assert(json.find("\"physical_vehicle_verified\":false")!=std::string::npos);
        assert(json.find("\"request_hex\":\"0110\"")==std::string::npos);
        assert(json.find("\"raw_encoded\":255")!=std::string::npos);
        assert(json.find(identity?"\"number\":770":"\"number\":null")!=std::string::npos);
        std::fclose(f);
    }
}
}
int main() {
    custom_ranges();
    unverified_and_absent();
    fuel_trim_context();
    drive_cycle_context();
    report();
    std::puts("J1979 C++20 scoped configuration, fuel trims and monitor context: synthetic tests");
}
