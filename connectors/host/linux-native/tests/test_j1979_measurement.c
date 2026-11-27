#include "j1979_measurement.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>
static void measurement(const uint8_t *req,size_t qlen,
                        const uint8_t *res,size_t n,
                        const char *name,double expected) {
    j1979_measurement m={0};
    assert(j1979_measurement_decode(req,qlen,res,n,&m));
    assert(m.interpreted && !m.context_required);
    assert(!strcmp(m.name,name) && m.number==expected);
}
int main(void) {
    const uint8_t rpm_req[2]={1,0x0c},rpm[4]={0x41,0x0c,0x1a,0xf8};
    measurement(rpm_req,2,rpm,4,"engine-rpm",1726);
    const uint8_t speed_req[2]={1,0x0d},speed[3]={0x41,0x0d,42};
    measurement(speed_req,2,speed,3,"vehicle-speed",42);
    const uint8_t coolant_req[2]={1,5},coolant[3]={0x41,5,90};
    measurement(coolant_req,2,coolant,3,"coolant-temperature",50);
    const uint8_t freeze_req[3]={2,0x0c,0},freeze_rpm[5]={0x42,0x0c,0,0x1a,0xf8};
    measurement(freeze_req,3,freeze_rpm,5,"engine-rpm",1726);
    const uint8_t oil_req[2]={1,0x5c},oil[3]={0x41,0x5c,100};
    measurement(oil_req,2,oil,3,"engine-oil-temperature",60);
    const uint8_t pressure_req[2]={1,0x32},pressure[4]={0x41,0x32,0xff,0xfc};
    measurement(pressure_req,2,pressure,4,"evap-vapor-pressure",-1);
    const uint8_t odo_req[2]={1,0xa6},odo[6]={0x41,0xa6,0,1,0x86,0xa0};
    measurement(odo_req,2,odo,6,"odometer",10000);

    j1979_measurement m={0};
    const uint8_t map_req[2]={1,0x0b},map[3]={0x41,0x0b,100};
    assert(j1979_measurement_decode(map_req,2,map,3,&m));
    assert(m.context_required && !m.interpreted);
    const uint8_t maf_req[2]={1,0x10},maf[4]={0x41,0x10,0x01,0x02};
    assert(j1979_measurement_decode(maf_req,2,maf,4,&m));
    assert(m.context_required && !m.interpreted);

    const uint8_t trim_req[2]={1,6},trim[4]={0x41,6,128,200};
    assert(j1979_measurement_decode(trim_req,2,trim,4,&m));
    assert(m.interpreted && m.upper_trim_unverified && m.number==0);
    const uint8_t wrong_pid[4]={0x41,0x0d,0x1a,0xf8};
    assert(!j1979_measurement_decode(rpm_req,2,wrong_pid,4,&m));
    assert(!j1979_measurement_decode(rpm_req,2,rpm,3,&m));
    assert(!j1979_measurement_decode(freeze_req,3,freeze_rpm,4,&m));
    const uint8_t unknown_req[2]={1,0xc1},unknown[3]={0x41,0xc1,42};
    assert(!j1979_measurement_decode(unknown_req,2,unknown,3,&m));
    puts("Native C Mode01/02 numeric/unknown/config-sensitive projection: synthetic tests written");
    return 0;
}
