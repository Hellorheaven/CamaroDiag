#include "kernel_can.h"

bool cd_kernel_can_bind(cd_kernel_can *board, cd_flash_controller *flash,
    const cd_flexcan_bus *can_bus, unsigned rx_mb, unsigned tx_mb,
    uint32_t request_id, uint32_t response_id, bool extended,
    uint32_t timeout_us, uint8_t block_size, uint8_t st_min)
{
    if (!board) return false;
    board->initialized = false;
    if (!flash || !cd_flexcan_bind(&board->can, can_bus, rx_mb,
        tx_mb, request_id, response_id, extended)) return false;
    cd_isotp_channel channel = {request_id, response_id, extended, 0,
        timeout_us, &board->can, cd_flexcan_send};
    board->initialized = cd_kernel_runtime_init(&board->runtime, flash, &channel,
        block_size, st_min);
    return board->initialized;
}
bool cd_kernel_can_poll(cd_kernel_can *board, uint32_t now_us)
{
    if (!board || !board->initialized || board->runtime.link_failed) return false;
    cd_can_frame frame;
    cd_can_poll result=cd_flexcan_receive(&board->can,&frame);
    if (result==CD_CAN_ERROR) {
        cd_kernel_runtime_link_failure(&board->runtime);
        return false;
    }
    return cd_kernel_runtime_step(&board->runtime,
        result==CD_CAN_FRAME ? &frame : NULL, now_us);
}
