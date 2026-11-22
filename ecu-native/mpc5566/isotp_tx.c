#include "isotp.h"

bool cd_isotp_tx_init(cd_isotp_tx *tx, const cd_isotp_channel *channel)
{
    if (tx == NULL || !cd_isotp_channel_valid(channel)) return false;
    tx->channel = *channel;
    cd_isotp_tx_release(tx);
    return true;
}

void cd_isotp_tx_release(cd_isotp_tx *tx)
{
    if (tx == NULL) return;
    tx->payload = NULL;
    tx->size = tx->sent = 0;
    tx->last_activity = tx->not_before = tx->separation_us = 0;
    tx->sequence = 1;
    tx->block_size = tx->block_sent = tx->wait_frames = 0;
    tx->state = CD_ISOTP_TX_IDLE;
}

static cd_can_frame frame_for(const cd_isotp_tx *tx)
{
    cd_can_frame frame = {tx->channel.response_id, tx->channel.extended, 8, {0}};
    for (unsigned i = 0; i < 8; ++i) frame.bytes[i] = tx->channel.padding;
    return frame;
}

static bool send_frame(cd_isotp_tx *tx, const cd_can_frame *frame)
{
    if (tx->channel.send(tx->channel.context, frame)) return true;
    tx->state = CD_ISOTP_TX_FAILED;
    return false;
}

bool cd_isotp_tx_start(cd_isotp_tx *tx, const uint8_t *payload,
    size_t length, uint32_t now_us)
{
    if (tx == NULL || tx->state != CD_ISOTP_TX_IDLE || payload == NULL ||
        length == 0 || length > CD_ISOTP_MAX) return false;
    cd_can_frame frame = frame_for(tx);
    tx->payload = payload;
    tx->size = length;
    tx->last_activity = now_us;
    if (length <= 7) {
        frame.bytes[0] = (uint8_t)length;
        for (size_t i = 0; i < length; ++i) frame.bytes[i + 1] = payload[i];
        if (!send_frame(tx, &frame)) return false;
        tx->sent = length;
        tx->state = CD_ISOTP_TX_COMPLETE;
        return true;
    }
    frame.bytes[0] = 0x10u | (uint8_t)(length >> 8);
    frame.bytes[1] = (uint8_t)length;
    for (size_t i = 0; i < 6; ++i) frame.bytes[i + 2] = payload[i];
    if (!send_frame(tx, &frame)) return false;
    tx->sent = 6;
    tx->state = CD_ISOTP_TX_WAIT_FLOW;
    return true;
}

void cd_isotp_tx_flow(cd_isotp_tx *tx, const cd_can_frame *frame, uint32_t now_us)
{
    if (tx == NULL || frame == NULL || tx->state != CD_ISOTP_TX_WAIT_FLOW ||
        frame->id != tx->channel.request_id ||
        frame->extended != tx->channel.extended ||
        frame->length == 0 || frame->length > 8 || frame->bytes[0] >> 4 != 3)
        return;
    if (now_us - tx->last_activity >= tx->channel.timeout_us || frame->length < 3) {
        tx->state = CD_ISOTP_TX_FAILED;
        return;
    }
    uint8_t flow = frame->bytes[0] & 0x0F;
    if (flow == 1) {
        if (++tx->wait_frames > 3) tx->state = CD_ISOTP_TX_FAILED;
        else tx->last_activity = now_us;
        return;
    }
    if (flow != 0 || !cd_isotp_st_min_us(frame->bytes[2], &tx->separation_us) ||
        tx->separation_us >= tx->channel.timeout_us) {
        tx->state = CD_ISOTP_TX_FAILED;
        return;
    }
    tx->block_size = frame->bytes[1];
    tx->block_sent = 0;
    tx->wait_frames = 0;
    tx->last_activity = now_us;
    tx->not_before = now_us + tx->separation_us;
    tx->state = CD_ISOTP_TX_SENDING;
}

cd_isotp_tx_state cd_isotp_tx_poll(cd_isotp_tx *tx, uint32_t now_us)
{
    if (tx == NULL) return CD_ISOTP_TX_FAILED;
    if (tx->state != CD_ISOTP_TX_WAIT_FLOW && tx->state != CD_ISOTP_TX_SENDING)
        return tx->state;
    if (now_us - tx->last_activity >= tx->channel.timeout_us) {
        tx->state = CD_ISOTP_TX_FAILED;
        return tx->state;
    }
    if (tx->state == CD_ISOTP_TX_WAIT_FLOW ||
        (int32_t)(now_us - tx->not_before) < 0) return tx->state;
    cd_can_frame frame = frame_for(tx);
    frame.bytes[0] = 0x20u | tx->sequence;
    size_t remaining = tx->size - tx->sent;
    size_t count = remaining > 7 ? 7 : remaining;
    for (size_t i = 0; i < count; ++i) frame.bytes[i + 1] = tx->payload[tx->sent + i];
    if (!send_frame(tx, &frame)) return tx->state;
    tx->sent += count;
    tx->sequence = (tx->sequence + 1u) & 0x0Fu;
    tx->last_activity = now_us;
    tx->not_before = now_us + tx->separation_us;
    if (tx->sent == tx->size) tx->state = CD_ISOTP_TX_COMPLETE;
    else if (tx->block_size != 0 && ++tx->block_sent == tx->block_size)
        tx->state = CD_ISOTP_TX_WAIT_FLOW;
    return tx->state;
}
