#ifndef CAMARODIAG_LINUX_NATIVE_J1979_LIVE_H
#define CAMARODIAG_LINUX_NATIVE_J1979_LIVE_H

#include "j1979_can.h"
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#ifdef __cplusplus
extern "C" {
#endif

/* Native C++ replacement for read-only Python Mode 01 monitoring. All source,
 * identity and clock callbacks remain owned by the selected physical link.
 * Data is RAW; no unsupported J1979DA scaling is inferred from packet size.
 */
enum { J1979_LIVE_MAX_PIDS=32, J1979_LIVE_MAX_SAMPLES=10000,
       J1979_LIVE_MAX_STEPS=10100, J1979_LIVE_MAX_RAW=16*1024*1024 };
typedef bool (*j1979_live_wait)(void *context, unsigned milliseconds);
typedef struct {
    j1979_can_plan channel;
    uint8_t pids[J1979_LIVE_MAX_PIDS];
    unsigned pid_count, cycles, interval_ms;
    j1979_live_wait wait;
} j1979_live_plan;
typedef struct {
    uint8_t request[2];
    uint8_t *response;
    size_t response_size;
    uint64_t started_ms, elapsed_ms;
    unsigned duration_ms, cycle;
    char phase[16], status[16], detail[48];
} j1979_live_step;
typedef struct {
    j1979_live_step *steps;
    size_t step_count;
    size_t raw_bytes;
    unsigned cycles_completed, raw_samples, missing_samples;
    bool advertised[256], initial_vin_valid, final_vin_valid;
    bool completed, inventory_complete;
    char initial_vin[18], final_vin[18], stop_reason[48];
    uint64_t duration_ms;
} j1979_live_result;

bool j1979_live_plan_valid(const j1979_live_plan *plan);
int j1979_live_record(const j1979_live_plan *plan, j1979_live_result *result);
void j1979_live_free(j1979_live_result *result);
bool j1979_live_write_json(FILE *stream, const j1979_live_plan *plan,
                           const j1979_live_result *result,
                           const char *interface_name);
#ifdef __cplusplus
}
#endif
#endif
