#include "j1979_catalogue_projection.hpp"
#include "j1979_legacy_journal.hpp"
#include <openssl/evp.h>
#include <filesystem>
#include <fstream>
#include <iterator>
#include <cstdlib>
#include "native_json.hpp"
#include "j1979_legacy_live_wire.hpp"
#include <array>
#include <cassert>
#include <limits>
#include <stdexcept>
using namespace cd::catalogue;
namespace {
const std::string pin(64,'a'),source(64,'b'),catalogue_sha(64,'c');
constexpr std::string_view vin="1G1YY22G965109876";
template<class F>void rejects(F f){bool failed=false;try{f();}catch(const std::exception&){failed=true;}assert(failed);}
std::string definition(std::string_view protocols="[\"CAN\",\"LEGACY\"]",unsigned width=2){
    return "{\"pid\":12,\"payload_bytes\":"+std::to_string(width)+",\"protocols\":"+std::string(protocols)+
        ",\"reference\":\"Operator reference\",\"signals\":["
        "{\"name\":\"engine-rpm\",\"unit\":\"rpm\",\"reference\":\"Section 1\","
        "\"bits\":{\"offset\":0,\"width\":16,\"order\":\"MSB0\"},"
        "\"signed\":false,\"scale\":[1,4],\"offset\":[0,1],\"range\":[[0,1],[9000,1]]}]}";
}
std::string live(std::string_view defs){
    return "{\"schema\":\"camarodiag-j1979-pid-catalogue-v1\",\"edition\":\"Local edition\","
      "\"reference_name\":\"Local source\",\"reference_sha256\":\""+pin+"\",\"definitions\":["+std::string(defs)+"]}";
}
std::string freeze(std::string_view defs,unsigned frame=0){
    return "{\"schema\":\"camarodiag-j1979-freeze-catalogue-v1\",\"edition\":\"Local edition\","
      "\"reference_name\":\"Local source\",\"reference_sha256\":\""+pin+"\",\"frames\":[{\"frame\":"+
      std::to_string(frame)+",\"definitions\":["+std::string(defs)+"]}]}";
}
void parser(){
    const auto value=cd::json::parse("{\"a\":18446744073709551615,\"b\":-9223372036854775808,\"c\":\"\\uD83D\\uDE80\"}");
    assert(value.at("a").unsigned_integer()==std::numeric_limits<std::uint64_t>::max());
    assert(value.at("b").signed_integer()==std::numeric_limits<std::int64_t>::min());
    assert(value.at("c").string()=="\xf0\x9f\x9a\x80");
    assert(cd::json::parse(cd::json::quote(value.at("c").string())).string()==value.at("c").string());
    for(const std::string_view invalid:{"{\"a\":1,\"a\":2}","{\"a\":1,\"\\u0061\":2}","[01]","[1.0]","[1e0]",
        "[1,]","{\"a\":true,}","\"\\uD800\"","\"\\uDC00\"","\"\xc0\x80\"","true null","[NaN]"})
        rejects([&]{cd::json::parse(invalid);});
    rejects([&]{cd::json::parse("18446744073709551616").unsigned_integer();});
    rejects([&]{cd::json::parse("true").unsigned_integer();});
    assert(cd::json::valid_text("Référence",9)&&!cd::json::valid_text(" Référence",20));
}
void exact(){
    const auto max=std::numeric_limits<std::uint64_t>::max();
    auto n=exact_value(max,64,false,{1,1},{0,1});
    assert(n.numerator=="18446744073709551615"&&n.denominator=="1");
    n=exact_value(max,64,true,{1,3},{1,2});
    assert(n.numerator=="1"&&n.denominator=="6");
    n=exact_value(0x8000000000000000ULL,64,true,{1,1},{0,1});
    assert(n.numerator=="-9223372036854775808"&&n.denominator=="1");
    n=exact_value(255,8,false,{1,3},{-85,1});
    assert(n.numerator=="0"&&n.denominator=="1");
    n=exact_value(5,8,false,{1,2},{0,1},Ratio{0,1},Ratio{2,1});
    assert(n.numerator=="5"&&n.denominator=="2"&&n.outside);
    assert(compare({-2,3},{-1,2})<0&&compare({2,4},{1,2})==0);
    const std::array<std::uint8_t,9> bytes{0x01,0x23,0x45,0x67,0x89,0xab,0xcd,0xef,0x80};
    assert((Bits{0,64,Order::msb0}.extract(bytes)==0x0123456789abcdefULL));
    assert((Bits{0,64,Order::le_bytes}.extract(bytes)==0xefcdab8967452301ULL));
    assert((Bits{4,64,Order::msb0}.extract(bytes)==0x123456789abcdef8ULL));
    rejects([&]{Bits{1,8,Order::le_bytes}.extract(bytes);});
    rejects([&]{exact_value(256,8,false,{1,1},{0,1});});
    rejects([&]{exact_value(1,8,false,{0,1},{0,1});});
}
void schema(){
    auto c=parse_catalogue(live(definition()),Mode::live,pin);
    assert(c.find(12,Transport::can)&&c.find(12,Transport::legacy)&&!c.find(12,Transport::can,0));
    auto row=project_signal(c.definitions[0].signals[0],std::array<std::uint8_t,2>{0x1f,0x40});
    assert(row.status=="DECODED"&&row.value->numerator=="2000"&&row.value->denominator=="1");
    assert(inventory(c,catalogue_sha).find("\"normative_compliance_verified\":false")!=std::string::npos);
    auto frozen=parse_catalogue(freeze(definition("[\"CAN\"]")),Mode::freeze,pin);
    assert(frozen.find(12,Transport::can,0)&&!frozen.find(12,Transport::can,1)&&!frozen.find(12,Transport::legacy,0));
    rejects([&]{parse_catalogue(freeze(definition()),Mode::freeze,pin);});
    rejects([&]{parse_catalogue(live(definition()),Mode::freeze,pin);});
    rejects([&]{parse_catalogue(live(definition()+","+definition()),Mode::live,pin);});
    rejects([&]{parse_catalogue(live(definition("[\"LEGACY\"]",6)),Mode::live,pin);});
    rejects([&]{parse_catalogue(live(definition()),Mode::live,source);});
    auto d=definition();auto pos=d.find("\"pid\":12");d.replace(pos,8,"\"pid\":32");
    rejects([&]{parse_catalogue(live(d),Mode::live,pin);});
    Signal s{};s.name="flags";s.unit="state";s.reference="Local";s.bits={0,4,Order::msb0};
    s.scale={1,1};s.offset={0,1};s.presence=Presence{{4,4,Order::msb0},1,1};
    s.unavailable={15};s.enumeration={{1,"On"},{2,"Off"}};
    assert(project_signal(s,std::array<std::uint8_t,1>{0x10}).status=="NOT_PRESENT");
    assert(project_signal(s,std::array<std::uint8_t,1>{0xf1}).status=="UNAVAILABLE");
    assert(project_signal(s,std::array<std::uint8_t,1>{0x11}).enumeration=="On");
    assert(project_signal(s,std::array<std::uint8_t,1>{0x31}).status=="UNDEFINED_ENUMERATION");
}
namespace legacy=cd::legacy;
std::string wire(legacy::Data payload){
    legacy::Data bytes{0x48,0x6b,0x10};bytes.insert(bytes.end(),payload.begin(),payload.end());
    bytes.push_back(legacy::checksum(legacy::Protocol::vpw,bytes));return legacy::hex(bytes)+"\r";
}
using Trace=std::vector<legacy::LiveExchange>;
void add(Trace& t,legacy::LivePhase phase,unsigned cycle,std::string cmd,std::string response){
    const unsigned start=t.empty()?0:t.back().ended_ms;
    const bool sent=cmd!="WAIT";const unsigned duration=sent?10:0;
    t.push_back({{std::move(cmd),std::move(response),legacy::FreezeOutcome::received,duration,sent},
        phase,cycle,start,start+duration});
}
void identity(Trace& t,legacy::LivePhase phase,std::string_view id=vin){
    add(t,phase,0,"0100",wire({0x41,0,0,0x10,0,0}));
    add(t,phase,0,"ATDPN","2");
    add(t,phase,0,"0900",wire({0x49,0,1,0xc0,0,0,0}));
    add(t,phase,0,"0901",wire({0x49,1,5}));
    legacy::Data bytes(3,0);bytes.insert(bytes.end(),id.begin(),id.end());
    std::string raw;
    for(unsigned i=0;i<5;++i){
        legacy::Data row{0x49,2,std::uint8_t(i+1)};
        row.insert(row.end(),bytes.begin()+i*4,bytes.begin()+i*4+4);raw+=wire(row);
    }
    add(t,phase,0,"0902",raw);
}
void replay_projection(){
    using Phase=legacy::LivePhase;
    legacy::LivePlan plan{std::string(vin),{12},1,100,60000,true};
    Trace trace;
    identity(trace,Phase::initial);
    add(trace,Phase::inventory,0,"0100",wire({0x41,0,0,0x10,0,0}));
    add(trace,Phase::sample,1,"WAIT","");
    add(trace,Phase::sample,1,"010C",wire({0x41,12,0x1f,0x40}));
    identity(trace,Phase::final);
    const auto c=parse_catalogue(live(definition()),Mode::live,pin);
    const auto capture=legacy::encode_live_capture({legacy::Protocol::vpw,0x10,plan,trace});
    const auto p=project_llr1(capture,vin,2,0x10,c,source,catalogue_sha);
    assert(p.completed&&p.identity_rechecked&&p.rows.size()==1&&p.rows[0].status=="PROJECTED");
    assert(p.rows[0].signals[0].value->numerator=="2000");
    // Recreate the same capture from an independently recorded LJ journal.
    char name[]="/tmp/camarodiag-catalogue-XXXXXX";
    assert(mkdtemp(name));
    struct Cleanup{std::filesystem::path path;~Cleanup(){std::error_code ec;std::filesystem::remove_all(path,ec);}} cleanup{name};
    const auto path=(cleanup.path/"session.lj").string();
    {legacy::CaptureJournal writer(path.c_str(),legacy::LiveCapture{legacy::Protocol::vpw,0x10,plan,{}});
        for(const auto& row:trace)writer.append(row);
        writer.finish();}
    std::ifstream input(path,std::ios::binary);
    const legacy::Data journal(std::istreambuf_iterator<char>(input),{});
    std::array<unsigned char,32> digest{};unsigned digest_size{};
    assert(EVP_Digest(journal.data(),journal.size(),digest.data(),&digest_size,EVP_sha256(),nullptr)==1&&digest_size==32);
    std::string journal_sha;constexpr char digits[]="0123456789abcdef";
    for(auto byte:digest){journal_sha+=digits[byte>>4];journal_sha+=digits[byte&15];}
    const auto from_journal=project_lj1(journal,vin,2,0x10,c,journal_sha,catalogue_sha);
    assert(from_journal.completed&&from_journal.rows.size()==p.rows.size());
    assert(from_journal.rows[0].signals[0].value->numerator=="2000");
    assert(from_journal.source_sha256==journal_sha&&valid_digest(from_journal.reconstructed_capture_sha256));
    const auto journal_report=cd::json::parse_report(report(c,from_journal));
    assert(journal_report.at("journal_terminal_verified").boolean());
    assert(journal_report.at("source_capture_sha256").string()==from_journal.reconstructed_capture_sha256);
    assert(journal_report.at("source_journal_sha256").string()==journal_sha);
    assert(!journal_report.at("normative_compliance_verified").boolean());
    assert(projection_csv(c,from_journal).find(from_journal.reconstructed_capture_sha256)!=std::string::npos);
    rejects([&]{project_lj1(journal,vin,2,0x10,c,source,catalogue_sha);});
    auto incomplete=journal;incomplete.pop_back();
    rejects([&]{project_lj1(incomplete,vin,2,0x10,c,journal_sha,catalogue_sha);});
    const auto output=report(c,p);
    const auto csv=projection_csv(c,p);
    assert(csv.starts_with("pid_hex,frame,step_index,cycle,elapsed_ms,"));
    assert(csv.find("\"2000\",\"1\"")!=std::string::npos);
    assert(csv.find(std::string(source))!=std::string::npos&&csv.find(std::string(pin))!=std::string::npos);
    assert(csv.find(std::string(vin))!=std::string::npos);
    assert(csv.find("\r\n")!=std::string::npos);
    assert(output.find("\"transport_frames_replayed\":true")!=std::string::npos);
    assert(output.find("\"reference_authenticity_verified\":false")!=std::string::npos);
    auto invalid=trace;invalid.resize(8);identity(invalid,Phase::final,"1G1YY22G965109877");
    auto changed=legacy::encode_live_capture({legacy::Protocol::vpw,0x10,plan,invalid});
    const auto withheld=project_llr1(changed,vin,2,0x10,c,source,catalogue_sha);
    assert(!withheld.identity_rechecked&&withheld.rows[0].status=="IDENTITY_NOT_RECHECKED"&&withheld.rows[0].signals.empty());
    const auto unavailable=projection_csv(c,withheld);
    assert(unavailable.find("IDENTITY_NOT_RECHECKED")!=std::string::npos);
    assert(unavailable.find("\"2000\",\"1\"")==std::string::npos);
    auto fake=withheld;fake.rows[0].signals=p.rows[0].signals;
    rejects([&]{projection_csv(c,fake);});
    auto damaged=capture;damaged.back()^=1;
    rejects([&]{project_llr1(damaged,vin,2,0x10,c,source,catalogue_sha);});
    plan.raw_only=false;
    const auto numeric=legacy::encode_live_capture({legacy::Protocol::vpw,0x10,plan,trace});
    rejects([&]{project_llr1(numeric,vin,2,0x10,c,source,catalogue_sha);});
}
}
int main(){parser();exact();schema();replay_projection();}
