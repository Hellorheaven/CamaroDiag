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
    bool force_bad_vin, force_final_vin_mismatch, force_partial_mode06;
    bool force_nrc, force_dtc, force_monitor, force_info, force_late, force_transport, refuse_mutation;
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
    if (s->force_partial_mode06 && n == 2 && req[0] == 6 && req[1] == 0)
        return 1;
    if (s->force_late) s->now += timeout + 1;
    const uint8_t *bytes = NULL;
    size_t size = 0;
    static uint8_t v[20];
    if (req[0] == 9 && req[1] == 2) {
        v[0]=0x49;v[1]=2;v[2]=1;
        memcpy(v+3,s->force_bad_vin?"1FA6P8TH0L5100000":
            s->force_final_vin_mismatch && s->count > 1 ?
            "2G1FA1RXXR0123456" : vin,17);
        bytes=v;size=20;
    } else if (n == 1 && (req[0] == 3 || req[0] == 7 || req[0] == 10)) {
        if (s->force_nrc && req[0] == 7) {
            static const uint8_t negative[3] = {0x7f,7,0x11};
            bytes=negative;size=sizeof(negative);
        } else if (s->force_dtc && req[0] == 3) {
            static const uint8_t one_dtc[4]={0x43,1,0x03,0x01};
            bytes=one_dtc;size=sizeof(one_dtc);
        } else {
            static uint8_t dtc[2];
            dtc[0]=(uint8_t)(req[0]+0x40); dtc[1]=0;
            bytes=dtc;size=2;
        }
    } else if (n == 2 && (req[0] == 1 || req[0] == 6 ||
                          req[0] == 8 || req[0] == 9) && req[1] == 0) {
        static uint8_t bitmap[6];
        bitmap[0]=(uint8_t)(req[0]+0x40);bitmap[1]=0;
        bitmap[2]=req[0] == 1 ? 0x80 :
            (s->force_monitor && req[0] == 6 ? 0x00 : 0);
        if (s->force_monitor && req[0]==6) bitmap[5]=1;
        if (s->force_info && req[0]==9) {
            bitmap[2]=0x01; /* InfoType 08 */
            bitmap[3]=0x08; /* InfoType 0D */
        }
        bitmap[3]=bitmap[4]=bitmap[5]=0;
        bytes=bitmap;size=6;
    } else if (n == 2 && req[0] == 6 && req[1] == 0x20 &&
               s->force_monitor) {
        static const uint8_t next_page[6]={0x46,0x20,0x80,0,0,0};
        bytes=next_page;size=sizeof(next_page);
    } else if (n == 2 && req[0] == 6 && req[1] == 0x21 &&
               s->force_monitor) {
        static const uint8_t test_record[10]={
            0x46,0x21,0x01,0x07,0x03,0xE8,0x00,0x00,0x07,0xD0
        };
        bytes=test_record;size=sizeof(test_record);
    } else if (s->force_info && n==2 && req[0]==9 && req[1]==8) {
        static uint8_t counters[35]={0x49,8,16};
        memset(counters+3,0,32);
        counters[4]=10; /* general denominator */
        counters[6]=20; /* ignition cycles */
        counters[8]=3; counters[10]=6; /* catalyst bank 1 ratio=0.5 */
        bytes=counters;size=sizeof(counters);
    } else if (s->force_info && n==2 && req[0]==9 && req[1]==13) {
        static const uint8_t info[20]={
            0x49,13,1,0,0,'E','C','U','-','C','A','M','A','R',
            'O','-','E','N','G','1'
        };
        bytes=info;size=sizeof(info);
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
    j1979_can_result refused={0};
    j1979_can_plan missing=p;
    missing.current=NULL;
    assert(j1979_can_scan(&missing,&refused)!=0);
    assert(refused.steps==NULL);
    missing=p;missing.expected_vin=NULL;
    assert(j1979_can_scan(&missing,&refused)!=0);
    assert(refused.steps==NULL);
    j1979_can_result r={0};
    assert(j1979_can_scan(&p,&r)==0);
    assert(r.completed && r.vin_valid && !strcmp(r.observed_vin,vin));
    assert(!strcmp(r.vin_match,"MATCH"));
    assert(r.step_count==11);
    assert(s.count==11);
    assert(r.final_vin_valid && r.final_vin_checked);
    assert(!strcmp(r.final_vin,vin) && !strcmp(r.final_vin_match,"MATCH"));
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
    assert(strstr(text,"\"final_vin_match\":\"MATCH\""));
    assert(strstr(text,"\"final_vin_checked\":true"));
    fclose(output);
    j1979_can_result_free(&r);

    s=(stub){.current=true,.force_bad_vin=true};
    p=plan(&s);assert(j1979_can_scan(&p,&r)==0);
    assert(!r.completed && !strcmp(r.stop_reason,"VIN_MISMATCH"));
    assert(s.count==1);j1979_can_result_free(&r);
    s=(stub){.current=true,.force_dtc=true};
    p=plan(&s);assert(j1979_can_scan(&p,&r)==0);
    assert(r.completed && r.step_count==11);
    output=tmpfile();assert(output);
    assert(j1979_can_write_json(output,&p,&r,"vcan0"));
    rewind(output);memset(text,0,sizeof(text));
    assert(fread(text,1,sizeof(text)-1,output)>0);
    assert(strstr(text,"\"dtc_codes\":[\"P0301\"]"));
    fclose(output);j1979_can_result_free(&r);
    s=(stub){.current=true,.force_monitor=true};
    p=plan(&s);assert(j1979_can_scan(&p,&r)==0);
    assert(r.completed);
    output=tmpfile();assert(output);
    assert(j1979_can_write_json(output,&p,&r,"vcan0"));
    rewind(output);memset(text,0,sizeof(text));
    assert(fread(text,1,sizeof(text)-1,output)>0);
    assert(strstr(text,"\"monitor_records\":[{\"record_index\":0"));
    assert(strstr(text,"\"unit_scaling_id\":7"));
    assert(strstr(text,"\"numeric_relation\":\"WITHIN\""));
    assert(strstr(text,"\"monitor_verdict\":null"));
    fclose(output);j1979_can_result_free(&r);
    s=(stub){.current=true,.force_info=true};
    p=plan(&s);assert(j1979_can_scan(&p,&r)==0);
    assert(r.completed && r.inventory_state[9]==1);
    output=tmpfile();assert(output);
    assert(j1979_can_write_json(output,&p,&r,"vcan0"));
    rewind(output);memset(text,0,sizeof(text));
    assert(fread(text,1,sizeof(text)-1,output)>0);
    assert(strstr(text,"\"vehicle_info\":{\"info_type\":8"));
    assert(strstr(text,"\"general_denominator\":10"));
    assert(strstr(text,"\"ignition_cycles\":20"));
    assert(strstr(text,"\"ratio\":0.5"));
    assert(strstr(text,"\"vehicle_info\":{\"info_type\":13"));
    assert(strstr(text,"ECU-CAMARO-ENG1"));
    fclose(output);j1979_can_result_free(&r);
    s=(stub){.current=true,.force_final_vin_mismatch=true};
    p=plan(&s);assert(j1979_can_scan(&p,&r)==0);
    assert(!r.completed && !strcmp(r.stop_reason,"VIN_CHANGED"));
    assert(!strcmp(r.final_vin_match,"MISMATCH"));
    assert(s.count==11);j1979_can_result_free(&r);
    s=(stub){.current=true,.force_partial_mode06=true};
    p=plan(&s);assert(j1979_can_scan(&p,&r)==0);
    assert(!r.completed && !strcmp(r.stop_reason,"INVENTORY_INCOMPLETE"));
    assert(r.timeout_responses==1);j1979_can_result_free(&r);
    s=(stub){.current=true,.force_nrc=true};
    p=plan(&s);assert(j1979_can_scan(&p,&r)==0);
    assert(!r.completed && !strcmp(r.stop_reason,"OBSERVATION_INCOMPLETE"));
    assert(r.negative_responses==1);j1979_can_result_free(&r);
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
