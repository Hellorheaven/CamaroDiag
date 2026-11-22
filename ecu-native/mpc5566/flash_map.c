#include "flash_map.h"
#include <stddef.h>

/* Independent implementation of MPC5566RM rev. 2 section 13.3,
 * table 13-4 (NXP). The board/installed ECU is not qualified by this map.
 * All erase operations select a single entire silicon block. */
bool cd_flash_block_at(unsigned index, cd_flash_block *out)
{
    static const uint32_t boundaries[] = {
        0u, 0x4000u, 0x10000u, 0x1C000u, 0x20000u, 0x30000u, 0x40000u
    };
    if (out == NULL || index >= CD_MPC5566_BLOCK_COUNT) return false;
    out->lm_select = 0;
    out->h_select = 0;
    if (index < 6u) {
        out->start = boundaries[index];
        out->size = boundaries[index + 1u] - out->start;
        out->lm_select = UINT32_C(1) << index;
    } else {
        out->start = 0x40000u + (index - 6u) * 0x20000u;
        out->size = 0x20000u;
        if (index < 8u) out->lm_select = UINT32_C(1) << (16u + index - 6u);
        else out->h_select = UINT32_C(1) << (index - 8u);
    }
    return true;
}

bool cd_flash_block_for(uint32_t address, cd_flash_block *out)
{
    if (out == NULL || address >= CD_MPC5566_FLASH_SIZE) return false;
    for (unsigned i = 0; i < CD_MPC5566_BLOCK_COUNT; ++i) {
        cd_flash_block block;
        if (!cd_flash_block_at(i, &block)) return false;
        if (address >= block.start && address - block.start < block.size) {
            *out = block;
            return true;
        }
    }
    return false;
}

bool cd_e39_erase_block(uint32_t address, uint32_t size, cd_flash_block *out)
{
    cd_flash_block block;
    if (out == NULL || address < CD_E39_PROTECTED_END ||
        !cd_flash_block_for(address, &block) ||
        address != block.start || size != block.size) return false;
    *out = block;
    return true;
}

bool cd_e39_program_span(uint32_t address, uint32_t size, cd_flash_block *out)
{
    cd_flash_block block;
    if (out == NULL || address < CD_E39_PROTECTED_END || size == 0 ||
        address % CD_MPC5566_PROGRAM_UNIT != 0 ||
        size % CD_MPC5566_PROGRAM_UNIT != 0 ||
        !cd_flash_block_for(address, &block) ||
        size > block.size - (address - block.start)) return false;
    *out = block;
    return true;
}
