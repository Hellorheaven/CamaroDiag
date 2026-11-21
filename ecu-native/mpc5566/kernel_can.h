#ifndef CAMARODIAG_KERNEL_CAN_H
#define CAMARODIAG_KERNEL_CAN_H
#include "flexcan.h"
#include "kernel_runtime.h"

typedef struct {
    cd_flexcan can;
    cd_kernel_runtime runtime;
} cd_kernel_can;

/* Only use after MCU/board initialization and dedicated mailbox setup. */
bool cd_kernel_can_bind(cd_kernel_can *board, cd_flash_controller *flash,
    const cd_flexcan_bus *can_bus, unsigned rx_mb, unsigned tx_mb,
    uint32_t request_id, uint32_t response_id, bool extended,
    uint32_t timeout_us, uint8_t block_size, uint8_t st_min);
bool cd_kernel_can_poll(cd_kernel_can *board, uint32_t now_us);
#endif
