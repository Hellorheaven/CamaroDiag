#ifndef CAMARODIAG_MPC5566_FLASH_MMIO_H
#define CAMARODIAG_MPC5566_FLASH_MMIO_H
#include "flash_controller.h"

typedef struct {
    void *board_context;
    /* Must keep the actual E39 board watchdog active and report bounded time. */
    bool (*service_board)(void *board_context);
} cd_mpc5566_mmio_context;

/* The caller is responsible for RAM residency, interrupt/exception vectors,
 * MCU identity, uncached flash access and board power/watchdogs.
 * This function binds the real silicon register backend; it does not start IO.
 */
bool cd_mpc5566_mmio_bind(cd_flash_bus *bus, cd_mpc5566_mmio_context *context);

#endif
