#include "cl1.h"
#include "cf1.h"

#include <stdio.h>
#include <string.h>

/* This parser deliberately shares no mutable state with the CF/1 Mode 02
 * parser. CL/1 uses a different wire scope, observation and receipt. */
enum { ST_INITIAL = 1, ST_INVENTORY = 2, ST_PERIODIC = 3,
       ST_SAMPLE = 4, ST_FINAL = 5, ST_WAIT = 6, ST_STOP = 7 };
enum { CL1_REPLY_LIMIT = 16 * 1024 * 1024 };
typedef struct {
    cl1_result *result;
    unsigned stage, base, index, early_identity;
    bool cycle_started;
    uint32_t cycle_start;
} replay;
static uint16_t u16(const uint8_t *p) {
    return (uint16_t)((unsigned)p[0] | ((unsigned)p[1] << 8));
}
static uint32_t u32(const uint8_t *p) {
    return (uint32_t)p[0] | ((uint32_t)p[1] << 8) |
           ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
}
static void put16(uint8_t *p, uint16_t n) {
    p[0] = (uint8_t)n; p[1] = (uint8_t)(n >> 8);
}
static void put32(uint8_t *p, uint32_t n) {
    for (unsigned i = 0; i < 4; ++i) p[i] = (uint8_t)(n >> (8 * i));
}
static int deny(char *err, size_t n, const char *reason) {
    if (n) snprintf(err, n, "%s", reason);
    return -1;
}
static bool physical_pair(uint32_t tx, uint32_t rx, bool ext) {
    if (!ext) return tx >= 0x7e0 && tx <= 0x7e7 && rx == tx + 8;
    return tx <= 0x1fffffffu && (tx & 0x1fff00ffu) == 0x18da00f1u &&
           ((tx >> 8) & 0xffu) < 0xf0u &&
           rx == (0x18daf100u | ((tx >> 8) & 255u));
}
static bool plan_valid(const cl1_plan *p) {
    if (!physical_pair(p->tx, p->rx, p->extended) || !cf1_valid_vin(p->vin) ||
        !p->pid_count || p->pid_count > CL1_MAX_PIDS ||
        !p->cycles || p->cycles > 1000 ||
        (uint32_t)p->cycles * p->pid_count > CL1_MAX_SAMPLES ||
        p->interval_ms < 100 || p->interval_ms > 60000 ||
        p->budget_ms < 1000 || p->budget_ms > 600000) return false;
    bool seen[256] = {false};
    for (unsigned i = 0; i < p->pid_count; ++i) {
        uint8_t pid = p->pids[i];
        if (!pid || pid % 32 == 0 || seen[pid]) return false;
        seen[pid] = true;
    }
    return true;
}
static int parse_plan(const uint8_t *b, const char *vin, cl1_plan *p,
                      char *err, size_t capacity) {
    memset(p, 0, sizeof(*p));
    p->tx = u32(b); p->rx = u32(b + 4);
    if (b[8] > 1) return deny(err, capacity, "CL/1 invalid CAN width flag");
    p->extended = b[8] != 0;
    memcpy(p->vin, b + 9, 17); p->vin[17] = 0;
    p->pid_count = b[26];
    if (p->pid_count > CL1_MAX_PIDS)
        return deny(err, capacity, "CL/1 selected PID count exceeds scope");
    memcpy(p->pids, b + 27, p->pid_count);
    for (size_t i = p->pid_count; i < CL1_MAX_PIDS; ++i)
        if (b[27 + i]) return deny(err, capacity, "CL/1 noncanonical PID padding");
    p->cycles = u16(b + 59); p->interval_ms = u16(b + 61);
    p->budget_ms = u32(b + 63);
    if (!plan_valid(p) || strcmp(p->vin, vin))
        return deny(err, capacity, "CL/1 plan violates independent physical scope or VIN");
    return 0;
}
static unsigned expected_phase(const replay *r, uint32_t now) {
    const cl1_result *e = r->result;
    if (r->stage == ST_STOP || now >= e->plan.budget_ms) return ST_STOP;
    if (r->stage == ST_SAMPLE && r->index == 0) {
        if (r->cycle_started && now - r->cycle_start < e->plan.interval_ms)
            return ST_WAIT;
        if (now - e->last_identity_ms >= 30000) return ST_PERIODIC;
    }
    return r->stage;
}
static uint32_t maximum_reply(unsigned phase) {
    return phase == ST_SAMPLE ? 4095 :
           phase == ST_INVENTORY ? 6 :
           (phase == ST_INITIAL || phase == ST_PERIODIC || phase == ST_FINAL) ? 20 : 0;
}
static bool retainable(const replay *r, unsigned phase) {
    uint32_t bound = maximum_reply(phase);
    return bound != 0 && r->result->raw_bytes <= CL1_REPLY_LIMIT - bound;
}
static bool capacity_stop(const replay *r, uint32_t now) {
    unsigned phase = expected_phase(r, now);
    const cl1_result *e = r->result;
    if (phase == ST_SAMPLE && r->index == 0 && !r->early_identity &&
        now - e->last_identity_ms >= 29000) phase = ST_PERIODIC;
    if (r->stage == ST_SAMPLE && r->index == 0 && r->cycle_started &&
        now - r->cycle_start < (uint32_t)e->plan.interval_ms + 25)
        return false;
    return retainable(r, phase) == false && phase >= ST_INITIAL && phase <= ST_FINAL;
}
static void abort_replay(replay *r, uint8_t stop, uint32_t now) {
    r->result->stop = stop;
    r->result->duration_ms = now;
    r->stage = ST_STOP;
}
static unsigned response_shape(const uint8_t *req, const uint8_t *body,
                               size_t response_size, unsigned state) {
    if (!response_size) return state == 4 || state == 5 ? state : 0;
    if (body[0] == 0x7f)
        return response_size == 3 && body[1] == req[0] &&
               body[2] != 0 && body[2] != 0x78 ? 3 : 0;
    if (req[0] == 9 && req[1] == 2) {
        if (response_size != 20 || body[0] != 0x49 || body[1] != 2 || body[2] != 1)
            return 0;
        char vin[18];
        memcpy(vin, body + 3, 17); vin[17] = 0;
        return cf1_valid_vin(vin) ? 1 : 0;
    }
    if (req[0] != 1 || response_size < 3 ||
        body[0] != 0x41 || body[1] != req[1]) return 0;
    if (req[1] % 32 == 0)
        return response_size == 6 &&
               (req[1] != 224 || !(body[5] & 1)) ? 1 : 0;
    return 2; /* RAW measurements, NEVER silently scaled as Mode 01. */
}
static int observe(replay *r, const uint8_t *b, size_t n,
                   char *err, size_t capacity) {
    cl1_result *e = r->result;
    if (n < CL1_EVENT_SIZE) return deny(err, capacity, "CL/1 truncated observation");
    unsigned phase = b[0], state = b[1], accepted = b[2];
    uint16_t cycle = u16(b + 4), timeout = u16(b + 16);
    uint8_t pid = b[6];
    uint32_t start = u32(b + 8), end = u32(b + 12), exchange = u32(b + 20);
    uint16_t response_size = u16(b + 18);
    const uint8_t *req = b + 24;
    const uint8_t *response = b + CL1_EVENT_SIZE;
    if (phase < ST_INITIAL || phase > ST_FINAL ||
        state < 1 || state > 5 || accepted > 1 || b[3] || b[7] ||
        start > end || end > 604000 || timeout < 1 || timeout > 4000 ||
        response_size > 4095 || n != CL1_EVENT_SIZE + response_size ||
        ((state <= 3) != (response_size != 0)) ||
        (!accepted && state != 5) ||
        start < e->duration_ms || r->stage == ST_STOP ||
        e->steps >= CL1_MAX_STEPS)
        return deny(err, capacity, "CL/1 invalid event state, duration or retained response");

    unsigned expected = expected_phase(r, start);
    if (phase == ST_PERIODIC && expected == ST_SAMPLE &&
        r->index == 0 && !r->early_identity)
        expected = ST_PERIODIC;
    if (phase != expected || !retainable(r, phase) ||
        start >= e->plan.budget_ms ||
        timeout > e->plan.budget_ms - start)
        return deny(err, capacity, "CL/1 phase or exchange outside authorized scope");

    uint8_t request[2];
    if (phase == ST_INITIAL || phase == ST_PERIODIC || phase == ST_FINAL) {
        request[0] = 9; request[1] = 2;
    } else if (phase == ST_INVENTORY) {
        request[0] = 1; request[1] = (uint8_t)r->base;
    } else {
        request[0] = 1; request[1] = e->plan.pids[r->index];
    }
    if (memcmp(req, request, sizeof(request)) ||
        cycle != (phase == ST_SAMPLE ? e->completed_cycles + 1 : 0) ||
        pid != (phase == ST_SAMPLE ? request[1] : 0))
        return deny(err, capacity, "CL/1 request bytes, index or cycle mismatch");

    if (phase == ST_SAMPLE && r->index == 0) {
        r->cycle_start = start; r->cycle_started = true;
    }
    ++e->steps;
    e->duration_ms = end;
    if (end >= e->plan.budget_ms) {
        if (accepted) return deny(err, capacity, "Accepted late CL/1 response");
        abort_replay(r, 2, end); return 0;
    }
    if (end - start > timeout || exchange > timeout) {
        if (accepted) return deny(err, capacity, "Accepted timed-out CL/1 response");
        abort_replay(r, 4, end); return 0;
    }
    if (!accepted) {
        if (state != 5 || response_size)
            return deny(err, capacity, "Rejected CL/1 event contains response");
        abort_replay(r, 4, end); return 0;
    }
    if (response_shape(request, response, response_size, state) != state ||
        state == 5)
        return deny(err, capacity, "CL/1 observed reply cannot match approved request");
    e->raw_bytes += response_size;
    if (e->raw_bytes > CL1_REPLY_LIMIT)
        return deny(err, capacity, "CL/1 raw evidence capacity exceeded");

    if (phase == ST_INITIAL || phase == ST_PERIODIC || phase == ST_FINAL) {
        if (state != 1) { abort_replay(r, 5, end); return 0; }
        if (memcmp(response + 3, e->plan.vin, 17)) {
            abort_replay(r, 6, end); return 0;
        }
        e->last_identity_ms = end;
        if (phase == ST_INITIAL) { e->initial_vin = true; r->stage = ST_INVENTORY; }
        else if (phase == ST_PERIODIC) {
            r->early_identity = true; r->stage = ST_SAMPLE;
        } else {
            e->final_vin = true; r->stage = ST_STOP;
            e->completed = true;
            for (unsigned i = 0; i < e->plan.pid_count; ++i)
                if (!e->raw_per_pid[i]) e->completed = false;
            if (!e->completed) e->stop = 8;
        }
        return 0;
    }
    if (phase == ST_INVENTORY) {
        if (state != 1) { abort_replay(r, 7, end); return 0; }
        for (unsigned bit = 0; bit < 31; ++bit)
            if (response[2 + bit / 8] & (uint8_t)(0x80u >> (bit % 8)))
                e->advertised[r->base + bit + 1] = true;
        bool more = (response[5] & 1) != 0;
        e->inventory_complete = !more;
        bool selected_ready = true;
        for (unsigned i = 0; i < e->plan.pid_count; ++i)
            if (!e->advertised[e->plan.pids[i]]) selected_ready = false;
        if (selected_ready) r->stage = ST_SAMPLE;
        else if (!more || r->base == 224) abort_replay(r, 7, end);
        else r->base += 32;
        return 0;
    }
    if (phase != ST_SAMPLE || (state != 2 && state != 3 && state != 4))
        return deny(err, capacity, "CL/1 sample has invalid status");
    ++e->samples;
    if (state == 2) {
        ++e->raw_samples; ++e->raw_per_pid[r->index];
    } else if (state == 3) ++e->negative_samples;
    else ++e->timeout_samples;
    ++r->index;
    if (r->index == e->plan.pid_count) {
        r->index = 0; r->early_identity = false;
        ++e->completed_cycles;
        if (e->completed_cycles == e->plan.cycles) r->stage = ST_FINAL;
    }
    return 0;
}
static int terminal(replay *r, const uint8_t *data, size_t size,
                    char *err, size_t capacity) {
    cl1_result *e = r->result;
    if (size != CL1_TERMINAL_SIZE || data[0] > 11 ||
        data[0] == 1 || data[0] == 9 || data[0] == 10 ||
        (data[1] & 0xf0u) || u32(data + 20) > 604000 ||
        u32(data + 20) < e->duration_ms)
        return deny(err, capacity, "CL/1 invalid terminal summary");
    uint32_t end = u32(data + 20);
    if (r->stage != ST_STOP) {
        uint8_t stop = data[0];
        if (stop != 2 && stop != 3 && stop != 11)
            return deny(err, capacity, "CL/1 premature terminal");
        if (stop == 11 && !capacity_stop(r, end))
            return deny(err, capacity, "CL/1 premature evidence-capacity stop");
        if (stop == 2 && (end > e->plan.budget_ms ||
                          e->plan.budget_ms - end > 1))
            return deny(err, capacity, "CL/1 premature time-budget stop");
        abort_replay(r, stop, end);
    }
    uint8_t expected[CL1_TERMINAL_SIZE] = {0};
    expected[0] = e->stop;
    expected[1] = (uint8_t)(e->initial_vin ? 1 : 0);
    if (e->final_vin) expected[1] |= 2;
    if (e->inventory_complete) expected[1] |= 4;
    if (e->completed) expected[1] |= 8;
    put16(expected + 2, (uint16_t)e->completed_cycles);
    put32(expected + 4, (uint32_t)e->steps);
    put32(expected + 8, (uint32_t)e->samples);
    put32(expected + 12, (uint32_t)e->raw_samples);
    put32(expected + 16, (uint32_t)e->negative_samples);
    put32(expected + 20, (uint32_t)e->timeout_samples);
    put32(expected + 24, e->duration_ms);
    put32(expected + 28, e->raw_bytes);
    for (unsigned pid = 0; pid < 256; ++pid)
        if (e->advertised[pid])
            expected[32 + pid / 8] |= (uint8_t)(1u << (pid % 8));
    for (unsigned i = 0; i < CL1_MAX_PIDS; ++i)
        put16(expected + 64 + 2 * i, e->raw_per_pid[i]);
    if (memcmp(expected, data, CL1_TERMINAL_SIZE))
        return deny(err, capacity, "CL/1 terminal counters or receipt differ from independent replay");
    e->terminal_present = true;
    e->capture_integrity_verified = true;
    return 0;
}
static int inspect(const uint8_t *data, size_t length, const char *vin,
                   bool complete, cl1_result *out, char *err, size_t capacity) {
    if (!out) return deny(err, capacity, "Missing CL/1 inspection result");
    memset(out, 0, sizeof(*out));
    if (!data || !length || length > CL1_MAX_CAPTURE || !cf1_valid_vin(vin))
        return deny(err, capacity, "Invalid CL/1 evidence size or independently supplied VIN");
    replay r = { .result = out, .stage = ST_INITIAL };
    size_t pos = 0;
    while (pos < length) {
        size_t remaining = length - pos;
        size_t available = remaining < 8 ? remaining : 8;
        const uint8_t *h = data + pos;
        if (available >= 1 && h[0] != 'C') return deny(err, capacity, "CL/1 magic mismatch");
        if (available >= 2 && h[1] != 'L') return deny(err, capacity, "CL/1 magic mismatch");
        if (available >= 3 && h[2] != 1) return deny(err, capacity, "CL/1 version mismatch");
        if (available >= 4 && (h[3] < 1 || h[3] > 3 ||
            (out->records == 0 ? h[3] != 1 : h[3] == 1)))
            return deny(err, capacity, "CL/1 illegal record kind");
        if (available >= 5 && h[4] != (uint8_t)out->records)
            return deny(err, capacity, "CL/1 invalid low sequence byte");
        if (available >= 6 && h[5] != (uint8_t)(out->records >> 8))
            return deny(err, capacity, "CL/1 invalid high sequence byte");
        if (remaining < 8) {
            if (complete) return deny(err, capacity, "CL/1 truncated header");
            snprintf(out->interruption, sizeof(out->interruption), "TRUNCATED_HEADER");
            break;
        }
        uint16_t body = u16(h + 6);
        if (body < 1 || body > CL1_MAX_BODY ||
            (h[3] == 1 && body != CL1_PLAN_SIZE) ||
            (h[3] == 2 && body < CL1_EVENT_SIZE) ||
            (h[3] == 3 && body != CL1_TERMINAL_SIZE))
            return deny(err, capacity, "CL/1 illegal record length");
        size_t size = (size_t)body + 12;
        if (remaining < size) {
            if (complete) return deny(err, capacity, "CL/1 truncated complete record");
            snprintf(out->interruption, sizeof(out->interruption), "TRUNCATED_RECORD");
            break;
        }
        if (cf1_crc32(h, 8u + body) != u32(h + 8 + body))
            return deny(err, capacity, "CL/1 corrupted complete record CRC");
        if (h[3] == 1) {
            if (parse_plan(h + 8, vin, &out->plan, err, capacity)) return -1;
        } else if (h[3] == 2) {
            if (observe(&r, h + 8, body, err, capacity)) return -1;
        } else {
            if (!complete) return deny(err, capacity, "CL/1 terminal belongs to complete inspection");
            if (pos + size != length)
                return deny(err, capacity, "CL/1 trailing data after terminal receipt");
            if (terminal(&r, h + 8, body, err, capacity)) return -1;
        }
        pos += size;
        ++out->records;
        if (out->records > CL1_MAX_STEPS + 2)
            return deny(err, capacity, "CL/1 observation or record limit exceeded");
        if (out->terminal_present) break;
    }
    if (!out->records || (complete && !out->terminal_present))
        return deny(err, capacity, "CL/1 missing plan or terminal receipt");
    out->last_complete_offset = pos;
    out->discarded_tail_bytes = length - pos;
    if (complete) {
        if (out->discarded_tail_bytes)
            return deny(err, capacity, "CL/1 trailing bytes after completed receipt");
    } else {
        out->prefix_integrity_verified = true;
        if (!out->interruption[0])
            snprintf(out->interruption, sizeof(out->interruption), "MISSING_TERMINAL");
        out->completed = false; /* Prefix observations never claim completion. */
    }
    return 0;
}
int cl1_inspect_prefix(const uint8_t *data, size_t length, const char *vin,
                       cl1_result *out, char *err, size_t n) {
    return inspect(data, length, vin, false, out, err, n);
}
int cl1_inspect_complete(const uint8_t *data, size_t length, const char *vin,
                         cl1_result *out, char *err, size_t n) {
    return inspect(data, length, vin, true, out, err, n);
}
