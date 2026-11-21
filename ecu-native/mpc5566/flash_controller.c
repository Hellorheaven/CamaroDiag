#include "flash_controller.h"
#include <stddef.h>

/* Flash erase/program ordering independently implemented from NXP
 * MPC5566RM rev.2 sections 13.4.2.3 and 13.4.2.4.
 * No shadow writes, no automatic unlock, no automatic retry or reset.
 * Caller must run from RAM with valid exception vectors and watchdog service.
 */
#define ACTIVE_MASK (CD_MCR_EHV | CD_MCR_ESUS | CD_MCR_ERS | CD_MCR_PSUS | CD_MCR_PGM | CD_MCR_STOP)
#define ERROR_MASK (CD_MCR_RWE | CD_MCR_EER)
#define LM_MASK UINT32_C(0x0003003F)
#define HIGH_MASK UINT32_C(0x000FFFFF)

bool cd_flash_init(cd_flash_controller *c, const cd_flash_bus *bus)
{
    if (c == NULL || bus == NULL || bus->read_reg == NULL ||
        bus->write_reg == NULL || bus->read_word == NULL ||
        bus->write_word == NULL || bus->barrier == NULL || bus->service == NULL)
        return false;
    c->bus = *bus;
    c->recovery_required = false;
    return true;
}

static bool read_reg(cd_flash_controller *c, uint32_t reg, uint32_t *v)
{
    return c->bus.read_reg(c->bus.context, reg, v);
}

static bool write_reg(cd_flash_controller *c, uint32_t reg, uint32_t v)
{
    if (!c->bus.write_reg(c->bus.context, reg, v)) return false;
    c->bus.barrier(c->bus.context);
    return true;
}

static bool write_word(cd_flash_controller *c, uint32_t address, uint32_t v)
{
    if (!c->bus.write_word(c->bus.context, address, v)) return false;
    c->bus.barrier(c->bus.context);
    return true;
}

static bool change_mcr(cd_flash_controller *c, uint32_t set, uint32_t clear)
{
    uint32_t value;
    if (!read_reg(c, CD_MCR, &value)) return false;
    /* Error flags are W1C. Never clear them incidentally with RMW. */
    value &= ~ERROR_MASK;
    return write_reg(c, CD_MCR, (value | set) & ~clear);
}

static cd_flash_result fail_uncertain(cd_flash_controller *c)
{
    c->recovery_required = true;
    return CD_FLASH_RECOVERY_REQUIRED;
}

static cd_flash_result require_ready(cd_flash_controller *c, const cd_flash_block *block)
{
    uint32_t mcr, lm, slm, hi;
    if (c->recovery_required) return CD_FLASH_RECOVERY_REQUIRED;
    if (!c->bus.service(c->bus.context) || !read_reg(c, CD_MCR, &mcr) ||
        !read_reg(c, CD_LMLR, &lm) || !read_reg(c, CD_SLMLR, &slm) ||
        !read_reg(c, CD_HLR, &hi)) return CD_FLASH_IO_FAILURE;
    if ((mcr & (ACTIVE_MASK | ERROR_MASK)) != 0 || (mcr & CD_MCR_DONE) == 0)
        return CD_FLASH_NOT_READY;
    /* Primary + secondary locks both apply to low/mid blocks. */
    if (((lm | slm) & block->lm_select) != 0 || (hi & block->h_select) != 0)
        return CD_FLASH_LOCKED;
    return CD_FLASH_OK;
}

/* Only called after the controller entered erase/program mode.
 * Never end an unconfirmed high-voltage operation on timeout.
 * The caller must stop issuing flash commands and use bench recovery.
 */
static cd_flash_result finish_pulse(cd_flash_controller *c, uint32_t mode)
{
    uint32_t status;
    c->recovery_required = true;
    if (!change_mcr(c, CD_MCR_EHV, 0)) return fail_uncertain(c);
    for (;;) {
        if (!c->bus.service(c->bus.context) || !read_reg(c, CD_MCR, &status))
            return fail_uncertain(c);
        if ((status & CD_MCR_DONE) != 0) break;
    }
    const bool success = (status & CD_MCR_PEG) != 0 &&
        (status & (ERROR_MASK | CD_MCR_PEAS)) == 0;
    /* Separate EHV-off and mode-off transitions. DONE must already be set. */
    if (!change_mcr(c, 0, CD_MCR_EHV) || !change_mcr(c, 0, mode))
        return fail_uncertain(c);
    if (!read_reg(c, CD_MCR, &status) ||
        (status & (ACTIVE_MASK | ERROR_MASK)) != 0 || !success)
        return fail_uncertain(c);
    c->recovery_required = false;
    return CD_FLASH_OK;
}

cd_flash_result cd_flash_probe_program(cd_flash_controller *c,
    uint32_t address, uint32_t size)
{
    cd_flash_block block;
    if (c == NULL || !cd_e39_program_span(address, size, &block)) return CD_FLASH_INVALID;
    return require_ready(c, &block);
}

cd_flash_result cd_flash_erase(cd_flash_controller *c, uint32_t address, uint32_t size)
{
    cd_flash_block block;
    uint32_t selected_lm, selected_high;
    if (c == NULL || !cd_e39_erase_block(address, size, &block)) return CD_FLASH_INVALID;
    cd_flash_result ready = require_ready(c, &block);
    if (ready != CD_FLASH_OK) return ready;
    c->recovery_required = true;
    if (!change_mcr(c, CD_MCR_ERS, 0) ||
        !write_reg(c, CD_LMSR, block.lm_select) ||
        !write_reg(c, CD_HSR, block.h_select) ||
        !read_reg(c, CD_LMSR, &selected_lm) ||
        !read_reg(c, CD_HSR, &selected_high) ||
        (selected_lm & LM_MASK) != block.lm_select ||
        (selected_high & HIGH_MASK) != block.h_select ||
        !write_word(c, address, UINT32_MAX))
        return fail_uncertain(c);
    cd_flash_result completed = finish_pulse(c, CD_MCR_ERS);
    if (completed != CD_FLASH_OK) return completed;
    c->recovery_required = true;
    if (!write_reg(c, CD_LMSR, 0) || !write_reg(c, CD_HSR, 0))
        return fail_uncertain(c);
    for (uint32_t offset = 0; offset < size; offset += 4u) {
        uint32_t actual;
        if (!c->bus.service(c->bus.context) ||
            !c->bus.read_word(c->bus.context, address + offset, &actual) ||
            actual != UINT32_MAX) return fail_uncertain(c);
    }
    c->recovery_required = false;
    return CD_FLASH_OK;
}

static uint32_t read_be32(const uint8_t *data)
{
    return ((uint32_t)data[0] << 24) | ((uint32_t)data[1] << 16) |
        ((uint32_t)data[2] << 8) | data[3];
}

cd_flash_result cd_flash_program(cd_flash_controller *c, uint32_t address,
    const uint8_t *bytes, uint32_t size)
{
    cd_flash_block block;
    if (c == NULL || bytes == NULL ||
        !cd_e39_program_span(address, size, &block)) return CD_FLASH_INVALID;
    cd_flash_result ready = require_ready(c, &block);
    if (ready != CD_FLASH_OK) return ready;
    /* Check all programming units first. Avoid a second ECC programming pulse
     * on any unit; the host must provide a complete previously erased sector.
     */
    for (uint32_t offset = 0; offset < size; offset += 4u) {
        uint32_t actual;
        if (!c->bus.service(c->bus.context) ||
            !c->bus.read_word(c->bus.context, address + offset, &actual))
            return CD_FLASH_IO_FAILURE;
        if (actual != UINT32_MAX) return CD_FLASH_NOT_ERASED;
    }
    for (uint32_t offset = 0; offset < size; offset += CD_MPC5566_PROGRAM_UNIT) {
        const uint32_t first = read_be32(bytes + offset);
        const uint32_t second = read_be32(bytes + offset + 4u);
        uint32_t actual_first, actual_second;
        if (first == UINT32_MAX && second == UINT32_MAX) continue;
        if (!c->bus.service(c->bus.context)) return fail_uncertain(c);
        c->recovery_required = true;
        /* Two 32-bit words in one aligned doubleword, within a single page. */
        if (!change_mcr(c, CD_MCR_PGM, 0) ||
            !write_word(c, address + offset, first) ||
            !write_word(c, address + offset + 4u, second))
            return fail_uncertain(c);
        cd_flash_result completed = finish_pulse(c, CD_MCR_PGM);
        if (completed != CD_FLASH_OK) return completed;
        c->recovery_required = true;
        if (!c->bus.read_word(c->bus.context, address + offset, &actual_first) ||
            !c->bus.read_word(c->bus.context, address + offset + 4u, &actual_second) ||
            actual_first != first || actual_second != second)
            return fail_uncertain(c);
        c->recovery_required = false;
    }
    return CD_FLASH_OK;
}
