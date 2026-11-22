#ifndef CAMARODIAG_FLASH_MODEL_H
#define CAMARODIAG_FLASH_MODEL_H
#include "../flash_controller.h"

/* Simulate every writable main-array address, not only the first two low blocks. */
#define CD_MODEL_FLASH_BYTES (CD_MPC5566_FLASH_SIZE - CD_E39_PROTECTED_END)

typedef struct {
    uint32_t regs[6];
    uint32_t words[CD_MODEL_FLASH_BYTES / 4u];
    uint32_t interlock;
    uint32_t last_erase_lm, last_erase_high, last_erase_address;
    uint32_t pending[2];
    unsigned word_reads, word_writes, register_writes, pulses, pending_count;
    bool disturb_next_unit, disturb_previous_unit;
    bool stuck_selection_after_erase, lose_idle_status, busy_timeout, bad_peg, erase_corruption, selection_error, ecc_after_pulse, ecc_on_read;
} model;

void init_model(model *m);
cd_flash_controller controller_for(model *m);
#endif
