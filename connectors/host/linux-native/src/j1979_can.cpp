#define _POSIX_C_SOURCE 200809L
#include "j1979_can.h"
#include "j1979_policy.h"
#include "j1979_monitor_scaling.h"
#include "j1979_vehicle_info.h"
#include "j1979_measurement.h"
#include "j1979_context.hpp"
#include "j1979_projection.hpp"
#include <ctype.h>
#include <errno.h>
#include <limits.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <array>
#include <algorithm>
#include <memory>
#include <new>

static uint64_t monotonic_ms(void) {
    struct timespec now{};
    if (clock_gettime(CLOCK_MONOTONIC, &now)) return 0;
    return (uint64_t)now.tv_sec * 1000u + (uint64_t)now.tv_nsec / 1000000u;
}
static uint64_t now_ms(const j1979_can_plan *p) {
    return p->clock ? p->clock(p->context) : monotonic_ms();
}
bool j1979_can_physical_pair(uint32_t tx, uint32_t rx) {
    return cd_j1979_native_physical_pair(tx,rx,tx>0x7ffu);
}
bool j1979_can_valid_vin(const char *vin) {
    return vin && cd_j1979_native_vin(vin,strlen(vin));
}
bool j1979_can_allowed_read(const uint8_t *request,size_t count) {
    return cd_j1979_native_read_only(request,count);
}
static void stop(j1979_can_result *r, const char *reason) {
    if (!r->stop_reason[0]) {
        snprintf(r->stop_reason, sizeof(r->stop_reason), "%s", reason);
    }
}
static bool valid_dtc(const uint8_t *reply, size_t n) {
    if (n < 2 || n != 2u + 2u * reply[1]) return false;
    for (size_t i = 2; i < n; i += 2)
        if (reply[i] == 0 && reply[i+1] == 0) return false;
    return true;
}
static int pid_width(unsigned service, uint8_t pid) {
    if (pid % 32u == 0) return 4;
    if (service == 2 && pid == 2) return 2;
    switch (pid) {
        case 0x01: case 0x24: case 0x25: case 0x28: case 0x29:
        case 0x26: case 0x27: case 0x2a: case 0x2b:
        case 0x34: case 0x35: case 0x36: case 0x37: case 0x38: case 0x39:
        case 0x3a: case 0x3b: case 0x41: case 0x4f: case 0x50: case 0xa6: return 4;
        case 0x0c: case 0x10: case 0x14: case 0x15: case 0x16:
        case 0x17: case 0x1f: case 0x42: case 0x43: case 0x44: return 2;
        case 0x04: case 0x05: case 0x0a: case 0x0b: case 0x0d:
        case 0x0e: case 0x0f: case 0x11: case 0x13: case 0x1d:
        case 0x45: case 0x46: case 0x47: case 0x48: case 0x49:
        case 0x4a: case 0x4b: case 0x4c: case 0x52: case 0x5b: return 1;
        case 0x06: case 0x07: case 0x08: case 0x09:
        case 0x55: case 0x56: case 0x57: case 0x58: return -2; /* one or two trims */
        default: {
            const auto width=j1979_measurement_width(pid);
            return width?static_cast<int>(width):-1;
        } /* Unknown RAW records do not acquire an invented width. */
    }
}
bool j1979_can_classify_read(const uint8_t *request, size_t count,
                            const uint8_t *response, size_t n,
                            j1979_can_step *step) {
    if (!step) return false;
    snprintf(step->status,sizeof(step->status),"FAILED");
    step->detail[0]='\0';
    if (!j1979_can_allowed_read(request,count)) {
        snprintf(step->detail,sizeof(step->detail),"UNSAFE_SERVICE_REJECTED");
        return false;
    }
    if (!response || n == 0 || n > J1979_CAN_MAX_PAYLOAD) {
        snprintf(step->detail, sizeof(step->detail), "INVALID_LENGTH");
        return false;
    }
    if (response[0] == 0x7f) {
        if (n != 3 || response[1] != request[0] ||
            response[2] == 0 || response[2] == 0x78) {
            snprintf(step->detail, sizeof(step->detail), "INVALID_NEGATIVE_RESPONSE");
            return false;
        }
        snprintf(step->status, sizeof(step->status), "NOT_TESTED");
        snprintf(step->detail, sizeof(step->detail), "NRC_%02X", response[2]);
        return true;
    }
    if (response[0] != (uint8_t)(request[0] + 0x40u) ||
        (count > 1 && (n < count ||
            memcmp(response + 1, request + 1, count - 1)))) {
        snprintf(step->detail, sizeof(step->detail), "WRONG_ECU_SERVICE_OR_PID");
        return false;
    }
    if (request[0] == 3 || request[0] == 7 || request[0] == 10) {
        if (!valid_dtc(response, n)) {
            snprintf(step->detail, sizeof(step->detail), "INVALID_DTC_RECORDS");
            return false;
        }
        snprintf(step->status, sizeof(step->status), "PASSED");
        return true;
    }
    if (n <= count) {
        snprintf(step->detail, sizeof(step->detail), "MISSING_RESPONSE_DATA");
        return false;
    }
    if (request[0] == 1 || request[0] == 2) {
        int width = pid_width(request[0], request[1]);
        size_t data = n - count;
        if ((width >= 0 && data != (size_t)width) ||
            (width == -2 && data != 1 && data != 2)) {
            snprintf(step->detail, sizeof(step->detail), "INVALID_PID_WIDTH");
            return false;
        }
    }
    if (request[0]==6 && request[1]%32u!=0 &&
        !j1979_monitor_count(request,response,n)) {
        snprintf(step->detail,sizeof(step->detail),"INVALID_MODE06_RECORDS");
        return false;
    }
    if (request[0]==1 && request[1]==0x50 &&
        (response[3]||response[4]||response[5])) {
        snprintf(step->detail,sizeof(step->detail),"INVALID_PID50_CONFIGURATION");
        return false;
    }
    if (request[0]==1 && request[1]==0x41 &&
        !cd::j1979::drive_cycle({response+2,n-2},std::nullopt)) {
        snprintf(step->detail,sizeof(step->detail),"INVALID_DRIVE_CYCLE_RECORD");
        return false;
    }
    if (request[0]==9 &&
        (request[1]==8 || request[1]==11 ||
         request[1]==13 || request[1]==15)) {
        j1979_vehicle_info info{};
        if (!j1979_vehicle_info_decode(request[1],response,n,&info)) {
            snprintf(step->detail,sizeof(step->detail),"INVALID_MODE09_INFO");
            return false;
        }
    }
    if (request[0]==1 && count==2 && !cd::j1979::known_shape({{request,count},{response,n},true,true})) {
        snprintf(step->detail,sizeof(step->detail),"INVALID_KNOWN_PID_DEFINITION");
        return false;
    }
    if (request[0] == 9 && (request[1] == 4 || request[1] == 6 ||
                            request[1] == 10)) {
        size_t width = request[1] == 4 ? 16u : request[1] == 6 ? 4u : 20u;
        if (n < 3 || response[2] == 0 || n != 3u + (size_t)response[2] * width) {
            snprintf(step->detail, sizeof(step->detail), "INVALID_INFO_COUNT");
            return false;
        }
    }
    snprintf(step->status, sizeof(step->status), "RAW");
    return true;
}
static j1979_can_step *read_one(const j1979_can_plan *p, j1979_can_result *r,
        uint64_t start, const uint8_t *request, size_t count) {
    if (r->stop_reason[0]) return NULL;
    if (!j1979_can_allowed_read(request, count)) {
        stop(r, "UNSAFE_SERVICE_REJECTED");
        return NULL;
    }
    if (!p->current(p->context)) {
        stop(r, "CONTEXT_CHANGED"); return NULL;
    }
    uint64_t now = now_ms(p);
    if (now < start || now - start >= p->budget_ms) {
        stop(r, "BUDGET_EXHAUSTED"); return NULL;
    }
    if (r->step_count >= J1979_CAN_MAX_STEPS) {
        stop(r, "RECORD_LIMIT"); return NULL;
    }
    j1979_can_step *step = &r->steps[r->step_count++];
    memcpy(step->request, request, count);
    step->request_size = (uint8_t)count;
    snprintf(step->status, sizeof(step->status), "NOT_TESTED");
    size_t capacity = J1979_CAN_MAX_PAYLOAD + 1;
    std::array<uint8_t,J1979_CAN_MAX_PAYLOAD+1> answer{};
    uint64_t remaining = p->budget_ms - (now - start);
    unsigned timeout = remaining > 2500 ? 2500 : (unsigned)remaining;
    uint64_t begin = now_ms(p);
    int rc = p->exchange(p->context, request, count, answer.data(), &capacity, timeout);
    uint64_t end = now_ms(p);
    step->duration_ms = end >= begin && end - begin < UINT32_MAX
        ? (uint32_t)(end - begin) : UINT32_MAX;
    /* The extra sentinel byte is not a legal ISO-TP payload. On a
     * transport failure the output length is not part of the contract. */
    if (rc == 0 && capacity > J1979_CAN_MAX_PAYLOAD) {
        snprintf(step->status, sizeof(step->status), "FAILED");
        snprintf(step->detail, sizeof(step->detail), "UNBOUNDED_TRANSPORT_REPLY");
        stop(r, "TRANSPORT_ERROR"); return step;
    }
    if (rc == 0) {
        std::unique_ptr<uint8_t[]> owned{
            new (std::nothrow) uint8_t[capacity ? capacity : 1u]};
        if (!owned) {
            stop(r, "MEMORY_EXHAUSTED"); return step;
        }
        std::copy_n(answer.begin(),capacity,owned.get());
        step->response = owned.release(); /* Legacy ABI ownership -> result_free. */
        step->response_size = capacity;
    }
    /* A response obtained after expiration cannot be projected as a valid
     * current observation, even if its content looks syntactically correct. */
    if (end < start || end - start >= p->budget_ms) {
        snprintf(step->detail, sizeof(step->detail), "LATE_RESPONSE");
        stop(r, "BUDGET_EXHAUSTED"); return step;
    }
    if (p->current && !p->current(p->context)) {
        snprintf(step->detail, sizeof(step->detail), "CONTEXT_CHANGED");
        stop(r, "CONTEXT_CHANGED"); return step;
    }
    if (end < begin || (rc == 0 && end - begin > timeout)) {
        snprintf(step->status, sizeof(step->status), "FAILED");
        snprintf(step->detail, sizeof(step->detail), "EXCHANGE_DEADLINE_EXCEEDED");
        stop(r, "TRANSPORT_ERROR"); return step;
    }
    if (rc == 1) {
        snprintf(step->detail, sizeof(step->detail), "TIMEOUT");
        return step;
    }
    if (rc != 0) {
        snprintf(step->status, sizeof(step->status), "FAILED");
        snprintf(step->detail, sizeof(step->detail), "TRANSPORT_ERROR");
        stop(r, "TRANSPORT_ERROR"); return step;
    }
    if (!j1979_can_classify_read(request, count, answer.data(), capacity, step)) {
        snprintf(step->status, sizeof(step->status), "FAILED");
    }
    return step;
}
static bool positive(const j1979_can_step *s) {
    return s && !strcmp(s->status, "RAW");
}
static int inventory(const j1979_can_plan *p, j1979_can_result *r,
                     uint64_t start, unsigned mode, bool freeze) {
    /* Support pages remain separate per service (01/02/06/08/09). */
    if (mode >= 10) return -1;
    bool complete = false;
    for (unsigned base = 0; base <= 0xe0 && !r->stop_reason[0]; base += 0x20) {
        uint8_t req[] = {(uint8_t)mode, (uint8_t)base, 0};
        size_t request_size = freeze ? 3u : 2u;
        j1979_can_step *s = read_one(p, r, start, req, request_size);
        if (!positive(s) || s->response_size != request_size + 4u ||
            (base == 0xe0 && (s->response[s->response_size-1] & 1))) {
            if (s && !strcmp(s->status, "RAW")) {
                snprintf(s->status, sizeof(s->status), "FAILED");
                snprintf(s->detail, sizeof(s->detail), "INVALID_SUPPORT_BITMAP");
            }
            break;
        }
        const uint8_t *bits = s->response + request_size;
        for (unsigned i = 0; i < 31; ++i) {
            if (bits[i / 8] & (uint8_t)(0x80u >> (i % 8))) {
                r->advertised[mode][base+i+1] = 1;
                ++r->advertised_count[mode];
            }
        }
        bool next = (bits[3] & 1u) != 0;
        snprintf(s->status, sizeof(s->status), "PASSED");
        if (!next) {complete = true; break;}
    }
    r->inventory_state[mode] = complete ? 1 : 2;
    return complete ? 0 : 1;
}
static void measure_inventory(const j1979_can_plan *p, j1979_can_result *r,
                    uint64_t start, unsigned mode, bool freeze) {
    inventory(p, r, start, mode, freeze);
    if (r->inventory_state[mode] != 1) return;
    for (unsigned id = 1; id <= 255 && !r->stop_reason[0]; ++id) {
        if (!r->advertised[mode][id] || id % 32 == 0) continue;
        uint8_t req[] = {(uint8_t)mode, (uint8_t)id, 0};
        if (mode == 9 && id == 2) continue; /* VIN read independently at start */
        if (mode == 8) continue; /* inventory only; 08xx can actuate hardware */
        j1979_can_step *s = read_one(p, r, start, req, freeze ? 3u : 2u);
        (void)s;
    }
}
int j1979_can_scan(const j1979_can_plan *p, j1979_can_result *r) {
    if (!r) return -1;
    *r = {};
    /* The pure campaign cannot be reused with an anonymous ECU or a
     * channel that cannot be requalified before every request. */
    if (!p || !p->exchange || !p->current ||
        !j1979_can_physical_pair(p->tx, p->rx) ||
        p->budget_ms == 0 || p->budget_ms > 600000 ||
        !j1979_can_valid_vin(p->expected_vin))
        return -1;
    std::unique_ptr<j1979_can_step[]> owned{
        new (std::nothrow) j1979_can_step[J1979_CAN_MAX_STEPS]{}};
    if (!owned) return -1;
    r->steps = owned.release(); /* C ABI requires final result_free. */
    snprintf(r->vin_match, sizeof(r->vin_match), "%s",
             p->expected_vin ? "UNVERIFIED" : "NOT_REQUESTED");
    snprintf(r->final_vin_match, sizeof(r->final_vin_match), "NOT_TESTED");
    uint64_t start = now_ms(p);
    const uint8_t req_vin[2] = {9, 2};
    j1979_can_step *s = read_one(p, r, start, req_vin, 2);
    if (positive(s)) {
        if (s->response_size == 20 && s->response[2] == 1) {
            memcpy(r->observed_vin, s->response + 3, 17);
            r->observed_vin[17] = '\0';
            if (j1979_can_valid_vin(r->observed_vin)) {
                r->vin_valid = true;
                snprintf(s->status, sizeof(s->status), "PASSED");
            }
        }
        if (!r->vin_valid) {
            memset(r->observed_vin, 0, sizeof(r->observed_vin));
            snprintf(s->status, sizeof(s->status), "FAILED");
            snprintf(s->detail, sizeof(s->detail), "INVALID_VIN_RECORD");
        }
    }
    if (p->expected_vin) {
        if (r->vin_valid && !strcmp(r->observed_vin, p->expected_vin))
            snprintf(r->vin_match, sizeof(r->vin_match), "MATCH");
        else {
            snprintf(r->vin_match, sizeof(r->vin_match), "%s",
                     r->vin_valid ? "MISMATCH" : "UNVERIFIED");
            stop(r, r->vin_valid ? "VIN_MISMATCH" : "VIN_UNVERIFIED");
        }
    }
    for (size_t i = 0; i < 3 && !r->stop_reason[0]; ++i) {
        static const uint8_t services[3] = {3, 7, 10};
        read_one(p, r, start, &services[i], 1);
    }
    if (!r->stop_reason[0]) measure_inventory(p, r, start, 1, false);
    if (!r->stop_reason[0]) {
        const uint8_t freeze_dtc[3] = {2, 2, 0};
        s = read_one(p, r, start, freeze_dtc, 3);
        if (positive(s) && s->response_size == 5) {
            r->freeze_trigger_verified = true;
            r->freeze_present = s->response[3] != 0 || s->response[4] != 0;
            snprintf(s->status, sizeof(s->status), "PASSED");
            if (r->freeze_present) measure_inventory(p, r, start, 2, true);
        } else if (positive(s)) {
            snprintf(s->status, sizeof(s->status), "FAILED");
            snprintf(s->detail, sizeof(s->detail), "INVALID_FREEZE_TRIGGER");
        }
    }
    if (!r->stop_reason[0]) measure_inventory(p, r, start, 6, false);
    if (!r->stop_reason[0]) inventory(p, r, start, 8, false);
    if (!r->stop_reason[0]) measure_inventory(p, r, start, 9, false);
    /* Re-read Mode 09 PID 02 on the very same selected physical channel.
     * Software continuity only: this does NOT prove physical ECU provenance.
     * Never emit new requests after transport/context retirement.
     */
    if (!r->stop_reason[0] && r->vin_valid) {
        const uint8_t final_request[2] = {9, 2};
        s = read_one(p, r, start, final_request, sizeof(final_request));
        r->final_vin_checked = s != NULL;
        if (positive(s) && s->response_size == 20 && s->response[2] == 1) {
            memcpy(r->final_vin, s->response + 3, 17);
            r->final_vin[17] = '\0';
            if (j1979_can_valid_vin(r->final_vin)) {
                r->final_vin_valid = true;
                snprintf(s->status, sizeof(s->status), "PASSED");
            }
        }
        if (!r->final_vin_valid) {
            snprintf(r->final_vin_match, sizeof(r->final_vin_match),
                     "UNVERIFIED");
            stop(r, "FINAL_VIN_UNVERIFIED");
        } else if (strcmp(r->final_vin, r->observed_vin) ||
                   (p->expected_vin && strcmp(r->final_vin, p->expected_vin))) {
            snprintf(r->final_vin_match, sizeof(r->final_vin_match), "MISMATCH");
            stop(r, "VIN_CHANGED");
        } else {
            snprintf(r->final_vin_match, sizeof(r->final_vin_match), "MATCH");
        }
    }
    for (size_t i = 0; i < r->step_count; ++i) {
        const char *status = r->steps[i].status;
        if (!strcmp(status, "FAILED")) ++r->failed_responses;
        else if (!strcmp(status, "NOT_TESTED")) {
            if (!strncmp(r->steps[i].detail, "NRC_", 4))
                ++r->negative_responses;
            else ++r->timeout_responses;
        }
    }
    if (!r->stop_reason[0] &&
        (r->inventory_state[1] != 1 ||
         (r->freeze_present && r->inventory_state[2] != 1) ||
         r->inventory_state[6] != 1 ||
         r->inventory_state[8] != 1 ||
         r->inventory_state[9] != 1))
        stop(r, "INVENTORY_INCOMPLETE");
    if (!r->stop_reason[0] && !r->freeze_trigger_verified)
        stop(r, "FREEZE_TRIGGER_UNVERIFIED");
    if (!r->stop_reason[0] &&
        (r->failed_responses || r->negative_responses || r->timeout_responses))
        stop(r, "OBSERVATION_INCOMPLETE");
    uint64_t end = now_ms(p);
    r->duration_ms = end >= start ? end - start : 0;
    r->cancelled = !strcmp(r->stop_reason, "CANCELLED");
    r->completed = !r->stop_reason[0] && r->final_vin_valid;
    return 0;
}
void j1979_can_result_free(j1979_can_result *r) {
    if (!r) return;
    if (r->steps) {
        for (size_t i = 0; i < r->step_count; ++i)
            delete[] r->steps[i].response;
        delete[] r->steps;
    }
    *r = {};
}
static bool quoted(FILE *f, const char *v) {
    if (fputc('"', f) == EOF) return false;
    if (v) for (const unsigned char *p = (const unsigned char *)v; *p; ++p) {
        if (*p < 0x20 || *p == 0x7f) return false;
        if (*p == '"' || *p == '\\') {
            if (fputc('\\', f) == EOF) return false;
        }
        if (fputc(*p, f) == EOF) return false;
    }
    return fputc('"', f) != EOF;
}
static bool hex(FILE *f, const uint8_t *data, size_t n) {
    static const char map[] = "0123456789ABCDEF";
    if (fputc('"', f) == EOF) return false;
    for (size_t i = 0; i < n; ++i) {
        if (fputc(map[data[i] >> 4], f) == EOF ||
            fputc(map[data[i] & 15], f) == EOF) return false;
    }
    return fputc('"', f) != EOF;
}
bool j1979_can_write_json_transport(FILE *f,const j1979_can_plan *p,
    const j1979_can_result *r,const char *interface_name,const char *transport) {
    if (!f || !p || !r || !interface_name || !transport || !r->steps) return false;
    if (fprintf(f, "{\"schema\":\"camarodiag-functional-j1979-can-linux-c-v1\","
      "\"protocol\":\"SAE-J1979\",\"transport\":") < 0 || !quoted(f,transport) ||
        fprintf(f,","
      "\"physical_vehicle_verified\":false,\"permits_programming\":false,"
      "\"tx_can_id\":\"%X\",\"rx_can_id\":\"%X\",\"interface\":",p->tx,p->rx) < 0 ||
        !quoted(f, interface_name)) return false;
    if (fprintf(f, ",\"declared_vin\":") < 0) return false;
    if (p->expected_vin ? !quoted(f,p->expected_vin) : fputs("null",f) == EOF) return false;
    if (fputs(",\"observed_vin\":",f) == EOF) return false;
    if (r->vin_valid ? !quoted(f,r->observed_vin) : fputs("null",f) == EOF) return false;
    if (fputs(",\"vin_match\":",f) == EOF || !quoted(f,r->vin_match)) return false;
    if (fputs(",\"final_vin\":",f) == EOF) return false;
    if (r->final_vin_valid ? !quoted(f,r->final_vin) : fputs("null",f) == EOF) return false;
    if (fputs(",\"final_vin_match\":",f) == EOF || !quoted(f,r->final_vin_match)) return false;
    if (fprintf(f,",\"final_vin_checked\":%s,\"final_vin_valid\":%s,"
        "\"negative_responses\":%u,\"timeout_responses\":%u,\"failed_responses\":%u",
        r->final_vin_checked ? "true" : "false",
        r->final_vin_valid ? "true" : "false",r->negative_responses,
        r->timeout_responses,r->failed_responses) < 0) return false;
    if (fprintf(f, ",\"completed\":%s,\"duration_ms\":%llu,\"cancelled\":%s,"
        "\"stop_reason\":", r->completed ? "true":"false",
        (unsigned long long)r->duration_ms, r->cancelled ? "true":"false") < 0) return false;
    if (r->stop_reason[0] ? !quoted(f,r->stop_reason) : fputs("null",f) == EOF) return false;
    if (fprintf(f, ",\"freeze_trigger_verified\":%s,\"freeze_present\":%s,"
        "\"not_executed\":[\"MODE_04_CLEAR\",\"MODE_05_LEGACY\",\"MODE_08_CONTROL\"],"
        "\"inventory\":[",r->freeze_trigger_verified ? "true":"false",
        r->freeze_present ? "true":"false") < 0) return false;
    const unsigned modes[] = {1,2,6,8,9};
    for (size_t i = 0; i < sizeof(modes)/sizeof(modes[0]); ++i) {
        unsigned mode = modes[i];
        if (i && fputc(',',f) == EOF) return false;
        if (fprintf(f, "{\"mode\":%u,\"state\":",mode) < 0) return false;
        if (!quoted(f, r->inventory_state[mode] == 1 ? "COMPLETE" :
                         r->inventory_state[mode] == 2 ? "PARTIAL" : "NOT_TESTED"))
            return false;
        if (fputs(",\"advertised_ids\":[",f) == EOF) return false;
        bool first = true;
        for (unsigned id=1;id<256;++id) if (r->advertised[mode][id]) {
            if ((!first && fputc(',',f)==EOF) || fprintf(f,"%u",id)<0) return false;
            first=false;
        }
        if (fputs("]}",f)==EOF) return false;
    }
    if (fputs("],\"steps\":[",f)==EOF) return false;
    for (size_t i=0;i<r->step_count;++i) {
        const j1979_can_step *s=&r->steps[i];
        if (i && fputc(',',f)==EOF) return false;
        if (fputs("{\"request_hex\":",f)==EOF || !hex(f,s->request,s->request_size))
            return false;
        if (fputs(",\"response_hex\":",f)==EOF) return false;
        if (s->response ? !hex(f,s->response,s->response_size) :
            fputs("null",f)==EOF) return false;
        if (fputs(",\"measurement\":",f)==EOF) return false;
        j1979_measurement m{};
        if (s->response && (!strcmp(s->status,"RAW") ||
                            !strcmp(s->status,"PASSED")) &&
            j1979_measurement_decode(s->request,s->request_size,
                                     s->response,s->response_size,&m)) {
            if (fprintf(f,"{\"mode\":%u,\"pid\":%u,\"frame\":%u,"
                          "\"name\":",m.mode,m.pid,m.frame)<0 ||
                !quoted(f,m.name) ||
                fputs(",\"unit\":",f)==EOF || !quoted(f,m.unit) ||
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
        /* Mode 06: retain every complete MID/TID/UASID and 16-bit
         * comparison; unknown UASIDs remain RAW, never a monitor verdict.
         */
        if (fputs(",\"monitor_records\":[",f)==EOF) return false;
        if (s->request_size==2 && s->request[0]==6 &&
            !strcmp(s->status,"RAW") && s->response) {
            size_t record_count=j1979_monitor_count(s->request,s->response,
                                                    s->response_size);
            for (size_t j=0;j<record_count;++j) {
                j1979_monitor_record monitor{};
                if (!j1979_monitor_decode(s->request,s->response,
                                         s->response_size,j,&monitor)) return false;
                if ((j&&fputc(',',f)==EOF) ||
                    fprintf(f,"{\"record_index\":%zu,\"monitor_id\":%u,"
                              "\"test_id\":%u,\"unit_scaling_id\":%u,"
                              "\"value_encoded\":%u,\"minimum_encoded\":%u,"
                              "\"maximum_encoded\":%u,\"scaled\":",
                              j,monitor.monitor_id,monitor.test_id,
                              monitor.scaling_id,monitor.encoded_value,
                              monitor.encoded_minimum,monitor.encoded_maximum)<0)
                    return false;
                if (monitor.scaled) {
                    if (fputs("{\"definition\":\"ISO15031-5:2006-ANNEX-E-SUBSET\","
                              "\"unit\":",f)==EOF ||
                        !quoted(f,monitor.unit) ||
                        fprintf(f,",\"value\":%.12g,\"minimum\":%.12g,"
                                  "\"maximum\":%.12g,\"numeric_relation\":",
                                monitor.value,monitor.minimum,monitor.maximum)<0 ||
                        !quoted(f,j1979_monitor_relation_name(monitor.relation)) ||
                        fputs(",\"monitor_verdict\":null}",f)==EOF)
                        return false;
                } else if (fputs("null",f)==EOF) return false;
                if (fputc('}',f)==EOF) return false;
            }
        }
        if (fputc(']',f)==EOF) return false;
        /* Mode 09 counters and identification text are exactly decoded
         * from one correlated CAN reply. A VIN or printed ECU name is not
         * a hardware provenance proof.
         */
        if (fputs(",\"vehicle_info\":",f)==EOF) return false;
        j1979_vehicle_info info{};
        if (s->request_size==2 && s->request[0]==9 &&
            !strcmp(s->status,"RAW") && s->response &&
            j1979_vehicle_info_decode(s->request[1],s->response,
                                      s->response_size,&info)) {
            if (fprintf(f,"{\"info_type\":%u,\"regulatory_verdict\":null,"
                          "\"physical_ecu_verified\":false",
                        info.info_type)<0) return false;
            if (info.counter_vector) {
                if (fprintf(f,",\"counter_count\":%u,"
                              "\"general_denominator\":%u,\"ignition_cycles\":%u,"
                              "\"monitors\":[",
                            info.counter_count,info.general_denominator,
                            info.ignition_cycles)<0) return false;
                for (unsigned j=0;j<info.monitor_count;++j) {
                    const j1979_info_monitor *m=&info.monitors[j];
                    if ((j&&fputc(',',f)==EOF) ||
                        fputs("{\"name\":",f)==EOF || !quoted(f,m->name) ||
                        fprintf(f,",\"numerator\":%u,\"denominator\":%u,"
                                  "\"ratio\":",m->numerator,m->denominator)<0)
                        return false;
                    if (m->ratio_valid) {
                        if (fprintf(f,"%.12g",m->ratio)<0) return false;
                    } else if (fputs("null",f)==EOF) return false;
                    if (fputc('}',f)==EOF) return false;
                }
                if (fputc(']',f)==EOF) return false;
            } else if (info.printable_text) {
                if (fputs(",\"values\":[",f)==EOF ||
                    !quoted(f,info.text) || fputc(']',f)==EOF) return false;
            }
            if (fputc('}',f)==EOF) return false;
        } else if (fputs("null",f)==EOF) return false;
        /* SAE J1979 Mode 03/07/0A two-byte DTC encoding: retain raw
         * evidence and expose a textual code only on validated records.
         * A code string is not a diagnosis, an ECU attribution or a repair.
         */
        if (fputs(",\"dtc_codes\":[",f)==EOF) return false;
        if (s->request_size==1 &&
            (s->request[0]==3 || s->request[0]==7 || s->request[0]==10) &&
            !strcmp(s->status,"PASSED") &&
            s->response && valid_dtc(s->response,s->response_size)) {
            for (size_t j=2;j<s->response_size;j+=2) {
                char code[6]{};
                if (!cd_j1979_native_dtc_code(s->response+j,code) ||
                    (j!=2 && fputc(',',f)==EOF) || !quoted(f,code))
                    return false;
            }
        }
        if (fputs("],\"status\":",f)==EOF || !quoted(f,s->status)) return false;
        if (fputs(",\"detail\":",f)==EOF) return false;
        if (s->detail[0] ? !quoted(f,s->detail) : fputs("null",f)==EOF) return false;
        if (fprintf(f,",\"duration_ms\":%u}",s->duration_ms)<0) return false;
    }
    if (fputs("],\"operating_context\":",f)==EOF) return false;
    std::vector<cd::j1979::Observation> observations;
    if (r->step_count>J1979_CAN_MAX_STEPS) return false;
    observations.reserve(r->step_count);
    for (size_t i=0;i<r->step_count;++i) {
        const auto& step=r->steps[i];
        if (step.request_size>sizeof(step.request) || step.response_size>J1979_CAN_MAX_PAYLOAD ||
            (step.response_size && !step.response)) return false;
        observations.push_back({{step.request,step.request_size},{step.response,step.response_size},
            !strcmp(step.status,"RAW") || !strcmp(step.status,"PASSED"),true});
    }
    const bool identity=r->vin_valid && r->final_vin_valid &&
        !strcmp(r->vin_match,"MATCH") && !strcmp(r->final_vin_match,"MATCH");
    if (!cd::j1979::write_context_json(f,observations,identity) ||
        !cd::j1979::write_projections(f,observations,identity)) return false;
    return fputs("}\n",f)!=EOF;
}

bool j1979_can_write_json(FILE *f,const j1979_can_plan *p,const j1979_can_result *r,const char *iface){
    return j1979_can_write_json_transport(f,p,r,iface,"LINUX_SOCKETCAN_ISOTP");
}
