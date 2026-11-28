#include "j1979_network_journal.hpp"
#include "j1979_native_report_detail.hpp"
#include <algorithm>
#include <array>
#include <cassert>
#include <cerrno>
#include <cstdlib>
#include <fcntl.h>
#include <stdexcept>
#include <sys/stat.h>
#include <unistd.h>
using namespace cd::network;
using cd::json::Value;
namespace sc=cd::socketcampaign;
namespace {
constexpr char vin[]="1G1FA1RXXR0123456";
constexpr char iface[]="vcan0";
struct File{
    int fd{-1};
    std::string name;
    File(){
        char path[]="/tmp/camarodiag-network-test-XXXXXX";
        fd=mkstemp(path);assert(fd>=0);name=path;assert(fchmod(fd,0600)==0);
    }
    ~File(){if(fd>=0)close(fd);unlink(name.c_str());}
    sc::Data read()const{
        struct stat s{};assert(!fstat(fd,&s)&&s.st_size>0);
        sc::Data out(static_cast<std::size_t>(s.st_size));
        std::size_t at=0;
        while(at<out.size()){
            const auto n=pread(fd,out.data()+at,out.size()-at,static_cast<off_t>(at));
            assert(n>0);at+=static_cast<std::size_t>(n);
        }
        return out;
    }
};
struct Fake{std::uint64_t now{1000};unsigned calls{},vin_calls{};bool wrong_vin{},timeout{};};
std::uint64_t clock(void* p){return static_cast<Fake*>(p)->now;}
bool current(void*){return true;}
bool wait(void* p,unsigned n){static_cast<Fake*>(p)->now+=n;return true;}
int exchange(void* p,const std::uint8_t* q,std::size_t n,std::uint8_t* out,std::size_t* size,unsigned timeout){
    auto& f=*static_cast<Fake*>(p);
    assert(j1979_can_allowed_read(q,n)&&timeout>0);
    ++f.calls;f.now+=5;
    sc::Data response;
    if(n==2&&q[0]==9&&q[1]==2){
        ++f.vin_calls;response={0x49,2,1};
        const std::string_view identity=f.wrong_vin?"2G1FA1RXXR0123456":vin;
        response.insert(response.end(),identity.begin(),identity.end());
    }else if(n==1)response={static_cast<std::uint8_t>(q[0]+0x40),0};
    else if(n==3&&q[0]==2&&q[1]==2)response={0x42,2,0,0,0};
    else if(q[1]==0)response={static_cast<std::uint8_t>(q[0]+0x40),0,static_cast<std::uint8_t>(q[0]==1?8:0),0,0,0};
    else if(q[0]==1&&q[1]==5){
        if(f.timeout)return 1;
        response={0x41,5,100};
    }else assert(false);
    assert(response.size()<=*size);
    std::copy(response.begin(),response.end(),out);*size=response.size();return 0;
}
sc::Plan plan(Pair pair,unsigned budget=90000){
    sc::Plan p{};p.operation=sc::Operation::scan;p.tx=pair.tx;p.rx=pair.rx;p.vin=vin;p.interface=iface;p.budget_ms=budget;
    assert(p.valid());return p;
}
struct Captured{EcuReport row;sc::Data bytes;};
Captured capture(Pair pair,unsigned budget=90000,Fake f={}){
    File file;const auto p=plan(pair,budget);sc::Writer writer(file.fd,p);
    const sc::Link link{exchange,clock,current,wait,&f};
    const auto result=sc::record(p,link,writer);sc::finish(writer,result);
    return {{pair,result.completed?"PASSED":"NOT_TESTED",result.stop,result.report},file.read()};
}
template<class F>void rejects(F f){
    bool rejected=false;try{f();}catch(const std::exception&){rejected=true;}assert(rejected);
}
struct Directory{
    std::string path;
    Directory(){
        char named[]="/tmp/camarodiag-network-dir-XXXXXX";
        assert(mkdtemp(named));path=named;assert(chmod(path.c_str(),0700)==0);
    }
    ~Directory(){
        for(auto name:{"discovery.json","ecu-7E8.scj","ecu-7E9.scj","foreign"})unlink((path+"/"+name).c_str());
        rmdir(path.c_str());
    }
    void write(std::string_view name,sc::Bytes bytes){
        const auto file=path+"/"+std::string(name);
        const int fd=open(file.c_str(),O_WRONLY|O_CREAT|O_EXCL|O_NOFOLLOW,0600);assert(fd>=0);
        std::size_t at=0;
        while(at<bytes.size()){
            const auto n=::write(fd,bytes.data()+at,bytes.size()-at);assert(n>0);at+=static_cast<std::size_t>(n);
        }
        assert(!close(fd));
    }
    void write(std::string_view name,std::string_view bytes){
        write(name,{reinterpret_cast<const std::uint8_t*>(bytes.data()),bytes.size()});
    }
};
}
int main(){
    const Pair first{0x7e0,0x7e8},second{0x7e1,0x7e9};
    const std::array frames{Frame{first.rx,false,false,false,{6,0x41,0,8,0,0,0,0}},
        Frame{second.rx,false,false,false,{6,0x41,0,8,0,0,0,0}}};
    auto discovery=replay_discovery(false,true,frames,"",10);
    assert(discovery.completed&&discovery.eligible.size()==2);
    const auto a=capture(first),b=capture(second,89000);
    Scan scan{};scan.discovery=discovery;scan.vin=vin;scan.selected={first.rx,second.rx};
    scan.ecus={a.row,b.row};scan.completed=true;scan.duration_ms=1000;
    history::NetworkJournalInput inputs{report(discovery),{{first.rx,a.bytes},{second.rx,b.bytes}}};
    const auto source=cd::json::parse_report(report(scan));
    const auto check=[&](const Value& root,const history::NetworkJournalInput& files,std::string_view selected_interface=iface){
        return history::verify_network_journals(root,files,vin,selected_interface,std::string(64,'a'));
    };
    const auto proof=check(source,inputs);
    assert(proof.at("completed").boolean()&&proof.at("terminal_journals").unsigned_integer()==2);
    assert(!proof.at("physical_vehicle_verified").boolean()&&!proof.at("permits_automatic_retry").boolean());
    assert(proof.at("ecu_reports").array()[0].at("binding").string()=="TERMINAL_CALLBACK_REPLAY_MATCHED");
    auto wrong=inputs;wrong.journals.erase(first.rx);rejects([&]{(void)check(source,wrong);});
    wrong=inputs;wrong.journals[second.rx]=a.bytes;rejects([&]{(void)check(source,wrong);});
    wrong=inputs;wrong.journals.emplace(0x7ea,a.bytes);rejects([&]{(void)check(source,wrong);});
    wrong=inputs;wrong.journals[first.rx].back()^=1;rejects([&]{(void)check(source,wrong);});
    wrong=inputs;wrong.journals[first.rx].pop_back();rejects([&]{(void)check(source,wrong);});
    rejects([&]{(void)check(source,inputs,"can1");});
    auto altered=source.object();altered["duration_ms"]=Value{cd::json::Integer{"0"}};
    rejects([&]{(void)check(Value{altered},inputs);});
    auto reversed=capture(second,99000);wrong=inputs;wrong.journals[second.rx]=reversed.bytes;
    rejects([&]{(void)check(source,wrong);});
    const auto different_discovery=replay_discovery(false,true,std::span(frames).first(1),"",10);
    wrong=inputs;wrong.discovery=report(different_discovery);rejects([&]{(void)check(source,wrong);});

    // A transport that never opened has only its durable scoped header.
    Scan incomplete=scan;incomplete.completed=false;incomplete.stop="TRANSPORT_UNAVAILABLE";
    incomplete.ecus={{first,"NOT_TESTED","TRANSPORT_UNAVAILABLE",{}},{second,"NOT_TESTED","TRANSPORT_UNAVAILABLE",{}}};
    history::NetworkJournalInput prefix{report(discovery),{{first.rx,sc::encode_header(plan(first))}}};
    auto partial=check(cd::json::parse_report(report(incomplete)),prefix);
    assert(!partial.at("completed").boolean()&&partial.at("header_only_journals").unsigned_integer()==1);
    assert(partial.at("unattempted_rows").unsigned_integer()==1);
    prefix.journals.emplace(second.rx,sc::encode_header(plan(second)));
    rejects([&]{(void)check(cd::json::parse_report(report(incomplete)),prefix);});
    // A sealed incomplete campaign must not be promoted to PASSED.
    Fake bad{};bad.wrong_vin=true;const auto mismatch=capture(first,90000,bad);
    incomplete.stop=mismatch.row.error;incomplete.ecus={mismatch.row,{second,"NOT_TESTED",incomplete.stop,{}}};
    prefix={report(discovery),{{first.rx,mismatch.bytes}}};
    partial=check(cd::json::parse_report(report(incomplete)),prefix);
    assert(!partial.at("completed").boolean()&&partial.at("terminal_journals").unsigned_integer()==1);

    Directory directory;
    directory.write("discovery.json",inputs.discovery);
    directory.write(journal_name(first.rx),inputs.journals.at(first.rx));
    directory.write(journal_name(second.rx),inputs.journals.at(second.rx));
    auto read=history::load_network_journals(directory.path.c_str(),scan.selected);
    assert(read.discovery==inputs.discovery&&read.journals==inputs.journals);
    directory.write("foreign","x");
    rejects([&]{(void)history::load_network_journals(directory.path.c_str(),scan.selected);});
    assert(!unlink((directory.path+"/foreign").c_str()));
    assert(!chmod((directory.path+"/ecu-7E8.scj").c_str(),0644));
    rejects([&]{(void)history::load_network_journals(directory.path.c_str(),scan.selected);});
    assert(!chmod((directory.path+"/ecu-7E8.scj").c_str(),0600));
    assert(!chmod(directory.path.c_str(),0755));
    rejects([&]{(void)history::load_network_journals(directory.path.c_str(),scan.selected);});
    assert(!chmod(directory.path.c_str(),0700));
    assert(!unlink((directory.path+"/ecu-7E8.scj").c_str()));
    assert(!symlink("ecu-7E9.scj",(directory.path+"/ecu-7E8.scj").c_str()));
    rejects([&]{(void)history::load_network_journals(directory.path.c_str(),scan.selected);});
    // Creating an acquisition directory never resumes an existing campaign.
    rejects([&]{JournalDirectory existing(directory.path.c_str());});
    const auto new_path=directory.path+"/new";
    {
        JournalDirectory acquired(new_path.c_str());acquired.save_discovery(discovery);
        rejects([&]{acquired.save_discovery(discovery);});
        const auto no_exchange=acquired.scan(first,999,iface,vin,nullptr);
        assert(no_exchange.error=="BUDGET_EXHAUSTED"&&no_exchange.json.empty());
    }
    assert(!unlink((new_path+"/discovery.json").c_str()));
    assert(!rmdir(new_path.c_str()));
}
