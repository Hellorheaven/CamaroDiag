#ifndef CAMARODIAG_NATIVE_J1979_MEASUREMENT_H
#define CAMARODIAG_NATIVE_J1979_MEASUREMENT_H
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

/* Raw evidence remains authoritative. Numeric values come only from the
 * explicitly selected classic SAE Mode 01/02 definitions in the existing
 * Python j1979_campaign_evidence.py source; never OEM or unknown Annex data.
 */
typedef struct {
    uint8_t mode,pid,frame;
    const char *name,*unit;
    bool interpreted,context_required,upper_trim_unverified;
    double number;
} j1979_measurement;
/* Returns true for a known supported definition with strictly correlated
 * application bytes. Context-dependent values retain name/unit but no
 * trusted number until the independent ECU configuration has been checked.
 */
bool j1979_measurement_decode(const uint8_t *request,size_t request_size,
                              const uint8_t *response,size_t response_size,
                              j1979_measurement *out);
#endif
