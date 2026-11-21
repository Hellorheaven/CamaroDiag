#include "flexcan.h"

#define MCR 0x00u
#define TIMER 0x08u
#define ESR 0x20u
#define IFLAG2 0x2Cu
#define IFLAG1 0x30u
#define MB(n) (0x80u + 16u * (n))
#define CODE(v) (((v) >> 24) & 0xFu)
#define IDE UINT32_C(0x00200000)
#define SRR UINT32_C(0x00400000)
#define RTR UINT32_C(0x00100000)
#define NOT_READY UINT32_C(0x89000000)
#define BUS_OFF UINT32_C(0x00000020)

static bool rd(cd_flexcan *c, uint32_t o, uint32_t *v) {
    return c->bus.read(c->bus.context, o, v);
}
static bool wr(cd_flexcan *c, uint32_t o, uint32_t v) {
    return c->bus.write(c->bus.context, o, v);
}
static uint32_t flag_reg(unsigned n) { return n < 32u ? IFLAG1 : IFLAG2; }
static uint32_t flag(unsigned n) { return UINT32_C(1) << (n % 32u); }
static uint32_t encode_id(uint32_t id, bool ext) { return ext ? id : id << 18; }
static bool healthy(cd_flexcan *c) {
    uint32_t mcr, esr;
    return !c->fault && c->bus.service(c->bus.context) &&
        rd(c, MCR, &mcr) && !(mcr & NOT_READY) &&
        rd(c, ESR, &esr) && !(esr & BUS_OFF);
}
static bool failed(cd_flexcan *c) { c->fault = true; return false; }

bool cd_flexcan_bind(cd_flexcan *c, const cd_flexcan_bus *b,
    unsigned rx, unsigned tx, uint32_t req, uint32_t res, bool ext)
{
    uint32_t limit = ext ? UINT32_C(0x1FFFFFFF) : UINT32_C(0x7FF);
    if (!c || !b || !b->read || !b->write || !b->service ||
        rx >= 64u || tx >= 64u || rx == tx || req > limit || res > limit ||
        req == res) return false;
    *c = (cd_flexcan){*b, rx, tx, req, res, ext, false};
    uint32_t mcr, cs, id, ignored;
    /* Verify existing RX configuration. Never take over an active mailbox. */
    if (!healthy(c) || !rd(c, MCR, &mcr) ||
        rx > (mcr & 0x3Fu) || tx > (mcr & 0x3Fu) ||
        !rd(c, MB(tx), &cs) || CODE(cs) != 8u ||
        !rd(c, MB(rx), &cs)) return failed(c);
    bool configured = CODE(cs) == 4u && !!(cs & IDE) == ext &&
        rd(c, MB(rx)+4u, &id) && id == encode_id(req, ext);
    if (!rd(c, TIMER, &ignored) || !configured) return failed(c);
    return true;
}

cd_can_poll cd_flexcan_receive(cd_flexcan *c, cd_can_frame *frame)
{
    if (!c || !frame) return CD_CAN_ERROR;
    uint32_t v, cs, id, a, b, ignored;
    unsigned n = c->rx_mb;
    if (!healthy(c) || !rd(c, flag_reg(n), &v)) goto error;
    if (!(v & flag(n))) return CD_CAN_EMPTY;
    if (!rd(c, MB(n), &cs)) goto error;
    /* Reading CS locks the mailbox. Read TIMER to unlock even on errors. */
    unsigned code = CODE(cs);
    if (code & 1u) {
        if (!rd(c, TIMER, &ignored)) goto error;
        return CD_CAN_EMPTY;
    }
    bool ok = rd(c, MB(n)+4u, &id) && rd(c, MB(n)+8u, &a) &&
        rd(c, MB(n)+12u, &b);
    if (!wr(c, flag_reg(n), flag(n))) ok = false;
    if (!rd(c, TIMER, &ignored)) ok = false;
    if (!ok || code != 2u) goto error;
    unsigned length = (cs >> 16) & 15u;
    bool ext = !!(cs & IDE);
    if ((cs & RTR) || length > 8u || ext != c->extended ||
        id != encode_id(c->request_id, ext)) return CD_CAN_EMPTY;
    *frame = (cd_can_frame){c->request_id, ext, (uint8_t)length, {0}};
    for (unsigned i = 0; i < length; ++i)
        frame->bytes[i] = (uint8_t)((i < 4u ? a : b) >> (24u - 8u * (i%4u)));
    return CD_CAN_FRAME;
error:
    failed(c);
    return CD_CAN_ERROR;
}

bool cd_flexcan_send(void *context, const cd_can_frame *frame)
{
    cd_flexcan *c = context;
    if (!c || !frame || frame->length > 8u || frame->id != c->response_id ||
        frame->extended != c->extended) return false;
    unsigned n = c->tx_mb;
    uint32_t cs, a = 0, b = 0, v;
    if (!healthy(c) || !rd(c, MB(n), &cs) || CODE(cs) != 8u)
        return failed(c);
    for (unsigned i = 0; i < frame->length; ++i) {
        uint32_t byte = (uint32_t)frame->bytes[i] << (24u - 8u*(i%4u));
        if (i < 4u) a |= byte; else b |= byte;
    }
    if (!wr(c, flag_reg(n), flag(n)) ||
        !wr(c, MB(n)+4u, encode_id(frame->id, frame->extended)) ||
        !wr(c, MB(n)+8u, a) || !wr(c, MB(n)+12u, b) ||
        !wr(c, MB(n), UINT32_C(0x0C000000) | ((uint32_t)frame->length << 16) |
            (frame->extended ? IDE | SRR : 0u))) return failed(c);
    /* Only confirm transmission when hardware signals completion. The
     * service callback owns the timeout and watchdog; never retry blindly. */
    do {
        if (!healthy(c) || !rd(c, flag_reg(n), &v)) return failed(c);
    } while (!(v & flag(n)));
    return (rd(c, MB(n), &cs) && CODE(cs) == 8u &&
        wr(c, flag_reg(n), flag(n))) || failed(c);
}
