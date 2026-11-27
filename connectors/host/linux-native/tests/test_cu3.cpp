#include "cl1_transport.h"
#include <assert.h>
#include <openssl/crypto.h>
#include <openssl/evp.h>
#include <openssl/hmac.h>
#include <stdio.h>
#include <string.h>

/* Synthetic CU/3 proof transcript only; no physical channel or ECU. */
static cl1_plan scope(void) {
    cl1_plan p{};
    p.tx=0x7e0;p.rx=0x7e8;
    memcpy(p.vin,"1G1FA1RXXR0123456",18);
    p.pids[0]=0x0c;p.pid_count=1;p.cycles=2;
    p.interval_ms=500;p.budget_ms=60000;
    return p;
}
static void sign(const uint8_t hello[CL1_CU3_HELLO_SIZE],
                 const uint8_t challenge[CL1_CU3_CHALLENGE_SIZE],
                 const uint8_t key[32],uint8_t domain,uint8_t tag[32]) {
    uint8_t transcript[1+CL1_CU3_PROFILE_SIZE+64]={0};
    unsigned tag_length=0;
    transcript[0]=domain;
    memcpy(transcript+1,hello+4,CL1_CU3_PROFILE_SIZE+32);
    memcpy(transcript+1+CL1_CU3_PROFILE_SIZE+32,challenge+4,32);
    assert(HMAC(EVP_sha256(),key,32,transcript,sizeof(transcript),
                tag,&tag_length)!=NULL);
    assert(tag_length==32);
    OPENSSL_cleanse(transcript,sizeof(transcript));
}
int main(void) {
    cl1_plan p=scope();char err[160]={0};
    assert(cl1_valid_plan(&p));
    uint8_t plan[CL1_PLAN_SIZE]={0};
    uint8_t auth[CL1_CU3_PROFILE_SIZE]={0};
    uint8_t nonce[32]={0},key[32]={0};
    for (size_t i=0;i<32;++i) {
        nonce[i]=(uint8_t)(i+1);
        key[i]=(uint8_t)(i+71);
    }
    assert(cl1_encode_plan(&p,plan,err,sizeof(err)));
    assert(cl1_encode_auth(&p,auth,err,sizeof(err)));
    assert(!memcmp(auth,plan,9));
    assert(!memcmp(auth+13,plan+9,CL1_PLAN_SIZE-9));
    assert(auth[30]==1 && auth[31]==0x0c);
    assert(auth[63]==2 && auth[65]==(uint8_t)500);
    uint8_t hello[CL1_CU3_HELLO_SIZE]={0},challenge[CL1_CU3_CHALLENGE_SIZE]={0};
    uint8_t proof[CL1_CU3_PROOF_SIZE]={0};
    assert(cl1_cu3_hello(&p,nonce,hello,err,sizeof(err)));
    assert(!memcmp(hello,"CU\x03\x01",4));
    assert(!memcmp(hello+4,auth,sizeof(auth)));
    assert(!memcmp(hello+4+sizeof(auth),nonce,32));
    memcpy(challenge,"CU\x03\x02",4);
    for (size_t i=0;i<32;++i) challenge[4+i]=(uint8_t)(255-i);
    sign(hello,challenge,key,'L',challenge+36);
    assert(cl1_cu3_proof(hello,challenge,key,proof,err,sizeof(err)));
    assert(!memcmp(proof,"CU\x03\x03",4));
    uint8_t expected[32]={0};
    sign(hello,challenge,key,'l',expected);
    assert(CRYPTO_memcmp(expected,proof+4,32)==0);

    uint8_t tampered[CL1_CU3_CHALLENGE_SIZE];
    memcpy(tampered,challenge,sizeof(tampered));tampered[67]^=1;
    memset(proof,0xa5,sizeof(proof));
    assert(!cl1_cu3_proof(hello,tampered,key,proof,err,sizeof(err)));
    for (size_t i=0;i<sizeof(proof);++i) assert(proof[i]==0xa5);

    uint8_t forged[CL1_CU3_HELLO_SIZE];
    memcpy(forged,hello,sizeof(forged));
    forged[4+13]='Q'; /* invalid VIN even with a recomputed HMAC */
    memcpy(tampered,challenge,sizeof(tampered));
    sign(forged,tampered,key,'L',tampered+36);
    assert(!cl1_cu3_proof(forged,tampered,key,proof,err,sizeof(err)));
    memcpy(forged,hello,sizeof(forged));
    forged[4+31+1]=0x55; /* nonzero padding after only one PID */
    sign(forged,tampered,key,'L',tampered+36);
    assert(!cl1_cu3_proof(forged,tampered,key,proof,err,sizeof(err)));

    memset(nonce,0,sizeof(nonce));
    assert(!cl1_cu3_hello(&p,nonce,hello,err,sizeof(err)));
    p.pids[0]=0;
    assert(!cl1_cu3_hello(&p,key,hello,err,sizeof(err)));
    p=scope();p.rx=0x7e9;
    assert(!cl1_cu3_hello(&p,key,hello,err,sizeof(err)));
    OPENSSL_cleanse(key,sizeof(key));
    OPENSSL_cleanse(expected,sizeof(expected));
    puts("CU/3 native C++ mutual credential and canonical scope: synthetic checks passed");
    return 0;
}
