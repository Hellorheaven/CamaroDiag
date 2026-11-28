#include "j1979_elm_can_campaign.hpp"
#include <algorithm>
#include <cstdio>
#include <cstdlib>
#include <memory>
#include <stdexcept>

namespace cd::elmcan {
namespace {
void require(bool b,const char* why){if(!b)throw std::invalid_argument(why);}
bool scope_equal(const Scope& a,const Scope& b){return a.profile==b.profile&&a.tx==b.tx&&a.rx==b.rx;}
bool visible(std::string_view s){
    return !s.empty()&&s.size()<=255&&std::all_of(s.begin(),s.end(),[](unsigned char c){return c>=32&&c<=126;});
}
struct Channel{
    virtual ~Channel()=default;
    virtual uint64_t clock()=0;
    virtual bool current()=0;
    virtual int exchange(Bytes,std::span<uint8_t>,size_t&,unsigned)=0;
    virtual bool wait(unsigned)=0;
    static uint64_t tick(void* p){return static_cast<Channel*>(p)->clock();}
    static bool active(void* p){return static_cast<Channel*>(p)->current();}
    static int query(void* p,const uint8_t* q,size_t n,uint8_t* r,size_t* count,unsigned timeout){
        return static_cast<Channel*>(p)->exchange({q,n},{r,*count},*count,timeout);
    }
    static bool delay(void* p,unsigned ms){return static_cast<Channel*>(p)->wait(ms);}
};
CampaignResult run(Channel& link,const CampaignPlan& p){
    require(p.valid(),"Invalid ELM campaign plan");
    j1979_can_plan plan{Channel::query,Channel::tick,Channel::active,&link,p.target.tx,p.target.rx,p.budget_ms,p.vin.c_str()};
    char* buffer=nullptr;size_t bytes=0;
    struct Stream{
        FILE* file{};
        char*& buffer;
        ~Stream(){if(file)fclose(file);std::free(buffer);}
    } stream{open_memstream(&buffer,&bytes),buffer};
    if(!stream.file)throw std::runtime_error("CAMPAIGN_REPORT_ALLOCATION_FAILED");
    bool serialized=false;
    CampaignResult result{};
    if(p.operation==TraceOperation::scan){
        struct Owner{j1979_can_result r{};~Owner(){j1979_can_result_free(&r);}} out;
        if(j1979_can_scan(&plan,&out.r)||!out.r.steps)throw std::runtime_error("SCAN_ENGINE_FAILED");
        result.completed=out.r.completed&&out.r.vin_valid&&out.r.final_vin_valid;
        result.stop=out.r.stop_reason;result.steps=out.r.step_count;
        serialized=j1979_can_write_json_transport(stream.file,&plan,&out.r,p.interface.c_str(),"ELM_SERIAL_CLASSIC_ISOTP");
    }else{
        j1979_live_plan live{};live.channel=plan;live.pid_count=static_cast<unsigned>(p.pids.size());
        live.cycles=p.cycles;live.interval_ms=p.interval_ms;live.wait=Channel::delay;
        std::copy(p.pids.begin(),p.pids.end(),live.pids);
        struct Owner{j1979_live_result r{};~Owner(){j1979_live_free(&r);}} out;
        if(j1979_live_record(&live,&out.r)||!out.r.steps)throw std::runtime_error("LIVE_ENGINE_FAILED");
        result.completed=out.r.completed&&out.r.initial_vin_valid&&out.r.final_vin_valid;
        result.stop=out.r.stop_reason;result.steps=out.r.step_count;
        serialized=j1979_live_write_json_transport(stream.file,&live,&out.r,p.interface.c_str(),"ELM_SERIAL_CLASSIC_ISOTP");
    }
    const bool closed=fclose(stream.file)==0;stream.file=nullptr;
    if(!serialized||!closed||!buffer||!bytes||bytes>64*1024*1024)
        throw std::runtime_error("CAMPAIGN_REPORT_SERIALIZATION_FAILED");
    result.native_report.assign(buffer,bytes);return result;
}
class Recording final:public Channel{
    Session& session_;
    const legacy::Clock& time_;
    const Wait& wait_;
    std::vector<Event>& events_;
    uint64_t origin_{},last_{};
    void add(EventKind k,uint32_t value,uint32_t aux=0){
        if(events_.size()>=campaign_max_events)throw legacy::TransportError("CAMPAIGN_TIMELINE_LIMIT");
        events_.push_back({k,value,aux});
    }
public:
    Recording(Session& session,const legacy::Clock& time,const Wait& wait,std::vector<Event>& events)
        :session_(session),time_(time),wait_(wait),events_(events){}
    uint64_t clock()override{
        const auto n=time_();
        require(n&&n>=last_,"Campaign monotonic clock unavailable");
        if(!origin_)origin_=n;
        last_=n;
        require(n-origin_<3600000,"Campaign clock limit");
        const auto relative=static_cast<uint32_t>(n-origin_+1);
        add(EventKind::clock,relative);return relative;
    }
    bool current()override{const bool active=session_.current();add(EventKind::current,active?1:0);return active;}
    int exchange(Bytes q,std::span<uint8_t> r,size_t& size,unsigned timeout)override{
        const auto before=session_.evidence().size();
        // Reserve a timeline record before dispatch, so a recording limit
        // cannot cause an additional untracked vehicle request.
        add(EventKind::exchange,static_cast<uint32_t>(before),timeout);
        const int status=session_.exchange(q,r,size,timeout);
        if(session_.evidence().size()!=before+1||session_.evidence().back().status!=status)
            throw legacy::TransportError("UNRECORDED_CAMPAIGN_EXCHANGE");
        return status;
    }
    bool wait(unsigned ms)override{
        require(ms>=1&&ms<=100,"Invalid campaign wait");
        if(events_.size()>=campaign_max_events)throw legacy::TransportError("CAMPAIGN_TIMELINE_LIMIT");
        const bool ok=wait_(ms);add(EventKind::wait,ms,ok?1:0);return ok;
    }
};
class Replaying final:public Channel{
    const CampaignCapture& capture_;
    size_t event_{},row_;
    uint32_t time_{},minimum_{};
    bool revoked_{},transport_fault_{};
    Event next(EventKind expected){
        require(event_<capture_.events.size()&&capture_.events[event_].kind==expected,"Campaign callback sequence mismatch");
        return capture_.events[event_++];
    }
public:
    Replaying(const CampaignCapture& capture,size_t setup):capture_(capture),row_(setup){}
    uint64_t clock()override{
        auto e=next(EventKind::clock);
        require(!e.aux&&e.value>=time_&&e.value>=minimum_&&e.value<=3600000&&e.value&&
            (time_||e.value==1),"Invalid campaign clock transcript");
        time_=e.value;return time_;
    }
    bool current()override{
        auto e=next(EventKind::current);
        require(!e.aux&&e.value<=1&&(!e.value||(!revoked_&&!transport_fault_)),"Invalid campaign context transcript");
        if(!e.value)revoked_=true;
        return e.value!=0;
    }
    int exchange(Bytes q,std::span<uint8_t> r,size_t& size,unsigned timeout)override{
        const auto e=next(EventKind::exchange);
        require(!revoked_&&!transport_fault_&&e.value==row_&&e.aux==timeout&&row_<capture_.trace.rows.size(),
            "Campaign exchange order or deadline mismatch");
        const auto& row=capture_.trace.rows[row_++];
        require(std::equal(q.begin(),q.end(),row.request.begin(),row.request.end())&&row.timeout_ms==timeout&&
            row.response.size()<=std::min(size,r.size()),"Campaign application evidence mismatch");
        minimum_=time_+row.duration_ms;
        size=row.response.size();std::copy(row.response.begin(),row.response.end(),r.begin());
        if(row.status<0)transport_fault_=true;
        return row.status;
    }
    bool wait(unsigned ms)override{
        const auto e=next(EventKind::wait);
        require(!revoked_&&!transport_fault_&&ms>=1&&ms<=100&&e.value==ms&&e.aux<=1,"Invalid campaign wait transcript");
        if(e.aux)minimum_=time_+ms;
        else revoked_=true;
        return e.aux!=0;
    }
    void complete()const{
        require(event_==capture_.events.size()&&row_==capture_.trace.rows.size(),"Extra campaign timeline or adapter rows");
    }
};
}
bool CampaignPlan::valid()const{
    if(!target.valid()||vin.size()!=17||!j1979_can_valid_vin(vin.c_str())||!visible(interface)||
        budget_ms<1000||budget_ms>600000)return false;
    if(operation==TraceOperation::scan)return !cycles&&!interval_ms&&pids.empty();
    if(operation!=TraceOperation::live||cycles<1||cycles>1000||interval_ms<100||interval_ms>60000||
        pids.empty()||pids.size()>J1979_LIVE_MAX_PIDS||pids.size()*cycles>J1979_LIVE_MAX_SAMPLES)return false;
    std::array<bool,256> seen{};
    for(auto pid:pids){if(!pid||pid%32==0||seen[pid])return false;seen[pid]=true;}
    return true;
}
CampaignResult record_campaign(Session& session,const CampaignPlan& plan,legacy::Clock clock,Wait wait,CampaignCapture& capture){
    require(plan.valid()&&clock&&wait&&session.current()&&scope_equal(session.scope(),plan.target)&&session.evidence().size()==15,
        "Fresh configured ELM campaign required");
    capture={};capture.plan=plan;
    Recording recording(session,clock,wait,capture.events);
    auto result=run(recording,plan);
    capture.trace={plan.target,plan.vin,plan.operation,session.evidence()};
    const auto replay=replay_campaign(capture);
    require(result.native_report==replay.native_report&&result.stop==replay.stop&&result.completed==replay.completed,
        "Live and replayed campaign differ");
    return result;
}
CampaignResult replay_campaign(const CampaignCapture& capture){
    const auto& p=capture.plan;
    require(p.valid()&&scope_equal(p.target,capture.trace.target)&&p.vin==capture.trace.vin&&
        p.operation==capture.trace.operation&&!capture.events.empty()&&capture.events.size()<=campaign_max_events,
        "Campaign and adapter scope differ");
    const auto adapter=replay_trace(capture.trace);
    require(adapter.configured&&adapter.setup_rows==15,"ELM configuration not verified");
    Replaying replay(capture,adapter.setup_rows);
    const auto result=run(replay,p);
    replay.complete();return result;
}
}
