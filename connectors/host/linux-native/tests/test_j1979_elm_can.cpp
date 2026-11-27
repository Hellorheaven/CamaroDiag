#include "j1979_elm_can.hpp"
#include <algorithm>
#include <cassert>
#include <deque>
#include <utility>
using namespace cd::elmcan;
namespace {
template<class F>void rejects(F f){bool caught=false;try{f();}catch(const std::exception&){caught=true;}assert(caught);}
void decoding(){
    const Scope standard{6,0x7e0,0x7e8};
    const Scope extended{7,0x18da10f1,0x18daf110};
    for(auto scope:{standard,extended}){
        const std::string header=scope.extended()?"18 DA F1 10 ":"7E8 ";
        const std::string other=scope.extended()?"18DAF120 ":"7E9 ";
        auto read=decode(header+"04 41 0C 1F 40 CC CC CC\r>",scope,Data{1,12});
        assert(read.payload==Data({0x41,12,0x1f,0x40})&&read.selected_frames==1);
        auto pending=decode(header+"03 7F 01 78\r"+other+"04 41 0C 00 00\r"+
            header+"04 41 0C 1F 40\r",scope,Data{1,12});
        assert(pending.payload==read.payload&&pending.pending_frames==1&&pending.other_frames==1);
        auto segmented=decode(header+"10 14 49 02 01 31 47 31\r"+
            header+"21 46 41 31 52 58 58 52\r"+
            header+"22 30 31 32 33 34 35 36\r",scope,Data{9,2});
        assert(segmented.payload.size()==20&&segmented.selected_frames==3);
        assert(std::string(segmented.payload.begin()+3,segmented.payload.end())=="1G1FA1RXXR0123456");
        assert(decode("NO DATA\r>",scope,Data{1,12}).status==DecodeStatus::no_data);
        assert(decode("02010C\rNO DATA",scope,Data{1,12}).status==DecodeStatus::no_data);
        assert(decode(header+"03 7F 01 12",scope,Data{1,12}).payload==Data({0x7f,1,0x12}));
        for(const auto& raw:std::vector<std::string>{
            header+"04 41 0C 1F",header+"04 41 0D 1F 40",header+"03 7F 01 78",
            header+"04 41 0C 1F 40\r"+header+"04 41 0C 1F 40",
            header+"10 14 49 02 01 31 47 31\r"+header+"22 46 41 31 52 58 58 52",
            other+"04 41 0C 1F 40","CAN ERROR","NO DATA\r"+header+"04 41 0C 1F 40",
            header+"04 41 0C 1F 40\r>\rCAN ERROR",header+"00",
            header+"04 41 0C 1F ZZ",header+"04 41 0C 1F 40 CC CC CC CC"}){
            rejects([&]{decode(raw,scope,Data{1,12});});
        }
    }
    assert(fixed_profile("ATDPN\r6\r>")==6);
    assert(!fixed_profile("A6")&&!fixed_profile("6\r7")&&!fixed_profile("CAN ERROR"));
    assert((!Scope{6,0x7df,0x7e8}.valid()));
    assert((!Scope{7,0x7e0,0x7e8}.valid()));
    assert((!Scope{6,0x7e0,0x7e9}.valid()));
}
struct Fake:cd::legacy::Link{
    std::deque<std::pair<std::string,std::string>> rows;
    unsigned writes{};
    void add(std::string command,std::string response){rows.emplace_back(std::move(command),std::move(response));}
    void setup(unsigned profile,bool valid=true){
        add("ATZ","ELM327");
        for(const auto* q:{"ATE0","ATL0","ATH1","ATS1","ATD0"})add(q,"OK");
        add("ATSP"+std::to_string(profile),"OK");add("ATCAF0","OK");
        const bool ext=profile==7||profile==9;
        add(ext?"ATSH18DA10F1":"ATSH7E0","OK");
        add(ext?"ATCRA18DAF110":"ATCRA7E8","OK");
        add(ext?"ATFCSH18DA10F1":"ATFCSH7E0","OK");
        for(const auto* q:{"ATFCSD300000","ATFCSM1","ATCFC1"})add(q,"OK");
        add("ATDPN",valid?std::to_string(profile):"A"+std::to_string(profile));
    }
    std::string command(std::string_view q,unsigned timeout)override{
        assert(timeout>0&&timeout<=4000&&rows.size()&&rows.front().first==q);
        ++writes;auto result=std::move(rows.front().second);rows.pop_front();return result;
    }
};
void sessions(){
    for(unsigned profile=6;profile<=9;++profile){
        const bool ext=profile==7||profile==9;
        Scope scope{profile,ext?0x18da10f1u:0x7e0u,ext?0x18daf110u:0x7e8u};
        Fake fake;fake.setup(profile);
        const std::string header=ext?"18 DA F1 10 ":"7E8 ";
        fake.add("02010C",header+"04 41 0C 1F 40");
        fake.add("0103","NO DATA");
        fake.add("02010D",header+"03 41 0E 00");
        bool current=true;Session session(fake,scope,[&]{return current;});
        session.configure();assert(session.current());
        rejects([&]{session.configure();});
        std::array<uint8_t,512> data{};size_t size=data.size();
        auto before=fake.writes;
        assert(session.exchange(Data{4},data,size,1000)==-1&&fake.writes==before);
        size=data.size();assert(session.exchange(Data{8,1},data,size,1000)==-1&&fake.writes==before);
        size=data.size();assert(session.exchange(Data{1,12},data,size,1000)==0&&size==4&&data[2]==0x1f);
        size=data.size();assert(session.exchange(Data{3},data,size,1000)==1&&size==0);
        size=data.size();assert(session.exchange(Data{1,13},data,size,1000)==-1&&size==0&&!session.current());
        assert(fake.rows.empty());
        before=fake.writes;size=data.size();assert(session.exchange(Data{1,12},data,size,1000)==-1&&fake.writes==before);
        const auto json=report(scope,"1G1FA1RXXR0123456","SCAN","{}",session.evidence(),"fault",false);
        assert(json.find("\"transport\":\"ELM_SERIAL_CLASSIC_ISOTP\"")!=std::string::npos);
        assert(json.find("\"permits_programming\":false")!=std::string::npos);
        Fake wrong;wrong.setup(profile,false);Session refused(wrong,scope,[]{return true;});
        rejects([&]{refused.configure();});assert(!refused.current());
    }
}
}
int main(){decoding();sessions();}
