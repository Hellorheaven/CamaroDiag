#include "cf1.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static uint16_t little16(const uint8_t *p) {
    return (uint16_t)((uint16_t)p[0] | ((uint16_t)p[1] << 8));
}
static uint32_t little32(const uint8_t *p) {
    return (uint32_t)p[0] | ((uint32_t)p[1] << 8) |
        ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
}
static int reject(char *error, size_t size, const char *message) {
    if (size) snprintf(error, size, "%s", message);
    return -1;
}
static bool distinct(const uint8_t *values, size_t length) {
    bool seen[256] = {false};
    for (size_t i = 0; i < length; ++i) {
        if (seen[values[i]]) return false;
        seen[values[i]] = true;
    }
    return true;
}
bool cf1_valid_vin(const char *s) {
    if (!s || strlen(s) != 17) return false;
    for (size_t i = 0; i < 17; ++i) {
        char c = s[i];
        if (!(c >= '0' && c <= '9') &&
            !(c >= 'A' && c <= 'H') &&
            !(c >= 'J' && c <= 'N') &&
            !(c == 'P' || (c >= 'R' && c <= 'Z')) &&
            false) return false;
    }
    return true;
}
uint32_t cf1_crc32(const uint8_t *p, size_t size) {
    uint32_t crc = 0xffffffffu;
    for (size_t i = 0; i < size; ++i) {
        crc ^= p[i];
        for (int bit = 0; bit < 8; ++bit)
            crc = (crc >> 1) ^ (0xedb88320u & (uint32_t)-(int)(crc & 1u));
    }
    return ~crc;
}
static bool physical_pair(uint32_t tx, uint32_t rx, bool extended) {
    if (!extended) return tx >= 0x7e0 && tx <= 0x7e7 && rx == tx + 8;
    return tx <= 0x1fffffffu && (tx & 0x1fff00ffu) == 0x18da00f1u &&
        ((tx >> 8) & 255u) < 0xf0u && rx == (0x18daf100u | ((tx >> 8) & 255u));
}
static int decode_plan(const uint8_t *body, cf1_plan *p,
                       const char *expected, char *err, size_t cap) {
    memset(p, 0, sizeof(*p));
    p->tx = little32(body);
    p->rx = little32(body + 4);
    if (body[8] > 1) return reject(err, cap, "Invalid extended CAN flag");
    p->extended = body[8] != 0;
    p->budget_ms = little32(body + 9);
    memcpy(p->vin, body + 13, 17);
    p->vin[17] = '\0';
    p->frame_count = body[30];
    p->pid_count = body[39];
    if (!physical_pair(p->tx, p->rx, p->extended) ||
        p->budget_ms < 1000 || p->budget_ms > 600000 ||
        !cf1_valid_vin(p->vin) || strcmp(p->vin, expected) != 0 ||
        p->frame_count == 0 || p->frame_count > CF1_MAX_FRAMES ||
        p->pid_count > CF1_MAX_PIDS)
        return reject(err, cap, "CF/1 plan differs from independently approved physical scope");
    memcpy(p->frames, body + 31, p->frame_count);
    memcpy(p->pids, body + 40, p->pid_count);
    if (!distinct(p->frames, p->frame_count) ||
        !distinct(p->pids, p->pid_count))
        return reject(err, cap, "Repeated CF/1 frame or PID");
    for (size_t i = 0; i < p->pid_count; ++i)
        if (p->pids[i] == 0 || p->pids[i] == 2 || p->pids[i] % 32 == 0)
            return reject(err, cap, "Invalid selected measurement PID");
    for (size_t i = p->frame_count; i < CF1_MAX_FRAMES; ++i)
        if (body[31 + i]) return reject(err, cap, "Noncanonical CF/1 frame padding");
    for (size_t i = p->pid_count; i < CF1_MAX_PIDS; ++i)
        if (body[40 + i]) return reject(err, cap, "Noncanonical CF/1 PID padding");
    return 0;
}
static int decode_observation(const uint8_t *body, size_t size,
                              cf1_observation *o, char *err, size_t cap) {
    if (size < 12 || body[0] == 0 || body[0] > 6 ||
        body[1] > 1 || body[2] < 2 || body[2] > 3 || body[3])
        return reject(err, cap, "Invalid CF/1 observation framing");
    uint16_t response = little16(body + 8);
    if (response > CF1_MAX_RESPONSE || size != (size_t)10 + body[2] + response)
        return reject(err, cap, "Invalid CF/1 observation size");
    if ((body[0] == 1 || body[0] == 6) != (response > 0) ||
        ((body[0] == 1 || body[0] == 2 || body[0] == 3 || body[0] == 6) && !body[1]) ||
        ((body[0] == 4 || body[0] == 5) && body[1]) ||
        little32(body + 4) > 3600000)
        return reject(err, cap, "Contradictory CF/1 transport observation");
    memset(o, 0, sizeof(*o));
    o->outcome = body[0]; o->attempted = body[1] != 0;
    o->request_length = body[2];
    o->duration_ms = little32(body + 4);
    o->response_length = response;
    memcpy(o->request, body + 10, o->request_length);
    o->response = response ? body + 10 + o->request_length : NULL;
    return 0;
}

typedef struct {
    cf1_evidence *e;
    size_t cursor;
    bool missing, failed;
    char *error;
    size_t capacity;
} replay;

static void set_stop(cf1_evidence *e, const char *reason) {
    e->recipe_stopped = true;
    snprintf(e->recipe_stop_reason, sizeof(e->recipe_stop_reason), "%s", reason);
}
static const cf1_observation *read_recipe(replay *r, const uint8_t *req, size_t n) {
    cf1_evidence *e = r->e;
    if (r->missing || r->failed || e->recipe_stopped) return NULL;
    if (r->cursor == e->observation_count) {
        e->replay_missing_observation = true;
        e->has_next_request = true;
        e->next_request_length = (uint8_t)n;
        memcpy(e->next_request, req, n);
        r->missing = true;
        return NULL;
    }
    const cf1_observation *o = &e->observations[r->cursor++];
    if (o->request_length != n || memcmp(o->request, req, n)) {
        r->failed = true;
        reject(r->error, r->capacity, "CF/1 observation request differs from Mode 02 recipe");
        return NULL;
    }
    e->has_last_request = true;
    e->last_request_length = (uint8_t)n;
    memcpy(e->last_request, req, n);
    switch (o->outcome) {
        case 3: set_stop(e, "TRANSPORT_ERROR"); break;
        case 4: set_stop(e, "BUDGET_EXHAUSTED"); break;
        case 5: set_stop(e, "CONTEXT_CHANGED"); break;
        case 6: set_stop(e, "BUDGET_EXHAUSTED"); break;
        default: break;
    }
    return o;
}
static bool positive_vin(const cf1_observation *o, char output[18]) {
    if (!o || o->outcome != 1 || o->response_length != 20 ||
        memcmp(o->response, "\x49\x02\x01", 3)) return false;
    memcpy(output, o->response + 3, 17); output[17] = '\0';
    return cf1_valid_vin(output);
}
static bool page_response(const cf1_observation *o, uint8_t base,
                          uint8_t frame, bool *has_next,
                          bool advertised[256]) {
    if (!o || o->outcome != 1 || o->response_length != 7 ||
        o->response[0] != 0x42 || o->response[1] != base ||
        o->response[2] != frame ||
        (base == 0xe0 && (o->response[6] & 1))) return false;
    const uint8_t *bitmap = o->response + 3;
    for (size_t k = 0; k < 31; ++k)
        if (bitmap[k / 8] & (uint8_t)(0x80u >> (k % 8)))
            advertised[base + k + 1] = true;
    *has_next = (bitmap[3] & 1) != 0;
    return true;
}
static bool trigger_response(const cf1_observation *o, uint8_t frame,
                             uint16_t *dtc) {
    if (!o || o->outcome != 1 || o->response_length != 5 ||
        o->response[0] != 0x42 || o->response[1] != 2 ||
        o->response[2] != frame) return false;
    *dtc = (uint16_t)(((unsigned)o->response[3] << 8) | o->response[4]);
    return true;
}
static bool measurement_response(const cf1_observation *o, uint8_t pid,
                                 uint8_t frame) {
    if (!o || o->outcome != 1 || o->response_length < 4 ||
        o->response[0] != 0x42 || o->response[1] != pid ||
        o->response[2] != frame) return false;
    /* Nonzero data, correct echo only. Unknown values MUST remain RAW. */
    return true;
}
static const cf1_observation *request_frame(replay *r, uint8_t pid, uint8_t frame) {
    uint8_t req[3] = {2, pid, frame};
    return read_recipe(r, req, 3);
}
static void acquire_frame(replay *r, uint8_t frame) {
    cf1_evidence *e = r->e;
    cf1_frame_progress *f = &e->progress[e->visited_frames++];
    memset(f, 0, sizeof(*f)); f->number = frame;
    snprintf(f->provisional_status, sizeof(f->provisional_status), "UNAVAILABLE");
    bool ids[256] = {false}, next = false, complete = false;
    const cf1_observation *o = request_frame(r, 0, frame);
    if (!o || !page_response(o, 0, frame, &next, ids)) return;
    if (!ids[2]) return;
    uint16_t initial_dtc = 0, final_dtc = 0;
    o = request_frame(r, 2, frame);
    if (!o || !trigger_response(o, frame, &initial_dtc)) return;
    if (initial_dtc == 0) {
        snprintf(f->provisional_status, sizeof(f->provisional_status), "ABSENT");
        return;
    }
    complete = !next;
    for (unsigned base = 0x20; next && base <= 0xe0 && !e->recipe_stopped; base += 0x20) {
        o = request_frame(r, (uint8_t)base, frame);
        if (!o || !page_response(o, (uint8_t)base, frame, &next, ids)) break;
        complete = !next;
    }
    f->inventory_complete = complete;
    for (unsigned pid = 1; pid < 256; ++pid)
        if (ids[pid] && pid != 2 && pid % 32) ++f->advertised_count;
    /* Mode 02 selected PIDs narrow the deterministic recipe; otherwise
     * query all announced measurements, always sorted by PID. */
    bool failed_measurement = false;
    for (unsigned pid = 1; pid < 256 && !e->recipe_stopped; ++pid) {
        if (!ids[pid] || pid == 2 || pid % 32) continue;
        if (e->plan.pid_count) {
            bool selected = false;
            for (size_t k = 0; k < e->plan.pid_count; ++k)
                if (e->plan.pids[k] == pid) selected = true;
            if (!selected) continue;
        }
        o = request_frame(r, (uint8_t)pid, frame);
        if (!o) break;
        ++f->measurement_count;
        if (o->attempted) ++f->transmitted_count;
        if (!measurement_response(o, (uint8_t)pid, frame)) failed_measurement = true;
    }
    if (e->recipe_stopped || r->missing) {
        snprintf(f->provisional_status, sizeof(f->provisional_status), "PARTIAL");
        return;
    }
    o = request_frame(r, 2, frame);
    if (!o) {
        snprintf(f->provisional_status, sizeof(f->provisional_status), "PARTIAL");
        return;
    }
    if (trigger_response(o, frame, &final_dtc)) {
        f->trigger_stable = initial_dtc == final_dtc;
        if (!f->trigger_stable) {
            snprintf(f->provisional_status, sizeof(f->provisional_status), "CHANGED");
            return;
        }
    } else {
        snprintf(f->provisional_status, sizeof(f->provisional_status), "PARTIAL");
        return;
    }
    size_t candidate_count = 0;
    for (unsigned pid = 1; pid < 256; ++pid) {
        if (!ids[pid] || pid == 2 || pid % 32) continue;
        if (!e->plan.pid_count) { ++candidate_count; continue; }
        for (size_t k = 0; k < e->plan.pid_count; ++k)
            if (e->plan.pids[k] == pid) { ++candidate_count; break; }
    }
    snprintf(f->provisional_status, sizeof(f->provisional_status), "%s",
        complete && !failed_measurement && candidate_count == f->measurement_count
        ? "COMPLETE" : "PARTIAL");
}
static int replay_prefix(cf1_evidence *e, char *error, size_t cap) {
    replay r = { .e = e, .error = error, .capacity = cap };
    const uint8_t vin_request[] = {9, 2};
    const cf1_observation *o = read_recipe(&r, vin_request, sizeof(vin_request));
    if (o) {
        char vin[18] = {0};
        if (positive_vin(o, vin)) {
            e->initial_vin_observed = true;
            memcpy(e->initial_vin, vin, sizeof(vin));
        }
        if (!e->recipe_stopped && strcmp(vin, e->plan.vin))
            set_stop(e, vin[0] ? "VIN_MISMATCH" : "VIN_UNVERIFIED");
    }
    for (size_t i = 0; i < e->plan.frame_count && !r.missing && !r.failed &&
                       !e->recipe_stopped; ++i)
        acquire_frame(&r, e->plan.frames[i]);
    if (!r.missing && !r.failed && !e->recipe_stopped) {
        o = read_recipe(&r, vin_request, sizeof(vin_request));
        if (o) {
            char vin[18] = {0};
            if (positive_vin(o, vin)) {
                e->final_vin_observed = true;
                memcpy(e->final_vin, vin, sizeof(vin));
            }
            if (!e->recipe_stopped && strcmp(vin, e->plan.vin))
                set_stop(e, vin[0] ? "FINAL_VIN_MISMATCH" : "FINAL_VIN_UNVERIFIED");
        }
    }
    if (r.failed) return -1;
    if (r.cursor != e->observation_count)
        return reject(error, cap, "Extra CF/1 observation beyond deterministic recipe");
    /* A complete recipe with missing terminal is still a PREFIX, not success. */
    return 0;
}
void cf1_evidence_free(cf1_evidence *e) {
    if (!e) return;
    free(e->observations);
    memset(e, 0, sizeof(*e));
}
int cf1_inspect_prefix(const uint8_t *raw, size_t length,
                       const char *independent_vin, cf1_evidence *out,
                       char *error, size_t cap) {
    if (!out) return reject(error, cap, "Missing result storage");
    memset(out, 0, sizeof(*out));
    if (!raw || length == 0 || length > CF1_MAX_BYTES ||
        !cf1_valid_vin(independent_vin))
        return reject(error, cap, "Invalid CF/1 prefix size or independently supplied VIN");
    out->observations = calloc(CF1_MAX_STEPS, sizeof(*out->observations));
    if (!out->observations) return reject(error, cap, "Insufficient memory for bounded CF/1 replay");
    size_t position = 0;
    while (position < length) {
        size_t left = length - position;
        size_t n = left < 8 ? left : 8;
        const uint8_t *h = raw + position;
        const uint8_t magic[3] = {'C', 'F', 1};
        if (memcmp(h, magic, n < 3 ? n : 3))
            return reject(error, cap, "Invalid CF/1 prefix magic");
        if (n >= 4 && (h[3] != (out->record_count ? 2 : 1) &&
                       !(out->record_count && h[3] == 3)))
            return reject(error, cap, "Invalid CF/1 record order");
        if (n >= 5 && h[4] != (out->record_count & 255u))
            return reject(error, cap, "Invalid CF/1 prefix low sequence");
        if (n >= 6 && h[5] != (out->record_count >> 8))
            return reject(error, cap, "Invalid CF/1 prefix high sequence");
        if (left < 8) {
            snprintf(out->interruption, sizeof(out->interruption), "TRUNCATED_HEADER");
            break;
        }
        size_t body_size = little16(h + 6);
        if (body_size < 1 || body_size > CF1_MAX_BODY ||
            (h[3] == 1 && body_size != CF1_PLAN_SIZE) ||
            (h[3] == 2 && body_size < 12) ||
            (h[3] == 3 && body_size != 20))
            return reject(error, cap, "Invalid CF/1 record length");
        size_t record = 8 + body_size + 4;
        if (left < record) {
            snprintf(out->interruption, sizeof(out->interruption), "TRUNCATED_RECORD");
            break;
        }
        if (cf1_crc32(h, 8 + body_size) != little32(h + 8 + body_size))
            return reject(error, cap, "CF/1 complete record CRC mismatch");
        if (h[3] == 3)
            return reject(error, cap, "Terminal record present: use full CF/1 inspector");
        if (!out->record_count) {
            if (decode_plan(h + 8, &out->plan, independent_vin, error, cap))
                return -1;
        } else {
            if (out->observation_count >= CF1_MAX_STEPS)
                return reject(error, cap, "CF/1 maximum observation limit exceeded");
            cf1_observation *o = &out->observations[out->observation_count];
            if (decode_observation(h + 8, body_size, o, error, cap))
                return -1;
            out->total_duration_ms += o->duration_ms;
            ++out->observation_count;
        }
        ++out->record_count;
        if (out->record_count > CF1_MAX_STEPS + 1)
            return reject(error, cap, "CF/1 maximum record limit exceeded");
        position += record;
    }
    if (!out->record_count)
        return reject(error, cap, "CF/1 plan record is incomplete");
    out->last_complete_offset = position;
    out->discarded_tail_bytes = length - position;
    if (!out->interruption[0])
        snprintf(out->interruption, sizeof(out->interruption), "MISSING_TERMINAL");
    return replay_prefix(out, error, cap);
}
