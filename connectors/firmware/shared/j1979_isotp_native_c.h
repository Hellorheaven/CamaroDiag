#ifndef CAMARODIAG_SAE_J1979_ISOTP_NATIVE_C_H
#define CAMARODIAG_SAE_J1979_ISOTP_NATIVE_C_H
#include "j1979_native_c.h"
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

enum {CD_J1979_ISOTP_CAPACITY=4095};
typedef enum {
    CD_J1979_ISOTP_REFUSED=0,
    CD_J1979_ISOTP_WAITING=1,
    CD_J1979_ISOTP_FLOW_REQUIRED=2,
    CD_J1979_ISOTP_COMPLETE=3,
    CD_J1979_ISOTP_NEGATIVE=4,
    CD_J1979_ISOTP_RESPONSE_PENDING=5,
    CD_J1979_ISOTP_TIMED_OUT=6,
    CD_J1979_ISOTP_FAULT=7
} cd_j1979_isotp_status;
typedef struct {
    uint32_t tx,rx,started_ms,last_frame_ms,timeout_ms;
    bool extended,flow_pending,flow_sent,armed;
    uint8_t request[3],request_size;
    uint8_t response[CD_J1979_ISOTP_CAPACITY];
    size_t expected,received;
    uint8_t next_sequence;
    cd_j1979_isotp_status state;
    cd_j1979_native_shape shape;
} cd_j1979_isotp;
/* Caller owns actual bus lease, TX queue completion and RX queue isolation.
 * No retry, discovery, fallback, device opening or memory allocation here.
 */
bool cd_j1979_isotp_begin(cd_j1979_isotp *s,uint32_t tx,uint32_t rx,
                          bool extended,const uint8_t *request,
                          size_t request_size,uint32_t now,uint32_t timeout,
                          uint8_t outgoing[8]);
void cd_j1979_isotp_abort(cd_j1979_isotp *s);
cd_j1979_isotp_status cd_j1979_isotp_poll(cd_j1979_isotp *s,uint32_t now);
cd_j1979_isotp_status cd_j1979_isotp_receive(cd_j1979_isotp *s,
    uint32_t response_can_id,bool extended,const uint8_t *bytes,
    size_t dlc,uint32_t now,uint8_t flow[8]);
bool cd_j1979_isotp_flow_transmitted(cd_j1979_isotp *s,uint32_t now);
const uint8_t *cd_j1979_isotp_response(const cd_j1979_isotp *s,size_t *size);
#endif
