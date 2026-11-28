#include "j1979_live.h"
#include "j1979_native_live_inspection.hpp"
#include <stdexcept>
#include <string>
#include <cstdlib>
#include <assert.h>
#include <stdio.h>
#include <string.h>

/* Entirely synthetic: no socket, vehicle, adapter or transport is opened. */
static const char *vin="1G1FA1RXXR0123456";
typedef struct {
    uint64_t clock;
    unsigned requests, vin_requests;
    bool bad_final_vin, negative_sample, timeout_sample;
    bool suppress_advertisement, retire_context, malformed_sample, oversized_sample;
    unsigned clock_reads{}, stall_at_read{}, stall_ms{};
    uint8_t emitted[64][2];
} fake;
static uint64_t clock_fake(void *ctx) {
    auto* f=static_cast<fake*>(ctx);
    if(++f->clock_reads==f->stall_at_read)f->clock+=f->stall_ms;
    return f->clock;
}
static bool current_fake(void *ctx) {
    auto *f = static_cast<fake *>(ctx);
    return !f->retire_context || f->requests<3;
}
static bool wait_fake(void *ctx,unsigned milliseconds) {
    auto *f = static_cast<fake *>(ctx);
    assert(milliseconds>0 && milliseconds<=100);
    f->clock+=milliseconds;
    return true;
}
static int exchange_fake(void *ctx,const uint8_t *request,size_t n,
                         uint8_t *reply,size_t *length,unsigned timeout) {
    auto *f = static_cast<fake *>(ctx);
    assert(n==2 && timeout>0 && timeout<=2500);
    assert(request[0]==1 || request[0]==9);
    assert(f->requests<sizeof(f->emitted)/sizeof(f->emitted[0]));
    memcpy(f->emitted[f->requests++],request,2);
    f->clock+=9;
    if (request[0]==9 && request[1]==2) {
        ++f->vin_requests;
        assert(*length>=20);
        reply[0]=0x49;reply[1]=2;reply[2]=1;
        memcpy(reply+3,f->bad_final_vin && f->vin_requests>1 ?
                   "2G1FA1RXXR0123456":vin,17);
        *length=20;
        return 0;
    }
    if (request[0]==1 && request[1]==0) {
        assert(*length>=6);
        const uint8_t bitmap[6]={0x41,0x00,0x00,0x18,0x00,0x00};
        memcpy(reply,bitmap,sizeof(bitmap));
        if (f->suppress_advertisement) reply[3]=0;
        *length=6;
        return 0;
    }
    if (f->timeout_sample && request[1]==0x0d) return 1;
    if (f->negative_sample && request[1]==0x0d) {
        const uint8_t nrc[3]={0x7f,1,0x11};
        assert(*length>=sizeof(nrc));
        memcpy(reply,nrc,sizeof(nrc));*length=sizeof(nrc);
        return 0;
    }
    if (request[0]==1 && request[1]==0x0c) {
        if (f->oversized_sample) { *length=4096; return 0; }
        const uint8_t rpm[4]={0x41,0x0c,0x1a,0xf8};
        assert(*length>=sizeof(rpm));
        memcpy(reply,rpm,sizeof(rpm));*length=f->malformed_sample?3:sizeof(rpm);
        return 0;
    }
    if (request[0]==1 && request[1]==0x0d) {
        const uint8_t speed[3]={0x41,0x0d,0x2a};
        assert(*length>=sizeof(speed));
        memcpy(reply,speed,sizeof(speed));*length=sizeof(speed);
        return 0;
    }
    assert(!"Unexpected request or write service");
    return -1;
}
static j1979_live_plan plan(fake *f) {
    j1979_live_plan p{};
    p.channel.exchange=exchange_fake;
    p.channel.clock=clock_fake;
    p.channel.current=current_fake;
    p.channel.context=f;
    p.channel.tx=0x7e0;p.channel.rx=0x7e8;
    p.channel.expected_vin=vin;
    p.channel.budget_ms=90000;
    p.pids[0]=0x0c;p.pids[1]=0x0d;p.pid_count=2;
    p.cycles=2;p.interval_ms=500;p.wait=wait_fake;
    return p;
}
namespace {
std::string json_report(const j1979_live_plan& p,const j1979_live_result& r){
    char* buffer=nullptr;std::size_t size=0;
    auto* file=open_memstream(&buffer,&size);assert(file);
    assert(j1979_live_write_json(file,&p,&r,"vcan0"));
    assert(fclose(file)==0);
    std::string raw(buffer,size);std::free(buffer);return raw;
}
auto replay(const std::string& raw){
    return cd::network::history::inspect_native_live(cd::json::parse_report(raw),{0x7e0,0x7e8},vin);
}
std::string change(std::string s,std::string_view old,std::string_view replacement){
    auto p=s.find(old);assert(p!=std::string::npos);s.replace(p,old.size(),replacement);return s;
}
void rejects(const std::string& raw){
    bool rejected=false;
    try{(void)replay(raw);}catch(const std::exception&){rejected=true;}
    assert(rejected);
}
auto project(const j1979_live_plan& p,const j1979_live_result& r){
    return cd::network::history::project_native_live(cd::json::parse_report(json_report(p,r)),{0x7e0,0x7e8},vin);
}
void check_replay(const j1979_live_plan& p,const j1979_live_result& r){
    auto checked=replay(json_report(p,r));
    assert(checked.completed==r.completed&&checked.cycles==r.cycles_completed);
    assert(checked.raw_samples==r.raw_samples&&checked.missing_samples==r.missing_samples);
    assert(checked.initial_identity==r.initial_vin_valid&&checked.final_identity==r.final_vin_valid);
    assert(checked.stop_reason==r.stop_reason);
}
}
int main(void) {
    fake f{}; f.clock=1000;
    j1979_live_plan p=plan(&f);
    assert(j1979_live_plan_valid(&p));
    j1979_live_plan missing=p;
    missing.channel.current=NULL;
    assert(!j1979_live_plan_valid(&missing));
    missing=p;missing.channel.expected_vin=NULL;
    assert(!j1979_live_plan_valid(&missing));
    assert(p.channel.expected_vin && p.pid_count==2);
    j1979_live_result r{};
    assert(j1979_live_record(&p,&r)==0);
    assert(r.completed && r.initial_vin_valid && r.final_vin_valid);
    assert(r.inventory_complete && r.cycles_completed==2);
    assert(r.raw_samples==4 && r.missing_samples==0);
    assert(r.step_count==7 && f.requests==7 && f.vin_requests==2);
    assert(!strcmp(r.initial_vin,vin) && !strcmp(r.final_vin,vin));
    assert(r.advertised[0x0c] && r.advertised[0x0d]);
    FILE *stream=tmpfile();assert(stream);
    assert(j1979_live_write_json(stream,&p,&r,"vcan0"));
    rewind(stream);
    char json[12000]={0};
    size_t n=fread(json,1,sizeof(json)-1,stream);
    assert(n>0 && strstr(json,"\"schema\":\"camarodiag-j1979-live-linux-c-v1\""));
    assert(strstr(json,"\"selected_pids\":[12,13]"));
    assert(strstr(json,"\"completed_cycles\":2"));
    assert(strstr(json,"\"physical_vehicle_verified\":false"));
    assert(strstr(json,"\"response_hex\":\"410C1AF8\""));
    assert(strstr(json,"\"name\":\"engine-rpm\""));
    assert(strstr(json,"\"number\":1726"));
    assert(strstr(json,"\"name\":\"vehicle-speed\""));
    assert(strstr(json,"\"number\":42"));
    fclose(stream);
    check_replay(p,r);
    const auto original=json_report(p,r);
    const auto projection=project(p,r);
    assert(projection.complete&&projection.report.at("identity_rechecked").boolean());
    const auto& channels=projection.report.at("channel_summary").array();
    assert(channels.size()==2);
    assert(channels[0].at("pid").unsigned_integer()==12);
    assert(channels[0].at("mean").finite_number()==1726);
    assert(channels[0].at("count").unsigned_integer()==2);
    assert(channels[1].at("mean").finite_number()==42);
    assert(projection.report.at("samples").array().size()==4);
    assert(!projection.report.at("physical_vehicle_verified").boolean());
    const auto csv=cd::network::history::native_live_csv(projection);
    assert(csv.find("signal_name,raw_encoded,scale_source")!=std::string::npos);
    assert(csv.find("\"engine-rpm\"")!=std::string::npos);
    assert(csv.find("\"vehicle-speed\"")!=std::string::npos);
    rejects(change(original,"\"number\":1726","\"number\":1727"));
    rejects(change(original,"\"raw_samples\":4","\"raw_samples\":3"));
    rejects(change(original,"\"request_hex\":\"010C\"","\"request_hex\":\"010D\""));
    rejects(change(original,"\"selected_pids\":[12,13]","\"selected_pids\":[13,12]"));
    rejects(change(original,"\"phase\":\"FINAL_VIN\"","\"phase\":\"PERIODIC_VIN\""));
    rejects(change(original,"\"interval_ms\":500","\"interval_ms\":501"));
    rejects(change(original,"\"physical_vehicle_verified\":false","\"physical_vehicle_verified\":true"));
    j1979_live_free(&r);
    assert(!r.steps && !r.step_count);

    f = fake{}; f.clock=1000;
    p=plan(&f);p.interval_ms=35000;
    assert(j1979_live_record(&p,&r)==0);
    assert(r.completed && f.vin_requests==3 && r.step_count==8);
    assert(!strcmp(r.steps[4].phase,"PERIODIC_VIN"));
    check_replay(p,r);
    j1979_live_free(&r);

    f = fake{}; f.clock=1000; f.bad_final_vin=true;
    p=plan(&f);assert(j1979_live_record(&p,&r)==0);
    assert(!r.completed && !strcmp(r.stop_reason,"VIN_MISMATCH"));
    assert(!r.final_vin_valid);check_replay(p,r);
    const auto unidentified=project(p,r);
    assert(!unidentified.complete&&!unidentified.report.at("identity_rechecked").boolean());
    assert(unidentified.report.at("channel_summary").array().empty());
    for(const auto& sample:unidentified.report.at("samples").array()){
        assert(!sample.at("engineering_verified").boolean()&&sample.at("signals").array().empty());
    }
    j1979_live_free(&r);

    f = fake{}; f.clock=1000; f.negative_sample=true;
    p=plan(&f);assert(j1979_live_record(&p,&r)==0);
    assert(!r.completed && r.final_vin_valid && r.missing_samples==2);
    assert(!strcmp(r.stop_reason,"INCOMPLETE_SAMPLES"));
    check_replay(p,r);
    j1979_live_free(&r);

    f = fake{}; f.clock=1000; f.timeout_sample=true;
    p=plan(&f);assert(j1979_live_record(&p,&r)==0);
    assert(!r.completed && r.missing_samples==2);
    assert(!strcmp(r.stop_reason,"INCOMPLETE_SAMPLES"));
    check_replay(p,r);
    j1979_live_free(&r);

    f = fake{}; f.clock=1000; f.suppress_advertisement=true;
    p=plan(&f);assert(j1979_live_record(&p,&r)==0);
    assert(!r.completed && !strcmp(r.stop_reason,"PID_NOT_ADVERTISED"));
    assert(f.requests==2);check_replay(p,r);j1979_live_free(&r);

    f = fake{}; f.clock=1000; f.retire_context=true;
    p=plan(&f);assert(j1979_live_record(&p,&r)==0);
    assert(!r.completed && !strcmp(r.stop_reason,"CONTEXT_CHANGED"));
    assert(f.requests==3);check_replay(p,r);j1979_live_free(&r);

    f = fake{}; f.clock=1000; f.malformed_sample=true;
    p=plan(&f);assert(j1979_live_record(&p,&r)==0);
    assert(!r.completed && !strcmp(r.stop_reason,"INVALID_PID_WIDTH"));
    assert(r.raw_samples==0 && r.missing_samples==1 && f.requests==3);
    assert(!strcmp(r.steps[2].status,"FAILED"));
    check_replay(p,r);
    j1979_live_free(&r);

    f = fake{}; f.clock=1000; f.oversized_sample=true;
    p=plan(&f);assert(j1979_live_record(&p,&r)==0);
    assert(!r.completed && !strcmp(r.stop_reason,"UNBOUNDED_REPLY"));
    assert(r.raw_samples==0 && r.missing_samples==1 && f.requests==3);
    check_replay(p,r);
    j1979_live_free(&r);

    // Vary scheduler delay at each clock observation. Archive replay must
    // accept actual completed recordings despite unrecorded callback gaps.
    for(unsigned at=1;at<=45;++at){
        f=fake{};f.clock=1000;f.stall_at_read=at;f.stall_ms=25;
        p=plan(&f);assert(j1979_live_record(&p,&r)==0);
        if(r.completed)check_replay(p,r);
        j1979_live_free(&r);
    }

    p=plan(&f);p.pids[1]=p.pids[0];
    assert(!j1979_live_plan_valid(&p));
    p=plan(&f);p.pids[1]=0x20;
    assert(!j1979_live_plan_valid(&p));
    p=plan(&f);p.channel.expected_vin=NULL;
    assert(!j1979_live_plan_valid(&p));
    p=plan(&f);p.channel.tx=0x7df;
    assert(!j1979_live_plan_valid(&p));
    p=plan(&f);p.cycles=1001;
    assert(!j1979_live_plan_valid(&p));
    puts("Native C J1979 live RAW monitoring: synthetic policy checks passed");
    return 0;
}
