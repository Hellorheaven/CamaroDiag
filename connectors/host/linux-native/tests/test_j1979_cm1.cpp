#include "j1979_cm1.hpp"
#include <algorithm>
#include <array>
#include <cassert>
#include <stdexcept>
#include <string>
#include <vector>

using namespace cd::cm1;
namespace {
using Buffer=std::vector<std::uint8_t>;
constexpr std::string_view vin="1G1FA1RXXR0123456";
constexpr std::array<std::string_view,16> clear{"0902","03","07","0A","0101","010D","010C","0902",
    "010D","010C","04","03","07","0A","0101","0902"};
constexpr std::array<unsigned,16> stages{1,2,2,2,2,2,2,4,4,4,6,7,7,7,7,7};
constexpr std::array<std::string_view,9> evap{"0902","0800","010D","010C","0902","0800","010D","010C","0801"};
constexpr std::array<unsigned,9> evap_stages{1,2,2,2,4,4,4,4,6};
void p16(Buffer& b,std::size_t at,unsigned v){b.at(at)=std::uint8_t(v);b.at(at+1)=std::uint8_t(v>>8);}
void p32(Buffer& b,std::size_t at,std::uint32_t v){for(unsigned i=0;i<4;++i)b.at(at+i)=std::uint8_t(v>>(8*i));}
std::uint32_t crc(const Buffer& b,std::size_t n){
    std::uint32_t c=0xffffffffu;
    for(std::size_t i=0;i<n;++i){c^=b[i];for(unsigned k=0;k<8;++k)c=(c>>1)^((c&1)?0xedb88320u:0u);}
    return c^0xffffffffu;
}
void fix_crc(Buffer& b){p32(b,b.size()-4,crc(b,b.size()-4));}
Buffer hex(std::string_view s){
    Buffer b;
    constexpr std::string_view digits="0123456789ABCDEF";
    assert(s.size()%2==0);
    for(std::size_t i=0;i<s.size();i+=2){
        auto a=digits.find(s[i]),c=digits.find(s[i+1]);assert(a<16&&c<16);
        b.push_back(std::uint8_t(a*16+c));
    }
    return b;
}
void append(Buffer& b,const Buffer& x){b.insert(b.end(),x.begin(),x.end());}
Buffer response(std::string_view cmd,std::size_t i,bool remaining){
    if(cmd=="0902"){auto b=hex("490201");b.insert(b.end(),vin.begin(),vin.end());return b;}
    if(cmd=="03")return hex(i<10||remaining?"43010123":"4300");
    if(cmd=="07")return hex("4700");
    if(cmd=="0A")return hex("4A00");
    if(cmd=="0101")return hex("410100000000");
    if(cmd=="010D")return hex("410D00");
    if(cmd=="010C")return hex("410C0000");
    if(cmd=="0800")return hex("480080000000");
    if(cmd=="04")return hex("44");
    assert(cmd=="0801");return hex("4801");
}
Buffer scope(unsigned op,bool extended){
    Buffer b(scope_size,0);b[0]=std::uint8_t(op);b[1]=extended?1:0;
    p32(b,2,extended?0x18da10f1:0x7e0);p32(b,6,extended?0x18daf110:0x7e8);p32(b,10,60000);
    std::copy(vin.begin(),vin.end(),b.begin()+14);return b;
}
Buffer row(unsigned stage,unsigned status,std::string_view cmd,const Buffer& res,unsigned i){
    const auto req=hex(cmd);
    Buffer b(row_size,0);b[0]=std::uint8_t(stage);b[1]=std::uint8_t(status);b[2]=std::uint8_t(req.size());
    std::copy(req.begin(),req.end(),b.begin()+3);
    p16(b,6,unsigned(res.size()));p32(b,8,10*i);p32(b,12,10*i+5);p16(b,16,1800);
    append(b,res);return b;
}
Buffer summary(unsigned outcome,unsigned reason,unsigned count,unsigned nrc,unsigned a,unsigned b){
    Buffer s(summary_size,0);s[0]=std::uint8_t(outcome);s[1]=std::uint8_t(reason);s[2]=std::uint8_t(count);
    s[3]=std::uint8_t(nrc);s[4]=std::uint8_t(a);s[5]=std::uint8_t(b);
    p32(s,8,count?10*(count-1)+5:0);return s;
}
Buffer record(const Buffer& sc,unsigned seq,unsigned kind,const Buffer& sum,
              const std::vector<Buffer>& rows,unsigned count){
    Buffer b(header_size,0);b[0]='C';b[1]='M';b[2]=1;b[3]=std::uint8_t(kind);
    b[4]=std::uint8_t(seq);b[5]=std::uint8_t(count);append(b,sc);
    if(kind!=1){append(b,sum);for(unsigned i=0;i<count;++i)append(b,rows.at(i));}
    p32(b,8,std::uint32_t(b.size()-header_size));b.resize(b.size()+4);fix_crc(b);return b;
}
struct Fixture {
    std::array<Buffer,4> records;
    Buffer joined(unsigned count=4)const{
        Buffer out;for(unsigned i=0;i<count;++i)append(out,records.at(i));return out;
    }
};
enum class Result {complete,refused,uncertain,remaining};
Fixture fixture(unsigned operation,Result result=Result::complete,bool extended=false){
    const std::span<const std::string_view> plan=operation==1?std::span<const std::string_view>(clear):
        std::span<const std::string_view>(evap);
    const std::span<const unsigned> phase=operation==1?std::span<const unsigned>(stages):
        std::span<const unsigned>(evap_stages);
    std::vector<Buffer> rows;
    for(unsigned i=0;i<plan.size();++i){
        unsigned state=1;
        auto res=response(plan[i],i,result==Result::remaining);
        if(phase[i]==6&&result==Result::refused){state=2;res={0x7f,hex(plan[i])[0],0x22};}
        if(phase[i]==6&&result==Result::uncertain){state=3;res.clear();}
        rows.push_back(row(phase[i],state,plan[i],res,i));
        if(state!=1)break;
    }
    const unsigned baseline=operation==1?7:4,intent=operation==1?10:8;
    const unsigned before=operation==1?128:0;
    const auto sc=scope(operation,extended);
    unsigned outcome=operation==1?5:6,reason=0,nrc=0,a=before|16|32|1|2|4|8,b=16;
    if(operation==1){a|=64;b|=15;}
    if(result==Result::remaining){reason=13;b&=~4u;}
    if(result==Result::refused||result==Result::uncertain){
        outcome=result==Result::refused?2:3;reason=result==Result::refused?11:10;
        nrc=result==Result::refused?0x22:0;a=before|16|32|1|2|4;b=16|(result==Result::uncertain?32:0);
    }
    return {{record(sc,0,1,{},rows,0),
        record(sc,1,2,summary(0,0,baseline,0,before|16,0),rows,baseline),
        record(sc,2,3,summary(0,0,intent,0,before|16|32|1,0),rows,intent),
        record(sc,3,4,summary(outcome,reason,unsigned(rows.size()),nrc,a,b),rows,unsigned(rows.size()))}};
}
template<class F>void rejected(F action){
    bool threw=false;try{action();}catch(const std::invalid_argument&){threw=true;}assert(threw);
}
void terminal_cases(){
    for(unsigned op:{1u,2u})for(bool extended:{false,true}){
        for(auto expected:{Result::complete,Result::refused,Result::uncertain}){
            const auto f=fixture(op,expected,extended);
            auto e=inspect(f.joined(),vin,false);
            assert(e.records.size()==4&&e.terminal&&e.baseline&&e.intent);
            assert(e.scope.extended==extended&&e.scope.operation==op);
            const unsigned outcome=expected==Result::refused?2:expected==Result::uncertain?3:op==1?5:6;
            assert(e.terminal->outcome==outcome);
            assert(e.recovery_required()==(expected==Result::uncertain));
            assert((e.terminal->a&8)!=0u ? expected==Result::complete : expected!=Result::complete);
            const auto json=report(e,std::string(64,'a'));
            assert(json.find("\"permits_automatic_retry\":false")!=std::string::npos);
            assert(json.find("\"physical_vehicle_verified\":false")!=std::string::npos);
            assert(json.find("\"protocol\":\"SAE-J1979\"")!=std::string::npos);
        }
    }
    const auto e=inspect(fixture(1,Result::remaining).joined(),vin,false);
    assert(e.terminal&&e.terminal->reason==13&&!e.recovery_required());
}
void interrupted(){
    const auto f=fixture(1);
    for(unsigned n=1;n<4;++n){
        const auto raw=f.joined(n);
        const auto e=inspect(raw,vin,false);
        assert(!e.terminal&&e.recovery_required()&&e.intent==(n==3));
        auto prefix=inspect(raw,vin,true);
        assert(prefix.prefix&&prefix.verified_bytes==raw.size()&&prefix.next_kind==0);
        for(std::size_t cut=1;cut<f.records[n].size();++cut){
            Buffer partial=raw;partial.insert(partial.end(),f.records[n].begin(),f.records[n].begin()+cut);
            prefix=inspect(partial,vin,true);
            assert(prefix.recovery_required()&&!prefix.terminal&&prefix.verified_bytes==raw.size());
            assert(prefix.total_bytes==partial.size()&&prefix.next_kind==(cut>=4?n+1:0));
        }
    }
    rejected([&]{inspect(f.joined(),vin,true);});
    rejected([&]{inspect(Buffer{0x43,0x4d},vin,true);});
}
void corrupt_and_forged(){
    auto f=fixture(1);
    auto raw=f.joined();
    raw[15]^=1;
    rejected([&]{inspect(raw,vin,false);});
    rejected([&]{inspect(raw,vin,true);});
    raw=f.joined();raw.pop_back();
    rejected([&]{inspect(raw,vin,false);});
    raw=f.joined();raw.push_back(0);
    rejected([&]{inspect(raw,vin,false);});
    rejected([&]{inspect(f.joined(),"1FA6P8TH0L5100000",false);});
    f.records[3][header_size+scope_size+5]^=2;fix_crc(f.records[3]);
    rejected([&]{inspect(f.joined(),vin,false);});
    f=fixture(1);
    f.records[3][header_size+scope_size+summary_size+8]^=1;fix_crc(f.records[3]);
    rejected([&]{inspect(f.joined(),vin,false);});
    f=fixture(2,Result::uncertain);
    f.records[3][header_size+scope_size+5]&=std::uint8_t(~32u);fix_crc(f.records[3]);
    rejected([&]{inspect(f.joined(),vin,false);});
    f=fixture(1);
    auto partial=f.joined(2);
    auto torn=f.records[2];torn[header_size+14]='2';torn.resize(header_size+scope_size);
    append(partial,torn);
    rejected([&]{inspect(partial,vin,true);});
}
}
int main(){terminal_cases();interrupted();corrupt_and_forged();}
