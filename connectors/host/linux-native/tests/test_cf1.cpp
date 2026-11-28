#include "cf1.h"
#include "j1979_cf1_history.hpp"
#include <stdexcept>
#include <string>

#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* Purely synthetic CF/1 fixtures. No actual vehicle/ECU access is needed.
 * Keep tests in source, but run only during final protocol gate.
 */
static const char vin[] = "2G1FA1E30D7654321";
static uint8_t data[6000];
static size_t position;
static uint16_t sequence;
static void put16(uint8_t *p, uint16_t v) {
    p[0] = (uint8_t)v; p[1] = (uint8_t)(v >> 8);
}
static void put32(uint8_t *p, uint32_t v) {
    for (size_t i = 0; i < 4; ++i) p[i] = (uint8_t)(v >> (8*i));
}
static void append(uint8_t type, const uint8_t *payload, size_t size) {
    assert(position + size + 12 <= sizeof(data) && size <= CF1_MAX_BODY);
    uint8_t *p = data + position;
    p[0] = 'C'; p[1] = 'F'; p[2] = 1; p[3] = type;
    put16(p + 4, sequence++);
    put16(p + 6, (uint16_t)size);
    memcpy(p + 8, payload, size);
    put32(p + 8 + size, cf1_crc32(p, 8 + size));
    position += 12 + size;
}
static void append_observation(uint8_t result, int attempted,
                               const uint8_t *request, size_t rlen,
                               const uint8_t *response, size_t length) {
    uint8_t p[CF1_MAX_BODY];
    assert(rlen <= 3 && length <= CF1_MAX_RESPONSE);
    memset(p, 0, sizeof(p));
    p[0] = result; p[1] = (uint8_t)attempted;
    p[2] = (uint8_t)rlen;
    put32(p + 4, 12);
    put16(p + 8, (uint16_t)length);
    memcpy(p + 10, request, rlen);
    if (length) memcpy(p + 10 + rlen, response, length);
    append(2, p, 10 + rlen + length);
}
static void prepare_plan(void) {
    uint8_t plan[CF1_PLAN_SIZE] = {0};
    position = sequence = 0;
    put32(plan, 0x7e0); put32(plan + 4, 0x7e8);
    put32(plan + 9, 60000);
    memcpy(plan + 13, vin, 17);
    plan[30] = 1; plan[31] = 0;
    plan[39] = 1; plan[40] = 0x0c;
    append(1, plan, sizeof(plan));
}
static void make_capture(void) {
    const uint8_t vin_req[] = {9, 2};
    uint8_t vin_response[20] = {0x49, 2, 1};
    const uint8_t support_req[] = {2, 0, 0};
    const uint8_t support_response[] = {0x42, 0, 0, 0x40, 0x10, 0, 0};
    const uint8_t trigger_req[] = {2, 2, 0};
    const uint8_t trigger_response[] = {0x42, 2, 0, 0x12, 0x34};
    const uint8_t measurement_req[] = {2, 0x0c, 0};
    const uint8_t measurement_response[] = {0x42, 0x0c, 0, 0x10, 0};
    prepare_plan();
    memcpy(vin_response + 3, vin, 17);
    append_observation(1, 1, vin_req, 2, vin_response, sizeof(vin_response));
    append_observation(1, 1, support_req, 3, support_response, sizeof(support_response));
    append_observation(1, 1, trigger_req, 3, trigger_response, sizeof(trigger_response));
    append_observation(1, 1, measurement_req, 3, measurement_response, sizeof(measurement_response));
    append_observation(1, 1, trigger_req, 3, trigger_response, sizeof(trigger_response));
    append_observation(1, 1, vin_req, 2, vin_response, sizeof(vin_response));
}
static void allowed(const uint8_t *bytes, size_t length) {
    cf1_evidence e{}; char err[200] = {0};
    int status = cf1_inspect_prefix(bytes, length, vin, &e, err, sizeof(err));
    if (status) fprintf(stderr, "Unexpected native CF/1 denial: %s\n", err);
    assert(status == 0);
    assert(e.execution_completed == false && e.capture_integrity_verified == false);
    assert(e.physical_vehicle_verified == false);
    cf1_evidence_free(&e);
}
static void refused(const uint8_t *bytes, size_t length, const char *expected) {
    cf1_evidence e{}; char err[200] = {0};
    assert(cf1_inspect_prefix(bytes, length, expected, &e, err, sizeof(err)) != 0);
    assert(!e.execution_completed);
    cf1_evidence_free(&e);
}
static void checks(void) {
    assert(cf1_valid_vin(vin));
    assert(!cf1_valid_vin("2G1FA1E30D765432Q")); /* Q is forbidden in VIN. */
    assert(!cf1_valid_vin("2G1FA1E30D765432I"));
    assert(!cf1_valid_vin("2G1FA1E30D765432O"));
    assert(!cf1_valid_vin("2G1FA1E30D765432"));
    assert(cf1_crc32((const uint8_t *)"123456789", 9) == 0xcbf43926u);
    make_capture();
    size_t full_size = position;
    allowed(data, position);
    cf1_evidence e{}; char err[200] = {0};
    assert(cf1_inspect_prefix(data, position, vin, &e, err, sizeof(err)) == 0);
    assert(e.record_count == 7 && e.observation_count == 6);
    assert(e.visited_frames == 1 && !strcmp(e.progress[0].provisional_status, "COMPLETE"));
    assert(e.progress[0].inventory_complete && e.progress[0].trigger_stable);
    assert(e.progress[0].advertised_count == 1 && e.progress[0].measurement_count == 1);
    assert(e.initial_vin_observed && e.final_vin_observed);
    assert(!e.has_next_request && !e.replay_missing_observation);
    assert(!strcmp(e.interruption, "MISSING_TERMINAL"));
    cf1_evidence_free(&e);

    /* Only the canonical plan is complete: next request is theoretical VIN. */
    size_t just_plan = 12 + CF1_PLAN_SIZE;
    assert(cf1_inspect_prefix(data, just_plan, vin, &e, err, sizeof(err)) == 0);
    assert(e.record_count == 1 && !e.observation_count);
    assert(e.replay_missing_observation && e.has_next_request &&
           e.next_request_length == 2 && e.next_request[0] == 9 && e.next_request[1] == 2);
    assert(!e.has_last_request);
    cf1_evidence_free(&e);

    assert(cf1_inspect_prefix(data, full_size - 1, vin, &e, err, sizeof(err)) == 0);
    assert(e.observation_count == 5 && e.discarded_tail_bytes > 0);
    assert(!strcmp(e.interruption, "TRUNCATED_RECORD") && e.has_next_request);
    assert(e.next_request[0] == 9 && e.next_request[1] == 2);
    cf1_evidence_free(&e);

    /* Complete records must have valid CRC; partial headers must be canonical. */
    data[just_plan - 1] ^= 1;
    refused(data, full_size, vin);
    data[just_plan - 1] ^= 1;
    data[4] = 1;
    refused(data, full_size, vin);
    data[4] = 0;
    data[just_plan] = 'X';
    refused(data, full_size, vin);
    data[just_plan] = 'C';

    refused(data, full_size, "1G1FA1E30D7654321");
    refused(data, full_size, "2G1FA1E30D765432Q");
    data[just_plan + 8 + 10] ^= 1; /* Wrong request and CRC. */
    refused(data, full_size, vin);
    data[just_plan + 8 + 10] ^= 1;

    /* Recompute CRC so wrong request order cannot hide behind checksum. */
    size_t second_record = just_plan;
    uint16_t second_body = (uint16_t)(data[second_record + 6] |
                                      (data[second_record + 7] << 8));
    data[second_record + 8 + 10] = 2;
    put32(data + second_record + 8 + second_body,
          cf1_crc32(data + second_record, 8 + second_body));
    refused(data, full_size, vin);
    data[second_record + 8 + 10] = 9;
    put32(data + second_record + 8 + second_body,
          cf1_crc32(data + second_record, 8 + second_body));

    /* A well-formed terminal record must NOT be promoted via prefix path. */
    uint8_t terminal[20] = {0};
    append(3, terminal, sizeof(terminal));
    refused(data, position, vin);
    allowed(data, full_size);

    /* The distinct full inspector must recompute a coherent terminal. */
    make_capture();
    size_t prefix_size = position;
    memset(terminal, 0, sizeof(terminal));
    terminal[0] = 0; terminal[1] = 1; terminal[2] = 7;
    put32(terminal + 4, 6); put32(terminal + 8, 100);
    terminal[12] = 2; /* COMPLETE frame 00 */
    append(3, terminal, sizeof(terminal));
    assert(cf1_inspect_complete(data, position, vin, &e, err, sizeof(err)) == 0);
    assert(e.capture_integrity_verified && e.execution_completed);
    assert(!e.physical_vehicle_verified && e.terminal_present);
    assert(e.terminal_duration_ms == 100);
    cf1_evidence_free(&e);
    /* Even with an updated CRC, a forged completion flag is rejected. */
    size_t terminal_at = prefix_size;
    data[terminal_at + 8 + 2] ^= 4;
    put32(data + terminal_at + 8 + sizeof(terminal),
          cf1_crc32(data + terminal_at, 8 + sizeof(terminal)));
    refused(data, position, vin);
    assert(cf1_inspect_complete(data, position, vin, &e, err, sizeof(err)) != 0);
    cf1_evidence_free(&e);
    data[terminal_at + 8 + 2] ^= 4;
    put32(data + terminal_at + 8 + sizeof(terminal),
          cf1_crc32(data + terminal_at, 8 + sizeof(terminal)));
    assert(cf1_inspect_complete(data, position - 1, vin, &e, err, sizeof(err)) != 0);
    cf1_evidence_free(&e);
    assert(cf1_inspect_complete(data, prefix_size, vin, &e, err, sizeof(err)) != 0);
    cf1_evidence_free(&e);

    /* No valid profile can be invented from an incomplete first record. */
    refused(data, just_plan - 1, vin);
    refused(data, 0, vin);
}

static void historical_checks(){
    using cd::json::Value;
    make_capture();
    const auto prefix=position;
    uint8_t terminal[20]={};
    terminal[1]=1;terminal[2]=7;
    put32(terminal+4,6);put32(terminal+8,100);terminal[12]=2;
    append(3,terminal,sizeof(terminal));
    const std::string digest(64,'a'),report_digest(64,'b');
    const auto bytes=std::span<const uint8_t>(data,position);
    const auto decoded=cd::cfhistory::decode(bytes);
    assert(decoded.at("capture_integrity_verified").boolean());
    assert(decoded.at("result").at("all_frames_stable").boolean());
    const auto& step=decoded.at("result").at("steps").array()[3];
    assert(step.at("evidence").at("measurement").at("number").finite_number()==1024);
    const auto checked=cd::cfhistory::verify(bytes,decoded,digest,report_digest,std::string(vin));
    assert(checked.verified&&checked.completed&&!checked.proof.at("credential_independently_verified").boolean());
    auto denied=[&](const Value& host){
        bool bad=false;try{(void)cd::cfhistory::verify(bytes,host,digest,report_digest,std::string(vin));}
        catch(const std::invalid_argument&){bad=true;}assert(bad);
    };
    auto fields=decoded.object();fields["physical_vehicle_verified"]=Value{true};denied(Value{fields});
    fields=decoded.object();fields["capture_sha256"]=Value{std::string(64,'c')};denied(Value{fields});
    fields=decoded.object();fields["source"]=Value{std::string("LIVE_USB_CU2")};
    fields["device_credential_authenticated"]=Value{true};fields["client_proof_sent"]=Value{true};fields["cancelled"]=Value{false};
    auto live=cd::cfhistory::verify(bytes,Value{fields},digest,report_digest,std::string(vin));
    assert(live.verified&&live.proof.at("declared_live_credential_authenticated").boolean());
    assert(!live.proof.at("credential_independently_verified").boolean());
    fields["client_proof_sent"]=Value{false};denied(Value{fields});
    const auto partial=cd::cfhistory::decode(bytes.first(prefix));
    assert(!partial.at("capture_integrity_verified").boolean()&&partial.at("result").null());
    assert(partial.at("observations_received").unsigned_integer()==6);
    const auto partial_check=cd::cfhistory::verify(bytes.first(prefix),partial,digest,report_digest,std::string(vin));
    assert(!partial_check.verified&&!partial_check.completed);
    // Corrupt the terminal, then recompute CRC. The independently replayed
    // final state must reject the fabricated completion, not merely its CRC.
    data[prefix+8+2]^=4;
    put32(data+prefix+8+sizeof(terminal),cf1_crc32(data+prefix,8+sizeof(terminal)));
    assert(!cd::cfhistory::decode(bytes).at("capture_integrity_verified").boolean());
    data[prefix+8+2]^=4;
    put32(data+prefix+8+sizeof(terminal),cf1_crc32(data+prefix,8+sizeof(terminal)));
}

int main(void) {
    historical_checks();
    checks();
    puts("CF/1 native C++ fixture assertions passed (offline simulation only)");
    return 0;
}
