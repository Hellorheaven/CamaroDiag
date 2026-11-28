// Native C++20 regression fixture; executed only by the final gate.
#include "j1979_vehicle_info.h"
#include <assert.h>
#include <stdio.h>
#include <cstring>
#include <array>
#include <span>

int main(void) {
    /* Explicit 16-counter spark IPT vector, correlated to physical CAN.
     * Numeric ratios do not imply regulatory monitor completion.
     */
    uint8_t spark[35]={0x49,8,16};
    spark[4]=10;spark[6]=20;
    spark[8]=3;spark[10]=6; /* first ratio = 1/2 */
    spark[12]=2;spark[14]=0; /* missing denominator */
    j1979_vehicle_info info{};
    assert(j1979_vehicle_info_decode(8,spark,sizeof(spark),&info));
    assert(info.counter_vector && info.counter_count==16);
    assert(info.general_denominator==10 && info.ignition_cycles==20);
    assert(info.monitor_count==7);
    assert(!strcmp(info.monitors[0].name,"catalyst-bank-1"));
    assert(info.monitors[0].ratio_valid && info.monitors[0].ratio==0.5);
    assert(!info.monitors[1].ratio_valid);

    uint8_t extended_spark[43]={0x49,8,20};
    assert(j1979_vehicle_info_decode(8,extended_spark,
                                     sizeof(extended_spark),&info));
    assert(info.monitor_count==9);
    assert(!strcmp(info.monitors[8].name,"secondary-oxygen-bank-2"));

    uint8_t diesel[39]={0x49,11,18};
    assert(j1979_vehicle_info_decode(11,diesel,sizeof(diesel),&info));
    assert(info.monitor_count==8);
    assert(!strcmp(info.monitors[7].name,"fuel-system"));

    uint8_t text[20]={0x49,13,1};
    memcpy(text+5,"ECU-CAMARO-ENG1",15);
    assert(j1979_vehicle_info_decode(13,text,sizeof(text),&info));
    assert(info.printable_text && !strcmp(info.text,"ECU-CAMARO-ENG1"));
    text[10]=0;
    assert(!j1979_vehicle_info_decode(13,text,sizeof(text),&info));
    text[10]='A';text[2]=2;
    assert(!j1979_vehicle_info_decode(13,text,sizeof(text),&info));
    text[2]=1;text[1]=15;
    assert(j1979_vehicle_info_decode(15,text,sizeof(text),&info));

    assert(!j1979_vehicle_info_decode(8,spark,sizeof(spark)-2,&info));
    assert(!j1979_vehicle_info_decode(9,spark,sizeof(spark),&info));
    assert(!j1979_vehicle_info_decode(13,text,sizeof(text)-1,&info));
    assert(!j1979_vehicle_info_decode(13,NULL,sizeof(text),&info));
    puts("Mode09 native IPT counters and fixed-width identification: synthetic tests written");
    return 0;
}
