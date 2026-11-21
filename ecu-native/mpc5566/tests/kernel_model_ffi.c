/* HOST TEST ONLY. No memory-mapped IO, device DLL or ECU connection.
 * Exposes the actual native kernel to the managed protocol integration tests.
 */
#include "flash_model.h"
#include "../kernel_protocol.h"

static model memory;
static cd_flash_controller flash;
static cd_kernel kernel;
static bool initialized;

int cd_sim_init(const uint8_t *input, uint32_t length)
{
    initialized = false;
    if (!input || length != sizeof(memory.words)) return 0;
    init_model(&memory);
    for (uint32_t i = 0; i < length; i += 4)
        memory.words[i / 4] = ((uint32_t)input[i] << 24) |
            ((uint32_t)input[i + 1] << 16) | ((uint32_t)input[i + 2] << 8) |
            input[i + 3];
    flash = controller_for(&memory);
    initialized = cd_kernel_init(&kernel, &flash);
    return initialized ? 1 : 0;
}

int cd_sim_exchange(const uint8_t *request, uint32_t length,
    uint8_t *response, uint32_t capacity)
{
    if (!initialized || !request || !response ||
        length > CD_KERNEL_MAX_PAYLOAD || capacity > CD_KERNEL_MAX_PAYLOAD) return -1;
    return (int)cd_kernel_process(&kernel, request, length, response, capacity);
}

uint32_t cd_sim_pulses(void) { return memory.pulses; }
uint32_t cd_sim_remaining(void) { return kernel.remaining; }
int cd_sim_recovery(void) { return flash.recovery_required ? 1 : 0; }
void cd_sim_fail_next_pulse(void) { memory.bad_peg = true; }
