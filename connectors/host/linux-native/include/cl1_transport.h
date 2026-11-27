#ifndef CAMARODIAG_LINUX_CL1_TRANSPORT_H
#define CAMARODIAG_LINUX_CL1_TRANSPORT_H
#ifdef __cplusplus
extern "C" {
#endif
#include "cl1.h"
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

enum {
    CL1_CU3_PROFILE_SIZE=71,
    CL1_CU3_HELLO_SIZE=107,
    CL1_CU3_CHALLENGE_SIZE=68,
    CL1_CU3_PROOF_SIZE=36
};
bool cl1_encode_plan(const cl1_plan *plan,uint8_t wire[CL1_PLAN_SIZE],
                     char *error,size_t error_size);
bool cl1_encode_auth(const cl1_plan *plan,
                     uint8_t wire[CL1_CU3_PROFILE_SIZE],
                     char *error,size_t error_size);
bool cl1_cu3_random_nonce(uint8_t nonce[32]);
bool cl1_cu3_hello(const cl1_plan *plan,const uint8_t nonce[32],
                   uint8_t hello[CL1_CU3_HELLO_SIZE],
                   char *error,size_t error_size);
/* A server tag that does not prove the same exact scope is never accepted.
 * A failed challenge must not cause the client proof to be transmitted.
 */
bool cl1_cu3_proof(const uint8_t hello[CL1_CU3_HELLO_SIZE],
                   const uint8_t challenge[CL1_CU3_CHALLENGE_SIZE],
                   const uint8_t key[32],
                   uint8_t proof[CL1_CU3_PROOF_SIZE],
                   char *error,size_t error_size);
#ifdef __cplusplus
}
#endif
#endif
