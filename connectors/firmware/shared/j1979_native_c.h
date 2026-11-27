#ifndef CAMARODIAG_SAE_J1979_NATIVE_C_H
#define CAMARODIAG_SAE_J1979_NATIVE_C_H
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif

/* Hardware-independent C11 SAE J1979 admission and response envelope.
 * This component never probes or operates a vehicle, drives a pin, installs
 * a CAN driver, retries a request, or authorizes programming.
 */
typedef enum {
    CD_J1979_NATIVE_INVALID=0,
    CD_J1979_NATIVE_RAW=1,
    CD_J1979_NATIVE_NEGATIVE=2,
    CD_J1979_NATIVE_PENDING=3,
    CD_J1979_NATIVE_VIN=4,
    CD_J1979_NATIVE_DTC=5,
    CD_J1979_NATIVE_BITMAP=6
} cd_j1979_native_shape;

bool cd_j1979_native_physical_pair(uint32_t tx,uint32_t rx,bool extended);
bool cd_j1979_native_vin(const char *vin,size_t length);
bool cd_j1979_native_read_only(const uint8_t *request,size_t length);
cd_j1979_native_shape cd_j1979_native_classify(const uint8_t *request,
    size_t request_length,const uint8_t *response,size_t response_length);
bool cd_j1979_native_dtc_code(const uint8_t code[2],char out[6]);
#ifdef __cplusplus
}
#endif
#endif
