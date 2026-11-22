#include "../isotp.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>

typedef struct {
    cd_can_frame frames[700];
    size_t count;
    bool fail;
} capture;
static bool send(void *context, const cd_can_frame *frame)
{
    capture *c = context;
    if (c->fail || c->count >= 700) return false;
    c->frames[c->count++] = *frame;
    return true;
}
static cd_can_frame frame(uint8_t first, uint8_t second, uint8_t third)
{
    cd_can_frame f = {0x7E0, false, 8, {first, second, third, 3, 4, 5, 6, 7}};
    return f;
}

int main(void)
{
    static capture c;
    cd_isotp_channel channel = {0x7E0, 0x7E8, false, 0xCC, 1000000, &c, send};
    uint8_t data[CD_ISOTP_MAX];
    cd_isotp_rx rx;
    cd_isotp_tx tx;
    assert(cd_isotp_rx_init(&rx, &channel, data, sizeof(data), 2, 0xF1));
    assert(cd_isotp_tx_init(&tx, &channel));
    cd_can_frame f = frame(0x03, 0x22, 0xF1);
    assert(cd_isotp_rx_frame(&rx, &f, 0) == CD_ISOTP_COMPLETE);
    assert(rx.used == 3 && data[0] == 0x22 && data[1] == 0xF1);
    cd_isotp_rx_release(&rx);

    f = frame(0x10, 21, 0);
    assert(cd_isotp_rx_frame(&rx, &f, 100) == CD_ISOTP_RECEIVING);
    assert(c.count == 1 && c.frames[0].id == 0x7E8 &&
        c.frames[0].bytes[0] == 0x30 && c.frames[0].bytes[1] == 2 &&
        c.frames[0].bytes[2] == 0xF1);
    f = frame(0x21, 8, 9);
    assert(cd_isotp_rx_frame(&rx, &f, 200) == CD_ISOTP_RECEIVING);
    f = frame(0x22, 10, 11);
    assert(cd_isotp_rx_frame(&rx, &f, 300) == CD_ISOTP_RECEIVING && c.count == 2);
    f = frame(0x23, 12, 13);
    assert(cd_isotp_rx_frame(&rx, &f, 400) == CD_ISOTP_COMPLETE && rx.used == 21);
    cd_isotp_rx_release(&rx);

    f = frame(0x10, 18, 0);
    assert(cd_isotp_rx_frame(&rx, &f, 500) == CD_ISOTP_RECEIVING);
    f = frame(0x22, 0, 0);
    assert(cd_isotp_rx_frame(&rx, &f, 600) == CD_ISOTP_INVALID && !rx.receiving);
    f = frame(0x10, 18, 0);
    assert(cd_isotp_rx_frame(&rx, &f, UINT32_MAX - 50) == CD_ISOTP_RECEIVING);
    assert(cd_isotp_rx_poll(&rx, 100) == CD_ISOTP_RECEIVING);
    assert(cd_isotp_rx_poll(&rx, 1000000) == CD_ISOTP_TIMEOUT);
    f = frame(0x1F, 0xFF, 0);
    assert(cd_isotp_rx_frame(&rx, &f, 0) == CD_ISOTP_INVALID);
    assert(c.frames[c.count - 1].bytes[0] == 0x32); /* oversized first frame */
    f = frame(0x05, 0, 0); f.length = 4;
    assert(cd_isotp_rx_frame(&rx, &f, 0) == CD_ISOTP_INVALID);
    f = frame(0x10, 20, 0); f.id = 0x7E1;
    assert(cd_isotp_rx_frame(&rx, &f, 0) == CD_ISOTP_IGNORED);
    f.id = 0x7E0; f.extended = true;
    assert(cd_isotp_rx_frame(&rx, &f, 0) == CD_ISOTP_IGNORED);

    c.count = 0;
    for (size_t i = 0; i < sizeof(data); ++i) data[i] = (uint8_t)i;
    assert(cd_isotp_tx_start(&tx, data, 3, 0));
    assert(tx.state == CD_ISOTP_TX_COMPLETE && c.count == 1 &&
        c.frames[0].bytes[0] == 3 && c.frames[0].bytes[4] == 0xCC);
    cd_isotp_tx_release(&tx);
    assert(cd_isotp_tx_start(&tx, data, 40, 100));
    assert(tx.state == CD_ISOTP_TX_WAIT_FLOW);
    f = frame(0x30, 2, 5); /* block of two, 5 ms separation */
    cd_isotp_tx_flow(&tx, &f, 200);
    assert(cd_isotp_tx_poll(&tx, 5199) == CD_ISOTP_TX_SENDING && tx.sent == 6);
    assert(cd_isotp_tx_poll(&tx, 5200) == CD_ISOTP_TX_SENDING && tx.sent == 13);
    assert(cd_isotp_tx_poll(&tx, 10200) == CD_ISOTP_TX_WAIT_FLOW && tx.sent == 20);
    f = frame(0x30, 0, 0xF2);
    cd_isotp_tx_flow(&tx, &f, 10300);
    assert(cd_isotp_tx_poll(&tx, 10500) == CD_ISOTP_TX_SENDING && tx.sent == 27);
    assert(cd_isotp_tx_poll(&tx, 10700) == CD_ISOTP_TX_SENDING);
    assert(cd_isotp_tx_poll(&tx, 10900) == CD_ISOTP_TX_COMPLETE && tx.sent == 40);
    cd_isotp_tx_release(&tx);
    assert(cd_isotp_tx_start(&tx, data, 20, 0));
    f = frame(0x31, 0, 0);
    for (unsigned i = 0; i < 4; ++i) cd_isotp_tx_flow(&tx, &f, i + 1);
    assert(tx.state == CD_ISOTP_TX_FAILED);
    cd_isotp_tx_release(&tx);
    assert(cd_isotp_tx_start(&tx, data, 20, 0));
    f = frame(0x32, 0, 0);
    cd_isotp_tx_flow(&tx, &f, 1);
    assert(tx.state == CD_ISOTP_TX_FAILED);
    cd_isotp_tx_release(&tx);
    assert(cd_isotp_tx_start(&tx, data, 20, 0));
    f = frame(0x30, 0, 0x80);
    cd_isotp_tx_flow(&tx, &f, 1);
    assert(tx.state == CD_ISOTP_TX_FAILED);
    cd_isotp_tx_release(&tx);
    assert(cd_isotp_tx_start(&tx, data, 20, 0));
    assert(cd_isotp_tx_poll(&tx, 1000000) == CD_ISOTP_TX_FAILED);
    cd_isotp_tx_release(&tx);

    /* Full-size transport transfer, including every four-bit sequence wrap. */
    c.count = 0;
    assert(cd_isotp_tx_start(&tx, data, sizeof(data), 0));
    f = frame(0x30, 0, 0);
    cd_isotp_tx_flow(&tx, &f, 1);
    while (tx.state == CD_ISOTP_TX_SENDING) cd_isotp_tx_poll(&tx, 2);
    assert(tx.state == CD_ISOTP_TX_COMPLETE && tx.sent == sizeof(data));
    assert(c.frames[0].bytes[0] == 0x1F && c.frames[0].bytes[1] == 0xFB);
    assert(c.frames[16].bytes[0] == 0x20 && c.frames[17].bytes[0] == 0x21);
    uint8_t rebuilt[CD_ISOTP_MAX];
    capture control = {0};
    channel.context = &control;
    assert(cd_isotp_rx_init(&rx, &channel, rebuilt, sizeof(rebuilt), 0, 0));
    for (size_t i = 0; i < c.count; ++i) {
        cd_can_frame incoming = c.frames[i]; incoming.id = 0x7E0;
        cd_isotp_rx_result result = cd_isotp_rx_frame(&rx, &incoming, (uint32_t)i);
        assert(result == (i + 1 == c.count ? CD_ISOTP_COMPLETE : CD_ISOTP_RECEIVING));
    }
    assert(rx.used == sizeof(data) && !memcmp(data, rebuilt, sizeof(data)));
    puts("PASS: native ISO-TP transport reassembly, timing and sequence tests");
    return 0;
}
