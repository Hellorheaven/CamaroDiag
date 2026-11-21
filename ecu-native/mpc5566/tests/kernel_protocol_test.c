#include "flash_model.h"
#include "../kernel_protocol.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>

static size_t process(cd_kernel *k, const uint8_t *req, size_t n, uint8_t *out)
{
    return cd_kernel_process(k, req, n, out, CD_KERNEL_MAX_PAYLOAD);
}
static uint8_t out[CD_KERNEL_MAX_PAYLOAD];
static uint8_t req[CD_KERNEL_MAX_PAYLOAD];

static size_t build_write(uint8_t mode, uint32_t address, uint16_t size, uint8_t value)
{
    req[0] = 0x36; req[1] = mode;
    req[2] = (uint8_t)(size >> 8); req[3] = (uint8_t)size;
    req[4] = (uint8_t)(address >> 16); req[5] = (uint8_t)(address >> 8);
    req[6] = (uint8_t)address;
    memset(req + 7, value, size);
    uint32_t sum = (uint32_t)value * size;
    req[7 + size] = (uint8_t)(sum >> 8);
    req[8 + size] = (uint8_t)sum;
    return size + 9u;
}

static uint32_t independent_crc(const uint8_t *data, size_t length)
{
    uint32_t crc = 0;
    while (length--) {
        crc ^= (uint32_t)*data++ << 24;
        for (unsigned i = 0; i < 8; ++i)
            crc = (crc & 0x80000000u) ? (crc << 1) ^ 0x04C11DB7u : crc << 1;
    }
    return crc;
}

int main(void)
{
    static model m;
    init_model(&m);
    cd_flash_controller flash = controller_for(&m);
    cd_kernel kernel;
    assert(cd_kernel_init(&kernel, &flash));
    const uint8_t handshake[] = {0x3D, 0};
    assert(process(&kernel, handshake, sizeof(handshake), out) == 10);
    assert(!memcmp(out, (uint8_t[]){0x7D, 0, 'C', 'D', '3', '9', 1, 0, 28, 0}, 10));

    size_t n = build_write(0x44, 0x20000, 8, 0x55);
    assert(process(&kernel, req, n, out) == 1 && out[0] == 0x76);
    assert(m.word_writes == 0 && m.pulses == 0);
    req[n - 1] ^= 1;
    assert(process(&kernel, req, n, out) == 3 && out[2] == 0x13);
    n = build_write(0, 0x20000, 8, 0x55);
    assert(process(&kernel, req, n, out) == 3 && out[2] == 0x24);
    n = build_write(0x44, 0x1FFF8, 8, 0x55);
    assert(process(&kernel, req, n, out) == 3 && out[2] == 0x31);

    const uint8_t erase[] = {0x3D, 5, 2, 0, 0};
    const uint8_t wrong_erase[] = {0x3D, 5, 2, 0, 8};
    assert(process(&kernel, wrong_erase, sizeof(wrong_erase), out) == 3 && out[2] == 0x31);
    assert(process(&kernel, erase, sizeof(erase), out) == 3 && out[0] == 0x7D);
    assert(kernel.remaining == 0x10000 && m.pulses == 1);
    assert(process(&kernel, erase, sizeof(erase), out) == 3 && out[2] == 0x24);
    assert(process(&kernel, handshake, sizeof(handshake), out) == 10 && out[9] == 1);
    n = build_write(0, 0x20008, 8, 0x55);
    assert(process(&kernel, req, n, out) == 3 && out[2] == 0x24);
    n = build_write(0, 0x20000, 8, 0x55);
    assert(process(&kernel, req, n, out) == 1 && out[0] == 0x76);
    assert(kernel.remaining == 0xFFF8 && kernel.next_address == 0x20008);
    assert(process(&kernel, req, n, out) == 3 && out[2] == 0x24);
    /* Finish remaining sector using aligned, all-erased chunks. */
    while (kernel.remaining) {
        uint16_t size = (uint16_t)(kernel.remaining > 4080 ? 4080 : kernel.remaining);
        n = build_write(0, kernel.next_address, size, 0xFF);
        assert(process(&kernel, req, n, out) == 1 && out[0] == 0x76);
    }
    assert(process(&kernel, handshake, sizeof(handshake), out) == 10 && out[9] == 0);

    const uint8_t read[] = {0x23, 0x14, 0, 2, 0, 0, 8};
    assert(process(&kernel, read, sizeof(read), out) == 9 && out[0] == 0x63);
    for (unsigned i = 1; i < 9; ++i) assert(out[i] == 0x55);
    const uint8_t crc[] = {0x3D, 2, 0, 0, 8, 2, 0, 0};
    assert(process(&kernel, crc, sizeof(crc), out) == 6 && out[0] == 0x7D);
    uint32_t observed = ((uint32_t)out[2] << 24) | ((uint32_t)out[3] << 16) |
        ((uint32_t)out[4] << 8) | out[5];
    uint8_t expected[8]; memset(expected, 0x55, sizeof(expected));
    assert(observed == independent_crc(expected, sizeof(expected)));
    const uint8_t shadow[] = {0x23, 0x14, 0, 0xFF, 0xFC, 0, 8};
    assert(process(&kernel, shadow, sizeof(shadow), out) == 3 && out[2] == 0x31);
    const uint8_t bad[] = {0x36, 0, 0xFF, 0xFF, 2, 0, 0, 0, 0};
    assert(process(&kernel, bad, sizeof(bad), out) == 3 && out[2] == 0x13);
    assert(cd_kernel_process(&kernel, read, sizeof(read), out, 8) == 0);

    init_model(&m);
    flash = controller_for(&m);
    assert(cd_kernel_init(&kernel, &flash));
    assert(process(&kernel, erase, sizeof(erase), out) == 3 && out[0] == 0x7D);
    m.busy_timeout = true;
    n = build_write(0, 0x20000, 8, 1);
    assert(process(&kernel, req, n, out) == 3 && out[2] == 0x72);
    assert(process(&kernel, handshake, sizeof(handshake), out) == 10 && out[9] == 1);
    assert(process(&kernel, read, sizeof(read), out) == 3 && out[2] == 0x22);
    puts("PASS: native E39 RAM-kernel diagnostic protocol");
    return 0;
}
