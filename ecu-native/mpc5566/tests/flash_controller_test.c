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
