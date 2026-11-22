#include "kernel_runtime.h"

bool cd_kernel_runtime_init(cd_kernel_runtime *r,
    cd_flash_controller *flash, const cd_isotp_channel *channel,
    uint8_t receive_block_size, uint8_t receive_st_min)
{
    if (r == NULL) return false;
    r->initialized = false;
    if (!cd_kernel_init(&r->kernel, flash) ||
        !cd_isotp_rx_init(&r->rx, channel, r->request, sizeof(r->request),
            receive_block_size, receive_st_min) ||
        !cd_isotp_tx_init(&r->tx, channel)) return false;
    r->destructive_response = false;
    r->link_failed = false;
    r->write_idle_armed = false;
    r->write_idle_since = 0;
    r->initialized = true;
    return true;
}

void cd_kernel_runtime_link_failure(cd_kernel_runtime *r)
{
    if (r == NULL || !r->initialized) return;
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
    if (r == NULL || !r->initialized || r->link_failed) return false;
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
    /* Arm on the first poll after synchronous flash processing, so erase
     * latency is not mistaken for host inactivity. Only an accepted write
     * fragment/command refreshes the deadline, not unrelated CAN traffic. */
    if (r->kernel.remaining != 0) {
        if (!r->write_idle_armed) {
            r->write_idle_armed = true;
            r->write_idle_since = now_us;
        } else if ((uint32_t)(now_us - r->write_idle_since) >= r->rx.channel.timeout_us) {
            cd_kernel_runtime_link_failure(r);
            return false;
        }
    } else r->write_idle_armed = false;
    if (incoming == NULL) return true;
    rx = cd_isotp_rx_frame(&r->rx, incoming, now_us);
    if (rx == CD_ISOTP_SEND_FAILED ||
        ((rx == CD_ISOTP_INVALID || rx == CD_ISOTP_TIMEOUT) && r->kernel.remaining != 0)) {
        cd_kernel_runtime_link_failure(r);
        return false;
    }
    if (rx == CD_ISOTP_RECEIVING && r->write_idle_armed) {
        /* A fragmented read or arbitrary request cannot keep a partially
         * erased sector alive. The first frame already includes service/mode. */
        if (r->rx.used < 2 || r->request[0] != 0x36 || r->request[1] != 0x00) {
            cd_kernel_runtime_link_failure(r);
            return false;
        }
        r->write_idle_since = now_us;
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

    uint32_t remaining_before = r->kernel.remaining;
    r->destructive_response = destructive;
    size_t size = cd_kernel_process(&r->kernel, r->request, r->rx.used,
        r->response, sizeof(r->response));
    cd_isotp_rx_release(&r->rx);
    /* Once an erase has occurred, a rejected command is not permission to
     * retry. Deliver the diagnostic rejection, then permanently stop this
     * runtime rather than accepting another fragment on uncertain flash. */
    bool rejected_incomplete = remaining_before != 0 &&
        (size == 0 || r->response[0] != 0x76);
    if (rejected_incomplete && (size != 3u || r->response[0] != 0x7Fu)) {
        r->response[0] = 0x7Fu;
        r->response[1] = r->request[0];
        r->response[2] = 0x24u;
        size = 3u;
    }
    if (r->kernel.remaining != remaining_before) r->write_idle_armed = false;
    if (size == 0 || !cd_isotp_tx_start(&r->tx, r->response, size, now_us)) {
        cd_kernel_runtime_link_failure(r);
        return false;
    }
    if (rejected_incomplete) {
        cd_kernel_runtime_link_failure(r);
        return false;
    }
    return true;
}
