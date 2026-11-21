#include "flash_model.h"
#include <assert.h>
#include <stdio.h>

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
    /* A different flash silicon geometry or prior failed pulse is never ready. */
    m.regs[CD_MCR / 4] ^= UINT32_C(0x01000000);
    assert(cd_flash_probe_program(&c, 0x20000, 8) == CD_FLASH_NOT_READY);
    assert(!cd_flash_init(&c, &c.bus));
    m.regs[CD_MCR / 4] ^= UINT32_C(0x01000000);
    m.regs[CD_MCR / 4] &= ~CD_MCR_PEG;
    assert(cd_flash_probe_program(&c, 0x20000, 8) == CD_FLASH_NOT_READY);
    m.regs[CD_MCR / 4] |= CD_MCR_PEG;
    m.regs[CD_MCR / 4] |= CD_MCR_PEAS;
    assert(cd_flash_probe_program(&c, 0x20000, 8) == CD_FLASH_NOT_READY);
    m.regs[CD_MCR / 4] &= ~CD_MCR_PEAS;
    assert(cd_flash_init(&c, &c.bus));
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

    init_model(&m);
    c = controller_for(&m);
    m.ecc_after_pulse = true;
    assert(cd_flash_erase(&c, 0x20000, 0x10000) == CD_FLASH_RECOVERY_REQUIRED);
    assert(m.words[0] == UINT32_MAX && (m.regs[0] & CD_MCR_EER));
    assert(c.recovery_required); /* correct bytes alone cannot prove a valid erase */
    init_model(&m);
    c = controller_for(&m);
    m.lose_idle_status = true;
    assert(cd_flash_erase(&c, 0x20000, 0x10000) == CD_FLASH_RECOVERY_REQUIRED);
    assert(c.recovery_required && m.pulses == 1 && m.words[0] == UINT32_MAX);
    assert(cd_flash_program(&c, 0x20000, data, 8) == CD_FLASH_RECOVERY_REQUIRED);
    assert(m.pulses == 1);

    init_model(&m);
    c = controller_for(&m);
    assert(cd_flash_erase(&c, 0x20000, 0x10000) == CD_FLASH_OK);
    m.lose_idle_status = true;
    assert(cd_flash_program(&c, 0x20000, data, 8) == CD_FLASH_RECOVERY_REQUIRED);
    assert(c.recovery_required && m.words[0] == 0x01020304);
    for (unsigned high = 0; high < 2; ++high) {
        init_model(&m);
        c = controller_for(&m);
        m.stuck_selection_after_erase = true;
        assert(cd_flash_erase(&c, high ? 0x80000 : 0x20000, high ? 0x20000 : 0x10000)
            == CD_FLASH_RECOVERY_REQUIRED);
        assert(m.pulses == 1 && c.recovery_required);
        assert(cd_flash_probe_program(&c, 0x20000, 8) == CD_FLASH_RECOVERY_REQUIRED);
    }
    /* Independently exercise middle and high select bits, including last block. */
    const uint32_t addresses[] = {0x40000, 0x60000, 0x80000, 0x2E0000};
    const uint32_t lm[] = {0x10000, 0x20000, 0, 0};
    const uint32_t hi[] = {0, 0, 1, 0x80000};
    for (unsigned i = 0; i < 4; ++i) {
        init_model(&m);
        c = controller_for(&m);
        uint32_t addr = addresses[i];
        uint32_t start = (addr - CD_E39_PROTECTED_END) / 4u;
        uint32_t end = start + 0x20000 / 4u;
        assert(cd_flash_erase(&c, addr, 0x20000) == CD_FLASH_OK);
        assert(m.last_erase_address == addr && m.last_erase_lm == lm[i] &&
            m.last_erase_high == hi[i] && m.pulses == 1);
        assert(m.words[start - 1] == 0 && m.words[start] == UINT32_MAX &&
            m.words[end - 1] == UINT32_MAX);
        if (end < CD_MODEL_FLASH_BYTES / 4u) assert(m.words[end] == 0);
        assert(cd_flash_program(&c, addr + 0x1FFF8, data, 8) == CD_FLASH_OK);
        assert(m.words[end - 2] == 0x01020304 && m.words[end - 1] == 0xA0B0C0D0);
        assert(!c.recovery_required);
    }
    puts("PASS: MPC5566 register-level erase/program model");
    return 0;
}
