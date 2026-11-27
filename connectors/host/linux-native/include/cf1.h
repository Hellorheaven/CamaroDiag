#ifndef CAMARODIAG_LINUX_J1979_CF1_H
#define CAMARODIAG_LINUX_J1979_CF1_H
#ifdef __cplusplus
extern "C" {
#endif

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

/* Offline SAE J1979 Mode 02 CF/1 inspection. No ECU I/O or command transmitter.
 * CRC confirms record consistency, NOT vehicle or connector authenticity.
 * All observations point into caller-owned immutable capture bytes.
 */
enum {
    CF1_MAX_FRAMES = 8,
    CF1_MAX_PIDS = 64,
    CF1_MAX_STEPS = CF1_MAX_FRAMES * (8 + 2 + 247) + 2,
    CF1_MAX_BODY = 4108,
    CF1_PLAN_SIZE = 104,
    CF1_MAX_RESPONSE = 4095,
    CF1_MAX_BYTES = (CF1_MAX_STEPS + 2) * (CF1_MAX_BODY + 12)
};

typedef struct {
    uint32_t tx, rx, budget_ms;
    bool extended;
    char vin[18];
    uint8_t frame_count, frames[CF1_MAX_FRAMES];
    uint8_t pid_count, pids[CF1_MAX_PIDS];
} cf1_plan;

typedef struct {
    uint8_t request[3], request_length;
    uint8_t outcome;
    bool attempted;
    uint32_t duration_ms;
    const uint8_t *response;
    uint16_t response_length;
} cf1_observation;

typedef struct {
    uint8_t number;
    char provisional_status[16];
    bool inventory_complete, trigger_stable;
    uint16_t advertised_count, measurement_count, transmitted_count;
} cf1_frame_progress;

typedef struct {
    cf1_plan plan;
    cf1_observation *observations;
    size_t observation_count, record_count;
    size_t last_complete_offset, discarded_tail_bytes;
    uint64_t total_duration_ms;
    char interruption[20];
    bool replay_missing_observation;
    bool has_next_request, has_last_request;
    uint8_t next_request[3], next_request_length;
    uint8_t last_request[3], last_request_length;
    bool initial_vin_observed, final_vin_observed;
    char initial_vin[18], final_vin[18];
    cf1_frame_progress progress[CF1_MAX_FRAMES];
    uint8_t visited_frames;
    bool recipe_stopped;
    char recipe_stop_reason[32];
    /* Invariant: a prefix can NEVER assert completed physical execution. */
    bool execution_completed, capture_integrity_verified, physical_vehicle_verified;
    bool terminal_present;
    uint32_t terminal_duration_ms;
    char terminal_stop_reason[32];
} cf1_evidence;

/* Returns 0 only for a valid, interrupted CF/1 prefix. Complete terminal is
 * deliberately rejected and must be inspected using a distinct full parser.
 * Any failure leaves out cleared; call cf1_evidence_free even on failure.
 */
int cf1_inspect_prefix(const uint8_t *raw, size_t length,
                       const char *independent_vin, cf1_evidence *out,
                       char *error, size_t error_size);
int cf1_inspect_complete(const uint8_t *raw, size_t length,
                         const char *independent_vin, cf1_evidence *out,
                         char *error, size_t error_size);
void cf1_evidence_free(cf1_evidence *evidence);
uint32_t cf1_crc32(const uint8_t *data, size_t size);
bool cf1_valid_vin(const char *vin);
bool cf1_valid_plan(const cf1_plan *plan);
#ifdef __cplusplus
}
#endif
#endif
