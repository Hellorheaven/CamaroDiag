#include "j1979_live.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>

/* Entirely synthetic: no socket, vehicle, adapter or transport is opened. */
static const char *vin="1G1FA1RXXR0123456";
typedef struct {
    uint64_t clock;
    unsigned requests, vin_requests;
    bool bad_final_vin, negative_sample, timeout_sample;
    bool suppress_advertisement, retire_context;
    uint8_t emitted[64][2];
} fake;
static uint64_t clock_fake(void *ctx) {return static_cast<fake *>(ctx)->clock;}
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
        const uint8_t rpm[4]={0x41,0x0c,0x1a,0xf8};
        assert(*length>=sizeof(rpm));
        memcpy(reply,rpm,sizeof(rpm));*length=sizeof(rpm);
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
    j1979_live_free(&r);
    assert(!r.steps && !r.step_count);

    f = fake{}; f.clock=1000;
    p=plan(&f);p.interval_ms=35000;
    assert(j1979_live_record(&p,&r)==0);
    assert(r.completed && f.vin_requests==3 && r.step_count==8);
    assert(!strcmp(r.steps[4].phase,"PERIODIC_VIN"));
    j1979_live_free(&r);

    f = fake{}; f.clock=1000; f.bad_final_vin=true;
    p=plan(&f);assert(j1979_live_record(&p,&r)==0);
    assert(!r.completed && !strcmp(r.stop_reason,"VIN_MISMATCH"));
    assert(!r.final_vin_valid);j1979_live_free(&r);

    f = fake{}; f.clock=1000; f.negative_sample=true;
    p=plan(&f);assert(j1979_live_record(&p,&r)==0);
    assert(!r.completed && r.final_vin_valid && r.missing_samples==2);
    assert(!strcmp(r.stop_reason,"INCOMPLETE_SAMPLES"));
    j1979_live_free(&r);

    f = fake{}; f.clock=1000; f.timeout_sample=true;
    p=plan(&f);assert(j1979_live_record(&p,&r)==0);
    assert(!r.completed && r.missing_samples==2);
    assert(!strcmp(r.stop_reason,"INCOMPLETE_SAMPLES"));
    j1979_live_free(&r);

    f = fake{}; f.clock=1000; f.suppress_advertisement=true;
    p=plan(&f);assert(j1979_live_record(&p,&r)==0);
    assert(!r.completed && !strcmp(r.stop_reason,"PID_NOT_ADVERTISED"));
    assert(f.requests==2);j1979_live_free(&r);

    f = fake{}; f.clock=1000; f.retire_context=true;
    p=plan(&f);assert(j1979_live_record(&p,&r)==0);
    assert(!r.completed && !strcmp(r.stop_reason,"CONTEXT_CHANGED"));
    assert(f.requests==3);j1979_live_free(&r);

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
