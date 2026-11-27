#define _POSIX_C_SOURCE 200809L
#include "j1979_live.h"
#include "j1979_measurement.h"
#include "j1979_context.hpp"
#include <limits.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <array>
#include <algorithm>
#include <memory>
#include <new>
#include <utility>

static uint64_t uptime_ms(void) {
    struct timespec ts{};
    if (clock_gettime(CLOCK_MONOTONIC,&ts)) return 0;
    return (uint64_t)ts.tv_sec*1000u+(uint64_t)ts.tv_nsec/1000000u;
}
static uint64_t tick(const j1979_live_plan *p) {
    return p->channel.clock ? p->channel.clock(p->channel.context) : uptime_ms();
}
static bool current(const j1979_live_plan *p) {
    return p->channel.current(p->channel.context);
}
static void halt(j1979_live_result *r,const char *reason) {
    if (!r->stop_reason[0])
        snprintf(r->stop_reason,sizeof(r->stop_reason),"%s",reason);
}
bool j1979_live_plan_valid(const j1979_live_plan *p) {
    if (!p || !p->channel.exchange || !p->channel.current ||
        !j1979_can_physical_pair(p->channel.tx,p->channel.rx) ||
        !j1979_can_valid_vin(p->channel.expected_vin) ||
        p->pid_count==0 || p->pid_count>J1979_LIVE_MAX_PIDS ||
        p->cycles==0 || p->cycles>1000 ||
        p->cycles*p->pid_count>J1979_LIVE_MAX_SAMPLES ||
        p->interval_ms<100 || p->interval_ms>60000 ||
        p->channel.budget_ms<1000 || p->channel.budget_ms>600000) return false;
    bool used[256]={false};
    for (unsigned i=0;i<p->pid_count;++i) {
        unsigned pid=p->pids[i];
        if (pid==0 || pid%32u==0 || used[pid]) return false;
        used[pid]=true;
    }
    return true;
}
static j1979_live_step *query(const j1979_live_plan *p,j1979_live_result *r,
        uint64_t start, const char *phase,unsigned cycle,uint8_t mode,uint8_t pid) {
    if (r->stop_reason[0]) return NULL;
    if (!current(p)) {halt(r,"CONTEXT_CHANGED");return NULL;}
    uint64_t now=tick(p);
    if (now<start || now-start>=p->channel.budget_ms) {
        halt(r,"BUDGET_EXHAUSTED");return NULL;
    }
    if (r->step_count==J1979_LIVE_MAX_STEPS) {
        halt(r,"STEP_LIMIT");return NULL;
    }
    j1979_live_step *s=&r->steps[r->step_count++];
    snprintf(s->phase,sizeof(s->phase),"%s",phase);
    snprintf(s->status,sizeof(s->status),"NOT_TESTED");
    s->request[0]=mode;s->request[1]=pid;s->cycle=cycle;
    s->started_ms=now-start;
    unsigned remaining=(unsigned)(p->channel.budget_ms-(now-start));
    unsigned timeout=remaining>2500?2500:remaining;
    std::array<uint8_t,J1979_CAN_MAX_PAYLOAD+1> raw{};
    size_t length=raw.size();
    int rc=p->channel.exchange(p->channel.context,s->request,2,raw.data(),&length,timeout);
    uint64_t end=tick(p);
    if (end>=now && end-now<=UINT_MAX) s->duration_ms=(unsigned)(end-now);
    s->elapsed_ms=end>=start?end-start:0;
    if (length>J1979_CAN_MAX_PAYLOAD) {halt(r,"UNBOUNDED_REPLY");return s;}
    if (rc==0 && length && r->raw_bytes+length<=J1979_LIVE_MAX_RAW) {
        std::unique_ptr<uint8_t[]> owned{new (std::nothrow) uint8_t[length]};
        if (!owned) {halt(r,"MEMORY_EXHAUSTED");return s;}
        std::copy_n(raw.begin(),length,owned.get());
        s->response=owned.release(); /* Legacy ABI: j1979_live_free owns it. */
        s->response_size=length;
        r->raw_bytes+=length;
    } else if (rc==0) {halt(r,"INVALID_REPLY_SIZE");return s;}
    if (end<start || end-start>=p->channel.budget_ms) {
        snprintf(s->detail,sizeof(s->detail),"LATE_RESPONSE");
        halt(r,"BUDGET_EXHAUSTED");return s;
    }
    if (!current(p)) {halt(r,"CONTEXT_CHANGED");return s;}
    if (end<now || end-now>timeout) {
        snprintf(s->detail,sizeof(s->detail),"EXCHANGE_DEADLINE_EXCEEDED");
        halt(r,"TRANSPORT_ERROR");return s;
    }
    if (rc==1) {
        snprintf(s->status,sizeof(s->status),"TIMEOUT");
        return s;
    }
    if (rc!=0) {halt(r,"TRANSPORT_ERROR");return s;}
    if (length==3 && raw[0]==0x7f && raw[1]==mode &&
        raw[2]!=0 && raw[2]!=0x78) {
        snprintf(s->status,sizeof(s->status),"NEGATIVE");
        snprintf(s->detail,sizeof(s->detail),"NRC_%02X",raw[2]);
        return s;
    }
    if (length<3 || raw[0]!=(uint8_t)(mode+0x40u) || raw[1]!=pid) {
        snprintf(s->status,sizeof(s->status),"FAILED");
        halt(r,"WRONG_ECU_RESPONSE");return s;
    }
    snprintf(s->status,sizeof(s->status),"RAW");
    return s;
}
static bool passed(const j1979_live_step *s) {
    return s && !strcmp(s->status,"RAW");
}
static bool identity(const j1979_live_plan *p,j1979_live_result *r,
                     uint64_t start,const char *phase,bool final) {
    j1979_live_step *s=query(p,r,start,phase,0,9,2);
    if (!passed(s) || s->response_size!=20 ||
        s->response[2]!=1) {
        halt(r,final?"FINAL_VIN_UNVERIFIED":"VIN_UNVERIFIED");
        return false;
    }
    char observed[18]{};
    memcpy(observed,s->response+3,17);
    if (!j1979_can_valid_vin(observed) ||
        strcmp(observed,p->channel.expected_vin) ||
        (r->initial_vin_valid && strcmp(observed,r->initial_vin))) {
        halt(r,"VIN_MISMATCH");return false;
    }
    if (final) {
        memcpy(r->final_vin,observed,sizeof(observed));
        r->final_vin_valid=true;
    } else {
        memcpy(r->initial_vin,observed,sizeof(observed));
        r->initial_vin_valid=true;
    }
    snprintf(s->status,sizeof(s->status),"PASSED");
    return true;
}
static bool support(const j1979_live_plan *p,j1979_live_result *r,uint64_t start) {
    for (unsigned base=0;base<=224;++base) {
        if (base%32u) continue;
        j1979_live_step *s=query(p,r,start,"INVENTORY",0,1,(uint8_t)base);
        if (!passed(s) || s->response_size!=6) {
            halt(r,"INVENTORY_INCOMPLETE");return false;
        }
        const uint8_t *b=s->response+2;
        for (unsigned bit=0;bit<31;++bit)
            if (b[bit/8] & (uint8_t)(0x80u>>(bit%8u)))
                r->advertised[base+bit+1]=true;
        bool next=(b[3]&1u)!=0;
        snprintf(s->status,sizeof(s->status),"PASSED");
        if (!next) {r->inventory_complete=true;break;}
        if (base==224) {halt(r,"INVALID_SUPPORT_CHAIN");return false;}
    }
    if (!r->inventory_complete) {halt(r,"INVENTORY_INCOMPLETE");return false;}
    for (unsigned i=0;i<p->pid_count;++i)
        if (!r->advertised[p->pids[i]]) {
            halt(r,"PID_NOT_ADVERTISED");return false;
        }
    return true;
}
/* Read only the prerequisites for the selected measurement set. A missing
 * or malformed optional prerequisite leaves its engineering value unknown,
 * not a fabricated default. Never ask an unadvertised PID. */
static bool admit_context(const j1979_live_plan *p,j1979_live_result *r,uint64_t start) {
    bool layout=false,first=false,second=false,baseline=false;
    for (unsigned i=0;i<p->pid_count;++i) {
        const unsigned pid=p->pids[i];
        layout=layout||cd::j1979::trim_pid(pid);
        baseline=baseline||pid==0x41;
        if (const auto dep=cd::j1979::dependency(pid)) {
            first=first||*dep==0x4f;
            second=second||*dep==0x50;
        }
    }
    const std::array<std::pair<unsigned,bool>,4> prerequisites{{
        {0x1d,layout},{0x4f,first},{0x50,second},{0x01,baseline}}};
    for (const auto& [pid,needed]:prerequisites) {
        if (!needed||!r->advertised[pid]) continue;
        const auto* step=query(p,r,start,"CONTEXT",0,1,static_cast<uint8_t>(pid));
        if (!step||r->stop_reason[0]) return false;
        // Optional application failure/timeout is retained, not retried.
    }
    return true;
}
static bool delay_until(const j1979_live_plan *p,j1979_live_result *r,
                        uint64_t start,uint64_t target) {
    for (;;) {
        if (!current(p)) {halt(r,"CONTEXT_CHANGED");return false;}
        uint64_t now=tick(p);
        if (now<start || now-start>=p->channel.budget_ms) {
            halt(r,"BUDGET_EXHAUSTED");return false;
        }
        if (now>=target) return true;
        unsigned slice=target-now>100?100:(unsigned)(target-now);
        if (!slice) slice=1;
        if (p->wait) {
            if (!p->wait(p->channel.context,slice)) {
                halt(r,"CANCELLED");return false;
            }
        } else {
            struct timespec ts={(time_t)(slice/1000u),
                                (long)(slice%1000u)*1000000L};
            nanosleep(&ts,NULL);
        }
    }
}
int j1979_live_record(const j1979_live_plan *p,j1979_live_result *r) {
    if (!r) return -1;
    *r={};
    if (!j1979_live_plan_valid(p)) return -1;
    std::unique_ptr<j1979_live_step[]> owned{
        new (std::nothrow) j1979_live_step[J1979_LIVE_MAX_STEPS]{}};
    if (!owned) return -1;
    r->steps=owned.release();
    uint64_t start=tick(p),last_identity=start,last_cycle_start=start;
    if (!start) {halt(r,"CLOCK_UNAVAILABLE");goto finish;}
    if (!identity(p,r,start,"INITIAL_VIN",false)) goto finish;
    if (!support(p,r,start)) goto finish;
    if (!admit_context(p,r,start)) goto finish;
    for (unsigned cycle=1;cycle<=p->cycles && !r->stop_reason[0];++cycle) {
        uint64_t cycle_start=tick(p);
        if (cycle>1 && !delay_until(p,r,start,
                                   last_cycle_start + p->interval_ms)) break;
        cycle_start=tick(p);
        if (cycle_start<start) {halt(r,"CLOCK_REGRESSION");break;}
        if (cycle_start-last_identity>=30000u) {
            if (!identity(p,r,start,"PERIODIC_VIN",false)) break;
            last_identity=tick(p);
        }
        for (unsigned i=0;i<p->pid_count && !r->stop_reason[0];++i) {
            j1979_live_step *s=query(p,r,start,"SAMPLE",cycle,1,p->pids[i]);
            if (!s) break;
            if (!passed(s)) {
                ++r->missing_samples;
                if (strcmp(s->status,"TIMEOUT") && strcmp(s->status,"NEGATIVE"))
                    break;
            } else {
                ++r->raw_samples;
            }
        }
        if (r->stop_reason[0]) break;
        ++r->cycles_completed;
        last_cycle_start=cycle_start;
    }
    if (!r->stop_reason[0] && r->cycles_completed==p->cycles)
        identity(p,r,start,"FINAL_VIN",true);
    if (!r->stop_reason[0] && r->missing_samples)
        halt(r,"INCOMPLETE_SAMPLES");
finish:
    { uint64_t end=tick(p);r->duration_ms=end>=start?end-start:0; }
    r->completed=!r->stop_reason[0] && r->final_vin_valid &&
        r->cycles_completed==p->cycles;
    return 0;
}
void j1979_live_free(j1979_live_result *r) {
    if (!r) return;
    for (size_t i=0;i<r->step_count;++i) delete[] r->steps[i].response;
    delete[] r->steps;
    *r={};
}
static bool string(FILE *f,const char *s) {
    if (fputc('"',f)==EOF) return false;
    for (const unsigned char *v=(const unsigned char *)s;*v;++v) {
        if (*v<32 || *v==127) return false;
        if ((*v=='"' || *v=='\\') && fputc('\\',f)==EOF) return false;
        if (fputc(*v,f)==EOF) return false;
    }
    return fputc('"',f)!=EOF;
}
static bool hex(FILE *f,const uint8_t *b,size_t n) {
    const char alphabet[]="0123456789ABCDEF";
    if (fputc('"',f)==EOF) return false;
    for (size_t i=0;i<n;++i) {
        if (fputc(alphabet[b[i]>>4],f)==EOF ||
            fputc(alphabet[b[i]&15],f)==EOF) return false;
    }
    return fputc('"',f)!=EOF;
}
bool j1979_live_write_json(FILE *f,const j1979_live_plan *p,
                            const j1979_live_result *r,const char *iface) {
    if (!f||!p||!r||!r->steps||!iface) return false;
    if (fputs("{\"schema\":\"camarodiag-j1979-live-linux-c-v1\","
      "\"protocol\":\"SAE-J1979\",\"transport\":\"LINUX_SOCKETCAN_ISOTP\","
      "\"source\":\"LIVE_PHYSICAL_PAIR\",\"raw_only\":true,"
      "\"physical_vehicle_verified\":false,\"permits_programming\":false,"
      "\"interface\":",f)==EOF||!string(f,iface)) return false;
    if (fprintf(f,",\"tx_can_id\":\"%X\",\"rx_can_id\":\"%X\",\"expected_vin\":",
               p->channel.tx,p->channel.rx)<0||
        !string(f,p->channel.expected_vin)) return false;
    if (fputs(",\"initial_vin\":",f)==EOF) return false;
    if (r->initial_vin_valid ? !string(f,r->initial_vin) :
        fputs("null",f)==EOF) return false;
    if (fputs(",\"final_vin\":",f)==EOF) return false;
    if (r->final_vin_valid ? !string(f,r->final_vin) :
        fputs("null",f)==EOF) return false;
    if (fprintf(f,",\"completed\":%s,\"inventory_complete\":%s,"
        "\"requested_cycles\":%u,\"completed_cycles\":%u,"
        "\"raw_samples\":%u,\"missing_samples\":%u,"
        "\"duration_ms\":%llu,\"raw_bytes\":%zu,"
        "\"budget_ms\":%u,\"interval_ms\":%u,"
        "\"selected_pids\":[",
        r->completed?"true":"false",r->inventory_complete?"true":"false",
        p->cycles,r->cycles_completed,r->raw_samples,r->missing_samples,
        (unsigned long long)r->duration_ms,r->raw_bytes,
        p->channel.budget_ms,p->interval_ms)<0) return false;
    for (unsigned i=0;i<p->pid_count;++i) {
        if ((i&&fputc(',',f)==EOF)||fprintf(f,"%u",p->pids[i])<0) return false;
    }
    if (fputs("],\"stop_reason\":",f)==EOF) return false;
    if (r->stop_reason[0] ? !string(f,r->stop_reason) :
        fputs("null",f)==EOF) return false;
    if (fputs(",\"steps\":[",f)==EOF) return false;
    for (size_t i=0;i<r->step_count;++i) {
        const j1979_live_step *s=&r->steps[i];
        if ((i&&fputc(',',f)==EOF) || fputs("{\"phase\":",f)==EOF ||
            !string(f,s->phase) || fprintf(f,",\"cycle\":%u,\"request_hex\":",s->cycle)<0 ||
            !hex(f,s->request,2) || fputs(",\"response_hex\":",f)==EOF) return false;
        if (s->response ? !hex(f,s->response,s->response_size) :
            fputs("null",f)==EOF) return false;
        if (fputs(",\"measurement\":",f)==EOF) return false;
        j1979_measurement m{};
        if (!strcmp(s->status,"RAW") && s->response &&
            j1979_measurement_decode(s->request,2,s->response,
                                     s->response_size,&m)) {
            if (fprintf(f,"{\"mode\":%u,\"pid\":%u,\"name\":",
                        m.mode,m.pid)<0 ||
                !string(f,m.name) ||
                fputs(",\"unit\":",f)==EOF || !string(f,m.unit) ||
                fprintf(f,",\"context_required\":%s,"
                          "\"upper_trim_unverified\":%s,\"number\":",
                          m.context_required?"true":"false",
                          m.upper_trim_unverified?"true":"false")<0)
                return false;
            if (m.interpreted) {
                if (fprintf(f,"%.12g",m.number)<0) return false;
            } else if (fputs("null",f)==EOF) return false;
            if (fputc('}',f)==EOF) return false;
        } else if (fputs("null",f)==EOF) return false;
        if (fputs(",\"status\":",f)==EOF || !string(f,s->status) ||
            fputs(",\"detail\":",f)==EOF) return false;
        if (s->detail[0] ? !string(f,s->detail) :
            fputs("null",f)==EOF) return false;
        if (fprintf(f,",\"started_elapsed_ms\":%llu,\"elapsed_ms\":%llu,"
            "\"duration_ms\":%u}",
            (unsigned long long)s->started_ms,
            (unsigned long long)s->elapsed_ms,s->duration_ms)<0) return false;
    }
    if (fputs("],\"operating_context\":",f)==EOF) return false;
    std::vector<cd::j1979::Observation> observations;
    if (r->step_count>J1979_LIVE_MAX_STEPS) return false;
    observations.reserve(r->step_count);
    for (size_t i=0;i<r->step_count;++i) {
        const auto& step=r->steps[i];
        if (step.response_size>J1979_CAN_MAX_PAYLOAD || (step.response_size&&!step.response)) return false;
        const bool admission=!strcmp(step.phase,"CONTEXT") || !strcmp(step.phase,"INVENTORY");
        observations.push_back({{step.request,2},{step.response,step.response_size},
            !strcmp(step.status,"RAW") || !strcmp(step.status,"PASSED"),admission});
    }
    const bool identity=r->initial_vin_valid&&r->final_vin_valid &&
        !strcmp(r->initial_vin,r->final_vin) && !strcmp(r->final_vin,p->channel.expected_vin);
    if (!cd::j1979::write_context_json(f,observations,identity)) return false;
    return fputs("}\n",f)!=EOF;
}
