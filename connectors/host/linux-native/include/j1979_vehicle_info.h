#ifndef CAMARODIAG_NATIVE_J1979_VEHICLE_INFO_H
#define CAMARODIAG_NATIVE_J1979_VEHICLE_INFO_H
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif

/* Strict CAN Mode 09 InfoTypes 08/0B/0D/0F, ported from the existing
 * j1979_vehicle_info.py rules. Does not infer vehicle or ECU provenance.
 */
enum {J1979_INFO_MAX_MONITORS=9};
typedef struct {
    const char *name;
    uint16_t numerator,denominator;
    bool ratio_valid;
    double ratio;
} j1979_info_monitor;
typedef struct {
    uint8_t info_type;
    bool counter_vector,printable_text;
    uint16_t general_denominator,ignition_cycles;
    unsigned counter_count,monitor_count;
    j1979_info_monitor monitors[J1979_INFO_MAX_MONITORS];
    char text[18];
} j1979_vehicle_info;

/* Entire complete CAN response, never legacy fragments across responders. */
bool j1979_vehicle_info_decode(uint8_t info_type,const uint8_t *response,
                               size_t response_size,j1979_vehicle_info *out);
#ifdef __cplusplus
}
#endif
#endif
