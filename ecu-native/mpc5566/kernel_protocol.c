#include "kernel_protocol.h"

#define NRC_FORMAT 0x13u
#define NRC_CONDITIONS 0x22u
#define NRC_SEQUENCE 0x24u
#define NRC_RANGE 0x31u
#define NRC_PROGRAM 0x72u

static uint32_t be(const uint8_t *p, unsigned n)
{
    uint32_t v = 0;
    while (n--) v = (v << 8) | *p++;
    return v;
}

static size_t negative(uint8_t *out, size_t capacity, uint8_t service, uint8_t code)
{
    if (capacity < 3) return 0;
    out[0] = 0x7F; out[1] = service; out[2] = code;
    return 3;
}

static bool readable(uint32_t address, uint32_t length)
{
    return address < CD_MPC5566_FLASH_SIZE && length != 0 &&
        length <= CD_MPC5566_FLASH_SIZE - address;
}

static bool read_status(cd_kernel *k)
{
    uint32_t status;
    cd_flash_bus *bus = &k->flash->bus;
    return !k->flash->recovery_required &&
        bus->service(bus->context) &&
        bus->read_reg(bus->context, CD_MCR, &status) &&
        (status & CD_MCR_DONE) != 0 &&
        (status & (CD_MCR_EHV | CD_MCR_PGM | CD_MCR_ERS | CD_MCR_PSUS |
                   CD_MCR_ESUS | CD_MCR_STOP | CD_MCR_RWE | CD_MCR_EER)) == 0;
}

static bool byte_at(cd_kernel *k, uint32_t address, uint8_t *out)
{
    cd_flash_bus *bus = &k->flash->bus;
    uint32_t word;
    if (!bus->service(bus->context) ||
        !bus->read_word(bus->context, address & ~UINT32_C(3), &word))
        return false;
    *out = (uint8_t)(word >> (8u * (3u - (address & 3u))));
    return true;
}

bool cd_kernel_init(cd_kernel *k, cd_flash_controller *flash)
{
    if (k == NULL || flash == NULL || flash->recovery_required) return false;
    k->flash = flash;
    k->next_address = 0;
    k->remaining = 0;
    return true;
}

static size_t flash_crc(cd_kernel *k, const uint8_t *req, size_t n,
    uint8_t *out, size_t cap)
{
    if (n != 8) return negative(out, cap, 0x3D, NRC_FORMAT);
    const uint32_t length = be(req + 2, 3), address = be(req + 5, 3);
    if (!readable(address, length)) return negative(out, cap, 0x3D, NRC_RANGE);
    if (cap < 6) return 0;
    if (!read_status(k)) return negative(out, cap, 0x3D, NRC_CONDITIONS);
    uint32_t crc = 0;
    for (uint32_t offset = 0; offset < length; ++offset) {
        uint8_t byte;
        if (!byte_at(k, address + offset, &byte))
            return negative(out, cap, 0x3D, NRC_CONDITIONS);
        crc ^= (uint32_t)byte << 24;
        for (unsigned bit = 0; bit < 8; ++bit)
            crc = (crc << 1) ^ ((crc & UINT32_C(0x80000000)) ?
                UINT32_C(0x04C11DB7) : 0);
    }
    if (!read_status(k)) return negative(out, cap, 0x3D, NRC_CONDITIONS);
    out[0] = 0x7D; out[1] = 0x02;
    for (unsigned i = 0; i < 4; ++i) out[2 + i] = (uint8_t)(crc >> (24 - 8 * i));
    return 6;
}

static size_t flash_read(cd_kernel *k, const uint8_t *req, size_t n,
    uint8_t *out, size_t cap)
{
    if (n < 2 || (req[1] != 0x14 && req[1] != 0x24) ||
        n != (req[1] == 0x14 ? 7u : 8u))
        return negative(out, cap, 0x23, NRC_FORMAT);
    uint32_t address = be(req + 2, 4), length = be(req + 6, req[1] >> 4);
    if (!readable(address, length) || length >= CD_KERNEL_MAX_PAYLOAD)
        return negative(out, cap, 0x23, NRC_RANGE);
    if (cap < length + 1u) return 0;
    if (!read_status(k)) return negative(out, cap, 0x23, NRC_CONDITIONS);
    for (uint32_t i = 0; i < length; ++i)
        if (!byte_at(k, address + i, out + 1 + i))
            return negative(out, cap, 0x23, NRC_CONDITIONS);
    if (!read_status(k)) return negative(out, cap, 0x23, NRC_CONDITIONS);
    out[0] = 0x63;
    return length + 1u;
}

static size_t flash_erase(cd_kernel *k, const uint8_t *req, size_t n,
    uint8_t *out, size_t cap)
{
    if (n != 5) return negative(out, cap, 0x3D, NRC_FORMAT);
    if (k->remaining != 0) return negative(out, cap, 0x3D, NRC_SEQUENCE);
    const uint32_t address = be(req + 2, 3);
    cd_flash_block block;
    if (cap < 3) return 0;
    if (!cd_flash_block_for(address, &block) ||
        !cd_e39_erase_block(address, block.size, &block))
        return negative(out, cap, 0x3D, NRC_RANGE);
    if (cd_flash_erase(k->flash, address, block.size) != CD_FLASH_OK)
        return negative(out, cap, 0x3D, NRC_PROGRAM);
    k->next_address = address;
    k->remaining = block.size;
    out[0] = 0x7D; out[1] = 0x05; out[2] = 0;
    return 3;
}

static size_t flash_write(cd_kernel *k, const uint8_t *req, size_t n,
    uint8_t *out, size_t cap)
{
    if (n < 9 || (req[1] != 0x00 && req[1] != 0x44))
        return negative(out, cap, 0x36, NRC_FORMAT);
    const uint32_t length = be(req + 2, 2), address = be(req + 4, 3);
    if (length != n - 9u) return negative(out, cap, 0x36, NRC_FORMAT);
    cd_flash_block block;
    if (!cd_e39_program_span(address, length, &block))
        return negative(out, cap, 0x36, NRC_RANGE);
    uint32_t sum = 0;
    for (uint32_t i = 0; i < length; ++i) sum += req[7 + i];
    if ((sum & 0xFFFFu) != be(req + 7 + length, 2))
        return negative(out, cap, 0x36, NRC_FORMAT);
    if (cap < 3) return 0;
    if (req[1] == 0x44) {
        if (k->remaining != 0 || cd_flash_probe_program(k->flash, address, length) != CD_FLASH_OK)
            return negative(out, cap, 0x36, NRC_CONDITIONS);
        out[0] = 0x76;
        return 1;
    }
    if (k->remaining == 0 || address != k->next_address || length > k->remaining)
        return negative(out, cap, 0x36, NRC_SEQUENCE);
    if (cd_flash_program(k->flash, address, req + 7, length) != CD_FLASH_OK) {
        k->flash->recovery_required = true;
        return negative(out, cap, 0x36, NRC_PROGRAM);
    }
    k->next_address += length;
    k->remaining -= length;
    out[0] = 0x76;
    return 1;
}

size_t cd_kernel_process(cd_kernel *k, const uint8_t *req, size_t n,
    uint8_t *out, size_t cap)
{
    if (k == NULL || k->flash == NULL || req == NULL || n == 0 ||
        out == NULL || cap < 3) return 0;
    uint8_t sid = req[0];
    if (n > CD_KERNEL_MAX_PAYLOAD) return negative(out, cap, sid, NRC_FORMAT);
    /* Native capability identification does not exist on the stock ECU.
     * It is only used after loading our separately qualified RAM runtime.
     */
    if (sid == 0x3D && n == 2 && req[1] == 0x00) {
        if (cap < 10) return 0;
        out[0] = 0x7D; out[1] = 0;
        out[2] = 'C'; out[3] = 'D'; out[4] = '3'; out[5] = '9';
        out[6] = CD_KERNEL_ABI_MAJOR; out[7] = CD_KERNEL_ABI_MINOR;
        out[8] = CD_MPC5566_BLOCK_COUNT;
        out[9] = (uint8_t)(k->flash->recovery_required || k->remaining != 0);
        return 10;
    }
    if (k->flash->recovery_required) return negative(out, cap, sid, NRC_CONDITIONS);
    if (sid == 0x23) return flash_read(k, req, n, out, cap);
    if (sid == 0x36) return flash_write(k, req, n, out, cap);
    if (sid == 0x3D && n >= 2) {
        if (req[1] == 0x02) return flash_crc(k, req, n, out, cap);
        if (req[1] == 0x05) return flash_erase(k, req, n, out, cap);
    }
    return negative(out, cap, sid, 0x11);
}
