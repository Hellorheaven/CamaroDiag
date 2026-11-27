#include "cf1_transport.h"

#include <assert.h>
#include <openssl/crypto.h>
#include <openssl/evp.h>
#include <openssl/hmac.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

/* Synthetic mutual proof transcript, never connected to a real ECU.
 * These assertions must run only in the final protocol gate.
 */
static cf1_plan known_plan(void) {
    cf1_plan plan = {0};
    plan.tx = 0x7e0; plan.rx = 0x7e8; plan.budget_ms = 60000;
    memcpy(plan.vin, "2G1FA1E30D7654321", 18);
    plan.frame_count = 1; plan.frames[0] = 0;
    plan.pid_count = 1; plan.pids[0] = 0x0c;
    return plan;
}
static void sign_transcript(const uint8_t hello[140],
                            const uint8_t challenge[68], const uint8_t key[32],
                            uint8_t domain, uint8_t output[32]) {
    uint8_t text[169];
    unsigned count = 0;
    text[0] = domain;
    memcpy(text + 1, hello + 4, 136);
    memcpy(text + 137, challenge + 4, 32);
    assert(HMAC(EVP_sha256(), key, 32, text, sizeof(text), output, &count) != NULL);
    assert(count == 32);
    OPENSSL_cleanse(text, sizeof(text));
}
int main(void) {
    cf1_plan plan = known_plan();
    assert(cf1_valid_plan(&plan));
    uint8_t nonce[32], key[32], challenge[68] = {0};
    for (size_t i = 0; i < 32; ++i) { nonce[i] = (uint8_t)(i + 1); key[i] = (uint8_t)(i + 20); }
    uint8_t hello[140] = {0}, proof[36] = {0};
    char error[200] = {0};
    assert(cf1_cu2_hello(&plan, nonce, hello, error, sizeof(error)));
    assert(!memcmp(hello, "CU\x02\x01", 4));
    assert(hello[17] == '2'); /* 4 + 13 */
    assert(hello[34] == 1 && hello[35] == 0);
    assert(hello[43] == 1 && hello[44] == 0x0c);
    assert(!memcmp(hello + 108, nonce, 32));
    memcpy(challenge, "CU\x02\x02", 4);
    for (size_t i = 0; i < 32; ++i) challenge[4+i] = (uint8_t)(255-i);
    sign_transcript(hello, challenge, key, 'F', challenge + 36);
    assert(cf1_cu2_proof(hello, challenge, key, proof, error, sizeof(error)));
    assert(!memcmp(proof, "CU\x02\x03", 4));
    uint8_t expected[32] = {0};
    sign_transcript(hello, challenge, key, 'f', expected);
    assert(CRYPTO_memcmp(expected, proof + 4, 32) == 0);
    uint8_t altered[68] = {0};
    memcpy(altered, challenge, 68); altered[67] ^= 1;
    memset(proof, 0xa5, sizeof(proof));
    assert(!cf1_cu2_proof(hello, altered, key, proof, error, sizeof(error)));
    for (size_t i = 0; i < sizeof(proof); ++i) assert(proof[i] == 0xa5);
    nonce[0] ^= 1;
    assert(!cf1_cu2_proof(hello, altered, key, proof, error, sizeof(error)));
    /* Valid HMAC is insufficient when the CU/2 approved scope has changed. */
    uint8_t forged_hello[140], forged_challenge[68];
    memcpy(forged_hello, hello, sizeof(forged_hello));
    memcpy(forged_challenge, challenge, sizeof(forged_challenge));
    forged_hello[17] = 'Q'; /* Forbidden VIN character in the approved plan. */
    sign_transcript(forged_hello, forged_challenge, key, 'F', forged_challenge + 36);
    assert(!cf1_cu2_proof(forged_hello, forged_challenge, key, proof,
                          error, sizeof(error)));
    memset(nonce, 0, sizeof(nonce));
    assert(!cf1_cu2_hello(&plan, nonce, hello, error, sizeof(error)));
    plan.pids[0] = 0; assert(!cf1_cu2_hello(&plan, key, hello, error, sizeof(error)));
    plan = known_plan(); plan.rx = 0x7e9;
    assert(!cf1_cu2_hello(&plan, key, hello, error, sizeof(error)));
    OPENSSL_cleanse(key, sizeof(key));
    OPENSSL_cleanse(expected, sizeof(expected));
    puts("CU/2 native C authentication assertions passed (synthetic only)");
    return 0;
}
