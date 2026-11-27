#include "j1979_legacy_identity.hpp"
#include "j1979_legacy_mutation.hpp"
#include <algorithm>
namespace cd::legacy {
bool valid_vin(std::string_view vin){
    return vin.size()==17&&std::all_of(vin.begin(),vin.end(),[](char c){
        return (c>='0'&&c<='9')||(c>='A'&&c<='Z'&&c!='I'&&c!='O'&&c!='Q');
    });
}
bool vin_support_page(Bytes p){
    return p.size()==7&&p[0]==0x49&&p[1]==0&&p[2]==1&&(p[3]&0xc0)==0xc0;
}
bool vin_fragment_count(Bytes p){
    return p.size()==3&&p[0]==0x49&&p[1]==1&&p[2]==5;
}
std::optional<std::string> normalized_vin(std::span<const Data> fragments){
    if(fragments.size()!=5)return {};
    std::array<std::uint8_t,20> vin{};
    for(unsigned n=0;n<5;++n){
        const auto& r=fragments[n];
        if(r.size()!=7||r[0]!=0x49||r[1]!=2||r[2]!=n+1)return {};
        std::copy_n(r.begin()+3,4,vin.begin()+4*n);
    }
    if(vin[0]||vin[1]||vin[2])return {};
    std::string result(vin.begin()+3,vin.end());
    return valid_vin(result)?std::optional<std::string>{std::move(result)}:std::nullopt;
}
bool MutationPlan::valid()const{
    return valid_protocol(protocol)&&(operation==MutationOperation::clear||operation==MutationOperation::evap)&&
        valid_vin(vin)&&budget_ms>=1000&&budget_ms<=60000&&sources.size()>=1&&sources.size()<=32&&
        sources.contains(engine)&&std::all_of(sources.begin(),sources.end(),valid_source);
}
}
