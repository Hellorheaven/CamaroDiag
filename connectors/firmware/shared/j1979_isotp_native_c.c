#include "j1979_isotp_native_c.h"
#include <string.h>

static cd_j1979_isotp_status fault(cd_j1979_isotp *s) {
    if (s) s->state=CD_J1979_ISOTP_FAULT;
    return CD_J1979_ISOTP_FAULT;
}
void cd_j1979_isotp_abort(cd_j1979_isotp *s) {
    if (s) {
        s->state=CD_J1979_ISOTP_FAULT;
        s->flow_pending=false;
        s->flow_sent=false;
        s->armed=false;
    }
}
bool cd_j1979_isotp_begin(cd_j1979_isotp *s,uint32_t tx,uint32_t rx,
                          bool extended,const uint8_t *request,
                          size_t request_size,uint32_t now,uint32_t timeout,
                          uint8_t outgoing[8]) {
    if (!s || !outgoing || !request ||
        !cd_j1979_native_physical_pair(tx,rx,extended) ||
        !cd_j1979_native_read_only(request,request_size) ||
        !request_size || request_size>3 ||
        !timeout || timeout>4000u) return false;
    memset(s,0,sizeof(*s));
    s->tx=tx;s->rx=rx;s->extended=extended;
    s->started_ms=now;s->last_frame_ms=now;s->timeout_ms=timeout;
    s->state=CD_J1979_ISOTP_WAITING;
    s->request_size=(uint8_t)request_size;
    memcpy(s->request,request,request_size);
    memset(outgoing,0,8);
    outgoing[0]=(uint8_t)request_size;
    memcpy(outgoing+1,request,request_size);
    s->armed=true;
    return true;
}
cd_j1979_isotp_status cd_j1979_isotp_poll(cd_j1979_isotp *s,uint32_t now) {
    if (!s || !s->armed) return CD_J1979_ISOTP_REFUSED;
    if (s->state!=CD_J1979_ISOTP_WAITING &&
        s->state!=CD_J1979_ISOTP_FLOW_REQUIRED)
        return s->state;
    if ((uint32_t)(now-s->started_ms)>=s->timeout_ms ||
        (s->received && (uint32_t)(now-s->last_frame_ms)>1000u)) {
        s->state=CD_J1979_ISOTP_TIMED_OUT;
        return s->state;
    }
    return s->state;
}
static cd_j1979_isotp_status finished(cd_j1979_isotp *s) {
    s->shape=cd_j1979_native_classify(s->request,s->request_size,
                                      s->response,s->received);
    if (s->shape==CD_J1979_NATIVE_INVALID) return fault(s);
    if (s->shape==CD_J1979_NATIVE_PENDING) {
        /* An ECU 7F xx 78 is not a successful observation. Retain the
         * original absolute deadline and require a new response on the
         * same physical pair. It never causes another transmitted request.
         */
        s->received=0;s->expected=0;s->next_sequence=0;
        s->flow_pending=false;s->flow_sent=false;
        s->state=CD_J1979_ISOTP_RESPONSE_PENDING;
        return s->state;
    }
    s->state=s->shape==CD_J1979_NATIVE_NEGATIVE ?
        CD_J1979_ISOTP_NEGATIVE : CD_J1979_ISOTP_COMPLETE;
    return s->state;
}
cd_j1979_isotp_status cd_j1979_isotp_receive(cd_j1979_isotp *s,
    uint32_t id,bool extended,const uint8_t *bytes,size_t dlc,
    uint32_t now,uint8_t flow[8]) {
    if (!s || !s->armed) return CD_J1979_ISOTP_REFUSED;
    /* Exact physical responder and addressing format; unrelated traffic
     * must not mutate a selected ECU's transport state.
     */
    if (id!=s->rx || extended!=s->extended)
        return cd_j1979_isotp_poll(s,now);
    if (s->state==CD_J1979_ISOTP_RESPONSE_PENDING)
        s->state=CD_J1979_ISOTP_WAITING;
    if (cd_j1979_isotp_poll(s,now)!=CD_J1979_ISOTP_WAITING &&
        s->state!=CD_J1979_ISOTP_FLOW_REQUIRED) return s->state;
    if (!bytes || !dlc || dlc>8) return fault(s);
    const unsigned type=bytes[0]>>4u;
    if (s->received==0 && !s->flow_pending && !s->flow_sent) {
        if (type==0) {
            const size_t length=bytes[0]&15u;
            if (!length || length>7u || dlc<length+1u)
                return fault(s);
            memcpy(s->response,bytes+1,length);
            s->received=s->expected=length;
            return finished(s);
        }
        if (type!=1 || dlc!=8 || !flow) return fault(s);
        const size_t length=((size_t)(bytes[0]&15u)<<8u)|bytes[1];
        if (length<=7 || length>CD_J1979_ISOTP_CAPACITY)
            return fault(s);
        memcpy(s->response,bytes+2,6);
        s->received=6;s->expected=length;s->next_sequence=1;
        s->flow_pending=true;s->last_frame_ms=now;
        memset(flow,0,8);
        flow[0]=0x30; /* CTS, zero block size and STmin */
        s->state=CD_J1979_ISOTP_FLOW_REQUIRED;
        return s->state;
    }
    if (!s->flow_sent || s->flow_pending || type!=2 ||
        (bytes[0]&15u)!=s->next_sequence) return fault(s);
    const size_t remaining=s->expected-s->received;
    const size_t chunk=remaining<7 ? remaining:7;
    if (dlc<chunk+1u) return fault(s);
    memcpy(s->response+s->received,bytes+1,chunk);
    s->received+=chunk;
    s->next_sequence=(uint8_t)((s->next_sequence+1u)&15u);
    s->last_frame_ms=now;
    if (s->received==s->expected) return finished(s);
    return s->state;
}
bool cd_j1979_isotp_flow_transmitted(cd_j1979_isotp *s,uint32_t now) {
    if (!s || !s->armed || !s->flow_pending ||
        cd_j1979_isotp_poll(s,now)!=CD_J1979_ISOTP_FLOW_REQUIRED)
        return false;
    s->flow_pending=false;
    s->flow_sent=true;
    s->state=CD_J1979_ISOTP_WAITING;
    s->last_frame_ms=now;
    return true;
}
const uint8_t *cd_j1979_isotp_response(const cd_j1979_isotp *s,size_t *n) {
    if (n) *n=0;
    if (!s || !n || (s->state!=CD_J1979_ISOTP_COMPLETE &&
                     s->state!=CD_J1979_ISOTP_NEGATIVE))
        return NULL;
    *n=s->received;
    return s->response;
}
