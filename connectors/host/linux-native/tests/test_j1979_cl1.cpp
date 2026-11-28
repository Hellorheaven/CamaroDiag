#include "cl1.h"
#include "j1979_native_live_inspection.hpp"
#include <stdexcept>
#include <array>
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
enum class Case { success, timeout, wrong_final, early_inventory, unknown_width };
static fixture complete(Case variant=Case::success) {
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
    const uint8_t bitmap[6]={0x41,0,0,0x10,0,
        static_cast<uint8_t>(variant==Case::early_inventory?1:0)};
    observation(&f,&seq,2,1,0,0,2,3,support_req,bitmap,sizeof(bitmap));
    const uint8_t sample_req[2]={1,0x0c};
    const uint8_t sample[4]={0x41,0x0c,0x1a,0xf8};
    const bool timeout=variant==Case::timeout;
    const auto sample_size=timeout?0u:variant==Case::unknown_width?3u:static_cast<unsigned>(sizeof(sample));
    observation(&f,&seq,4,timeout?4:2,1,0x0c,100,101,sample_req,sample,sample_size);
    if(variant==Case::wrong_final)vin_reply[19]='7';
    observation(&f,&seq,5,1,0,0,102,103,read_vin,vin_reply,sizeof(vin_reply));
    uint8_t terminal[CL1_TERMINAL_SIZE]={0};
    terminal[0]=timeout?8:variant==Case::wrong_final?6:0;
    terminal[1]=variant==Case::wrong_final?5:timeout?7:variant==Case::early_inventory?11:15;
    le16(terminal+2,1); /* completed cycles */
    le32(terminal+4,4); /* observations */
    le32(terminal+8,1); /* samples */
    le32(terminal+12,timeout?0:1);/* RAW */
    le32(terminal+20,timeout?1:0);
    le32(terminal+24,103); /* final observation elapsed ms */
    le32(terminal+28,46+sample_size);
    terminal[33]=0x10; /* advertised PID 0C */
    le16(terminal+64,timeout?0:1);
    record(&f,3,seq++,terminal,sizeof(terminal));
    assert(seq==6);
    return f;
}
// Exact two-PID chronology used by the Android and Windows synthetic
// CU/3 tests. Independently generated CL/1 frames, not captured hardware.
static fixture portable_fixture(){
    fixture f{};
    uint8_t plan[CL1_PLAN_SIZE]{};
    le32(plan,0x7e0);le32(plan+4,0x7e8);memcpy(plan+9,vin,17);
    plan[26]=2;plan[27]=5;plan[28]=12;
    le16(plan+59,2);le16(plan+61,100);le32(plan+63,1000);
    unsigned seq=0;record(&f,1,seq++,plan,sizeof(plan));
    uint8_t identity[20]{0x49,2,1};memcpy(identity+3,vin,17);
    const uint8_t vq[]{9,2},iq[]{1,0},tq[]{1,5},rq[]{1,12};
    const uint8_t bitmap[]{0x41,0,8,0x10,0,0};
    const uint8_t t1[]{0x41,5,0x50},t2[]{0x41,5,0x51};
    const uint8_t r1[]{0x41,12,0x12,0x34},r2[]{0x41,12,0x12,0x35};
    // Use 100 ms timeout exactly as both other hosts; the test helper
    // usually uses 1000 ms and therefore must not be used for this plan.
    auto add=[&](unsigned phase,unsigned state,unsigned cycle,unsigned pid,unsigned at,
        const uint8_t* request,const uint8_t* response,std::size_t length){
        std::array<uint8_t,CL1_EVENT_SIZE+20> data{};
        data[0]=static_cast<uint8_t>(phase);data[1]=static_cast<uint8_t>(state);data[2]=1;
        le16(data.data()+4,cycle);data[6]=static_cast<uint8_t>(pid);
        le32(data.data()+8,at);le32(data.data()+12,at+5);le16(data.data()+16,100);
        le16(data.data()+18,static_cast<unsigned>(length));le32(data.data()+20,5);
        memcpy(data.data()+24,request,2);memcpy(data.data()+26,response,length);
        record(&f,2,seq++,data.data(),CL1_EVENT_SIZE+length);
    };
    add(1,1,0,0,0,vq,identity,20);add(2,1,0,0,5,iq,bitmap,6);
    add(4,2,1,5,10,tq,t1,3);add(4,2,1,12,15,rq,r1,4);
    add(4,2,2,5,110,tq,t2,3);add(4,2,2,12,115,rq,r2,4);
    add(5,1,0,0,120,vq,identity,20);
    uint8_t terminal[CL1_TERMINAL_SIZE]{};
    terminal[1]=15;le16(terminal+2,2);le32(terminal+4,7);
    le32(terminal+8,4);le32(terminal+12,4);le32(terminal+24,125);
    le32(terminal+28,60);terminal[32]=32;terminal[33]=16;
    le16(terminal+64,2);le16(terminal+66,2);
    record(&f,3,seq++,terminal,sizeof(terminal));assert(seq==9);
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
    // Compare two independently replayed recordings, never user-edited
    // engineering data. Different evidence digests are required.
    const auto cross=portable_fixture();
    const auto cross_result=project_cl1_scalar({cross.bytes,cross.used},{0x7e0,0x7e8},vin,std::string(64,'a'));
    assert(cross_result.complete);
    const auto& cross_channels=cross_result.report.at("channels").array();
    assert(cross_channels.size()==2&&cross_channels[0].at("mean").finite_number()==40.5);
    assert(cross_channels[1].at("mean").finite_number()==1165.125);
    assert(cross_channels[0].at("count").unsigned_integer()==2&&cross_channels[1].at("missing").unsigned_integer()==0);
    assert(cross_result.report.at("samples").array().size()==4);
    const auto scalar=project_cl1_scalar({f.bytes,f.used},{0x7e0,0x7e8},vin,std::string(64,'a'));
    assert(scalar.complete&&scalar.report.at("schema").string()=="camarodiag-j1979-cl1-scalar-projection-v1");
    assert(scalar.report.at("samples").array()[0].at("number").finite_number()==1726);
    assert(scalar.report.at("channels").array()[0].at("missing").unsigned_integer()==0);
    assert(verify_cl1_scalar(scalar,cd::json::parse_report(cd::json::serialize(scalar.report))));
    auto changed=scalar.report.object();changed["capture_sha256"]=cd::json::Value{std::string(64,'b')};
    assert(!verify_cl1_scalar(scalar,cd::json::Value{changed}));
    auto portable=scalar.report.object();
    auto samples_portable=portable.at("samples").array();
    auto sample_portable=samples_portable[0].object();
    sample_portable["number"]=cd::json::Value{cd::json::Integer{"1726"}};
    samples_portable[0]=cd::json::Value{sample_portable};
    portable["samples"]=cd::json::Value{samples_portable};
    assert(verify_cl1_scalar(scalar,cd::json::Value{portable}));
    sample_portable["number"]=cd::json::Value{cd::json::Integer{"1727"}};
    samples_portable[0]=cd::json::Value{sample_portable};
    portable["samples"]=cd::json::Value{samples_portable};
    assert(!verify_cl1_scalar(scalar,cd::json::Value{portable}));
    assert(cl1_scalar_csv(scalar).find("DOCUMENTED_SCALAR")!=std::string::npos);
    const std::array runs{projected,projected};
    const std::array hashes{std::string(64,'a'),std::string(64,'b')};
    const auto comparison=compare_native_live(runs,hashes);
    assert(comparison.at("all_recordings_complete").boolean());
    const auto& channel=comparison.at("channel_comparison").array()[0];
    assert(channel.at("comparable").boolean()&&channel.at("mean_range").finite_number()==0);
    bool refused=false;
    try{(void)project_cl1_live({f.bytes,f.used},{0x7e1,0x7e9},vin);}
    catch(const std::invalid_argument&){refused=true;}assert(refused);
    refused=false;
    try{(void)project_cl1_live({f.bytes,f.used-1},{0x7e0,0x7e8},vin);}
    catch(const std::invalid_argument&){refused=true;}assert(refused);

    for(auto variant:{Case::timeout,Case::wrong_final,Case::early_inventory,Case::unknown_width}){
        const auto input=complete(variant);
        cl1_result replay{};
        assert(cl1_inspect_complete(input.bytes,input.used,vin,&replay,error,sizeof(error))==0);
        const auto numeric=project_cl1_live({input.bytes,input.used},{0x7e0,0x7e8},vin);
        assert(numeric.complete==(variant==Case::early_inventory));
        assert(numeric.report.at("identity_rechecked").boolean()==(variant!=Case::wrong_final));
        const auto& row=numeric.report.at("samples").array()[0];
        if(variant!=Case::early_inventory){
            assert(row.at("signals").array().empty());
            assert(!row.at("engineering_verified").boolean());
        }else{
            assert(!replay.inventory_complete&&replay.completed);
            assert(row.at("engineering_verified").boolean());
        }
        if(variant==Case::timeout){
            assert(row.at("status").string()=="TIMEOUT"&&row.at("response_hex").null());
        }
        if(variant==Case::unknown_width){
            assert(row.at("response_hex").string()=="410C1A");
            assert(replay.completed&&!numeric.complete);
        }
    }
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
