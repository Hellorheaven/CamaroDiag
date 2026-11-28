#pragma once
#include <cstdint>
#include <map>
#include <stdexcept>
#include <string>
#include <string_view>
#include <variant>
#include <vector>
namespace cd::json {
struct Integer{std::string text;};
struct Decimal{std::string text;};
struct Value{
    using Array=std::vector<Value>;
    using Object=std::map<std::string,Value,std::less<>>;
    std::variant<std::nullptr_t,bool,Integer,std::string,Array,Object,Decimal> data{nullptr};
    const Object& object()const;
    const Array& array()const;
    const std::string& string()const;
    bool boolean()const;
    std::uint64_t unsigned_integer()const;
    std::int64_t signed_integer()const;
    double finite_number()const;
    bool null()const{return std::holds_alternative<std::nullptr_t>(data);}
    const Value& at(std::string_view key)const;
    const Value* find(std::string_view key)const;
};
Value parse(std::string_view,std::size_t max_bytes=4*1024*1024);
// Explicit compatibility path for reports containing measured decimals.
// Integer-only catalogue and authorization parsers keep their old policy.
Value parse_report(std::string_view,std::size_t max_bytes=4*1024*1024);
void fields(const Value&,std::initializer_list<std::string_view> required,
    std::initializer_list<std::string_view> optional={});
std::string quote(std::string_view);
std::string serialize(const Value&,std::size_t max_bytes=64u*1024u*1024u);
bool valid_text(std::string_view,std::size_t maximum);
}
