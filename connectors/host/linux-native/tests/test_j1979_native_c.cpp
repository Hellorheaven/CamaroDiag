#include "j1979_policy.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>

int main(void) {
    assert(cd_j1979_native_physical_pair(0x7e0,0x7e8,false));
    assert(cd_j1979_native_physical_pair(0x7e7,0x7ef,false));
    assert(cd_j1979_native_physical_pair(0x18da10f1,0x18daf110,true));
    assert(!cd_j1979_native_physical_pair(0x7df,0x7e8,false));
    assert(!cd_j1979_native_physical_pair(0x7e0,0x7e9,false));
    assert(!cd_j1979_native_physical_pair(0x18dafff1,0x18daf1ff,true));
    assert(!cd_j1979_native_physical_pair(0x18daf0f1,0x18daf1f0,true));
    assert(!cd_j1979_native_physical_pair(0x7e0,0x7e8,true));
    assert(cd_j1979_native_vin("1G1FA1RXXR0123456",17));
    assert(!cd_j1979_native_vin("1G1FA1RXXR0123456",16));
    assert(!cd_j1979_native_vin("1G1FA1RXXR012345I",17));
    assert(!cd_j1979_native_vin("1G1FA1RXXR012345O",17));
    assert(!cd_j1979_native_vin("1G1FA1RXXR012345Q",17));
    const uint8_t vin_req[2]={9,2},live_req[2]={1,0x0c};
    const uint8_t bitmap_req[2]={1,0},freeze_req[3]={2,2,0};
    const uint8_t dtc_req[1]={3},clear_req[1]={4};
    const uint8_t actuator_req[2]={8,1},read_control_bitmap[2]={8,0};
    assert(cd_j1979_native_read_only(vin_req,2));
    assert(cd_j1979_native_read_only(freeze_req,3));
    assert(cd_j1979_native_read_only(read_control_bitmap,2));
    assert(!cd_j1979_native_read_only(clear_req,1));
    assert(!cd_j1979_native_read_only(actuator_req,2));
    uint8_t vin[20]={0x49,2,1};
    memcpy(vin+3,"1G1FA1RXXR0123456",17);
    assert(cd_j1979_native_classify(vin_req,2,vin,sizeof(vin)) ==
           CD_J1979_NATIVE_VIN);
    vin[3]='Q';
    assert(cd_j1979_native_classify(vin_req,2,vin,sizeof(vin)) ==
           CD_J1979_NATIVE_INVALID);
    const uint8_t rpm[4]={0x41,0x0c,0x12,0x34};
    const uint8_t wrong_pid[4]={0x41,0x0d,0x12,0x34};
    assert(cd_j1979_native_classify(live_req,2,rpm,sizeof(rpm)) ==
           CD_J1979_NATIVE_RAW);
    assert(cd_j1979_native_classify(live_req,2,wrong_pid,sizeof(wrong_pid)) ==
           CD_J1979_NATIVE_INVALID);
    const uint8_t nrc[3]={0x7f,1,0x11},pending[3]={0x7f,1,0x78};
    assert(cd_j1979_native_classify(live_req,2,nrc,sizeof(nrc)) ==
           CD_J1979_NATIVE_NEGATIVE);
    assert(cd_j1979_native_classify(live_req,2,pending,sizeof(pending)) ==
           CD_J1979_NATIVE_PENDING);
    const uint8_t bitmap[6]={0x41,0,0,0x10,0,0};
    assert(cd_j1979_native_classify(bitmap_req,2,bitmap,sizeof(bitmap)) ==
           CD_J1979_NATIVE_BITMAP);
    const uint8_t freeze[5]={0x42,2,0,0x01,0x01};
    assert(cd_j1979_native_classify(freeze_req,3,freeze,sizeof(freeze)) ==
           CD_J1979_NATIVE_RAW);
    const uint8_t dtc[4]={0x43,1,0x03,0x01},no_dtc[2]={0x43,0};
    assert(cd_j1979_native_classify(dtc_req,1,dtc,sizeof(dtc)) ==
           CD_J1979_NATIVE_DTC);
    assert(cd_j1979_native_classify(dtc_req,1,no_dtc,sizeof(no_dtc)) ==
           CD_J1979_NATIVE_DTC);
    uint8_t dtc_code[2]={0x03,0x01};char code[6]{};
    assert(cd_j1979_native_dtc_code(dtc_code,code));
    assert(!strcmp(code,"P0301"));
    dtc_code[0]=0x42;dtc_code[1]=0x07;
    assert(cd_j1979_native_dtc_code(dtc_code,code));
    assert(!strcmp(code,"C0207"));
    const uint8_t bad_dtc[4]={0x43,1,0,0};
    assert(cd_j1979_native_classify(dtc_req,1,bad_dtc,sizeof(bad_dtc)) ==
           CD_J1979_NATIVE_INVALID);
    const uint8_t bad_count[4]={0x43,0,0x03,0x01};
    assert(cd_j1979_native_classify(dtc_req,1,bad_count,sizeof(bad_count)) ==
           CD_J1979_NATIVE_INVALID);
    puts("Native C++ SAE J1979 physical, read-only and DTC shapes accepted");
    return 0;
}
