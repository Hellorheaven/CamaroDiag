#ifndef CAMARODIAG_LINUX_J1979_CL1_H
#define CAMARODIAG_LINUX_J1979_CL1_H
#ifdef __cplusplus
extern "C" {
#endif
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

/* Native, read-only CL/1 Mode 01 application replay. A valid stream checks
 * recorder consistency; it does not authenticate a physical vehicle. */
enum {
    CL1_MAX_PIDS = 32,
    CL1_MAX_SAMPLES = 10000,
    CL1_MAX_STEPS = CL1_MAX_SAMPLES + 8 + 1002,
    CL1_PLAN_SIZE = 67,
    CL1_EVENT_SIZE = 26,
    CL1_TERMINAL_SIZE = 128,
    CL1_MAX_BODY = CL1_EVENT_SIZE + 4095,
    CL1_MAX_CAPTURE = 16 * 1024 * 1024 + (CL1_MAX_STEPS + 2) * (8 + CL1_EVENT_SIZE + 4) + 1024
};
typedef struct {
    uint32_t tx, rx, budget_ms;
    bool extended;
    char vin[18];
    uint8_t pid_count, pids[CL1_MAX_PIDS];
    uint16_t cycles, interval_ms;
} cl1_plan;
typedef struct {
    cl1_plan plan;
    size_t records, steps, last_complete_offset, discarded_tail_bytes;
    size_t samples, raw_samples, negative_samples, timeout_samples;
    uint32_t duration_ms, raw_bytes, completed_cycles, last_identity_ms;
    uint16_t raw_per_pid[CL1_MAX_PIDS];
    bool advertised[256], initial_vin, final_vin, inventory_complete, completed;
    bool terminal_present, prefix_integrity_verified, capture_integrity_verified;
    uint8_t stop;
    char interruption[24];
} cl1_result;

bool cl1_valid_plan(const cl1_plan *plan);

/* Both entrypoints require an independent VIN. The prefix variant rejects an
 * intact terminal, including a negative terminal, and cannot claim success.
 * Complete records with a bad CRC or invalid replay ALWAYS cause failure. */
int cl1_inspect_prefix(const uint8_t *data, size_t length, const char *vin,
                       cl1_result *out, char *error, size_t error_capacity);
int cl1_inspect_complete(const uint8_t *data, size_t length, const char *vin,
                         cl1_result *out, char *error, size_t error_capacity);
#ifdef __cplusplus
}
#endif
#endif
