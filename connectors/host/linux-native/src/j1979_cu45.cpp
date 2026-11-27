#include "j1979_cu45.hpp"
#include <algorithm>
#include <openssl/crypto.h>
#include <openssl/evp.h>
#include <openssl/hmac.h>
#include <stdexcept>
namespace cd::cu45 {
namespace {
bool valid_mode(Mode m){return m==Mode::maintenance||m==Mode::archive;}
bool nonzero(cm1::Bytes bytes){return std::any_of(bytes.begin(),bytes.end(),[](auto b){return b!=0;});}
std::uint32_t le32(cm1::Bytes b,std::size_t i){
    return std::uint32_t(b[i])|(std::uint32_t(b[i+1])<<8)|
        (std::uint32_t(b[i+2])<<16)|(std::uint32_t(b[i+3])<<24);
}
void put32(std::span<std::uint8_t> b,std::size_t i,std::uint32_t n){
    for(unsigned shift=0;shift<32;shift+=8)b[i++]=static_cast<std::uint8_t>(n>>shift);
}
bool header(cm1::Bytes b,Mode m,unsigned type){
    return valid_mode(m)&&b.size()>=4&&b[0]=='C'&&b[1]=='U'&&b[2]==static_cast<unsigned>(m)&&b[3]==type;
}
template<class T> struct Wipe{
    T& bytes;
    ~Wipe(){OPENSSL_cleanse(bytes.data(),bytes.size());}
};
std::uint32_t crc(cm1::Bytes bytes){
    std::uint32_t v=0xffffffffu;
    for(auto b:bytes){
        v^=b;
        for(unsigned i=0;i<8;++i)v=(v>>1)^((v&1)?0xedb88320u:0u);
    }
    return v^0xffffffffu;
}
}
bool valid_scope(const cm1::Scope& s){
    return (s.operation==1||s.operation==2)&&
        (s.extended?s.tx==0x18da10f1&&s.rx==0x18daf110:s.tx==0x7e0&&s.rx==0x7e8)&&
        s.budget>=1000&&s.budget<=60000&&s.vin.size()==17&&
        std::all_of(s.vin.begin(),s.vin.end(),[](char c){
            return (c>='0'&&c<='9')||(c>='A'&&c<='Z'&&c!='I'&&c!='O'&&c!='Q');
        });
}
bool encode_scope(const cm1::Scope& s,ScopeWire& wire){
    wire.fill(0);
    if(!valid_scope(s))return false;
    wire[0]=static_cast<std::uint8_t>(s.operation);
    wire[1]=s.extended?1:0;
    put32(wire,2,s.tx);put32(wire,6,s.rx);put32(wire,10,s.budget);
    std::copy(s.vin.begin(),s.vin.end(),wire.begin()+14);
    return true;
}
bool hello(Mode mode,const cm1::Scope& s,const Nonce& nonce,Hello& out){
    out.fill(0);
    ScopeWire wire{};
    if(!valid_mode(mode)||!encode_scope(s,wire)||!nonzero(nonce))return false;
    out[0]='C';out[1]='U';out[2]=static_cast<std::uint8_t>(mode);out[3]=1;
    std::copy(wire.begin(),wire.end(),out.begin()+4);
    std::copy(nonce.begin(),nonce.end(),out.begin()+35);
    return true;
}
bool proof(Mode mode,const Hello& request,const Challenge& challenge,
           std::span<const std::uint8_t,32> key,Proof& out){
    out.fill(0);
    if(!header(request,mode,1)||!header(challenge,mode,2)||request[5]>1||
        !nonzero(cm1::Bytes(request).subspan(35))||!nonzero(cm1::Bytes(challenge).subspan(4,32)))return false;
    const cm1::Scope s{request[4],request[5]==1,le32(request,6),le32(request,10),le32(request,14),
        std::string(request.begin()+18,request.begin()+35)};
    ScopeWire canonical{};
    if(!encode_scope(s,canonical)||!std::equal(canonical.begin(),canonical.end(),request.begin()+4))return false;
    std::array<std::uint8_t,96> transcript{};
    Wipe erase_transcript{transcript};
    std::array<std::uint8_t,EVP_MAX_MD_SIZE> tag{};
    Wipe erase_tag{tag};
    std::copy(request.begin()+4,request.end(),transcript.begin()+1);
    std::copy_n(challenge.begin()+4,32,transcript.begin()+64);
    const bool archive=mode==Mode::archive;
    transcript[0]=archive?'R':'M';
    unsigned size=0;
    if(!HMAC(EVP_sha256(),key.data(),32,transcript.data(),transcript.size(),tag.data(),&size)||
       size!=32||CRYPTO_memcmp(tag.data(),challenge.data()+36,32)!=0)return false;
    transcript[0]=archive?'r':'m';
    if(!HMAC(EVP_sha256(),key.data(),32,transcript.data(),transcript.size(),tag.data(),&size)||size!=32)return false;
    out[0]='C';out[1]='U';out[2]=static_cast<std::uint8_t>(mode);out[3]=3;
    std::copy_n(tag.begin(),32,out.begin()+4);return true;
}
std::optional<Declaration> declaration(Mode mode,const Envelope& b){
    if(!header(b,mode,4)||b[6]||b[7])return {};
    Declaration d{};d.length=le32(b,8);
    if(d.length<47||d.length>cm1::max_journal)return {};
    if(mode==Mode::maintenance){
        if(b[5]||b[4]<2||b[4]>3)return {};
        d.recovery=(b[4]&1)!=0;
    }else{
        if((b[4]&~7u)||b[5]<1||b[5]>4)return {};
        d.records=b[5];d.terminal=(b[4]&1)!=0;d.damaged=(b[4]&2)!=0;d.recovery=(b[4]&4)!=0;
        if((d.terminal&&d.records<2)||((!d.terminal||d.damaged)&&!d.recovery))return {};
    }
    return d;
}
bool receipt(const Envelope& b,cm1::Bytes raw){
    return header(b,Mode::archive,5)&&le32(b,4)==raw.size()&&le32(b,8)==crc(raw);
}
std::optional<cm1::Inspection> inspect(Mode mode,const cm1::Scope& scope,const Declaration& declared,cm1::Bytes raw){
    if(!valid_scope(scope)||raw.size()!=declared.length)return {};
    try{
        // An archive with a damaged suffix is only promoted to verified prefix
        // when the independent CM/1 parser accepts that exact prefix.
        auto e=cm1::inspect(raw,scope.vin,mode==Mode::archive&&declared.damaged);
        if(e.scope!=scope)return {};
        if(mode==Mode::maintenance){
            if(e.recovery_required()!=declared.recovery)return {};
        }else{
            if(e.records.size()!=declared.records||e.terminal.has_value()!=declared.terminal||
                (e.recovery_required()&&!declared.recovery))return {};
            if(declared.damaged!=(e.verified_bytes!=e.total_bytes))return {};
        }
        return e;
    }catch(const std::invalid_argument&){return {};}
}
}
