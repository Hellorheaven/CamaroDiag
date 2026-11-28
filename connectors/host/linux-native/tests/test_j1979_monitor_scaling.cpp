// Native C++20 regression fixture; executed only by the final gate.
#include "j1979_monitor_scaling.h"
#include <assert.h>
#include <stdio.h>
#include <cstring>
#include <array>
#include <span>
/* Synthetic engineering projection: no conclusion about monitor completion. */
int main(void) {
    const uint8_t request[2]={6,0x21};
    const uint8_t payload[10]={0x46,0x21,0x01,0x07,0x03,0xe8,0,0,0x07,0xd0};
    j1979_monitor_record record{};
    assert(j1979_monitor_count(request,payload,sizeof(payload))==1);
    assert(j1979_monitor_decode(request,payload,sizeof(payload),0,&record));
    assert(record.monitor_id==0x21 && record.test_id==1 &&
           record.scaling_id==7 && record.encoded_value==1000);
    assert(record.scaled && !strcmp(record.unit,"rpm"));
    assert(record.value==250 && record.minimum==0 && record.maximum==500);
    assert(record.relation==J1979_MONITOR_WITHIN);
    assert(!strcmp(j1979_monitor_relation_name(record.relation),"WITHIN"));

    /* Signed scaling mirrors the former Python 0x96 engineering definition. */
    assert(j1979_monitor_scale(0x96,0xff9c,0xff38,0x0032,&record));
    assert(record.scaled && !strcmp(record.unit,"degC") &&
           record.value==-10 && record.minimum==-20 && record.maximum==5);
    assert(record.relation==J1979_MONITOR_WITHIN);

    assert(j1979_monitor_scale(0x01,12,0,10,&record));
    assert(record.relation==J1979_MONITOR_ABOVE);
    assert(j1979_monitor_scale(0x01,1,2,9,&record));
    assert(record.relation==J1979_MONITOR_BELOW);
    assert(j1979_monitor_scale(0x01,5,10,0,&record));
    assert(record.relation==J1979_MONITOR_INVERTED);

    assert(!j1979_monitor_scale(0x2e,2,0,1,&record));
    assert(!record.scaled);
    assert(j1979_monitor_scale(0x2e,1,0,1,&record));
    assert(!strcmp(record.unit,"bool"));
    assert(!j1979_monitor_scale(0xf0,1,0,2,&record));
    assert(!record.scaled);

    uint8_t unknown[10]={0x46,0x21,0x02,0xf0,0,1,0,0,0,2};
    assert(j1979_monitor_decode(request,unknown,sizeof(unknown),0,&record));
    assert(!record.scaled && record.encoded_value==1 &&
           record.encoded_minimum==0 && record.encoded_maximum==2);
    assert(record.monitor_id==0x21 && record.test_id==2 &&
           record.scaling_id==0xf0 && record.unit==nullptr);
    const uint8_t combined[]={0x46,
        0x21,0x01,0x07,0x03,0xe8,0,0,0x07,0xd0,
        0x21,0x03,0x2e,0,2,0,0,0,1,
        0x21,0x04,0x96,0xff,0x9c,0xff,0x38,0,0x32};
    assert(j1979_monitor_count(request,combined,sizeof(combined))==3);
    assert(j1979_monitor_decode(request,combined,sizeof(combined),1,&record));
    assert(record.monitor_id==0x21 && record.test_id==3 &&
           record.scaling_id==0x2e && record.encoded_value==2 &&
           record.encoded_minimum==0 && record.encoded_maximum==1 &&
           !record.scaled && record.unit==nullptr);
    assert(j1979_monitor_decode(request,combined,sizeof(combined),2,&record));
    assert(record.monitor_id==0x21 && record.test_id==4 &&
           record.scaling_id==0x96 && record.encoded_value==0xff9c &&
           record.encoded_minimum==0xff38 && record.encoded_maximum==0x0032 &&
           record.scaled && record.value==-10 && record.minimum==-20 &&
           record.maximum==5);
    unknown[1]=0x22;
    assert(!j1979_monitor_decode(request,unknown,sizeof(unknown),0,&record));
    assert(j1979_monitor_count(request,unknown,sizeof(unknown))==0);
    assert(!j1979_monitor_decode(request,payload,sizeof(payload)-1,0,&record));
    assert(!j1979_monitor_decode(request,payload,sizeof(payload),1,&record));
    uint8_t wrong_service[2]={1,0x21};
    assert(!j1979_monitor_decode(wrong_service,payload,sizeof(payload),0,&record));
    puts("C++20 native SAE J1979 CAN Mode06 scaling/unknown record checks written");
    return 0;
}
