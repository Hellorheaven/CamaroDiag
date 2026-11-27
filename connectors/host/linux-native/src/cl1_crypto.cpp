// CamaroDiag native C++20 cl1_crypto implementation.
#include "cl1_transport.h"
#include <array>
#include <span>
#include <openssl/crypto.h>
#include <openssl/evp.h>
#include <openssl/hmac.h>
#include <openssl/rand.h>
#include <stdio.h>
#include <string.h>

static void le16(uint8_t *p,uint16_t n) {
    p[0]=(uint8_t)n;p[1]=(uint8_t)(n>>8);
}
static void le32(uint8_t *p,uint32_t n) {
    for (unsigned i=0;i<4;++i) p[i]=(uint8_t)(n>>(i*8));
}
static uint16_t read16(const uint8_t *p) {
    return (uint16_t)((unsigned)p[0] | ((unsigned)p[1]<<8));
}
static uint32_t read32(const uint8_t *p) {
    return (uint32_t)p[0] | ((uint32_t)p[1]<<8) |
           ((uint32_t)p[2]<<16) | ((uint32_t)p[3]<<24);
}
static bool fail(char *err,size_t n,const char *reason) {
    if (n) snprintf(err,n,"%s",reason);
    return false;
}
bool cl1_encode_plan(const cl1_plan *p,uint8_t wire[CL1_PLAN_SIZE],
                     char *err,size_t n) {
    if (!wire || !cl1_valid_plan(p)) return fail(err,n,"Invalid CL/1 physical scope");
    memset(wire,0,CL1_PLAN_SIZE);
    le32(wire,p->tx);le32(wire+4,p->rx);
    wire[8]=(uint8_t)(p->extended?1:0);
    memcpy(wire+9,p->vin,17);wire[26]=p->pid_count;
    memcpy(wire+27,p->pids,p->pid_count);
    le16(wire+59,p->cycles);
    le16(wire+61,p->interval_ms);
    le32(wire+63,p->budget_ms);
    return true;
}
bool cl1_encode_auth(const cl1_plan *p,
                     uint8_t wire[CL1_CU3_PROFILE_SIZE],
                     char *err,size_t n) {
    uint8_t capture[CL1_PLAN_SIZE]={0};
    if (!wire || !cl1_encode_plan(p,capture,err,n)) return false;
    memset(wire,0,CL1_CU3_PROFILE_SIZE);
    memcpy(wire,capture,9);le32(wire+9,p->budget_ms);
    memcpy(wire+13,capture+9,CL1_PLAN_SIZE-9);
    OPENSSL_cleanse(capture,sizeof(capture));
    return true;
}
bool cl1_cu3_random_nonce(uint8_t nonce[32]) {
    if (!nonce || RAND_bytes(nonce,32)!=1) return false;
    unsigned nonzero=0;
    for (size_t i=0;i<32;++i) nonzero |= nonce[i];
    return nonzero!=0;
}
bool cl1_cu3_hello(const cl1_plan *p,const uint8_t nonce[32],
                   uint8_t hello[CL1_CU3_HELLO_SIZE],
                   char *err,size_t n) {
    if (!hello || !nonce) return fail(err,n,"Missing CU/3 nonce or output");
    unsigned nonzero=0;
    for (size_t i=0;i<32;++i) nonzero |= nonce[i];
    if (!nonzero) return fail(err,n,"Zero CU/3 nonce not permitted");
    memset(hello,0,CL1_CU3_HELLO_SIZE);
    hello[0]='C';hello[1]='U';hello[2]=3;hello[3]=1;
    if (!cl1_encode_auth(p,hello+4,err,n)) return false;
    memcpy(hello+4+CL1_CU3_PROFILE_SIZE,nonce,32);
    return true;
}
static bool canonical_auth(const uint8_t wire[CL1_CU3_PROFILE_SIZE]) {
    if (wire[8]>1 || wire[30]<1 || wire[30]>CL1_MAX_PIDS) return false;
    cl1_plan p{};
    p.tx=read32(wire);p.rx=read32(wire+4);p.extended=wire[8]!=0;
    p.budget_ms=read32(wire+9);
    memcpy(p.vin,wire+13,17);
    p.pid_count=wire[30];memcpy(p.pids,wire+31,p.pid_count);
    p.cycles=read16(wire+63);
    p.interval_ms=read16(wire+65);
    if (p.budget_ms!=read32(wire+67)) return false;
    uint8_t reencoded[CL1_CU3_PROFILE_SIZE]={0};
    bool valid=cl1_encode_auth(&p,reencoded,NULL,0) &&
               CRYPTO_memcmp(wire,reencoded,sizeof(reencoded))==0;
    OPENSSL_cleanse(reencoded,sizeof(reencoded));
    return valid;
}
bool cl1_cu3_proof(const uint8_t hello[CL1_CU3_HELLO_SIZE],
                   const uint8_t challenge[CL1_CU3_CHALLENGE_SIZE],
                   const uint8_t key[32],uint8_t proof[CL1_CU3_PROOF_SIZE],
                   char *err,size_t n) {
    if (!hello||!challenge||!key||!proof||
        memcmp(hello,"CU\x03\x01",4)||
        memcmp(challenge,"CU\x03\x02",4)||
        !canonical_auth(hello+4))
        return fail(err,n,"Invalid or noncanonical CU/3 challenge scope");
    unsigned client_nonzero=0,device_nonzero=0;
    for (unsigned i=0;i<32;++i) {
        client_nonzero |= hello[4+CL1_CU3_PROFILE_SIZE+i];
        device_nonzero |= challenge[4+i];
    }
    if (!client_nonzero || !device_nonzero)
        return fail(err,n,"CU/3 nonce cannot be all zero");
    /* CU/3 domain separation: server L, client l. The transcript contains
     * 71 canonical scope bytes and both independently generated 32-byte
     * nonces. No downgrade to CU/1 or CU/2, no retry on uncertain proof.
     */
    std::array<uint8_t,1+CL1_CU3_PROFILE_SIZE+64> transcript{};
    std::array<uint8_t,EVP_MAX_MD_SIZE> tag{};
    unsigned tag_size=0;
    transcript[0]='L';
    memcpy(transcript.data()+1,hello+4,CL1_CU3_PROFILE_SIZE+32);
    memcpy(transcript.data()+1+CL1_CU3_PROFILE_SIZE+32,challenge+4,32);
    bool valid=HMAC(EVP_sha256(),key,32,transcript.data(),transcript.size(),
                    tag.data(),&tag_size)!=NULL &&
               tag_size==32 && CRYPTO_memcmp(tag.data(),challenge+36,32)==0;
    OPENSSL_cleanse(tag.data(),tag.size());
    if (valid) {
        transcript[0]='l';
        valid=HMAC(EVP_sha256(),key,32,transcript.data(),transcript.size(),
                   tag.data(),&tag_size)!=NULL && tag_size==32;
        if (valid) {
            memcpy(proof,"CU\x03\x03",4);
            memcpy(proof+4,tag.data(),32);
        }
    }
    OPENSSL_cleanse(tag.data(),tag.size());
    OPENSSL_cleanse(transcript.data(),transcript.size());
    return valid?true:fail(err,n,"CU/3 device credential proof rejected");
}
