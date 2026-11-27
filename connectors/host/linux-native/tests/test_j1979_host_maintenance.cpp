#include <algorithm>
#include "j1979_host_maintenance.hpp"
#include "j1979_cm1.hpp"
#include <cassert>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <iterator>
#include <sys/stat.h>
#include <unistd.h>
#include <vector>
namespace {
using namespace cd::hostmaint;
struct Directory{
    std::filesystem::path path;
    Directory(){
        char name[]="/tmp/camarodiag-maintenance-XXXXXX";
        const char* result=mkdtemp(name);assert(result);path=result;
    }
    ~Directory(){std::error_code ec;std::filesystem::remove_all(path,ec);}
};
template<class F>void rejected(F f){bool threw=false;try{f();}catch(const std::exception&){threw=true;}assert(threw);}
Scope scope(){
    Scope s{};s.tx=0x7e0;s.rx=0x7e8;s.budgetMs=60000;
    std::memcpy(s.vin,"1G1FA1RXXR0123456",17);return s;
}
std::vector<uint8_t> read(const std::filesystem::path& p){
    std::ifstream stream(p,std::ios::binary);
    assert(stream);
    return {std::istreambuf_iterator<char>(stream),std::istreambuf_iterator<char>()};
}
void durable_open(){
    Directory d;
    {
        FileStorage storage(d.path.c_str(),"session.cm");
        Journal::Scratch scratch{};
        Journal journal(storage,scratch);
        assert(journal.begin(scope()));
        size_t size=0;assert(storage.size(size)&&size==47);
        assert(std::filesystem::exists(d.path/"RECOVERY_REQUIRED"));
        rejected([&]{FileStorage competing(d.path.c_str(),"different.cm");});
        const auto bytes=read(d.path/"session.cm");
        const auto evidence=cd::cm1::inspect(bytes,"1G1FA1RXXR0123456",true);
        assert(evidence.records==std::vector<unsigned>{1});
        assert(evidence.recovery_required());
        std::array<uint8_t,47> restored{};
        assert(storage.read(0,restored.data(),restored.size()));
        assert(std::equal(restored.begin(),restored.end(),bytes.begin(),bytes.end()));
        assert(!storage.read(size,restored.data(),1));
    }
    rejected([&]{FileStorage unresolved(d.path.c_str(),"new.cm");});
    struct stat statbuf{};
    assert(stat((d.path/"session.cm").c_str(),&statbuf)==0&&!(statbuf.st_mode&077));
    assert(stat((d.path/"RECOVERY_REQUIRED").c_str(),&statbuf)==0&&!(statbuf.st_mode&077));
}
void unsafe_paths(){
    Directory d;
    rejected([&]{FileStorage a(d.path.c_str(),"../outside");});
    rejected([&]{FileStorage a(d.path.c_str(),"RECOVERY_REQUIRED");});
    assert(chmod(d.path.c_str(),0755)==0);
    rejected([&]{FileStorage a(d.path.c_str(),"session.cm");});
    assert(chmod(d.path.c_str(),0700)==0);
    const auto target=d.path/"existing";std::ofstream(target)<<"original";
    const auto link=d.path/"session.cm";assert(symlink(target.c_str(),link.c_str())==0);
    rejected([&]{FileStorage a(d.path.c_str(),"session.cm");});
    assert(read(target)==std::vector<uint8_t>({'o','r','i','g','i','n','a','l'}));
}
void changed_storage(){
    {
        Directory d;FileStorage storage(d.path.c_str(),"session.cm");
        Journal::Scratch scratch{};Journal journal(storage,scratch);
        assert(journal.begin(scope()));
        std::filesystem::rename(d.path/"session.cm",d.path/"old.cm");
        std::ofstream(d.path/"session.cm")<<"replacement";
        assert(!storage.ready());
        const uint8_t byte=1;assert(!storage.append(&byte,1)&&storage.latched());
    }
    {
        Directory d;FileStorage storage(d.path.c_str(),"session.cm");
        Journal::Scratch scratch{};Journal journal(storage,scratch);
        assert(journal.begin(scope()));
        std::filesystem::remove(d.path/"RECOVERY_REQUIRED");
        std::ofstream(d.path/"RECOVERY_REQUIRED")<<"replacement";
        assert(!storage.ready()&&!storage.flush()&&storage.latched());
    }
}
void approval_and_report(){
    Directory d;auto target=scope();
    volatile sig_atomic_t cancelled=1;
    rejected([&]{run_socket(target,"can0",d.path.c_str(),"session.cm",true,true,&cancelled);});
    rejected([&]{run_socket(target,"can0",d.path.c_str(),"session.cm",false,true,nullptr);});
    assert(std::filesystem::is_empty(d.path));
    Result r{};r.evidence.scope=target;r.local_socket_closed=true;r.journal_created=true;
    const auto text=report(r,"",{});
    assert(text.find("\"physical_stop_independently_verified\":false")!=std::string::npos);
    assert(text.find("\"physical_transceiver_disabled_verified\":false")!=std::string::npos);
    assert(text.find("\"permits_automatic_retry\":false")!=std::string::npos);
    assert(text.find("\"recovery_marker_requires_operator_review\":true")!=std::string::npos);
}
}
int main(){durable_open();unsafe_paths();changed_storage();approval_and_report();}
