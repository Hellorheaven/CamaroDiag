#ifndef CAMARODIAG_NATIVE_KERNEL_RUNTIME_H
#define CAMARODIAG_NATIVE_KERNEL_RUNTIME_H
#include "kernel_protocol.h"
#include "isotp.h"

typedef struct {
    cd_kernel kernel;
    cd_isotp_rx rx;
    cd_isotp_tx tx;
    uint8_t request[CD_ISOTP_MAX];
    uint8_t response[CD_ISOTP_MAX];
    bool initialized;
    bool destructive_response;
    bool link_failed;
    bool write_idle_armed;
    uint32_t write_idle_since;
} cd_kernel_runtime;

bool cd_kernel_runtime_init(cd_kernel_runtime *runtime,
    cd_flash_controller *flash, const cd_isotp_channel *channel,
    uint8_t receive_block_size, uint8_t receive_st_min);
bool cd_kernel_runtime_step(cd_kernel_runtime *runtime,
    const cd_can_frame *incoming, uint32_t now_us);
/* Board transport must call this after a CAN overrun, transmit error or bus-off. */
void cd_kernel_runtime_link_failure(cd_kernel_runtime *runtime);

#endif
