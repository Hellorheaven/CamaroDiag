#include "../flash_controller.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>

/* A register/bus model, not a real ECU or a certification test. */
typedef struct {
    uint32_t regs[6];
    uint32_t words[0x20000 / 4];
    uint32_t interlock;
    uint32_t pending[2];
    unsigned word_writes, register_writes, pulses, pending_count;
    bool busy_timeout, bad_peg, erase_corruption, selection_error;
} model;

static void init_model(model *m)
{
    memset(m, 0, sizeof(*m));
    m->regs[CD_MCR / 4] = CD_MCR_DONE | CD_MCR_PEG;
}

static bool read_reg(void *ctx, uint32_t offset, uint32_t *out)
{
    model *m = ctx;
    if (offset > CD_HSR || offset % 4) return false;
    *out = m->regs[offset / 4];
    return true;
}

static bool write_reg(void *ctx, uint32_t offset, uint32_t value)
{
    model *m = ctx;
    if (offset > CD_HSR || offset % 4) return false;
    ++m->register_writes;
    if (offset == CD_LMSR && m->selection_error && value) {
        m->regs[offset / 4] = value | 1u;
        return true;
    }
    if (offset != CD_MCR) {
        m->regs[offset / 4] = value;
        return true;
    }
    uint32_t old = m->regs[0];
    m->regs[0] = value;
    if ((value & CD_MCR_EHV) && !(old & CD_MCR_EHV)) {
        ++m->pulses;
        m->regs[0] &= ~(CD_MCR_DONE | CD_MCR_PEG);
        if (m->busy_timeout) return true;
        assert(m->interlock >= 0x20000 && m->interlock < 0x40000);
        if (value & CD_MCR_ERS) {
            assert(m->regs[CD_HSR / 4] == 0);
            assert(m->regs[CD_LMSR / 4] == 0x10 || m->regs[CD_LMSR / 4] == 0x20);
            unsigned start = (m->interlock - 0x20000) / 4;
            for (unsigned i = 0; i < 0x10000 / 4; ++i)
                m->words[start + i] = UINT32_MAX;
            if (m->erase_corruption) m->words[start] = 0;
        } else {
            assert(value & CD_MCR_PGM);
            assert(m->pending_count == 2);
            unsigned start = (m->interlock - 0x20000) / 4;
            m->words[start] &= m->pending[0];
            m->words[start + 1] &= m->pending[1];
        }
        m->pending_count = 0;
        m->regs[0] |= CD_MCR_DONE;
        if (!m->bad_peg) m->regs[0] |= CD_MCR_PEG;
    }
    return true;
}

static bool read_word(void *ctx, uint32_t address, uint32_t *out)
{
    model *m = ctx;
    if ((m->regs[0] & CD_MCR_EHV) || address < 0x20000 ||
        address >= 0x40000 || address % 4) return false;
    *out = m->words[(address - 0x20000) / 4];
    return true;
}

static bool write_word(void *ctx, uint32_t address, uint32_t value)
{
    model *m = ctx;
    if (address < 0x20000 || address >= 0x40000 || address % 4) return false;
    ++m->word_writes;
    if (m->regs[0] & CD_MCR_ERS) {
        m->interlock = address;
        return true;
    }
    if (!(m->regs[0] & CD_MCR_PGM) || m->pending_count >= 2) return false;
    if (m->pending_count == 0) m->interlock = address;
    else if (address != m->interlock + 4) return false;
    m->pending[m->pending_count++] = value;
    return true;
}

static void barrier(void *context) { (void)context; }
static bool service(void *ctx)
{
    model *m = ctx;
    return !(m->busy_timeout && (m->regs[0] & CD_MCR_EHV));
}

static cd_flash_controller controller_for(model *m)
{
    cd_flash_bus bus = {m, read_reg, write_reg, read_word, write_word, barrier, service};
    cd_flash_controller c;
    assert(cd_flash_init(&c, &bus));
    return c;
}

int main(void)
{
    static model m;
    const uint8_t data[16] = {
        0x01, 0x02, 0x03, 0x04, 0xA0, 0xB0, 0xC0, 0xD0,
        0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF
    };
    init_model(&m);
    cd_flash_controller c = controller_for(&m);
    assert(cd_flash_erase(&c, 0x10000, 0xC000) == CD_FLASH_INVALID);
    assert(cd_flash_erase(&c, 0x20000, 0x1000) == CD_FLASH_INVALID);
    assert(cd_flash_program(&c, 0x1FFF8, data, 8) == CD_FLASH_INVALID);
    assert(m.register_writes == 0 && m.word_writes == 0);
    m.regs[CD_SLMLR / 4] = 0x10;
    assert(cd_flash_erase(&c, 0x20000, 0x10000) == CD_FLASH_LOCKED);
    assert(m.register_writes == 0);
    m.regs[CD_SLMLR / 4] = 0;
    assert(cd_flash_program(&c, 0x20000, data, 16) == CD_FLASH_NOT_ERASED);
    assert(m.pulses == 0);
    assert(cd_flash_erase(&c, 0x20000, 0x10000) == CD_FLASH_OK);
    assert(m.pulses == 1 && m.words[0] == UINT32_MAX &&
        m.words[0x10000 / 4 - 1] == UINT32_MAX &&
        m.words[0x10000 / 4] == 0);
    assert(cd_flash_program(&c, 0x20000, data, 16) == CD_FLASH_OK);
    assert(m.pulses == 2 && m.words[0] == 0x01020304 &&
        m.words[1] == 0xA0B0C0D0 && m.words[2] == UINT32_MAX);
    assert(cd_flash_program(&c, 0x20000, data, 8) == CD_FLASH_NOT_ERASED);
    assert(m.pulses == 2 && !c.recovery_required);

    init_model(&m);
    c = controller_for(&m);
    m.selection_error = true;
    assert(cd_flash_erase(&c, 0x20000, 0x10000) == CD_FLASH_RECOVERY_REQUIRED);
    assert(m.pulses == 0 && c.recovery_required);

    init_model(&m);
    c = controller_for(&m);
    m.busy_timeout = true;
    assert(cd_flash_erase(&c, 0x20000, 0x10000) == CD_FLASH_RECOVERY_REQUIRED);
    assert((m.regs[0] & CD_MCR_EHV) != 0); /* never abort blindly */
    unsigned pulses = m.pulses;
    assert(cd_flash_erase(&c, 0x30000, 0x10000) == CD_FLASH_RECOVERY_REQUIRED);
    assert(m.pulses == pulses);

    init_model(&m);
    c = controller_for(&m);
    m.bad_peg = true;
    assert(cd_flash_erase(&c, 0x20000, 0x10000) == CD_FLASH_RECOVERY_REQUIRED);
    assert(c.recovery_required && !(m.regs[0] & CD_MCR_EHV));
    init_model(&m);
    c = controller_for(&m);
    m.erase_corruption = true;
    assert(cd_flash_erase(&c, 0x20000, 0x10000) == CD_FLASH_RECOVERY_REQUIRED);

    puts("PASS: MPC5566 register-level erase/program model");
    return 0;
}
