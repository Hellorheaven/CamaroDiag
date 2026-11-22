#ifndef CAMARODIAG_FLEXCAN_H
#define CAMARODIAG_FLEXCAN_H
#include "isotp.h"

/* MPC5566 RM, chapter 22. This driver requires an already initialized,
 * dedicated CAN module/mailboxes; it never changes clock, pin or bit timing. */
typedef struct {
    void *context;
    bool (*read)(void *, uint32_t, uint32_t *);
    bool (*write)(void *, uint32_t, uint32_t);
    bool (*service)(void *);
} cd_flexcan_bus;

typedef struct {
    cd_flexcan_bus bus;
    unsigned rx_mb, tx_mb;
    uint32_t request_id, response_id;
    bool extended, fault;
} cd_flexcan;

typedef enum { CD_CAN_EMPTY, CD_CAN_FRAME, CD_CAN_ERROR } cd_can_poll;
bool cd_flexcan_bind(cd_flexcan *can, const cd_flexcan_bus *bus,
    unsigned rx_mb, unsigned tx_mb, uint32_t request_id, uint32_t response_id,
    bool extended);
cd_can_poll cd_flexcan_receive(cd_flexcan *can, cd_can_frame *frame);
bool cd_flexcan_send(void *context, const cd_can_frame *frame);
#endif
