#include "cl1.h"
#include "j1979_native_live_inspection.hpp"
#include <stdexcept>
#include "cf1.h"
#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

/* Synthetic CL/1 recorder. No live port or physical vehicle is accessed. */
static const char *vin="1G1FA1RXXR0123456";
typedef struct {uint8_t bytes[4096];size_t used;} fixture;
static void le16(uint8_t *p,unsigned n) {
    p[0]=(uint8_t)n;p[1]=(uint8_t)(n>>8);
}
static void le32(uint8_t *p,uint32_t n) {
    for (unsigned i=0;i<4;++i) p[i]=(uint8_t)(n>>(i*8));
}
static void record(fixture *f,unsigned kind,unsigned sequence,
                   const uint8_t *body,size_t size) {
    assert(f->used+size+12<=sizeof(f->bytes) && size<=65535);
    uint8_t *h=f->bytes+f->used;
    h[0]='C';h[1]='L';h[2]=1;h[3]=(uint8_t)kind;
    le16(h+4,sequence);le16(h+6,(unsigned)size);
    memcpy(h+8,body,size);
    le32(h+8+size,cf1_crc32(h,8+size));
    f->used+=12+size;
}
static void observation(fixture *f,unsigned *sequence,unsigned phase,
                        unsigned state,unsigned cycle,unsigned pid,
                        uint32_t start,uint32_t end,
                        const uint8_t request[2],
                        const uint8_t *response,size_t size) {
    uint8_t data[CL1_EVENT_SIZE+20]={0};
    assert(size<=20);
    data[0]=(uint8_t)phase;data[1]=(uint8_t)state;data[2]=1;
    le16(data+4,cycle);data[6]=(uint8_t)pid;
    le32(data+8,start);le32(data+12,end);
    le16(data+16,1000);le16(data+18,(unsigned)size);
    le32(data+20,end-start);
    memcpy(data+24,request,2);
    memcpy(data+CL1_EVENT_SIZE,response,size);
    record(f,2,(*sequence)++,data,CL1_EVENT_SIZE+size);
}
static fixture complete(void) {
    fixture f{};
    uint8_t plan[CL1_PLAN_SIZE]={0};
    le32(plan,0x7e0);le32(plan+4,0x7e8);
    memcpy(plan+9,vin,17);
    plan[26]=1;plan[27]=0x0c;
    le16(plan+59,1);le16(plan+61,100);
    le32(plan+63,60000);
    unsigned seq=0;
    record(&f,1,seq++,plan,sizeof(plan));
    uint8_t vin_reply[20]={0x49,2,1};
    memcpy(vin_reply+3,vin,17);
    const uint8_t read_vin[2]={9,2};
    observation(&f,&seq,1,1,0,0,0,1,read_vin,vin_reply,sizeof(vin_reply));
    const uint8_t support_req[2]={1,0};
    const uint8_t bitmap[6]={0x41,0,0,0x10,0,0};
    observation(&f,&seq,2,1,0,0,2,3,support_req,bitmap,sizeof(bitmap));
    const uint8_t sample_req[2]={1,0x0c};
    const uint8_t sample[4]={0x41,0x0c,0x1a,0xf8};
    observation(&f,&seq,4,2,1,0x0c,100,101,sample_req,sample,sizeof(sample));
    observation(&f,&seq,5,1,0,0,102,103,read_vin,vin_reply,sizeof(vin_reply));
    uint8_t terminal[CL1_TERMINAL_SIZE]={0};
    terminal[1]=15; /* initial/final VIN, inventory, completed */
    le16(terminal+2,1); /* completed cycles */
    le32(terminal+4,4); /* observations */
    le32(terminal+8,1); /* samples */
    le32(terminal+12,1);/* RAW */
    le32(terminal+24,103); /* final observation elapsed ms */
    le32(terminal+28,50); /* 20+6+4+20 raw bytes */
    terminal[33]=0x10; /* advertised PID 0C */
    le16(terminal+64,1); /* one RAW per selected PID */
    record(&f,3,seq++,terminal,sizeof(terminal));
    assert(seq==6);
    return f;
}
int main(void) {
    fixture f=complete();
    cl1_result result{};char error[256]={0};
    assert(cl1_inspect_complete(f.bytes,f.used,vin,&result,error,sizeof(error))==0);
    assert(result.completed && result.terminal_present &&
           result.capture_integrity_verified);
    assert(result.records==6 && result.steps==4 && result.samples==1);
    assert(result.raw_samples==1 && result.raw_bytes==50);
    assert(result.initial_vin && result.final_vin && result.inventory_complete);
    assert(result.raw_per_pid[0]==1);

    using namespace cd::network::history;
    const auto projected=project_cl1_live({f.bytes,f.used},{0x7e0,0x7e8},vin);
    assert(projected.complete&&projected.report.at("source_transport").string()=="OFFLINE_CL1");
    assert(projected.report.at("identity_rechecked").boolean());
    assert(!projected.report.at("physical_vehicle_verified").boolean());
    const auto& samples=projected.report.at("samples").array();
    assert(samples.size()==1&&samples[0].at("pid").unsigned_integer()==12);
    assert(samples[0].at("response_hex").string()=="410C1AF8");
    assert(samples[0].at("signals").array()[0].at("number").finite_number()==1726);
    assert(projected.report.at("channel_summary").array()[0].at("mean").finite_number()==1726);
    assert(native_live_csv(projected).find("engine-rpm")!=std::string::npos);
    bool refused=false;
    try{(void)project_cl1_live({f.bytes,f.used},{0x7e1,0x7e9},vin);}
    catch(const std::invalid_argument&){refused=true;}assert(refused);
    refused=false;
    try{(void)project_cl1_live({f.bytes,f.used-1},{0x7e0,0x7e8},vin);}
    catch(const std::invalid_argument&){refused=true;}assert(refused);

    /* Full receipt never qualifies as interrupted prefix. */
    assert(cl1_inspect_prefix(f.bytes,f.used,vin,&result,error,sizeof(error))!=0);

    /* All complete records before a truncated header remain a valid prefix. */
    size_t terminal_size=CL1_TERMINAL_SIZE+12;
    size_t prefix_size=f.used-terminal_size;
    assert(cl1_inspect_prefix(f.bytes,prefix_size,vin,&result,error,sizeof(error))==0);
    assert(result.prefix_integrity_verified && !result.completed);
    assert(!result.terminal_present && result.records==5);
    assert(result.discarded_tail_bytes==0);
    assert(cl1_inspect_complete(f.bytes,prefix_size,vin,&result,error,sizeof(error))!=0);
    f.bytes[prefix_size]='C';f.bytes[prefix_size+1]='L';
    assert(cl1_inspect_prefix(f.bytes,prefix_size+2,vin,&result,error,sizeof(error))==0);
    assert(result.discarded_tail_bytes==2 &&
           !strcmp(result.interruption,"TRUNCATED_HEADER"));

    f=complete();f.bytes[f.used-1]^=1; /* corrupted terminal CRC */
    assert(cl1_inspect_complete(f.bytes,f.used,vin,&result,error,sizeof(error))!=0);
    f=complete();
    assert(cl1_inspect_complete(f.bytes,f.used,
        "2G1FA1RXXR0123456",&result,error,sizeof(error))!=0);
    f=complete();f.bytes[4]=1; /* invalid initial sequence */
    assert(cl1_inspect_complete(f.bytes,f.used,vin,&result,error,sizeof(error))!=0);
    f=complete();f.bytes[8+27]=0; /* selected PID no longer valid */
    assert(cl1_inspect_complete(f.bytes,f.used,vin,&result,error,sizeof(error))!=0);
    puts("Native CL/1 complete and partial Mode 01 replay policy: synthetic checks passed");
    return 0;
}
