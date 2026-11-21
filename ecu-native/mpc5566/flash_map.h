#ifndef CAMARODIAG_MPC5566_FLASH_MAP_H
#define CAMARODIAG_MPC5566_FLASH_MAP_H
#include <stdint.h>
#include <stdbool.h>

#define CD_MPC5566_FLASH_SIZE UINT32_C(0x300000)
#define CD_E39_PROTECTED_END UINT32_C(0x20000)
#define CD_MPC5566_BLOCK_COUNT 28u
#define CD_MPC5566_PROGRAM_UNIT 8u

typedef struct {
    uint32_t start;
    uint32_t size;
    uint32_t lm_select;
    uint32_t h_select;
} cd_flash_block;

bool cd_flash_block_at(unsigned index, cd_flash_block *out);
bool cd_flash_block_for(uint32_t address, cd_flash_block *out);
bool cd_e39_erase_block(uint32_t address, uint32_t size, cd_flash_block *out);
bool cd_e39_program_span(uint32_t address, uint32_t size, cd_flash_block *out);

#endif
