#include "j1979_cu1.hpp"
#include <cassert>
#include <string_view>
#include <algorithm>
#include <cstdio>

namespace {
template<std::size_t N> std::array<std::uint8_t,N> from_hex(std::string_view text){
    assert(text.size()==2*N);
    auto nibble=[](char c)->unsigned {
        if(c>='0'&&c<='9')return unsigned(c-'0');
        assert(c>='a'&&c<='f');return unsigned(c-'a'+10);
    };
    std::array<std::uint8_t,N> bytes{};
    for(std::size_t i=0;i<N;++i)bytes[i]=std::uint8_t(nibble(text[2*i])*16+nibble(text[2*i+1]));
    return bytes;
}
void reference(){
    using namespace cd::cu1;
    Nonce host{},key{};
    host.fill('H');
    for(unsigned i=0;i<key.size();++i)key[i]=std::uint8_t(i);
    const cd::cj1::Profile profile{0x7e0,0x7e8,10000,false};
    Hello request{};
    assert(hello(profile,host,request));
    assert(request[0]=='C'&&request[1]=='U'&&request[2]==1&&request[3]==1);
    const auto expected_profile=from_hex<13>("e0070000e80700000010270000");
    assert(std::equal(expected_profile.begin(),expected_profile.end(),request.begin()+4));
    Challenge challenge{};
    challenge[0]='C';challenge[1]='U';challenge[2]=1;challenge[3]=2;
    std::fill_n(challenge.begin()+4,32,'D');
    const auto tag=from_hex<32>("b97b539ae306e6525316479277660def5260ffaa27908b36a82d071cd3f51694");
    std::copy(tag.begin(),tag.end(),challenge.begin()+36);
    const auto expected=from_hex<36>("43550103effe75029406a796ee6bc3a18c55a59298f7d4760996d8a3e40ff660ac691590");
    Proof answer{};
    assert(proof(request,challenge,key,answer)&&answer==expected);
    for(unsigned i=0;i<challenge.size();++i){
        auto corrupted=challenge;corrupted[i]^=1;answer.fill(0xff);
        assert(!proof(request,corrupted,key,answer));
        assert(std::all_of(answer.begin(),answer.end(),[](auto v){return v==0;}));
    }
    for(unsigned i=0;i<request.size();++i){
        auto corrupted=request;corrupted[i]^=1;
        assert(!proof(corrupted,challenge,key,answer));
    }
    auto wrong=key;wrong[0]^=1;
    assert(!proof(request,challenge,wrong,answer));
    auto other=challenge;other[2]=2;
    assert(!proof(request,other,key,answer));
    Nonce empty{};
    assert(!hello(profile,empty,request));
    assert(!hello({0x7df,0x7e8,10000,false},host,request));
    assert(!hello({0x7e0,0x7e9,10000,false},host,request));
    assert(!hello({0x7e0,0x7e8,0,false},host,request));
    assert(!hello({0x7e0,0x7e8,600001,false},host,request));
    assert(hello({0x18da10f1,0x18daf110,600000,true},host,request));
    assert(!hello({0x18da10f1,0x18daf111,600000,true},host,request));
}
}
int main(){reference();std::puts("CU/1 native credential fixture and negative cases");}
