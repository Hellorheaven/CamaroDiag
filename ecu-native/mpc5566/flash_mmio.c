#include "flash_mmio.h"
#include <stddef.h>

/* Real MPC5566 memory-mapped bus access. Build only as freestanding 32-bit
 * big-endian PowerPC. Not included in any deployed CamaroDiag executable.
 * Deliberately no board initialization, lock bypass or automatic watchdog
 * disabling. The E39-specific integration is still a qualification task.
 */
#if !defined(__powerpc__) || defined(__powerpc64__) || \
    !defined(__BYTE_ORDER__) || __BYTE_ORDER__ != __ORDER_BIG_ENDIAN__
#error "MPC5566 MMIO requires a 32-bit big-endian PowerPC target"
#endif

#define FLASH_REG_BASE UINT32_C(0xC3F88000)

static uint32_t read32(uint32_t address)
{
    uint32_t value;
    /* Register-indexed form also works at flash address zero without a
     * null-pointer dereference in the freestanding C abstract machine.
     */
    __asm__ volatile("lwzx %0,0,%1" : "=r"(value) : "r"(address) : "memory");
    return value;
}

static void write32(uint32_t address, uint32_t value)
{
    __asm__ volatile("stwx %0,0,%1" : : "r"(value), "r"(address) : "memory");
}

static bool reg_read(void *context, uint32_t offset, uint32_t *value)
{
    (void)context;
    if (value == NULL || offset > CD_HSR || offset % 4u != 0) return false;
    *value = read32(FLASH_REG_BASE + offset);
    return true;
}

static bool reg_write(void *context, uint32_t offset, uint32_t value)
{
    (void)context;
    /* Never write lock registers, shadow control or arbitrary peripherals. */
    if (offset != CD_MCR && offset != CD_LMSR && offset != CD_HSR) return false;
    write32(FLASH_REG_BASE + offset, value);
    return true;
}

static bool word_read(void *context, uint32_t address, uint32_t *value)
{
    (void)context;
    if (value == NULL || address % 4u != 0 ||
        address > CD_MPC5566_FLASH_SIZE - 4u) return false;
    *value = read32(address);
    return true;
}

static bool word_write(void *context, uint32_t address, uint32_t value)
{
    (void)context;
    if (address % 4u != 0 || address < CD_E39_PROTECTED_END ||
        address > CD_MPC5566_FLASH_SIZE - 4u) return false;
    write32(address, value);
    return true;
}

static void barrier(void *context)
{
    (void)context;
    __asm__ volatile("sync" ::: "memory");
}

static bool service(void *context)
{
    cd_mpc5566_mmio_context *c = context;
    return c->service_board(c->board_context);
}

bool cd_mpc5566_mmio_bind(cd_flash_bus *bus, cd_mpc5566_mmio_context *context)
{
    if (bus == NULL || context == NULL || context->service_board == NULL) return false;
    *bus = (cd_flash_bus){context, reg_read, reg_write, word_read, word_write, barrier, service};
    return true;
}
