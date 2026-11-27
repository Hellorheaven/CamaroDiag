#include "j1979_cu1.hpp"
#include <algorithm>
#include <openssl/crypto.h>
#include <openssl/hmac.h>
#include <openssl/evp.h>
namespace cd::cu1 {
namespace {
template<class T> bool nonzero(const T& a) {
    return std::any_of(a.begin(),a.end(),[](auto b){return b!=0;});
}
std::uint32_t le32(const std::uint8_t* p) {
    return std::uint32_t(p[0])|(std::uint32_t(p[1])<<8)|(std::uint32_t(p[2])<<16)|(std::uint32_t(p[3])<<24);
}
template<class T> struct Wipe {
    T& bytes;
    ~Wipe(){OPENSSL_cleanse(bytes.data(),bytes.size());}
};
}
bool hello(const cj1::Profile& profile,const Nonce& nonce,Hello& out) {
    out.fill(0);
    if(!cj1::valid_profile(profile)||!nonzero(nonce))return false;
    const auto wire=cj1::encode_profile(profile);
    out[0]='C';out[1]='U';out[2]=1;out[3]=1;
    std::copy(wire.begin(),wire.end(),out.begin()+4);
    std::copy(nonce.begin(),nonce.end(),out.begin()+17);
    return true;
}
bool proof(const Hello& request,const Challenge& challenge,std::span<const std::uint8_t,32> key,Proof& out) {
    out.fill(0);
    const std::array<std::uint8_t,4> first{'C','U',1,1}, second{'C','U',1,2};
    if(!std::equal(first.begin(),first.end(),request.begin())||
       !std::equal(second.begin(),second.end(),challenge.begin())||request[12]>1||
       !nonzero(std::span(request).subspan(17))||!nonzero(std::span(challenge).subspan(4,32)))return false;
    const cj1::Profile profile{le32(request.data()+4),le32(request.data()+8),le32(request.data()+13),request[12]==1};
    if(!cj1::valid_profile(profile))return false;
    std::array<std::uint8_t,78> transcript{};
    Wipe erase_transcript{transcript};
    std::array<std::uint8_t,EVP_MAX_MD_SIZE> tag{};
    Wipe erase_tag{tag};
    std::copy(request.begin()+4,request.end(),transcript.begin()+1);
    std::copy_n(challenge.begin()+4,32,transcript.begin()+46);
    unsigned size=0;
    transcript[0]='S';
    if(!HMAC(EVP_sha256(),key.data(),32,transcript.data(),transcript.size(),tag.data(),&size)||
       size!=32||CRYPTO_memcmp(tag.data(),challenge.data()+36,32)!=0)return false;
    transcript[0]='C';
    if(!HMAC(EVP_sha256(),key.data(),32,transcript.data(),transcript.size(),tag.data(),&size)||size!=32)return false;
    out[0]='C';out[1]='U';out[2]=1;out[3]=3;
    std::copy_n(tag.begin(),32,out.begin()+4);
    return true;
}
}
