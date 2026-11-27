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
struct Value{
    using Array=std::vector<Value>;
    using Object=std::map<std::string,Value,std::less<>>;
    std::variant<std::nullptr_t,bool,Integer,std::string,Array,Object> data{nullptr};
    const Object& object()const;
    const Array& array()const;
    const std::string& string()const;
    bool boolean()const;
    std::uint64_t unsigned_integer()const;
    std::int64_t signed_integer()const;
    bool null()const{return std::holds_alternative<std::nullptr_t>(data);}
    const Value& at(std::string_view key)const;
    const Value* find(std::string_view key)const;
};
Value parse(std::string_view,std::size_t max_bytes=4*1024*1024);
void fields(const Value&,std::initializer_list<std::string_view> required,
    std::initializer_list<std::string_view> optional={});
std::string quote(std::string_view);
bool valid_text(std::string_view,std::size_t maximum);
}
