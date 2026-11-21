#include "kernel_runtime.h"

bool cd_kernel_runtime_init(cd_kernel_runtime *r,
    cd_flash_controller *flash, const cd_isotp_channel *channel,
    uint8_t receive_block_size, uint8_t receive_st_min)
{
    if (r == NULL || !cd_kernel_init(&r->kernel, flash) ||
        !cd_isotp_rx_init(&r->rx, channel, r->request, sizeof(r->request),
            receive_block_size, receive_st_min) ||
        !cd_isotp_tx_init(&r->tx, channel)) return false;
    r->destructive_response = false;
    r->link_failed = false;
    return true;
}

void cd_kernel_runtime_link_failure(cd_kernel_runtime *r)
{
    if (r == NULL) return;
    if (r->destructive_response || r->kernel.remaining != 0)
        r->kernel.flash->recovery_required = true;
    r->link_failed = true;
    cd_isotp_rx_release(&r->rx);
    cd_isotp_tx_release(&r->tx);
}

static bool potential_write(const uint8_t *req, size_t length)
{
    return length >= 2 &&
        ((req[0] == 0x3D && req[1] == 0x05) ||
         (req[0] == 0x36 && req[1] == 0x00));
}

bool cd_kernel_runtime_step(cd_kernel_runtime *r,
    const cd_can_frame *incoming, uint32_t now_us)
{
    if (r == NULL || r->link_failed) return false;
    if (!r->kernel.flash->bus.service(r->kernel.flash->bus.context)) {
        cd_kernel_runtime_link_failure(r);
        return false;
    }
    /* A completed single-frame response must not consume the next request. */
    if (r->tx.state == CD_ISOTP_TX_COMPLETE) {
        cd_isotp_tx_release(&r->tx);
        r->destructive_response = false;
    }
    if (r->tx.state == CD_ISOTP_TX_WAIT_FLOW && incoming != NULL)
        cd_isotp_tx_flow(&r->tx, incoming, now_us);
    cd_isotp_tx_state tx = cd_isotp_tx_poll(&r->tx, now_us);
    if (tx == CD_ISOTP_TX_FAILED) {
        cd_kernel_runtime_link_failure(r);
        return false;
    }
    if (tx == CD_ISOTP_TX_COMPLETE) {
        cd_isotp_tx_release(&r->tx);
        r->destructive_response = false;
        /* A frame processed as flow control must never become a new request. */
        return true;
    }
    if (tx != CD_ISOTP_TX_IDLE) return true;

    cd_isotp_rx_result rx = cd_isotp_rx_poll(&r->rx, now_us);
    if (rx == CD_ISOTP_TIMEOUT && r->kernel.remaining != 0) {
        cd_kernel_runtime_link_failure(r);
        return false;
    }
    if (incoming == NULL) return true;
    rx = cd_isotp_rx_frame(&r->rx, incoming, now_us);
    if (rx == CD_ISOTP_SEND_FAILED ||
        ((rx == CD_ISOTP_INVALID || rx == CD_ISOTP_TIMEOUT) && r->kernel.remaining != 0)) {
        cd_kernel_runtime_link_failure(r);
        return false;
    }
    if (rx != CD_ISOTP_COMPLETE) return true;

    bool destructive = potential_write(r->request, r->rx.used);
    if (destructive) {
        /* Announce the long operation before touching flash. The board send
         * callback must confirm transmission, not just enqueue the frame.
         */
        const uint8_t pending[] = {0x7F, r->request[0], 0x78};
        if (!cd_isotp_tx_start(&r->tx, pending, sizeof(pending), now_us)) {
            cd_kernel_runtime_link_failure(r);
            return false;
        }
        cd_isotp_tx_release(&r->tx);
    }

    r->destructive_response = destructive;
    size_t size = cd_kernel_process(&r->kernel, r->request, r->rx.used,
        r->response, sizeof(r->response));
    cd_isotp_rx_release(&r->rx);
    if (size == 0 || !cd_isotp_tx_start(&r->tx, r->response, size, now_us)) {
        cd_kernel_runtime_link_failure(r);
        return false;
    }
    return true;
}
