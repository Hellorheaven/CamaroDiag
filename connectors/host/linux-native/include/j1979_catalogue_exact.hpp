#pragma once
#include <cstdint>
#include <optional>
#include <string>
namespace cd::catalogue {
struct Ratio{
    std::int64_t numerator{};
    std::uint64_t denominator{1};
    bool valid()const;
    bool zero()const{return numerator==0;}
    bool one()const{return numerator>0&&static_cast<std::uint64_t>(numerator)==denominator;}
};
struct Exact{
    std::string numerator,denominator;
    bool outside{};
};
int compare(Ratio,Ratio);
Exact exact_value(std::uint64_t raw,unsigned bits,bool signed_value,Ratio scale,Ratio offset,
    std::optional<Ratio> minimum={},std::optional<Ratio> maximum={});
}
