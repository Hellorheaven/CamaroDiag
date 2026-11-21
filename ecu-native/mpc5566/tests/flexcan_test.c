#include "../flexcan.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>

#define MB(n) (0x80u + 16u*(n))
typedef struct {
    uint32_t reg[512];
    unsigned budget, unlocks;
    bool complete_tx;
} model;
static bool read(void *ctx, uint32_t off, uint32_t *v) {
    model *m=ctx;
    if (off == 8) ++m->unlocks;
    *v=m->reg[off/4];
    return true;
}
static bool write(void *ctx, uint32_t off, uint32_t v) {
    model *m=ctx;
    if (off == 0x30 || off == 0x2c) m->reg[off/4] &= ~v;
    else {
        m->reg[off/4]=v;
        if (off == MB(1) && (v>>24)==12 && m->complete_tx) {
            m->reg[off/4]=8u<<24;
            m->reg[0x30/4] |= 2;
        }
    }
    return true;
}
static bool service(void *ctx) { model *m=ctx; return m->budget && m->budget--; }
static void setup(model *m) {
    memset(m,0,sizeof(*m)); m->budget=100; m->complete_tx=true;
    m->reg[0]=63; m->reg[MB(0)/4]=4u<<24;
    m->reg[(MB(0)+4)/4]=0x7e0u<<18; m->reg[MB(1)/4]=8u<<24;
}
int main(void) {
    model m; setup(&m);
    cd_flexcan_bus b={&m,read,write,service}; cd_flexcan can;
    assert(cd_flexcan_bind(&can,&b,0,1,0x7e0,0x7e8,false));
    cd_can_frame f;
    assert(cd_flexcan_receive(&can,&f)==CD_CAN_EMPTY);
    m.reg[MB(0)/4]=(2u<<24)|(8u<<16);
    m.reg[(MB(0)+8)/4]=0x11223344; m.reg[(MB(0)+12)/4]=0x55667788;
    m.reg[0x30/4]=1;
    assert(cd_flexcan_receive(&can,&f)==CD_CAN_FRAME);
    assert(f.id==0x7e0 && f.length==8 && f.bytes[0]==0x11 && f.bytes[7]==0x88);
    assert(m.unlocks==2 && m.reg[0x30/4]==0);
    f.id=0x7e8;
    assert(cd_flexcan_send(&can,&f));
    assert(m.reg[(MB(1)+4)/4]==(0x7e8u<<18));
    assert(m.reg[(MB(1)+8)/4]==0x11223344 && m.reg[(MB(1)+12)/4]==0x55667788);
    m.reg[0x30/4]=1; m.reg[MB(0)/4]=6u<<24;
    assert(cd_flexcan_receive(&can,&f)==CD_CAN_ERROR && can.fault);
    setup(&m);
    assert(cd_flexcan_bind(&can,&b,0,1,0x7e0,0x7e8,false));
    m.complete_tx=false; m.budget=3;
    assert(!cd_flexcan_send(&can,&f) && can.fault);
    setup(&m); m.reg[0x20/4]=0x20;
    assert(!cd_flexcan_bind(&can,&b,0,1,0x7e0,0x7e8,false));
    puts("PASS: FlexCAN dedicated mailbox protocol and fault handling");
    return 0;
}
