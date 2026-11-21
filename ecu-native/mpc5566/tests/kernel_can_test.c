#include "../kernel_can.h"
#include "flash_model.h"
#include <assert.h>
#include <stdio.h>

typedef struct { uint32_t regs[512]; } can_model;
static bool read(void *ctx,uint32_t o,uint32_t *v) { *v=((can_model*)ctx)->regs[o/4];return true; }
static bool write(void *ctx,uint32_t o,uint32_t v) {
    can_model *m=ctx;
    if(o==0x30) m->regs[o/4]&=~v;
    else if(o==0x90 && (v>>24)==12) {m->regs[o/4]=8u<<24;m->regs[0x30/4]|=2;}
    else m->regs[o/4]=v;
    return true;
}
static bool service(void *ctx) {(void)ctx;return true;}
int main(void) {
    static model memory;
    static cd_kernel_can board;
    can_model m={0}; m.regs[0]=63;
    m.regs[0x80/4]=4u<<24; m.regs[0x84/4]=0x7e0u<<18;
    m.regs[0x90/4]=8u<<24;
    cd_flexcan_bus bus={&m,read,write,service};
    init_model(&memory);cd_flash_controller flash=controller_for(&memory);
    assert(cd_kernel_can_bind(&board,&flash,&bus,0,1,0x7e0,0x7e8,false,1000000,4,1));
    assert(cd_kernel_can_poll(&board,0));
    /* Single-frame unsupported request: negative response via mailbox. */
    m.regs[0x80/4]=(2u<<24)|(8u<<16);m.regs[0x88/4]=0x01ff0000;
    m.regs[0x30/4]=1;
    assert(cd_kernel_can_poll(&board,1));
    assert((m.regs[0x98/4] & 0xffffff00u)==0x037fff00u);
    /* Bus-off must put runtime into terminal link-failed state. */
    m.regs[0x20/4]=0x20;
    assert(!cd_kernel_can_poll(&board,2));
    assert(board.runtime.link_failed && !flash.recovery_required);
    puts("PASS: mailbox-to-kernel transport integration");
    return 0;
}
