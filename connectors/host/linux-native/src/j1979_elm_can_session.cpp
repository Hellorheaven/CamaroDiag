#include "j1979_elm_can.hpp"
#include <algorithm>
#include <array>
#include <cstdio>
#include <utility>
namespace cd::elmcan {
namespace {
constexpr std::size_t max_rows=J1979_LIVE_MAX_STEPS*2+64,max_raw=32*1024*1024;
std::string id(uint32_t n,bool extended){
    char buf[9]{};
    std::snprintf(buf,sizeof(buf),extended?"%08X":"%03X",n);return buf;
}
bool acknowledged(std::string_view raw,std::string_view command){
    std::vector<std::string> tokens;
    size_t at=0;
    for(size_t n=0;n<=raw.size();++n){
        if(n<raw.size()&&raw[n]!='\r'&&raw[n]!='\n'&&raw[n]!='>')continue;
        auto line=raw.substr(at,n-at);at=n+1;
        while(!line.empty()&&(line.front()==' '||line.front()=='\t'))line.remove_prefix(1);
        while(!line.empty()&&(line.back()==' '||line.back()=='\t'))line.remove_suffix(1);
        if(line.empty())continue;
        std::string token(line);
        for(char& c:token)if(c>='a'&&c<='z')c=char(c-'a'+'A');
        if(token!=command)tokens.push_back(std::move(token));
    }
    return tokens==std::vector<std::string>{"OK"};
}
unsigned elapsed(std::uint64_t start){
    const auto end=legacy::monotonic_ms();
    if(!start||end<start||end-start>3600000)throw legacy::TransportError("ELM CAN clock invalid");
    return static_cast<unsigned>(end-start);
}
}
Session::Session(legacy::Link& link,Scope scope,legacy::Current current)
    :link_(link),scope_(scope),current_(std::move(current)){
    if(!scope_.valid()||!current_)throw std::invalid_argument("Explicit fixed CAN ELM profile and physical pair required");
}
bool Session::current()const{return configured_&&!retired_&&current_();}
void Session::record(Observation row){
    if(evidence_.size()>=max_rows||row.raw.size()>32768||row.raw.size()>max_raw-total_raw_){
        retired_=true;throw legacy::TransportError("ELM CAN evidence limit");
    }
    total_raw_+=row.raw.size();evidence_.push_back(std::move(row));
}
std::string Session::setup(std::string_view command,unsigned timeout){
    if(retired_||!current_()||evidence_.size()>=max_rows||max_raw-total_raw_<32768)
        throw legacy::TransportError("ELM CAN setup revoked or evidence limit");
    Observation row{};row.command=command;row.timeout_ms=timeout;
    const auto start=legacy::monotonic_ms();
    try{
        row.raw=link_.command(command,timeout);row.duration_ms=elapsed(start);
        if(row.duration_ms>=timeout||!current_())throw legacy::TransportError("ELM CAN setup context or deadline changed");
        row.status=0;
    }catch(const std::exception& e){
        row.error=e.what();retired_=true;record(std::move(row));throw;
    }
    auto result=row.raw;record(std::move(row));return result;
}
void Session::configure(){
    if(used_||!current_())throw legacy::TransportError("ELM CAN session is single use");
    used_=true;
    try{
        auto banner=setup("ATZ",4000);
        for(char& c:banner)if(c>='a'&&c<='z')c=char(c-'a'+'A');
        if(banner.find("ELM")==std::string::npos&&banner.find("STN")==std::string::npos&&banner.find("OBDLINK")==std::string::npos)
            throw legacy::TransportError("Unrecognized ELM CAN adapter");
        const auto tx=id(scope_.tx,scope_.extended()),rx=id(scope_.rx,scope_.extended());
        const std::vector<std::string> config{"ATE0","ATL0","ATH1","ATS1","ATD0",
            "ATSP"+std::to_string(scope_.profile),"ATCAF0","ATSH"+tx,"ATCRA"+rx,
            "ATFCSH"+tx,"ATFCSD300000","ATFCSM1","ATCFC1"};
        for(const auto& cmd:config)if(!acknowledged(setup(cmd,2000),cmd))
            throw legacy::TransportError("Adapter rejected physical CAN configuration");
        if(fixed_profile(setup("ATDPN",2000))!=scope_.profile)
            throw legacy::TransportError("Fixed CAN protocol not confirmed");
        configured_=true;
    }catch(...){retired_=true;throw;}
}
int Session::exchange(Bytes request,std::span<uint8_t> response,std::size_t& size,unsigned timeout){
    return exchange_admitted(request,response,size,timeout,j1979_can_allowed_read(request.data(),request.size()));
}
int Session::exchange_freeze(const J1979FreezeScope& plan,Bytes request,std::span<uint8_t> response,std::size_t& size,unsigned timeout){
    return exchange_admitted(request,response,size,timeout,plan.valid()&&plan.allows(request.data(),request.size()));
}
int Session::exchange_admitted(Bytes request,std::span<uint8_t> response,std::size_t& size,unsigned timeout,bool allowed,bool exclusive_response){
    const auto capacity=std::min(size,response.size());size=0;
    if(!current()||!allowed||request.empty()||request.size()>7||
        !capacity||timeout<1||timeout>2500||evidence_.size()>=max_rows||max_raw-total_raw_<32768)return -1;
    Observation row{};row.timeout_ms=timeout;row.request.assign(request.begin(),request.end());
    Data frame{static_cast<uint8_t>(request.size())};frame.insert(frame.end(),request.begin(),request.end());
    row.command=legacy::hex(frame);
    const auto start=legacy::monotonic_ms();
    int status=-1;
    try{
        row.raw=link_.command(row.command,timeout);row.duration_ms=elapsed(start);
        if(row.duration_ms>=timeout||!current())throw legacy::TransportError("ELM CAN exchange context or deadline changed");
        const auto decoded=decode(row.raw,scope_,request);
        if(exclusive_response&&decoded.other_frames)throw legacy::TransportError("Unexpected CAN responder in maintenance session");
        if(decoded.status==DecodeStatus::no_data)status=1;
        else{
            if(decoded.payload.size()>capacity)throw legacy::TransportError("ELM CAN output buffer too small");
            row.response=decoded.payload;
            std::copy(row.response.begin(),row.response.end(),response.begin());
            size=row.response.size();status=0;
        }
    }catch(const std::exception& e){row.error=e.what();retired_=true;size=0;}
    row.status=status;
    try{record(std::move(row));}catch(const std::exception&){retired_=true;size=0;return -1;}
    return status;
}
bool Session::current_callback(void* context){return context&&static_cast<Session*>(context)->current();}
int Session::exchange_callback(void* context,const uint8_t* q,size_t n,uint8_t* r,size_t* size,unsigned timeout){
    if(!context||!q||!r||!size)return -1;
    return static_cast<Session*>(context)->exchange({q,n},{r,*size},*size,timeout);
}
}
