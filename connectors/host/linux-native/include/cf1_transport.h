#ifndef CAMARODIAG_LINUX_J1979_CF1_TRANSPORT_H
#define CAMARODIAG_LINUX_J1979_CF1_TRANSPORT_H
#ifdef __cplusplus
extern "C" {
#endif

#include "cf1.h"
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

enum {
    CF1_CU2_HELLO_SIZE = 140,
    CF1_CU2_CHALLENGE_SIZE = 68,
    CF1_CU2_PROOF_SIZE = 36
};

/* CU/2 native C serial authentication codec; no ECU I/O in this file.
 * Caller supplies a CSPRNG nonce (cf1_cu2_random_nonce in production).
 */
bool cf1_encode_profile(const cf1_plan *p, uint8_t wire[CF1_PLAN_SIZE],
                        char *error, size_t error_capacity);
bool cf1_cu2_random_nonce(uint8_t nonce[32]);
bool cf1_cu2_hello(const cf1_plan *p, const uint8_t nonce[32],
                    uint8_t wire[CF1_CU2_HELLO_SIZE],
                    char *error, size_t error_capacity);
/* Sends no proof on failed HMAC. Never accepts anonymous/downgraded CU/1. */
bool cf1_cu2_proof(const uint8_t hello[CF1_CU2_HELLO_SIZE],
                    const uint8_t challenge[CF1_CU2_CHALLENGE_SIZE],
                    const uint8_t key[32],
                    uint8_t proof[CF1_CU2_PROOF_SIZE],
                    char *error, size_t error_capacity);
#ifdef __cplusplus
}
#endif
#endif
