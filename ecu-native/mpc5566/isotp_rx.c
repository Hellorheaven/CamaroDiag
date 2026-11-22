#include "isotp.h"

bool cd_isotp_channel_valid(const cd_isotp_channel *channel)
{
    if (channel == NULL || channel->send == NULL ||
        channel->timeout_us == 0 || channel->timeout_us >= UINT32_C(0x80000000) ||
        channel->request_id == channel->response_id) return false;
    uint32_t max = channel->extended ? UINT32_C(0x1FFFFFFF) : UINT32_C(0x7FF);
    return channel->request_id <= max && channel->response_id <= max;
}

bool cd_isotp_st_min_us(uint8_t encoded, uint32_t *microseconds)
{
    if (microseconds == NULL) return false;
    if (encoded <= 0x7F) {
        *microseconds = (uint32_t)encoded * 1000u;
        return true;
    }
    if (encoded >= 0xF1 && encoded <= 0xF9) {
        *microseconds = (encoded - 0xF0u) * 100u;
        return true;
    }
    return false;
}

bool cd_isotp_rx_init(cd_isotp_rx *rx, const cd_isotp_channel *channel,
    uint8_t *buffer, size_t capacity, uint8_t block_size, uint8_t st_min)
{
    uint32_t st;
    if (rx == NULL || !cd_isotp_channel_valid(channel) || buffer == NULL ||
        capacity < 8 || capacity > CD_ISOTP_MAX ||
        !cd_isotp_st_min_us(st_min, &st)) return false;
    rx->channel = *channel;
    rx->buffer = buffer;
    rx->capacity = capacity;
    rx->block_size = block_size;
    rx->st_min = st_min;
    cd_isotp_rx_release(rx);
    return true;
}

void cd_isotp_rx_release(cd_isotp_rx *rx)
{
    if (rx == NULL) return;
    rx->used = 0;
    rx->total = 0;
    rx->sequence = 1;
    rx->block_received = 0;
    rx->receiving = false;
    rx->complete = false;
    rx->last_activity = 0;
}

static bool flow_control(cd_isotp_rx *rx, uint8_t status)
{
    cd_can_frame reply = {rx->channel.response_id, rx->channel.extended, 8, {0}};
    for (unsigned i = 0; i < 8; ++i) reply.bytes[i] = rx->channel.padding;
    reply.bytes[0] = 0x30u | status;
    reply.bytes[1] = rx->block_size;
    reply.bytes[2] = rx->st_min;
    return rx->channel.send(rx->channel.context, &reply);
}

static cd_isotp_rx_result reject(cd_isotp_rx *rx)
{
    cd_isotp_rx_release(rx);
    return CD_ISOTP_INVALID;
}

cd_isotp_rx_result cd_isotp_rx_poll(cd_isotp_rx *rx, uint32_t now_us)
{
    if (rx == NULL || !rx->receiving) return CD_ISOTP_IGNORED;
    /* Unsigned subtraction supports the board's wrapping microsecond clock. */
    if (now_us - rx->last_activity >= rx->channel.timeout_us) {
        cd_isotp_rx_release(rx);
        return CD_ISOTP_TIMEOUT;
    }
    return CD_ISOTP_RECEIVING;
}

cd_isotp_rx_result cd_isotp_rx_frame(cd_isotp_rx *rx,
    const cd_can_frame *frame, uint32_t now_us)
{
    if (rx == NULL || frame == NULL ||
        frame->id != rx->channel.request_id ||
        frame->extended != rx->channel.extended)
        return CD_ISOTP_IGNORED;
    if (frame->length == 0 || frame->length > 8) return reject(rx);
    if (cd_isotp_rx_poll(rx, now_us) == CD_ISOTP_TIMEOUT) return CD_ISOTP_TIMEOUT;
    uint8_t kind = frame->bytes[0] >> 4;
    if (kind == 3) return CD_ISOTP_IGNORED; /* handled by outgoing state */
    if (rx->complete) return CD_ISOTP_INVALID; /* caller owns completed data */
    if (kind == 0) {
        size_t length = frame->bytes[0] & 0x0F;
        if (rx->receiving || length == 0 || length > 7 ||
            length > frame->length - 1u || length > rx->capacity) return reject(rx);
        for (size_t i = 0; i < length; ++i) rx->buffer[i] = frame->bytes[1 + i];
        rx->used = rx->total = length;
        rx->complete = true;
        return CD_ISOTP_COMPLETE;
    }
    if (kind == 1) {
        if (rx->receiving || frame->length != 8) return reject(rx);
        size_t length = ((size_t)(frame->bytes[0] & 0x0F) << 8) | frame->bytes[1];
        if (length <= 7) return reject(rx);
        if (length > rx->capacity) {
            bool sent = flow_control(rx, 2); /* overflow, no allocation */
            cd_isotp_rx_release(rx);
            return sent ? CD_ISOTP_INVALID : CD_ISOTP_SEND_FAILED;
        }
        for (size_t i = 0; i < 6; ++i) rx->buffer[i] = frame->bytes[2 + i];
        rx->used = 6;
        rx->total = length;
        rx->sequence = 1;
        rx->block_received = 0;
        rx->last_activity = now_us;
        rx->receiving = true;
        if (!flow_control(rx, 0)) {
            cd_isotp_rx_release(rx);
            return CD_ISOTP_SEND_FAILED;
        }
        return CD_ISOTP_RECEIVING;
    }
    if (kind != 2 || !rx->receiving ||
        (frame->bytes[0] & 0x0F) != rx->sequence || frame->length < 2)
        return reject(rx);
    size_t remaining = rx->total - rx->used;
    size_t count = remaining > 7 ? 7 : remaining;
    if (frame->length - 1u < count) return reject(rx);
    for (size_t i = 0; i < count; ++i) rx->buffer[rx->used + i] = frame->bytes[1 + i];
    rx->used += count;
    rx->sequence = (rx->sequence + 1u) & 0x0Fu;
    rx->last_activity = now_us;
    if (rx->used == rx->total) {
        rx->receiving = false;
        rx->complete = true;
        return CD_ISOTP_COMPLETE;
    }
    if (rx->block_size != 0 && ++rx->block_received == rx->block_size) {
        rx->block_received = 0;
        if (!flow_control(rx, 0)) {
            cd_isotp_rx_release(rx);
            return CD_ISOTP_SEND_FAILED;
        }
    }
    return CD_ISOTP_RECEIVING;
}
