#include "j1979_catalogue_exact.hpp"
#include <array>
#include <memory>
#include <openssl/bn.h>
#include <openssl/crypto.h>
#include <stdexcept>
namespace cd::catalogue {
namespace {
using Number=std::unique_ptr<BIGNUM,decltype(&BN_free)>;
using Context=std::unique_ptr<BN_CTX,decltype(&BN_CTX_free)>;
void check(bool ok){if(!ok)throw std::runtime_error("Exact arithmetic failed");}
Number number(){
    Number n(BN_new(),BN_free);check(bool(n));return n;
}
Number unsigned_number(std::uint64_t v){
    std::array<unsigned char,8> bytes{};
    for(unsigned i=0;i<8;++i)bytes[7-i]=static_cast<unsigned char>(v>>(i*8));
    Number n(BN_bin2bn(bytes.data(),static_cast<int>(bytes.size()),nullptr),BN_free);
    check(bool(n));return n;
}
Number signed_number(std::int64_t v){
    const auto magnitude=v<0?std::uint64_t(-(v+1))+1:std::uint64_t(v);
    auto n=unsigned_number(magnitude);
    if(v<0)BN_set_negative(n.get(),1);
    return n;
}
Context context(){Context c(BN_CTX_new(),BN_CTX_free);check(bool(c));return c;}
Number multiply(const BIGNUM* a,const BIGNUM* b,BN_CTX* ctx){
    auto n=number();check(BN_mul(n.get(),a,b,ctx)==1);return n;
}
Number add(const BIGNUM* a,const BIGNUM* b){
    auto n=number();check(BN_add(n.get(),a,b)==1);return n;
}
Number subtract(const BIGNUM* a,const BIGNUM* b){
    auto n=number();check(BN_sub(n.get(),a,b)==1);return n;
}
Number quotient(const BIGNUM* a,const BIGNUM* b,BN_CTX* ctx){
    auto n=number(),r=number();check(BN_div(n.get(),r.get(),a,b,ctx)==1&&BN_is_zero(r.get()));return n;
}
int cross_compare(const BIGNUM* a,const BIGNUM* b,const BIGNUM* c,const BIGNUM* d,BN_CTX* ctx){
    auto left=multiply(a,d,ctx),right=multiply(c,b,ctx);
    return BN_cmp(left.get(),right.get());
}
struct FreeText{void operator()(char* p)const{OPENSSL_free(p);}};
std::string decimal(const BIGNUM* n){
    std::unique_ptr<char,FreeText> text(BN_bn2dec(n));
    check(bool(text));return text.get();
}
}
bool Ratio::valid()const{
    return numerator>=-1000000000000LL&&numerator<=1000000000000LL&&denominator>=1&&denominator<=1000000000000ULL;
}
int compare(Ratio a,Ratio b){
    if(!a.valid()||!b.valid())throw std::invalid_argument("Invalid catalogue rational");
    auto ctx=context();auto an=signed_number(a.numerator),ad=unsigned_number(a.denominator);
    auto bn=signed_number(b.numerator),bd=unsigned_number(b.denominator);
    return cross_compare(an.get(),ad.get(),bn.get(),bd.get(),ctx.get());
}
Exact exact_value(std::uint64_t raw,unsigned bits,bool signed_value,Ratio scale,Ratio offset,
                  std::optional<Ratio> minimum,std::optional<Ratio> maximum){
    if(bits<1||bits>64||(bits<64&&raw>=(std::uint64_t{1}<<bits))||
        !scale.valid()||!offset.valid()||scale.zero()||minimum.has_value()!=maximum.has_value()||
        (minimum&&compare(*minimum,*maximum)>0))throw std::invalid_argument("Invalid exact projection scope");
    auto ctx=context();
    auto value=unsigned_number(raw);
    if(signed_value&&(raw&(std::uint64_t{1}<<(bits-1)))){
        auto power=number();check(BN_set_bit(power.get(),static_cast<int>(bits))==1);
        value=subtract(value.get(),power.get());
    }
    auto sn=signed_number(scale.numerator),sd=unsigned_number(scale.denominator);
    auto on=signed_number(offset.numerator),od=unsigned_number(offset.denominator);
    auto scaled=multiply(value.get(),sn.get(),ctx.get());
    auto a=multiply(scaled.get(),od.get(),ctx.get()),b=multiply(on.get(),sd.get(),ctx.get());
    auto n=add(a.get(),b.get()),d=multiply(sd.get(),od.get(),ctx.get());
    auto magnitude=Number(BN_dup(n.get()),BN_free);check(bool(magnitude));BN_set_negative(magnitude.get(),0);
    auto gcd=number();check(BN_gcd(gcd.get(),magnitude.get(),d.get(),ctx.get())==1&&!BN_is_zero(gcd.get()));
    n=quotient(n.get(),gcd.get(),ctx.get());d=quotient(d.get(),gcd.get(),ctx.get());
    bool outside=false;
    if(minimum){
        auto low=signed_number(minimum->numerator),ld=unsigned_number(minimum->denominator);
        auto high=signed_number(maximum->numerator),hd=unsigned_number(maximum->denominator);
        outside=cross_compare(n.get(),d.get(),low.get(),ld.get(),ctx.get())<0||
            cross_compare(n.get(),d.get(),high.get(),hd.get(),ctx.get())>0;
    }
    return {decimal(n.get()),decimal(d.get()),outside};
}
}
