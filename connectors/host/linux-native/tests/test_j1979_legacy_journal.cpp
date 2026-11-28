#include "j1979_legacy_journal.hpp"
#include "j1979_legacy_live_journal.hpp"
#include <cassert>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <iterator>
#include <stdexcept>
#include <unistd.h>
using namespace cd::legacy;
namespace {
constexpr auto protocol=Protocol::vpw;
constexpr unsigned source=0x10;
constexpr const char* vin="1G1FA1E30D1234567";
struct Temporary {
    std::filesystem::path directory;
    Temporary(){char name[]="/tmp/camarodiag-lj-XXXXXX";const auto result=mkdtemp(name);assert(result);directory=result;}
    ~Temporary(){std::error_code ec;std::filesystem::remove_all(directory,ec);}
    std::string file(const char* name)const{return (directory/name).string();}
};
Data load(const std::string& path){
    std::ifstream file(path,std::ios::binary);assert(file);
    return Data(std::istreambuf_iterator<char>(file),{});
}
template<class F>void rejects(F f){bool failed=false;try{f();}catch(const std::exception&){failed=true;}assert(failed);}
void check_torn(const Data& bytes,size_t header,unsigned count,JournalKind kind){
    for(size_t n=header;n<bytes.size();++n){
        auto e=inspect_journal(Bytes(bytes).first(n),protocol,source,vin);
        assert(!e.terminal&&e.kind==kind&&e.observations<=count&&e.verified_bytes<=n);
        assert(e.verified_bytes+e.torn_bytes==n);
    }
}
void check_complete(const Data& bytes,const Data& expected,JournalKind kind,unsigned count,size_t header){
    const auto e=inspect_journal(bytes,protocol,source,vin);
    assert(e.kind==kind&&e.terminal&&e.observations==count&&e.capture==expected);
    assert(e.verified_bytes==bytes.size()&&e.torn_bytes==0);
    check_torn(bytes,header,count,kind);
    rejects([&]{(void)inspect_journal(bytes,protocol,0x11,vin);});
    rejects([&]{(void)inspect_journal(bytes,protocol,source,"1G1FA1E30D1234568");});
    auto altered=bytes;altered[header+9]^=1;rejects([&]{(void)inspect_journal(altered,protocol,source,vin);});
    altered=bytes;altered.back()^=1;rejects([&]{(void)inspect_journal(altered,protocol,source,vin);});
    altered=bytes;altered.push_back(0);rejects([&]{(void)inspect_journal(altered,protocol,source,vin);});
}
void scan(){
    Temporary t;const auto path=t.file("scan.lj");
    ScanCapture scope{protocol,source,60000,vin,{}};
    const ScanExchange row{"0100","",ScanOutcome::cancelled,0,0,false};
    {
        CaptureJournal writer(path.c_str(),scope);
        auto prefix=inspect_journal(load(path),protocol,source,vin);
        assert(!prefix.terminal&&prefix.observations==0&&prefix.capture.empty());
        rejects([&]{CaptureJournal second(path.c_str(),scope);});
        writer.append(row);
        prefix=inspect_journal(load(path),protocol,source,vin);
        assert(prefix.observations==1&&!prefix.terminal);
        writer.finish();
        rejects([&]{writer.finish();});
    }
    scope.trace.push_back(row);
    check_complete(load(path),encode_scan_capture(scope),JournalKind::scan,1,44);
}
void freeze(){
    Temporary t;const auto path=t.file("freeze.lj");
    FreezeCapture scope{protocol,source,{vin,{0},{12},60000},{}};
    const FreezeExchange row{"0100","",FreezeOutcome::cancelled,0,false};
    {CaptureJournal writer(path.c_str(),scope);writer.append(row);writer.finish();}
    scope.trace.push_back(row);
    check_complete(load(path),encode_freeze_capture(scope),JournalKind::freeze,1,116);
}
void live(){
    Temporary t;const auto path=t.file("live.lj");
    LivePlan plan{};plan.vin=vin;plan.pids={12};
    LiveCapture scope{protocol,source,plan,{}};
    const LiveExchange row{{"0100","",FreezeOutcome::cancelled,0,false},LivePhase::initial,0,0,0};
    {CaptureJournal writer(path.c_str(),scope);writer.append(row);writer.finish();}
    scope.trace.push_back(row);
    check_complete(load(path),encode_live_capture(scope),JournalKind::live,1,84);
    const auto original=load(path);
    const auto projection=project_live_journal(original,protocol,source,vin);
    assert(!projection.completed&&projection.report.at("journal_terminal_verified").boolean());
    assert(!projection.report.at("identity_rechecked").boolean());
    assert(projection.report.at("journal_sha256").string()==projection.journal_sha256);
    assert(projection.report.at("capture_sha256").string()==projection.capture_sha256);
    assert(projection.csv.starts_with("cycle,pid_hex"));
    assert(verify_live_journal(projection,projection.report,std::string(64,'a')).at("consistent").boolean());
    auto changed=projection.report.object();
    changed["journal_sha256"]=cd::json::Value{std::string(64,'b')};
    assert(!verify_live_journal(projection,cd::json::Value{changed},std::string(64,'a')).at("consistent").boolean());
    rejects([&]{(void)project_live_journal(original,protocol,0x11,vin);});
    const auto interrupted=t.file("interrupted.lj");scope.trace.clear();
    {CaptureJournal writer(interrupted.c_str(),scope);writer.append(row);}
    const auto e=inspect_journal(load(interrupted),protocol,source,vin);
    assert(!e.terminal&&e.observations==1&&e.torn_bytes==0);
    rejects([&]{(void)project_live_journal(load(interrupted),protocol,source,vin);});
}
}
int main(){scan();freeze();live();}
