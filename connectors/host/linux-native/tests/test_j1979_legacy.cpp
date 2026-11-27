#include "j1979_discovery.hpp"
#include <cassert>
#include <deque>
#include <utility>

using namespace cd::legacy;
namespace {
std::string wire(Protocol p,unsigned src,Data payload){
    Data bytes;
    if(p==Protocol::kwp_slow||p==Protocol::kwp_fast)
        bytes={static_cast<std::uint8_t>(0x80|payload.size()),0xf1,static_cast<std::uint8_t>(src)};
    else bytes={static_cast<std::uint8_t>(p==Protocol::pwm?0x41:0x48),0x6b,static_cast<std::uint8_t>(src)};
    bytes.insert(bytes.end(),payload.begin(),payload.end());
    bytes.push_back(checksum(p,bytes));
    return hex(bytes)+"\r";
}
template<class F> void rejected(F f){bool failed=false;try{f();}catch(const std::exception&){failed=true;}assert(failed);}
struct Fake:Link{
    std::deque<std::pair<std::string,std::string>> replies;
    std::vector<std::string> sent;
    std::string command(std::string_view request,unsigned timeout)override{
        assert(timeout>0&&timeout<=4000);
        sent.emplace_back(request);
        assert(!replies.empty()&&replies.front().first==request);
        auto response=std::move(replies.front().second);replies.pop_front();
        return response;
    }
    void config(Protocol p){
        replies.emplace_back("ATZ","ELM327");
        for(const char* cmd:{"ATE0","ATL0","ATS1","ATH1"})replies.emplace_back(cmd,"OK");
        replies.emplace_back("ATSP"+std::to_string(static_cast<unsigned>(p)),"OK");
        replies.emplace_back(p==Protocol::pwm?"ATSH616AF1":
            p==Protocol::kwp_slow||p==Protocol::kwp_fast?"ATSHC033F1":"ATSH686AF1","OK");
    }
};
}
int main(){
    // Standard CRC-8/SAE-J1850 check vector.
    const Data digits{'1','2','3','4','5','6','7','8','9'};
    assert(checksum(Protocol::pwm,digits)==0x4b);
    assert(checksum(Protocol::vpw,digits)==0x4b);
    assert(checksum(Protocol::iso9141,Data{0x48,0x6b,0x10})==0xc3);
    for(unsigned n=1;n<=5;++n){
        const auto protocol=static_cast<Protocol>(n);
        auto first=wire(protocol,0x10,{0x41,0,0x80,0,0,1});
        auto second=wire(protocol,0x20,{0x41,0,0,0,0,2});
        const auto negative=wire(protocol,0x30,{0x7f,1,0x11});
        auto observations=frames(protocol,"0100","0100\rSEARCHING...\r"+first+second+negative+">");
        assert(observations.size()==3);
        auto sources=classify(observations);
        assert(sources.size()==3&&sources[0].source==0x10);
        assert(sources[0].status=="PASSED"&&sources[0].supported==std::vector<unsigned>{1}&&sources[0].next==true);
        assert(sources[1].supported==std::vector<unsigned>{31}&&sources[1].next==false);
        assert(sources[2].status=="NOT_TESTED"&&sources[2].detail=="NRC_11");
        const auto duplicate=classify(frames(protocol,"0100",first+first));
        assert(duplicate.size()==1&&duplicate[0].status=="FAILED");
        const auto corrupt=first.substr(0,first.size()-3)+"00\r";
        if(corrupt!=first)rejected([&]{frames(protocol,"0100",corrupt);});
        rejected([&]{frames(protocol,"0100",wire(protocol,0xf1,{0x41,0,0,0,0,0}));});
        rejected([&]{frames(protocol,"0100","410000000000");});
        const auto report=replay_discovery(protocol,first+second,std::to_string(n));
        assert(report.complete&&report.responders.size()==2);
        assert(discovery_report(report).find("\"automatic_selection\":false")!=std::string::npos);
        assert(!replay_discovery(protocol,first,"A"+std::to_string(n)).complete);
        assert(!replay_discovery(protocol,negative,std::to_string(n)).complete);
        assert(!replay_discovery(protocol,"NO DATA",std::to_string(n)).complete);
        Fake link;link.config(protocol);
        link.replies.emplace_back("0100",first);
        link.replies.emplace_back("ATDPN",std::to_string(n));
        bool current=true;
        Elm elm(link,protocol,true,[&]{return current;});
        elm.configure();
        std::uint64_t tick=100;
        const auto live=discover(elm,9000,[&]{return tick++;},[&]{return current;});
        assert(live.complete&&link.replies.empty());
        rejected([&]{elm.configure();});
        const auto count=link.sent.size();
        for(const auto& q:{Data{4},Data{8,1,0,0,0,0,0},Data{0x27,1},Data{0x34,0}})
            rejected([&]{elm.exchange(q,100);});
        assert(link.sent.size()==count);
        current=false;
        rejected([&]{elm.exchange(Data{1,0},100);});
        assert(link.sent.size()==count);
    }
    for(const auto& probe:{"A1","1\r2","SEARCHING...\r1","1 2","NO DATA","6","0"})
        assert(!fixed_protocol(probe));
    assert(fixed_protocol("ATDPN\r 3 \r>")==Protocol::iso9141);
    assert(read_allowed(Data{5,1,0x80}));
    assert(!read_allowed(Data{5,1,3}));
    assert(read_allowed(Data{8,0,0,0,0,0,0}));
    assert(!read_allowed(Data{8,0,0,1,0,0,0}));
    assert(anonymous_records("0100","41 00 00 00 00 00").size()==1);
    rejected([]{anonymous_records("0100","410000000000\rERROR");});
    Fake wrong;wrong.config(Protocol::vpw);
    wrong.replies.emplace_back("0100",wire(Protocol::vpw,0x10,{0x41,0,0,0,0,0}));
    wrong.replies.emplace_back("ATDPN","3");
    Elm mismatch(wrong,Protocol::vpw,true,[]{return true;});mismatch.configure();
    const auto evidence=discover(mismatch,9000,[]{return std::uint64_t{1};},[]{return true;});
    assert(!evidence.complete&&!mismatch.ready());
    Fake timeout;timeout.config(Protocol::vpw);
    Elm expired(timeout,Protocol::vpw,true,[]{return true;});expired.configure();
    std::uint64_t clock=0;
    const auto budget=discover(expired,1,[&]{return clock++;},[]{return true;});
    assert(!budget.complete&&!budget.queried&&timeout.replies.empty());
    return 0;
}
