#ifndef CAMARODIAG_FLASH_MODEL_H
#define CAMARODIAG_FLASH_MODEL_H
#include "../flash_controller.h"

typedef struct {
    uint32_t regs[6];
    uint32_t words[0x20000 / 4];
    uint32_t interlock;
    uint32_t pending[2];
    unsigned word_writes, register_writes, pulses, pending_count;
    bool busy_timeout, bad_peg, erase_corruption, selection_error;
} model;

void init_model(model *m);
cd_flash_controller controller_for(model *m);
#endif
