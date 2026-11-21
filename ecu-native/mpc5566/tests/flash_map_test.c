#include "../flash_map.h"
#include <assert.h>
#include <stdio.h>

int main(void)
{
    cd_flash_block b;
    uint32_t next = 0;
    for (unsigned i = 0; i < CD_MPC5566_BLOCK_COUNT; ++i) {
        assert(cd_flash_block_at(i, &b));
        assert(b.start == next && b.size > 0);
        assert((b.lm_select == 0) != (b.h_select == 0));
        next += b.size;
        cd_flash_block end;
        assert(cd_flash_block_for(next - 1, &end));
        assert(end.start == b.start && end.size == b.size);
        assert(cd_e39_erase_block(b.start, b.size, &end) == (i >= 4));
    }
    assert(next == CD_MPC5566_FLASH_SIZE);
    assert(!cd_flash_block_at(28, &b));
    assert(!cd_flash_block_for(0x300000, &b));
    assert(!cd_flash_block_for(0xFFFC00, &b)); /* shadow isn't main flash */
    assert(cd_flash_block_at(1, &b) && b.start == 0x4000 && b.size == 0xC000);
    assert(cd_flash_block_at(4, &b) && b.lm_select == 0x10);
    assert(cd_flash_block_at(6, &b) && b.lm_select == 0x10000);
    assert(cd_flash_block_at(8, &b) && b.h_select == 1);
    assert(cd_flash_block_at(27, &b) && b.h_select == 0x80000);
    assert(!cd_e39_erase_block(0x20008, 0x10000, &b));
    assert(!cd_e39_erase_block(0x20000, 0x20000, &b));
    assert(cd_e39_program_span(0x2FFF8, 8, &b));
    assert(!cd_e39_program_span(0x2FFF8, 16, &b));
    assert(!cd_e39_program_span(0x20001, 8, &b));
    assert(!cd_e39_program_span(0x20000, 7, &b));
    assert(!cd_e39_program_span(0x20000, 0, &b));
    assert(!cd_e39_program_span(0x20000, UINT32_MAX, &b));
    assert(!cd_e39_program_span(0x1FFF8, 8, &b));
    puts("PASS: native MPC5566 physical block map");
    return 0;
}
