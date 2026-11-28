#include "j1979_elm_can_journal.hpp"
#include <cassert>
#include <cstdlib>
#include <fcntl.h>
#include <fstream>
#include <iterator>
#include <unistd.h>
using namespace cd::elmcan;
namespace {
constexpr std::string_view vin="1G1FA1RXXR0123456";
template<class F>void rejects(F f){bool rejected=false;try{f();}catch(const std::exception&){rejected=true;}assert(rejected);}
struct Temp{
    char path[48]="/tmp/camarodiag-ejr-XXXXXX";
    int fd{mkstemp(path)};
    Temp(){assert(fd>=0);}
    ~Temp(){close(fd);unlink(path);}
    Data bytes()const{
        std::ifstream input(path,std::ios::binary);assert(input);
        return {std::istreambuf_iterator<char>(input),std::istreambuf_iterator<char>()};
    }
};
struct Fake:cd::legacy::Link{
    unsigned calls{};
    std::string command(std::string_view q,unsigned)override{
        ++calls;
        if(q=="ATZ")return "ELM327";
        if(q=="ATDPN")return "6";
        if(q.starts_with("AT"))return "OK";
        if(q=="02010C")return "7E8 04 41 0C 1F 40";
        if(q=="0103")return "NO DATA";
        throw std::runtime_error("unexpected request");
    }
};
void roundtrip(){
    Temp file;Scope target{6,0x7e0,0x7e8};
    EvidenceJournal journal(file.fd,target,vin,TraceOperation::scan);
    const auto header=file.bytes();assert(header.size()==44);
    auto initial=inspect_journal(header,true);
    assert(!initial.terminal&&!initial.torn&&initial.trace.rows.empty());
    Fake fake;
    Session session(fake,target,[]{return true;},[&](const Observation& row){journal.append(row);});
    session.configure();
    auto setup=inspect_journal(file.bytes(),true);
    assert(setup.replay.configured&&setup.replay.setup_rows==15&&!setup.terminal);
    std::array<uint8_t,64> response{};size_t size=response.size();
    assert(session.exchange(Data{1,12},response,size,1000)==0&&size==4);
    size=response.size();assert(session.exchange(Data{3},response,size,1000)==1&&size==0);
    const auto prefix=file.bytes();
    auto inspected=inspect_journal(prefix,true);
    assert(inspected.replay.positive==1&&inspected.replay.no_data==1&&!inspected.terminal);
    rejects([&]{recover_trace(prefix);});
    journal.finish();
    const auto closed=file.bytes();
    const Trace trace{target,std::string(vin),TraceOperation::scan,session.evidence()};
    assert(recover_trace(closed)==encode_trace(trace));
    auto verified=inspect_journal(closed,false);
    assert(verified.terminal&&!verified.torn&&verified.verified_bytes==closed.size());
    for(size_t i=header.size();i<closed.size();++i){
        auto view=Bytes(closed).first(i);
        auto partial=inspect_journal(view,true);
        assert(!partial.terminal&&partial.verified_bytes<=i);
        rejects([&]{recover_trace(view);});
    }
    auto damaged=closed;damaged[70]^=1;rejects([&]{inspect_journal(damaged,true);});
    damaged=closed;damaged.back()^=1;rejects([&]{inspect_journal(damaged,true);});
    damaged=closed;damaged.push_back(0);rejects([&]{inspect_journal(damaged,true);});
    rejects([&]{journal.append(session.evidence().back());});
    rejects([&]{EvidenceJournal again(file.fd,target,vin,TraceOperation::scan);});
}
void sink_failure(){
    Fake fake;unsigned observed=0;
    Session session(fake,{6,0x7e0,0x7e8},[]{return true;},[&](const Observation&){
        if(++observed==16)throw cd::legacy::TransportError("DISK_FULL");
    });
    session.configure();
    std::array<uint8_t,64> response{};size_t size=response.size();
    assert(session.exchange(Data{1,12},response,size,1000)==-1&&size==0&&!session.current());
    const auto calls=fake.calls;size=response.size();
    assert(session.exchange(Data{3},response,size,1000)==-1&&fake.calls==calls);
    Fake setup_fail;
    Session retired(setup_fail,{6,0x7e0,0x7e8},[]{return true;},[](const Observation&){
        throw cd::legacy::TransportError("SYNC_FAILED");
    });
    rejects([&]{retired.configure();});assert(setup_fail.calls==1&&!retired.current());
}
}
int main(){roundtrip();sink_failure();}
