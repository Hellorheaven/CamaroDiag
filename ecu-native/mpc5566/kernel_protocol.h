#ifndef CAMARODIAG_MPC5566_KERNEL_PROTOCOL_H
#define CAMARODIAG_MPC5566_KERNEL_PROTOCOL_H
#include "flash_controller.h"
#include <stddef.h>

#define CD_KERNEL_MAX_PAYLOAD 4091u
#define CD_KERNEL_ABI_MAJOR 1u
#define CD_KERNEL_ABI_MINOR 0u

typedef struct {
    cd_flash_controller *flash;
    uint32_t next_address;
    uint32_t remaining;
} cd_kernel;

bool cd_kernel_init(cd_kernel *kernel, cd_flash_controller *flash);
/* Receives one fully reassembled diagnostic request, not CAN frames.
 * A board-specific, RAM-resident ISO-TP transport is required to invoke it.
 * Return is the number of response bytes, zero on invalid output buffer.
 */
size_t cd_kernel_process(cd_kernel *kernel, const uint8_t *request,
    size_t request_size, uint8_t *response, size_t response_capacity);

#endif
