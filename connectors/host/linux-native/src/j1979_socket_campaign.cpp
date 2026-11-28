#include "j1979_socket_campaign.hpp"
#include "j1979_native_report_detail.hpp"
#include <algorithm>
#include <array>
#include <cstdlib>
#include <memory>
#include <stdexcept>

namespace cd::socketcampaign {
namespace {
using network::history::detail::require;
using json::Value;
struct PrefixEnd {};
struct Channel {
    virtual ~Channel()=default;
    virtual std::uint64_t clock()=0;
    virtual bool current()=0;
    virtual int exchange(Bytes,std::span<std::uint8_t>,std::size_t&,unsigned)=0;
    virtual bool wait(unsigned)=0;
    static std::uint64_t tick(void* p){return static_cast<Channel*>(p)->clock();}
    static bool active(void* p){return static_cast<Channel*>(p)->current();}
    static int query(void* p,const std::uint8_t* q,std::size_t n,std::uint8_t* r,std::size_t* count,unsigned timeout){
        return static_cast<Channel*>(p)->exchange({q,n},{r,*count},*count,timeout);
    }
    static bool delay(void* p,unsigned ms){return static_cast<Channel*>(p)->wait(ms);}
};
struct ScanOwner {j1979_can_result data{};~ScanOwner(){j1979_can_result_free(&data);}};
struct LiveOwner {j1979_live_result data{};~LiveOwner(){j1979_live_free(&data);}};
Result run(const Plan& p,Channel& c){
    require(p.valid(),"Invalid SCJ plan");
    j1979_can_plan plan{Channel::query,Channel::tick,Channel::active,&c,p.tx,p.rx,p.budget_ms,p.vin.c_str()};
    char* buffer=nullptr;std::size_t size=0;
    struct Stream{
        FILE* file;
        char*& buffer;
        ~Stream(){if(file)fclose(file);std::free(buffer);}
    } stream{open_memstream(&buffer,&size),buffer};
    if(!stream.file)throw std::runtime_error("SCJ report allocation failed");
    Result out{};
    bool saved=false;
    if(p.operation==Operation::scan){
        ScanOwner r;
        if(j1979_can_scan(&plan,&r.data)||!r.data.steps)throw std::runtime_error("SCJ scan failed");
        out.completed=r.data.completed&&r.data.vin_valid&&r.data.final_vin_valid;
        out.stop=r.data.stop_reason;
        out.exchanges=static_cast<unsigned>(r.data.step_count);
        saved=j1979_can_write_json(stream.file,&plan,&r.data,p.interface.c_str());
    }else{
        j1979_live_plan live{};
        live.channel=plan;live.pid_count=static_cast<unsigned>(p.pids.size());
        live.cycles=p.cycles;live.interval_ms=p.interval_ms;live.wait=Channel::delay;
        std::copy(p.pids.begin(),p.pids.end(),live.pids);
        LiveOwner r;
        if(j1979_live_record(&live,&r.data)||!r.data.steps)throw std::runtime_error("SCJ live failed");
        out.completed=r.data.completed&&r.data.initial_vin_valid&&r.data.final_vin_valid;
        out.stop=r.data.stop_reason;
        out.exchanges=static_cast<unsigned>(r.data.step_count);
        saved=j1979_live_write_json(stream.file,&live,&r.data,p.interface.c_str());
    }
    const bool closed=fclose(stream.file)==0;stream.file=nullptr;
    if(!saved||!closed||!buffer||!size||size>max_report)throw std::runtime_error("SCJ serialization failed");
    out.report.assign(buffer,size);
    return out;
}
class Recording final:public Channel {
    const Link& link_;
    Writer& writer_;
    std::vector<Event> pending_;
    std::uint64_t origin_{},last_{};
    std::size_t event_count_{};
    bool revoked_{};
    unsigned dispatch_calls_{},response_records_{};
    void buffer(Event e){
        if(event_count_>=max_events-1)throw std::runtime_error("SCJ callback limit");
        ++event_count_;pending_.push_back(std::move(e));
    }
    void flush(){
        writer_.append_batch(pending_);
        pending_.clear();
    }
public:
    Recording(const Link& link,Writer& w):link_(link),writer_(w){
        require(link.exchange&&link.current&&link.clock&&link.wait,"SCJ link callbacks required");
    }
    std::uint64_t clock()override{
        const auto n=link_.clock(link_.context);
        require(n&&n>=last_,"SCJ clock unavailable or regressed");
        if(!origin_)origin_=n;
        last_=n;
        require(n-origin_<=3600000,"SCJ campaign clock bound");
        const auto relative=n-origin_+1;
        buffer({Kind::clock,relative,0,{}});
        return relative;
    }
    bool current()override{
        const bool active=!revoked_&&link_.current(link_.context);
        if(!active)revoked_=true;
        buffer({Kind::current,active?1u:0u,0,{}});
        return active;
    }
    int exchange(Bytes q,std::span<std::uint8_t> response,std::size_t& size,unsigned timeout)override{
        require(!revoked_&&timeout&&timeout<=2500&&j1979_can_allowed_read(q.data(),q.size()),
            "SCJ rejected unsafe or retired exchange");
        // Leave room for the reply before dispatch. If any journal write
        // fails, throw out of the engine rather than send an untracked read.
        require(event_count_+2<max_events,"SCJ exchange event limit");
        buffer({Kind::intent,timeout,0,Data(q.begin(),q.end())});
        flush();
        // Revalidate physical context after synchronous persistence; if
        // withdrawn, record the failed attempt without dispatching a read.
        int rc=-1;
        unsigned effective=0;
        const auto now=link_.clock(link_.context);
        // The engine's last recorded clock precedes intent persistence. Do
        // not give the transport a new full timeout after fsync/readback.
        if(!now||now<last_)throw std::runtime_error("SCJ dispatch clock regressed");
        const auto elapsed=now-last_;
        if(link_.current(link_.context)){
            if(elapsed<timeout){
                effective=timeout-static_cast<unsigned>(elapsed);
                ++dispatch_calls_;
                rc=link_.exchange(link_.context,q.data(),q.size(),response.data(),&size,effective);
            }else size=0;
        }else{revoked_=true;size=0;}
        Event result{Kind::response,2,effective,{}};
        if(rc==0){
            if(size>J1979_CAN_MAX_PAYLOAD||size>response.size()){
                result.value=3;size=J1979_CAN_MAX_PAYLOAD+1;
            }else{
                result.value=0;result.bytes.assign(response.begin(),response.begin()+size);
            }
        }else{
            size=0;
            if(rc==1)result.value=1;
            else rc=-1;
        }
        buffer(std::move(result));flush();++response_records_;
        return rc;
    }
    bool wait(unsigned ms)override{
        require(!revoked_&&ms>=1&&ms<=100,"SCJ wait invalid");
        const bool ok=link_.wait(link_.context,ms);
        if(!ok)revoked_=true;
        buffer({Kind::wait,ms,ok?1u:0u,{}});
        return ok;
    }
    void drain(){flush();}
    unsigned dispatch_calls()const{return dispatch_calls_;}
    unsigned response_records()const{return response_records_;}
};
class Replaying final:public Channel{
    const Journal& j_;
    std::size_t cursor_{};
    std::uint64_t clock_{};
    bool revoked_{},pending_{};
    unsigned exchanges_{},dispatch_calls_{},response_records_{};
    const Event& next(Kind kind){
        if(cursor_==j_.events.size()){
            require(!j_.terminal,"Missing SCJ callback before terminal");
            throw PrefixEnd{};
        }
        const auto& e=j_.events[cursor_++];
        require(e.kind==kind,"SCJ callback sequence mismatch");
        return e;
    }
public:
    explicit Replaying(const Journal& j):j_(j){}
    std::uint64_t clock()override{
        const auto& e=next(Kind::clock);
        require(e.value&&e.value>=clock_&&(clock_||e.value==1),"SCJ clock transcript invalid");
        clock_=e.value;return clock_;
    }
    bool current()override{
        const auto& e=next(Kind::current);
        require(!e.value||!revoked_,"SCJ retired context restored");
        if(!e.value)revoked_=true;
        return e.value!=0;
    }
    int exchange(Bytes q,std::span<std::uint8_t> r,std::size_t& n,unsigned timeout)override{
        const auto& intent=next(Kind::intent);
        require(!revoked_&&clock_&&intent.value==timeout&&std::equal(q.begin(),q.end(),intent.bytes.begin(),intent.bytes.end()),
            "SCJ exchange scope or deadline differs from engine");
        pending_=true;++exchanges_;
        const auto& e=next(Kind::response);
        pending_=false;++response_records_;
        require(e.aux<=timeout,"SCJ transport timeout exceeds engine request");
        if(e.aux)++dispatch_calls_;
        if(e.value==0){
            require(e.bytes.size()<=n&&e.bytes.size()<=r.size(),"SCJ response exceeds replay buffer");
            std::copy(e.bytes.begin(),e.bytes.end(),r.begin());n=e.bytes.size();return 0;
        }
        if(e.value==3){n=J1979_CAN_MAX_PAYLOAD+1;return 0;}
        n=0;return e.value==1?1:-1;
    }
    bool wait(unsigned ms)override{
        const auto& e=next(Kind::wait);
        require(!revoked_&&e.value==ms,"SCJ wait differs from engine");
        if(!e.aux)revoked_=true;
        return e.aux!=0;
    }
    bool pending()const{return pending_;}
    unsigned exchanges()const{return exchanges_;}
    unsigned dispatch_calls()const{return dispatch_calls_;}
    unsigned response_records()const{return response_records_;}
    void verify_terminal(const Result& r){
        const auto& e=next(Kind::terminal);
        const auto expected=network::history::detail::bytes(Value{sha256(std::string_view(r.report))},32);
        require(e.bytes==expected&&e.value==static_cast<unsigned>(r.completed)&&cursor_==j_.events.size(),
            "SCJ terminal report digest, completion or sequence differs");
    }
    void verify_exhausted()const{require(cursor_==j_.events.size(),"Extra SCJ callbacks after campaign completion");}
};
Value text(std::string_view s){return Value{std::string(s)};}
Value num(std::size_t n){return Value{json::Integer{std::to_string(n)}};}
Value flag(bool b){return Value{b};}
}
Result record(const Plan& p,const Link& link,Writer& w){
    Recording c(link,w);
    auto out=run(p,c);
    c.drain();out.dispatch_calls=c.dispatch_calls();out.response_records=c.response_records();
    return out;
}
void finish(Writer& writer,const Result& r){
    require(!r.report.empty()&&r.report.size()<=max_report&&!r.prefix,"SCJ terminal requires complete engine result");
    const auto digest=network::history::detail::bytes(Value{sha256(std::string_view(r.report))},32);
    writer.append({Kind::terminal,r.completed?1u:0u,0,digest});
}
Result replay(const Journal& j){
    require(j.plan.valid()&&j.events.size()<=max_events,"Invalid SCJ replay scope");
    Replaying c(j);
    try{
        auto out=run(j.plan,c);
        if(j.terminal)c.verify_terminal(out);
        else{
            c.verify_exhausted();
            // Without a durable terminal, even an engine that ran to its
            // final callback does not establish a published report.
            out.report.clear();out.completed=false;out.prefix=true;out.stop="TERMINAL_NOT_PERSISTED";
        }
        require(out.exchanges==c.exchanges(),"SCJ replay exchange accounting differs");
        out.dispatch_calls=c.dispatch_calls();out.response_records=c.response_records();
        return out;
    }catch(const PrefixEnd&){
        require(!j.terminal,"Terminal cannot establish an interrupted campaign");
        Result out{};out.prefix=true;out.pending_exchange=c.pending();out.exchanges=c.exchanges();
        out.dispatch_calls=c.dispatch_calls();out.response_records=c.response_records();
        out.stop=out.pending_exchange?"EXCHANGE_RESULT_NOT_PERSISTED":"CAMPAIGN_PREFIX";
        return out;
    }
}
json::Value proof(const Journal& j,const Result& r,std::string_view journal_digest){
    require(journal_digest.size()==64&&std::all_of(journal_digest.begin(),journal_digest.end(),[](char c){
        return (c>='0'&&c<='9')||(c>='a'&&c<='f');}),"Invalid SCJ source digest");
    return Value{Value::Object{{"schema",text("camarodiag-j1979-socketcan-journal-inspection-v1")},
        {"journal_sha256",text(journal_digest)},{"operation",text(j.plan.operation==Operation::scan?"scan":"live")},
        {"vin",text(j.plan.vin)},{"tx",num(j.plan.tx)},{"rx",num(j.plan.rx)},{"interface",text(j.plan.interface)},
        {"terminal",flag(j.terminal)},{"torn_tail",flag(j.torn)},{"verified_bytes",num(j.verified_bytes)},
        {"total_bytes",num(j.total_bytes)},{"callback_count",num(j.events.size())},{"exchanges",num(r.exchanges)},
        {"transport_callback_calls_with_recorded_result",num(r.dispatch_calls)},{"response_records",num(r.response_records)},
        {"pending_exchange",flag(r.pending_exchange)},{"prefix",flag(r.prefix)},{"completed",flag(r.completed)},
        {"stop_reason",r.stop.empty()?Value{}:text(r.stop)},
        {"report_sha256",r.report.empty()?Value{}:text(sha256(std::string_view(r.report)))},
        {"replay_engine",text("SAME_NATIVE_ENGINE_CALLBACK_REPLAY")},
        {"physical_vehicle_verified",flag(false)},{"authenticated",flag(false)},{"permits_programming",flag(false)},
        {"permits_automatic_retry",flag(false)}}};
}
}
