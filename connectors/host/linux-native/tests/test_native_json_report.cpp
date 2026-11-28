#include "native_json.hpp"
#include <cassert>
#include <cmath>
#include <stdexcept>
#include <string_view>
using namespace cd::json;
namespace {
template<class F>void rejects(F f){
    bool rejected=false;try{f();}catch(const std::invalid_argument&){rejected=true;}
    assert(rejected);
}
}
int main(){
    const auto report=parse_report(R"({"fraction":-12.125,"zero":0.0,"small":1e-9,"large":1.2E+6,"id":2024})");
    assert(report.at("fraction").finite_number()==-12.125);
    assert(report.at("zero").finite_number()==0);
    assert(std::abs(report.at("small").finite_number()-1e-9)<1e-20);
    assert(report.at("large").finite_number()==1200000);
    assert(report.at("id").unsigned_integer()==2024);
    rejects([&]{(void)report.at("zero").unsigned_integer();});
    rejects([&]{(void)report.at("fraction").signed_integer();});
    rejects([]{(void)parse("1.0");});
    rejects([]{(void)parse("1e2");});
    for(auto raw:{"01","-01","1.","1e","1e+","1e999","-1e999","NaN","Infinity","+1","--1","0.0.1","1e-999","{\"x\":1,\"x\":2}"})
        rejects([&]{(void)parse_report(raw);});
    rejects([]{(void)parse_report("1.5",2);});
    rejects([]{(void)parse_report("true").finite_number();});
    rejects([]{(void)parse_report("\"1.0\"").finite_number();});
    const auto exact=parse("18446744073709551615");
    assert(exact.unsigned_integer()==UINT64_MAX);
    assert(parse_report("18446744073709551615").unsigned_integer()==UINT64_MAX);
    const auto min=parse("-9223372036854775808");
    assert(min.signed_integer()==INT64_MIN);

    const auto encoded=serialize(report);
    assert(parse_report(encoded).at("fraction").finite_number()==-12.125);
    assert(serialize(parse_report(encoded))==encoded);
    rejects([&]{(void)serialize(report,5);});
    rejects([]{(void)serialize(Value{Integer{"1,2"}});});
    rejects([]{(void)serialize(Value{Decimal{"NaN"}});});
    rejects([]{(void)serialize(Value{Decimal{"1"}});});
    rejects([]{(void)serialize(Value{Integer{"1.0"}});});
    const auto escaped=Value{std::string("A\n\t\"\\B")};
    assert(parse_report(serialize(escaped)).string()==escaped.string());
    Value deep{};
    for(unsigned i=0;i<66;++i)deep=Value{Value::Array{std::move(deep)}};
    rejects([&]{(void)serialize(deep);});
}
