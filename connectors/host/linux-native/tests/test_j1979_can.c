#include "j1979_can.h"
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static const char *vin = "1G1FA1RXXR0123456";
typedef struct {
    uint64_t now;
    bool current;
    unsigned count;
    uint8_t emitted[24][3];
    size_t emitted_size[24];
    bool force_bad_vin, force_late, force_transport, refuse_mutation;
} stub;
static uint64_t clock_fake(void *ctx) { return ((stub *)ctx)->now; }
static bool current_fake(void *ctx) { return ((stub *)ctx)->current; }
static int exchange_fake(void *ctx, const uint8_t *req, size_t n,
                        uint8_t *reply, size_t *length, unsigned timeout) {
    stub *s = ctx;
    assert(timeout > 0 && timeout <= 2500);
    assert(j1979_can_allowed_read(req,n));
    if (s->count < 24) {
        memcpy(s->emitted[s->count], req, n);
        s->emitted_size[s->count] = n;
    }
    ++s->count;
    if (s->force_transport && s->count == 2) return -1;
    if (s->force_late) s->now += timeout + 1;
    const uint8_t *bytes = NULL;
    size_t size = 0;
    static uint8_t v[20];
    if (req[0] == 9 && req[1] == 2) {
        v[0]=0x49;v[1]=2;v[2]=1;
        memcpy(v+3,s->force_bad_vin?"1FA6P8TH0L5100000":vin,17);
        bytes=v;size=20;
    } else if (n == 1 && (req[0] == 3 || req[0] == 7 || req[0] == 10)) {
        static uint8_t dtc[2];
        dtc[0]=(uint8_t)(req[0]+0x40); dtc[1]=0;
        bytes=dtc;size=2;
    } else if (n == 2 && (req[0] == 1 || req[0] == 6 ||
                          req[0] == 8 || req[0] == 9) && req[1] == 0) {
        static uint8_t bitmap[6];
        bitmap[0]=(uint8_t)(req[0]+0x40);bitmap[1]=0;
        bitmap[2]=req[0] == 1 ? 0x80 : 0;
        bitmap[3]=bitmap[4]=bitmap[5]=0;
        bytes=bitmap;size=6;
    } else if (n == 2 && req[0] == 1 && req[1] == 1) {
        static const uint8_t status[6]={0x41,1,0,0,0,0};
        bytes=status;size=6;
    } else if (n == 3 && req[0] == 2 && req[1] == 2) {
        static const uint8_t frame[5]={0x42,2,0,0,0};
        bytes=frame;size=5;
    }
    assert(bytes && size <= *length);
    memcpy(reply,bytes,size);*length=size;return 0;
}
static j1979_can_plan plan(stub *s) {
    j1979_can_plan p={0};
    p.exchange=exchange_fake;p.clock=clock_fake;p.current=current_fake;
    p.context=s;p.tx=0x7e0;p.rx=0x7e8;p.budget_ms=30000;p.expected_vin=vin;
    return p;
}
int main(void) {
    assert(j1979_can_physical_pair(0x7e0,0x7e8));
    assert(j1979_can_physical_pair(0x18da10f1,0x18daf110));
    assert(!j1979_can_physical_pair(0x7df,0x7e8));
    assert(!j1979_can_physical_pair(0x18dafff1,0x18daf1ff));
    const uint8_t bad1[]={4},bad2[]={8,1},bad3[]={2,2,1};
    const uint8_t good[]={2,2,0};
    assert(!j1979_can_allowed_read(bad1,1));
    assert(!j1979_can_allowed_read(bad2,2));
    assert(!j1979_can_allowed_read(bad3,3));
    assert(j1979_can_allowed_read(good,3));

    stub s={.current=true};
    j1979_can_plan p=plan(&s);
    j1979_can_result r={0};
    assert(j1979_can_scan(&p,&r)==0);
    assert(r.completed && r.vin_valid && !strcmp(r.observed_vin,vin));
    assert(!strcmp(r.vin_match,"MATCH"));
    assert(r.step_count==10);
    assert(s.count==10);
    assert(r.advertised_count[1]==1);
    assert(r.inventory_state[1]==1 && r.inventory_state[2]==0);
    assert(!r.freeze_present && r.freeze_trigger_verified);
    for (unsigned i=0;i<s.count;++i)
        assert(!(s.emitted[i][0]==4 || (s.emitted[i][0]==8 && s.emitted[i][1]!=0)));
    FILE *output=tmpfile();assert(output);
    assert(j1979_can_write_json(output,&p,&r,"vcan0"));
    rewind(output);char text[8192]={0};
    size_t read_size=fread(text,1,sizeof(text)-1,output);
    assert(read_size>0 && strstr(text,"\"vin_match\":\"MATCH\""));
    assert(strstr(text,"\"MODE_04_CLEAR\""));
    fclose(output);
    j1979_can_result_free(&r);

    s=(stub){.current=true,.force_bad_vin=true};
    p=plan(&s);assert(j1979_can_scan(&p,&r)==0);
    assert(!r.completed && !strcmp(r.stop_reason,"VIN_MISMATCH"));
    assert(s.count==1);j1979_can_result_free(&r);
    s=(stub){.current=true,.force_transport=true};
    p=plan(&s);assert(j1979_can_scan(&p,&r)==0);
    assert(!r.completed && !strcmp(r.stop_reason,"TRANSPORT_ERROR"));
    assert(s.count==2);j1979_can_result_free(&r);
    s=(stub){.current=false};
    p=plan(&s);assert(j1979_can_scan(&p,&r)==0);
    assert(!r.completed && !strcmp(r.stop_reason,"CONTEXT_CHANGED"));
    assert(s.count==0);j1979_can_result_free(&r);
    s=(stub){.current=true,.force_late=true};
    p=plan(&s);p.budget_ms=100;
    assert(j1979_can_scan(&p,&r)==0);
    assert(!r.completed && !strcmp(r.stop_reason,"BUDGET_EXHAUSTED"));
    assert(s.count==1);j1979_can_result_free(&r);
    puts("Native C J1979 physical CAN scan policy: synthetic checks passed");
    return 0;
}
