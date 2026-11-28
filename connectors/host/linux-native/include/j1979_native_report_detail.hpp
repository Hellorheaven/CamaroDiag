#pragma once
#include "native_json.hpp"
#include <algorithm>
#include <charconv>
#include <cmath>
#include <cstdint>
#include <stdexcept>
#include <string>
#include <vector>
namespace cd::network::history::detail {
using json::Value;
using Bytes=std::vector<std::uint8_t>;
inline void require(bool b,const char* why){if(!b)throw std::invalid_argument(why);}
inline void no_claims(const Value& v){
    for(auto k:{"physical_vehicle_verified","whole_vehicle_coverage_verified","authenticated",
                "permits_programming","current_authorization_verified","permits_automatic_retry"})
        if(const auto* p=v.find(k))require(!p->boolean(),"Unsupported native verification claim");
}
inline unsigned address(const Value& v){
    const auto& s=v.string();unsigned n{};
    require(!s.empty()&&s.size()<=8,"Invalid native CAN address");
    const auto [p,e]=std::from_chars(s.data(),s.data()+s.size(),n,16);
    require(e==std::errc{}&&p==s.data()+s.size()&&n<=0x1fffffffu,"Invalid native CAN address");
    return n;
}
inline Bytes bytes(const Value& v,std::size_t maximum){
    const auto& s=v.string();
    require(s.size()<=2*maximum&&s.size()%2==0,"Invalid native payload size");
    Bytes out;out.reserve(s.size()/2);
    for(std::size_t i=0;i<s.size();i+=2){
        unsigned n{};const auto [p,e]=std::from_chars(s.data()+i,s.data()+i+2,n,16);
        require(e==std::errc{}&&p==s.data()+i+2&&n<=255,"Invalid native payload hex");
        out.push_back(static_cast<std::uint8_t>(n));
    }
    return out;
}
inline std::string nullable(const Value& v,std::size_t maximum){
    if(v.null())return {};
    const auto& s=v.string();require(!s.empty()&&s.size()<=maximum,"Invalid native report text");
    return s;
}
template<std::size_t N>void set(char (&buffer)[N],std::string_view value){
    require(value.size()<N&&value.find('\0')==std::string_view::npos,"Native fixed string overflow");
    std::copy(value.begin(),value.end(),buffer);buffer[value.size()]=0;
}
inline bool numeric(const Value& v){
    return std::holds_alternative<json::Integer>(v.data)||std::holds_alternative<json::Decimal>(v.data);
}
inline bool compare(const Value& actual,const Value& expected,std::string_view field={}){
    // Only continuous quantities admit numeric tolerance. Addresses, bitmap
    // IDs, counts, encoded bytes and times remain integer-typed and exact.
    const bool continuous=field=="number"||field=="value"||field=="minimum"||field=="maximum"||field=="ratio"||field=="mean"||field=="last_verified_value";
    if(continuous&&numeric(actual)&&numeric(expected)){
        const auto v=expected.finite_number(),a=actual.finite_number();
        return std::abs(a-v)<=std::max(1e-9,1e-9*std::abs(v));
    }
    if(actual.data.index()!=expected.data.index())return false;
    if(expected.null())return true;
    if(const auto* p=std::get_if<bool>(&expected.data))return *p==actual.boolean();
    if(const auto* p=std::get_if<json::Integer>(&expected.data))return p->text==std::get<json::Integer>(actual.data).text;
    if(const auto* p=std::get_if<json::Decimal>(&expected.data))return p->text==std::get<json::Decimal>(actual.data).text;
    if(const auto* p=std::get_if<std::string>(&expected.data))return *p==actual.string();
    if(const auto* p=std::get_if<Value::Array>(&expected.data)){
        const auto& a=actual.array();if(a.size()!=p->size())return false;
        for(std::size_t i=0;i<a.size();++i)if(!compare(a[i],p->at(i),field))return false;
        return true;
    }
    const auto& a=actual.object();const auto& e=expected.object();
    if(a.size()!=e.size())return false;
    for(const auto& [k,v]:e){const auto i=a.find(k);if(i==a.end()||!compare(i->second,v,k))return false;}
    return true;
}

}
