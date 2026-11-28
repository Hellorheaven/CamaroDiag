#include "j1979_legacy_info.hpp"
#include "j1979_legacy_identity.hpp"
#include <algorithm>
namespace cd::legacy {
namespace {
bool printable(Bytes b){
    return !b.empty()&&std::all_of(b.begin(),b.end(),[](auto v){return v>=32&&v<=126;});
}
Bytes trailing(Bytes b,bool spaces){
    while(!b.empty()&&(b.back()==0||(spaces&&b.back()==32)))b=b.first(b.size()-1);
    return b;
}
std::string text(Bytes b){return {b.begin(),b.end()};}
}
bool count_kind(unsigned kind){return kind==1||kind==3||kind==5||kind==7||kind==9||kind==12||kind==14;}
unsigned matching_count_kind(unsigned kind){return kind==11?7:kind?kind-1:0;}
std::optional<Identification> decode_identification(unsigned kind,unsigned count,std::span<const Data> rows){
    if(!kind||kind>255||count_kind(kind)||kind%32==0||!count||count>128||rows.size()!=count)return {};
    Identification result{};result.kind=kind;
    for(unsigned i=0;i<count;++i){
        const auto& row=rows[i];
        if(row.size()!=7||row[0]!=0x49||row[1]!=kind||row[2]!=i+1)return {};
        result.encoded.insert(result.encoded.end(),row.begin()+3,row.end());
    }
    const Bytes data=result.encoded;
    if(kind==2){
        const auto vin=normalized_vin(rows);
        if(!vin)return {};
        result.values.push_back(*vin);
    }else if(kind==4){
        if(count%4)return {};
        for(size_t i=0;i<data.size();i+=16){
            const auto field=trailing(data.subspan(i,16),true);
            if(!printable(field))return {};
            result.values.push_back(text(field));
        }
    }else if(kind==6){
        for(size_t i=0;i<data.size();i+=4)result.values.push_back(hex(data.subspan(i,4)));
    }else if(kind==8||kind==11){
        const bool size_ok=kind==8?(data.size()==32||data.size()==40):(data.size()==32||data.size()==36);
        if(!size_ok)return {};
        Data reply{0x49,static_cast<uint8_t>(kind),static_cast<uint8_t>(data.size()/2)};
        reply.insert(reply.end(),data.begin(),data.end());
        j1979_vehicle_info info{};
        if(!j1979_vehicle_info_decode(static_cast<uint8_t>(kind),reply.data(),reply.size(),&info))return {};
        result.counters=info;
    }else if(kind==10){
        if(count!=5||data[4]!='-')return {};
        const auto first=trailing(data.first(4),false),second=trailing(data.subspan(5),false);
        if(!printable(first)||!printable(second))return {};
        result.values.push_back(text(first)+"-"+text(second));
    }else if(kind==13||kind==15){
        if(count!=5||data[0]||data[1]||data[2])return {};
        auto field=data.subspan(3);
        while(!field.empty()&&field.front()==0)field=field.subspan(1);
        if(!printable(field))return {};
        result.values.push_back(text(field));
    }else return result;
    result.interpreted=true;
    return result;
}
}
