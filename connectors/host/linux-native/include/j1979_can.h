#ifndef CAMARODIAG_NATIVE_J1979_CAN_H
#define CAMARODIAG_NATIVE_J1979_CAN_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>

/* Native C SAE J1979 classic service reader. The exchange implementation is
 * injected: this engine never opens an ECU, chooses a connector, or writes.
 * A session is pinned to one explicitly selected physical CAN pair.
 */
enum { J1979_CAN_MAX_PAYLOAD = 4095, J1979_CAN_MAX_STEPS = 1100 };

typedef int (*j1979_can_exchange)(void *context, const uint8_t *request,
    size_t request_size, uint8_t *response, size_t *response_size,
    unsigned timeout_ms);
typedef uint64_t (*j1979_can_clock)(void *context);
typedef bool (*j1979_can_context_current)(void *context);

typedef struct {
    j1979_can_exchange exchange;
    j1979_can_clock clock;
    j1979_can_context_current current;
    void *context;
    uint32_t tx, rx;
    unsigned budget_ms;
    const char *expected_vin; /* NULL: VIN is recorded, not used for authorization */
} j1979_can_plan;

typedef struct {
    uint8_t request[3], request_size;
    uint8_t *response;
    size_t response_size;
    uint32_t duration_ms;
    char status[16], detail[80];
} j1979_can_step;

typedef struct {
    j1979_can_step *steps;
    size_t step_count;
    uint64_t duration_ms;
    bool completed, cancelled;
    char stop_reason[40];
    char observed_vin[18];
    char vin_match[16];
    bool vin_valid;
    /* Per-service complete independent support pages; a failed page cannot
     * be silently interpreted as "no supported PIDs". */
    uint8_t inventory_state[10]; /* 0=not visited, 1=complete, 2=partial */
    uint8_t advertised[10][256]; /* indexed by mode, not one global PID set */
    uint16_t advertised_count[10];
    bool freeze_present;
    bool freeze_trigger_verified;
    /* Initial identity cannot authorize the entire changing session. */
    bool final_vin_checked, final_vin_valid;
    char final_vin[18], final_vin_match[16];
    unsigned negative_responses, timeout_responses, failed_responses;
} j1979_can_result;

bool j1979_can_physical_pair(uint32_t tx, uint32_t rx);
bool j1979_can_allowed_read(const uint8_t *request, size_t size);
bool j1979_can_valid_vin(const char *vin);
int j1979_can_scan(const j1979_can_plan *plan, j1979_can_result *result);
void j1979_can_result_free(j1979_can_result *result);
/* Distinct schema: a raw observation is not a decoded/attested ECU value. */
bool j1979_can_write_json(FILE *output, const j1979_can_plan *plan,
                           const j1979_can_result *result,
                           const char *interface_name);
#endif
