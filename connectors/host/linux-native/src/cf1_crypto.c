#include "cf1_transport.h"

#include <openssl/crypto.h>
#include <openssl/evp.h>
#include <openssl/hmac.h>
#include <openssl/rand.h>
#include <stdio.h>
#include <string.h>

static void le32(uint8_t *p, uint32_t v) {
    for (size_t i = 0; i < 4; ++i) p[i] = (uint8_t)(v >> (i * 8));
}
static bool deny(char *error, size_t n, const char *why) {
    if (n) snprintf(error, n, "%s", why);
    return false;
}
bool cf1_encode_profile(const cf1_plan *p, uint8_t wire[CF1_PLAN_SIZE],
                        char *err, size_t cap) {
    if (!wire || !cf1_valid_plan(p)) return deny(err, cap, "Invalid CU/2 physical plan");
    memset(wire, 0, CF1_PLAN_SIZE);
    le32(wire, p->tx); le32(wire + 4, p->rx);
    wire[8] = p->extended ? 1u : 0u;
    le32(wire + 9, p->budget_ms);
    memcpy(wire + 13, p->vin, 17);
    wire[30] = p->frame_count;
    memcpy(wire + 31, p->frames, p->frame_count);
    wire[39] = p->pid_count;
    memcpy(wire + 40, p->pids, p->pid_count);
    return true;
}
bool cf1_cu2_hello(const cf1_plan *p, const uint8_t nonce[32],
                    uint8_t wire[CF1_CU2_HELLO_SIZE],
                    char *err, size_t cap) {
    if (!nonce || !wire) return deny(err, cap, "Missing CU/2 nonce or output");
    unsigned nonzero = 0;
    for (size_t i = 0; i < 32; ++i) nonzero |= nonce[i];
    if (!nonzero) return deny(err, cap, "CU/2 client nonce must be random and nonzero");
    memset(wire, 0, CF1_CU2_HELLO_SIZE);
    wire[0] = 'C'; wire[1] = 'U'; wire[2] = 2; wire[3] = 1;
    if (!cf1_encode_profile(p, wire + 4, err, cap)) return false;
    memcpy(wire + 108, nonce, 32);
    return true;
}
bool cf1_cu2_proof(const uint8_t hello[CF1_CU2_HELLO_SIZE],
                    const uint8_t challenge[CF1_CU2_CHALLENGE_SIZE],
                    const uint8_t key[32],
                    uint8_t proof[CF1_CU2_PROOF_SIZE], char *err, size_t cap) {
    if (!hello || !challenge || !key || !proof ||
        memcmp(hello, "CU\x02\x01", 4) ||
        memcmp(challenge, "CU\x02\x02", 4))
        return deny(err, cap, "Invalid CU/2 challenge framing");
    unsigned client_nonzero = 0, device_nonzero = 0;
    for (size_t i = 0; i < 32; ++i) {
        client_nonzero |= hello[108 + i];
        device_nonzero |= challenge[4 + i];
    }
    if (!client_nonzero || !device_nonzero)
        return deny(err, cap, "Empty CU/2 session nonce");
    /* Transcript: domain separator, 136 bytes of hello payload + 32 of
     * device nonce. Scope is bound to both HMACs; no pairing fallback.
     */
    uint8_t transcript[169] = {0}, computed[EVP_MAX_MD_SIZE] = {0};
    unsigned int computed_size = 0;
    transcript[0] = 'F';
    memcpy(transcript + 1, hello + 4, 136);
    memcpy(transcript + 137, challenge + 4, 32);
    bool ok = HMAC(EVP_sha256(), key, 32, transcript, sizeof(transcript),
                   computed, &computed_size) != NULL &&
              computed_size == 32 &&
              CRYPTO_memcmp(computed, challenge + 36, 32) == 0;
    OPENSSL_cleanse(computed, sizeof(computed));
    if (ok) {
        transcript[0] = 'f';
        ok = HMAC(EVP_sha256(), key, 32, transcript, sizeof(transcript),
                  computed, &computed_size) != NULL && computed_size == 32;
        if (ok) {
            memcpy(proof, "CU\x02\x03", 4);
            memcpy(proof + 4, computed, 32);
        }
    }
    OPENSSL_cleanse(computed, sizeof(computed));
    OPENSSL_cleanse(transcript, sizeof(transcript));
    return ok ? true : deny(err, cap, "CU/2 device credential proof rejected");
}
bool cf1_cu2_random_nonce(uint8_t nonce[32]) {
    if (!nonce || RAND_bytes(nonce, 32) != 1) return false;
    unsigned bits = 0;
    for (size_t i = 0; i < 32; ++i) bits |= nonce[i];
    return bits != 0;
}
