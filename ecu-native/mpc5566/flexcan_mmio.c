#include "flexcan_mmio.h"

#if !defined(__powerpc__) || defined(__powerpc64__) || \
    !defined(__BYTE_ORDER__) || __BYTE_ORDER__ != __ORDER_BIG_ENDIAN__
#error "MPC5566 MMIO requires 32-bit big-endian PowerPC"
#endif

static bool offset_valid(uint32_t off, bool write)
{
    if ((off & 3u) != 0) return false;
    if (off == 0x2cu || off == 0x30u) return true;
    if (!write && (off == 0 || off == 8 || off == 0x20u)) return true;
    return off >= 0x80u && off < 0x480u;
}
static bool read_reg(void *ctx, uint32_t off, uint32_t *v)
{
    cd_flexcan_mmio *c=ctx;
    if (!v || !offset_valid(off,false)) return false;
    uint32_t address=c->base+off;
    __asm__ volatile("lwzx %0,0,%1" : "=r"(*v) : "r"(address) : "memory");
    __asm__ volatile("sync" ::: "memory");
    return true;
}
static bool write_reg(void *ctx, uint32_t off, uint32_t v)
{
    cd_flexcan_mmio *c=ctx;
    if (!offset_valid(off,true)) return false;
    uint32_t address=c->base+off;
    __asm__ volatile("stwx %0,0,%1" : : "r"(v), "r"(address) : "memory");
    __asm__ volatile("sync" ::: "memory");
    return true;
}
static bool service(void *ctx)
{
    cd_flexcan_mmio *c=ctx;
    return c->service_board(c->board_context);
}
bool cd_flexcan_mmio_bind(cd_flexcan_bus *bus, cd_flexcan_mmio *c)
{
    if (!bus || !c || !c->service_board) return false;
    /* MPC5566 peripheral map, four FlexCAN instances. */
    switch (c->base) {
    case UINT32_C(0xFFFC0000): case UINT32_C(0xFFFC4000):
    case UINT32_C(0xFFFC8000): case UINT32_C(0xFFFCC000): break;
    default: return false;
    }
    *bus=(cd_flexcan_bus){c,read_reg,write_reg,service};
    return true;
}
