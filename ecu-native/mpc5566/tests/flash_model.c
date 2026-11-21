#include "flash_model.h"
#include <assert.h>
#include <string.h>



void init_model(model *m)
{
    memset(m, 0, sizeof(*m));
    m->regs[CD_MCR / 4] = CD_MCR_MPC5566_GEOMETRY | CD_MCR_DONE | CD_MCR_PEG;
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
        assert(m->interlock >= CD_E39_PROTECTED_END && m->interlock < CD_MPC5566_FLASH_SIZE);
        if (value & CD_MCR_ERS) {
            cd_flash_block block;
            assert(cd_flash_block_for(m->interlock, &block));
            assert(m->interlock == block.start);
            m->last_erase_lm = m->regs[CD_LMSR / 4];
            m->last_erase_high = m->regs[CD_HSR / 4];
            m->last_erase_address = m->interlock;
            assert(m->regs[CD_LMSR / 4] == block.lm_select);
            assert(m->regs[CD_HSR / 4] == block.h_select);
            unsigned start = (m->interlock - CD_E39_PROTECTED_END) / 4u;
            for (unsigned i = 0; i < block.size / 4u; ++i)
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
    if ((m->regs[0] & CD_MCR_EHV) || address < CD_E39_PROTECTED_END ||
        address >= CD_MPC5566_FLASH_SIZE || address % 4) return false;
    ++m->word_reads;
    *out = m->words[(address - 0x20000) / 4];
    if (m->ecc_on_read || (m->ecc_after_pulse && m->pulses)) m->regs[0] |= CD_MCR_EER;
    return true;
}

static bool write_word(void *ctx, uint32_t address, uint32_t value)
{
    model *m = ctx;
    if (address < CD_E39_PROTECTED_END || address >= CD_MPC5566_FLASH_SIZE || address % 4) return false;
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

cd_flash_controller controller_for(model *m)
{
    cd_flash_bus bus = {m, read_reg, write_reg, read_word, write_word, barrier, service};
    cd_flash_controller c;
    assert(cd_flash_init(&c, &bus));
    return c;
}


