#include "j1979_cm1.hpp"
#include "j1979_cu45_session.hpp"
#include <openssl/evp.h>
#include <openssl/hmac.h>
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
enum class FixtureResult {complete,refused,uncertain,remaining};
Fixture fixture(unsigned operation,FixtureResult result=FixtureResult::complete,bool extended=false){
    const std::span<const std::string_view> plan=operation==1?std::span<const std::string_view>(clear):
        std::span<const std::string_view>(evap);
    const std::span<const unsigned> phase=operation==1?std::span<const unsigned>(stages):
        std::span<const unsigned>(evap_stages);
    std::vector<Buffer> rows;
    for(unsigned i=0;i<plan.size();++i){
        unsigned state=1;
        auto res=response(plan[i],i,result==FixtureResult::remaining);
        if(phase[i]==6&&result==FixtureResult::refused){state=2;res={0x7f,hex(plan[i])[0],0x22};}
        if(phase[i]==6&&result==FixtureResult::uncertain){state=3;res.clear();}
        rows.push_back(row(phase[i],state,plan[i],res,i));
        if(state!=1)break;
    }
    const unsigned baseline=operation==1?7:4,intent=operation==1?10:8;
    const unsigned before=operation==1?128:0;
    const auto sc=scope(operation,extended);
    unsigned outcome=operation==1?5:6,reason=0,nrc=0,a=before|16|32|1|2|4|8,b=16;
    if(operation==1){a|=64;b|=15;}
    if(result==FixtureResult::remaining){reason=13;b&=~4u;}
    if(result==FixtureResult::refused||result==FixtureResult::uncertain){
        outcome=result==FixtureResult::refused?2:3;reason=result==FixtureResult::refused?11:10;
        nrc=result==FixtureResult::refused?0x22:0;a=before|16|32|1|2|4;b=16|(result==FixtureResult::uncertain?32:0);
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
        for(auto expected:{FixtureResult::complete,FixtureResult::refused,FixtureResult::uncertain}){
            const auto f=fixture(op,expected,extended);
            auto e=inspect(f.joined(),vin,false);
            assert(e.records.size()==4&&e.terminal&&e.baseline&&e.intent);
            assert(e.scope.extended==extended&&e.scope.operation==op);
            const unsigned outcome=expected==FixtureResult::refused?2:expected==FixtureResult::uncertain?3:op==1?5:6;
            assert(e.terminal->outcome==outcome);
            assert(e.recovery_required()==(expected==FixtureResult::uncertain));
            assert((e.terminal->a&8)!=0u ? expected==FixtureResult::complete : expected!=FixtureResult::complete);
            const auto json=report(e,std::string(64,'a'));
            assert(json.find("\"permits_automatic_retry\":false")!=std::string::npos);
            assert(json.find("\"physical_vehicle_verified\":false")!=std::string::npos);
            assert(json.find("\"protocol\":\"SAE-J1979\"")!=std::string::npos);
        }
    }
    const auto e=inspect(fixture(1,FixtureResult::remaining).joined(),vin,false);
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
    f=fixture(2,FixtureResult::uncertain);
    f.records[3][header_size+scope_size+5]&=std::uint8_t(~32u);fix_crc(f.records[3]);
    rejected([&]{inspect(f.joined(),vin,false);});
    f=fixture(1);
    auto partial=f.joined(2);
    auto torn=f.records[2];torn[header_size+14]='2';torn.resize(header_size+scope_size);
    append(partial,torn);
    rejected([&]{inspect(partial,vin,true);});
}
namespace transport {
using namespace cd::cu45;
Nonce key(){Nonce k{};for(unsigned i=0;i<k.size();++i)k[i]=static_cast<std::uint8_t>(i);return k;}
Nonce host(){Nonce n{};n.fill('H');return n;}
Nonce device(){Nonce n{};n.fill('D');return n;}
std::array<std::uint8_t,32> mac(const Buffer& data){
    std::array<std::uint8_t,32> output{};
    unsigned length=0;const auto k=key();
    assert(HMAC(EVP_sha256(),k.data(),k.size(),data.data(),data.size(),output.data(),&length));
    assert(length==output.size());return output;
}
Challenge challenge(Mode mode,const Hello& h){
    Challenge c{};c[0]='C';c[1]='U';c[2]=static_cast<std::uint8_t>(mode);c[3]=2;
    const auto d=device();std::copy(d.begin(),d.end(),c.begin()+4);
    Buffer transcript{static_cast<std::uint8_t>(mode==Mode::archive?'R':'M')};
    transcript.insert(transcript.end(),h.begin()+4,h.end());transcript.insert(transcript.end(),d.begin(),d.end());
    const auto tag=mac(transcript);std::copy(tag.begin(),tag.end(),c.begin()+36);return c;
}
Proof client(Mode mode,const Hello& h){
    Proof p{};p[0]='C';p[1]='U';p[2]=static_cast<std::uint8_t>(mode);p[3]=3;
    const auto d=device();
    Buffer transcript{static_cast<std::uint8_t>(mode==Mode::archive?'r':'m')};
    transcript.insert(transcript.end(),h.begin()+4,h.end());transcript.insert(transcript.end(),d.begin(),d.end());
    const auto tag=mac(transcript);std::copy(tag.begin(),tag.end(),p.begin()+4);return p;
}
Buffer result_wire(Mode mode,const Buffer& raw,unsigned count,bool terminal,bool damaged,bool recovery){
    Buffer result(12,0);result[0]='C';result[1]='U';result[2]=static_cast<std::uint8_t>(mode);result[3]=4;
    if(mode==Mode::archive){
        result[4]=std::uint8_t((terminal?1:0)|(damaged?2:0)|(recovery?4:0));
        result[5]=std::uint8_t(count);
    }else result[4]=recovery?3:2;
    p32(result,8,static_cast<std::uint32_t>(raw.size()));append(result,raw);
    if(mode==Mode::archive){
        Buffer receipt(12,0);receipt[0]='C';receipt[1]='U';receipt[2]=5;receipt[3]=5;
        p32(receipt,4,static_cast<std::uint32_t>(raw.size()));p32(receipt,8,crc(raw,raw.size()));
        append(result,receipt);
    }
    return result;
}
struct LinkSim:Link{
    Hello expected{};
    Proof expected_proof{};
    Buffer initial,after,available;
    unsigned writes{},quiets{};
    std::size_t offset{};
    bool closed{},fail_proof{},fail_close{},fail_quiet{};
    void write(Bytes b,std::uint64_t deadline)override{
        assert(deadline>0);
        ++writes;offset=0;
        if(writes==1){
            assert(std::equal(b.begin(),b.end(),expected.begin(),expected.end()));
            available=initial;
        }else if(writes==2){
            assert(std::equal(b.begin(),b.end(),expected_proof.begin(),expected_proof.end()));
            if(fail_proof)throw TransportError("SIMULATED_UNCERTAIN_PROOF");
            available=after;
        }else assert(false);
    }
    std::size_t read(std::span<std::uint8_t> b,std::uint64_t deadline)override{
        assert(deadline>0);
        const auto n=std::min({b.size(),std::size_t(7),available.size()-offset});
        std::copy_n(available.begin()+offset,n,b.begin());offset+=n;return n;
    }
    void quiet(std::uint64_t)override{
        ++quiets;
        if(offset!=available.size()||fail_quiet)throw TransportError("SIMULATED_TRAILING_INPUT");
    }
    void finish()override{closed=true;if(fail_close)throw TransportError("SIMULATED_CLOSE_FAILURE");}
};
LinkSim simulator(Mode mode,const Scope& sc,const Buffer& wire){
    LinkSim sim;
    assert(hello(mode,sc,host(),sim.expected));
    const auto c=challenge(mode,sim.expected);
    sim.initial.assign(c.begin(),c.end());
    sim.expected_proof=client(mode,sim.expected);
    sim.after=wire;return sim;
}
void authentication(){
    for(const auto mode:{Mode::maintenance,Mode::archive}){
        for(const auto op:{1u,2u}){
            Scope sc{op,false,0x7e0,0x7e8,60000,std::string(vin)};
            Hello h{};
            assert(hello(mode,sc,host(),h));
            const auto c=challenge(mode,h);Proof answer{};
            assert(proof(mode,h,c,key(),answer)&&answer==client(mode,h));
            for(std::size_t i=0;i<c.size();++i){
                auto changed=c;changed[i]^=1;answer.fill(255);
                assert(!proof(mode,h,changed,key(),answer));
                assert(std::all_of(answer.begin(),answer.end(),[](auto b){return b==0;}));
            }
            for(std::size_t i=0;i<h.size();++i){
                auto changed=h;changed[i]^=1;assert(!proof(mode,changed,c,key(),answer));
            }
            assert(!proof(mode==Mode::archive?Mode::maintenance:Mode::archive,h,c,key(),answer));
            assert(!hello(mode,sc,Nonce{},h));
            sc.tx=0x7df;assert(!hello(mode,sc,host(),h));
        }
    }
}
void transfers(){
    for(const auto mode:{Mode::maintenance,Mode::archive}){
        for(const auto op:{1u,2u}){
            const Scope sc{op,false,0x7e0,0x7e8,60000,std::string(vin)};
            for(const auto outcome:{FixtureResult::complete,FixtureResult::refused,FixtureResult::uncertain}){
                const auto f=fixture(op,outcome);
                const unsigned count_min=mode==Mode::archive?1:4;
                for(unsigned count=count_min;count<=4;++count){
                    const auto raw=f.joined(count);
                    const bool recovery=count!=4||outcome==FixtureResult::uncertain;
                    const auto wire=result_wire(mode,raw,count,count==4,false,recovery);
                    for(unsigned scenario=0;scenario<=9;++scenario){
                        auto sim=simulator(mode,sc,wire);
                        if(scenario==1)sim.initial[36]^=1;
                        if(scenario==2)sim.after.pop_back();
                        if(scenario==3)sim.fail_proof=true;
                        if(scenario==4)sim.after.push_back(0);
                        if(scenario==5)sim.after[2]=mode==Mode::archive?4:5;
                        if(scenario==6)sim.fail_close=true;
                        if(scenario==7)sim.fail_quiet=true;
                        if(scenario==8)sim.after[12+15]^=1;
                        Buffer persisted;
                        std::uint64_t tick=100;
                        Session session(sim,[&]{return tick++;},[&]{return scenario!=9||sim.writes<2;});
                        const auto result=session.run(mode,sc,key(),host(),[&](Bytes bytes){
                            persisted.insert(persisted.end(),bytes.begin(),bytes.end());
                        },true);
                        assert(sim.closed&&result.journal==persisted);
                        assert(result.verified()==(scenario==0));
                        if(scenario==0){
                            assert(result.inspection&&result.journal==raw&&result.recovery_required()==recovery);
                            const auto report=cd::cu45::report(result,std::string(64,'a'));
                            assert(report.find("\"permits_automatic_retry\":false")!=std::string::npos);
                            assert(report.find("\"physical_vehicle_verified\":false")!=std::string::npos);
                        }else if(mode==Mode::archive)assert(result.recovery_required());
                        else assert(result.recovery_required()==(scenario!=1&&scenario!=7));
                        rejected([&]{session.run(mode,sc,key(),host(),[](Bytes){},true);});
                        assert(sim.writes<=2);
                    }
                }
            }
        }
    }
}
void torn_archive(){
    const Scope sc{1,false,0x7e0,0x7e8,60000,std::string(vin)};
    const auto f=fixture(1);auto raw=f.joined(3);
    raw.insert(raw.end(),f.records[3].begin(),f.records[3].begin()+17);
    auto sim=simulator(Mode::archive,sc,result_wire(Mode::archive,raw,3,false,true,true));
    Session session(sim,[]{return std::uint64_t{100};},[]{return true;});
    const auto r=session.run(Mode::archive,sc,key(),host(),[](Bytes){},true);
    assert(r.verified()&&r.inspection->prefix&&r.recovery_required());
    assert(r.inspection->total_bytes-r.inspection->verified_bytes==17);
    auto no=simulator(Mode::archive,sc,result_wire(Mode::archive,raw,3,false,false,true));
    Session refused(no,[]{return std::uint64_t{100};},[]{return true;});
    assert(!refused.run(Mode::archive,sc,key(),host(),[](Bytes){},true).verified());
    auto not_authorized=simulator(Mode::maintenance,sc,{});
    Session denied(not_authorized,[]{return std::uint64_t{100};},[]{return true;});
    rejected([&]{denied.run(Mode::maintenance,sc,key(),host(),[](Bytes){},false);});
    assert(not_authorized.writes==0);
}
}
}
int main(){terminal_cases();interrupted();corrupt_and_forged();transport::authentication();transport::transfers();transport::torn_archive();}
