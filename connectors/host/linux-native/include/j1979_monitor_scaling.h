#ifndef CAMARODIAG_NATIVE_J1979_MONITOR_SCALING_H
#define CAMARODIAG_NATIVE_J1979_MONITOR_SCALING_H
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif

/* Port of the existing Python ISO 15031-5:2006 Annex E subset, never an
 * inferred definition for unknown monitor unit/scaling IDs. This numeric
 * comparison cannot attest ECU identity, monitor completion or compliance.
 */
typedef enum {J1979_MONITOR_INVERTED=0,J1979_MONITOR_BELOW=1,
    J1979_MONITOR_WITHIN=2,J1979_MONITOR_ABOVE=3} j1979_monitor_relation;
typedef struct {
    uint8_t monitor_id,test_id,scaling_id;
    uint16_t encoded_value,encoded_minimum,encoded_maximum;
    bool scaled;
    double value,minimum,maximum;
    const char *unit;
    j1979_monitor_relation relation;
} j1979_monitor_record;

bool j1979_monitor_scale(uint8_t scaling_id,uint16_t raw_value,
                         uint16_t raw_minimum,uint16_t raw_maximum,
                         j1979_monitor_record *record);
/* Read-only Mode 06 CAN response, [0x46, MID, TID, UASID, valueHi, ...].
 * A failed parse never returns a partly trusted record.
 */
bool j1979_monitor_decode(const uint8_t request[2],const uint8_t *response,
                          size_t length,size_t index,j1979_monitor_record *out);
size_t j1979_monitor_count(const uint8_t request[2],
                           const uint8_t *response,size_t length);
const char *j1979_monitor_relation_name(j1979_monitor_relation value);
#ifdef __cplusplus
}
#endif
#endif
