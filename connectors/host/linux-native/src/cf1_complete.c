#include "cf1.h"

#include <stdio.h>
#include <string.h>

/* Strict full CF/1 terminal inspector, shared by live capture and offline CLI.
 * The body is independently replayed by cf1_inspect_prefix first; a terminal
 * cannot legitimize an unobserved or impossible Mode 02 request.
 * Structural integrity is distinct from physical ECU authentication.
 */
static uint16_t le16(const uint8_t *p) {
    return (uint16_t)((uint16_t)p[0] | ((uint16_t)p[1] << 8));
}
static uint32_t le32(const uint8_t *p) {
    return (uint32_t)p[0] | ((uint32_t)p[1] << 8) |
           ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
}
static int denial(char *buffer, size_t capacity, const char *detail) {
    if (capacity) snprintf(buffer, capacity, "%s", detail);
    return -1;
}
static unsigned stop_code(const cf1_evidence *e) {
    if (!e->recipe_stopped) return 0;
    static const char *const stops[] = {
        "", "BUDGET_EXHAUSTED", "TRANSPORT_ERROR", "CONTEXT_CHANGED",
        "VIN_UNVERIFIED", "VIN_MISMATCH", "FINAL_VIN_UNVERIFIED",
        "FINAL_VIN_MISMATCH"
    };
    for (unsigned i = 1; i < sizeof(stops) / sizeof(stops[0]); ++i)
        if (!strcmp(e->recipe_stop_reason, stops[i])) return i;
    return 255;
}
static unsigned state_code(const char *state) {
    static const char *const states[] = {
        "UNAVAILABLE", "ABSENT", "COMPLETE", "PARTIAL", "CHANGED"
    };
    for (unsigned i = 0; i < sizeof(states) / sizeof(states[0]); ++i)
        if (!strcmp(state, states[i])) return i;
    return 255;
}
int cf1_inspect_complete(const uint8_t *raw, size_t length,
                         const char *vin, cf1_evidence *out,
                         char *error, size_t capacity) {
    if (!out) return denial(error, capacity, "Missing CF/1 result");
    memset(out, 0, sizeof(*out));
    if (!raw || length < CF1_PLAN_SIZE + 12 + 32 ||
        length > CF1_MAX_BYTES || !cf1_valid_vin(vin))
        return denial(error, capacity, "Invalid full CF/1 scope or length");
    size_t pos = 0, seq = 0;
    const uint8_t *terminal = NULL;
    while (pos < length) {
        if (length - pos < 8 || seq > CF1_MAX_STEPS + 1)
            return denial(error, capacity, "Truncated or excessive CF/1 full records");
        const uint8_t *h = raw + pos;
        if (h[0] != 'C' || h[1] != 'F' || h[2] != 1 ||
            le16(h + 4) != seq || (seq == 0 && h[3] != 1) ||
            (seq && h[3] != 2 && h[3] != 3))
            return denial(error, capacity, "Wrong CF/1 terminal stream ordering");
        uint16_t n = le16(h + 6);
        if (n < 1 || n > CF1_MAX_BODY ||
            (seq == 0 && n != CF1_PLAN_SIZE) ||
            (h[3] == 2 && n < 12) ||
            (h[3] == 3 && n != 20) ||
            length - pos < (size_t)n + 12)
            return denial(error, capacity, "Malformed full CF/1 record length");
        if (cf1_crc32(h, (size_t)n + 8) != le32(h + n + 8))
            return denial(error, capacity, "CF/1 CRC error in full stream");
        if (h[3] == 3) {
            if (pos + (size_t)n + 12 != length)
                return denial(error, capacity, "Trailing bytes after CF/1 terminal");
            terminal = h + 8;
            break;
        }
        pos += (size_t)n + 12;
        ++seq;
    }
    if (!terminal)
        return denial(error, capacity, "CF/1 terminal missing from complete stream");
    if (cf1_inspect_prefix(raw, pos, vin, out, error, capacity)) return -1;
    /* The independent recipe cannot be waiting for an observation when a
     * terminal is received. No synthetic completion is allowed. */
    if (out->replay_missing_observation ||
        out->observation_count == 0 || out->record_count != seq)
        return denial(error, capacity, "CF/1 terminal follows incomplete recipe");
    uint8_t recorded_stop = terminal[0], visited = terminal[1];
    uint8_t flags = terminal[2];
    uint32_t observed = le32(terminal + 4);
    uint32_t elapsed = le32(terminal + 8);
    if (recorded_stop > 7 || visited > out->plan.frame_count ||
        flags & 0xf8u || terminal[3] || observed != out->observation_count ||
        elapsed > 3600000 || elapsed < out->total_duration_ms ||
        visited != out->visited_frames || recorded_stop != stop_code(out))
        return denial(error, capacity, "CF/1 terminal contradicts independent replay");
    for (size_t i = 0; i < CF1_MAX_FRAMES; ++i) {
        unsigned state = i < visited
            ? state_code(out->progress[i].provisional_status) : 0;
        if (state > 4 || terminal[12 + i] != state)
            return denial(error, capacity, "CF/1 final frame state mismatches replay");
    }
    bool computed_completed = !out->recipe_stopped &&
        out->visited_frames == out->plan.frame_count &&
        out->initial_vin_observed && out->final_vin_observed &&
        !strcmp(out->initial_vin, vin) && !strcmp(out->final_vin, vin);
    bool computed_stable = computed_completed;
    for (size_t i = 0; i < out->visited_frames; ++i)
        if (strcmp(out->progress[i].provisional_status, "COMPLETE") &&
            strcmp(out->progress[i].provisional_status, "ABSENT"))
            computed_stable = false;
    bool identity_rechecked = computed_completed;
    if (((flags & 1u) != 0) != computed_completed ||
        ((flags & 2u) != 0) != identity_rechecked ||
        ((flags & 4u) != 0) != computed_stable)
        return denial(error, capacity, "CF/1 terminal flags differ from observed recipe");
    out->terminal_present = true;
    out->terminal_duration_ms = elapsed;
    if (out->recipe_stopped) {
        snprintf(out->terminal_stop_reason, sizeof(out->terminal_stop_reason), "%s",
                 out->recipe_stop_reason);
    }
    out->execution_completed = computed_completed;
    out->capture_integrity_verified = true;
    /* CRC and a matching recipe are NOT a vehicle hardware attestation. */
    out->physical_vehicle_verified = false;
    return 0;
}
