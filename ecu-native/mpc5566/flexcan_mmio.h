#ifndef CAMARODIAG_FLEXCAN_MMIO_H
#define CAMARODIAG_FLEXCAN_MMIO_H
#include "flexcan.h"

typedef struct {
    uint32_t base;
    void *board_context;
    bool (*service_board)(void *);
} cd_flexcan_mmio;

/* Board initialization and dedicated mailbox ownership are prerequisites. */
bool cd_flexcan_mmio_bind(cd_flexcan_bus *bus, cd_flexcan_mmio *context);
#endif
