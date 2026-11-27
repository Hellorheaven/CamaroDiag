#define _POSIX_C_SOURCE 200809L
#include "cf1.h"
#include "j1979_host_freeze.hpp"
#include "cf1_usb.h"
#include "cl1.h"
#include "cl1_usb.h"
#include "j1979_can_cli.h"
#include "j1979_can_network.hpp"
#include "j1979_elm_can.hpp"
#include "j1979_catalogue_projection.hpp"
#include "j1979_isotp.h"
#include "j1979_cm1.hpp"
#include "j1979_host_maintenance.hpp"
#include "j1979_cu45_session.hpp"
#include "j1979_cj1.hpp"
#include "j1979_cu1.hpp"
#include "j1979_discovery.hpp"
#include "j1979_legacy_scan.hpp"
#include "j1979_legacy_scan_wire.hpp"
#include "j1979_legacy_journal.hpp"
#include "j1979_legacy_mutation.hpp"
#include "j1979_legacy_freeze_wire.hpp"
#include "j1979_legacy_live_wire.hpp"
#include <time.h>
#include <array>
#include <algorithm>
#include <string_view>
#include <exception>

#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <openssl/evp.h>
#include <openssl/crypto.h>
#include <signal.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>
#include <memory>
#include <new>
#include <utility>

#ifndef O_NOFOLLOW
#error "Native evidence input requires O_NOFOLLOW (POSIX Linux)"
#endif
#ifndef O_CLOEXEC
#error "Native evidence input requires O_CLOEXEC (POSIX Linux)"
#endif

typedef struct {
    std::unique_ptr<unsigned char[]> bytes;
    size_t length;
    dev_t device;
    ino_t inode;
    char digest[65];
} input;
struct json_writer {
    std::unique_ptr<char[]> bytes;
    size_t used = 0;
    size_t capacity;
    bool failed = false;
    explicit json_writer(size_t maximum) : capacity(maximum) {}
};

static int error(const char *message) {
    fprintf(stderr, "CamaroDiag native J1979: %s\n", message);
    return 2;
}
static void sha256_hex(const unsigned char *bytes, size_t n, char digest[65]) {
    unsigned char result[EVP_MAX_MD_SIZE];
    unsigned int produced = 0;
    if (!EVP_Digest(bytes, n, result, &produced, EVP_sha256(), NULL) || produced != 32) {
        fprintf(stderr, "SHA-256 unavailable\n"); exit(2);
    }
    const char alphabet[] = "0123456789abcdef";
    for (size_t i = 0; i < 32; ++i) {
        digest[2*i] = alphabet[result[i] >> 4];
        digest[2*i+1] = alphabet[result[i] & 15];
    }
    digest[64] = '\0';
}
static bool load_regular(const char *path, size_t maximum, input *out) {
    *out = input{};
    int fd = open(path, O_RDONLY | O_NOFOLLOW | O_CLOEXEC | O_NONBLOCK);
    if (fd < 0) return false;
    struct stat st;
    bool ok = fstat(fd, &st) == 0 && S_ISREG(st.st_mode) &&
              st.st_size > 0 && (uint64_t)st.st_size <= maximum;
    if (ok) {
        out->length = (size_t)st.st_size;
        out->device = st.st_dev; out->inode = st.st_ino;
        out->bytes.reset(new (std::nothrow) unsigned char[out->length]);
        ok = out->bytes.get() != NULL;
    }
    size_t n = 0;
    while (ok && n < out->length) {
        ssize_t got = read(fd, out->bytes.get() + n, out->length - n);
        if (got < 0 && errno == EINTR) continue;
        if (got <= 0) { ok = false; break; }
        n += (size_t)got;
    }
    if (ok) {
        unsigned char extra;
        ssize_t got;
        do { got = read(fd, &extra, 1); } while (got < 0 && errno == EINTR);
        ok = got == 0;
    }
    close(fd);
    if (!ok) {
        *out = input{};
        return false;
    }
    sha256_hex(out->bytes.get(), out->length, out->digest);
    return true;
}
static void free_input(input *v) { *v = input{}; }
static bool same_input(const input *a, const input *b) {
    return a->length == b->length && a->device == b->device &&
        a->inode == b->inode && !memcmp(a->bytes.get(), b->bytes.get(), a->length);
}
static bool separate(const input *a, const input *b) {
    return a->device != b->device || a->inode != b->inode;
}
static void write_literal(json_writer *j, const char *text) {
    size_t length = strlen(text);
    if (j->failed || j->used >= j->capacity || length >= j->capacity - j->used) { j->failed = true; return; }
    memcpy(j->bytes.get() + j->used, text, length);
    j->used += length; j->bytes[j->used] = 0;
}
static void write_unsigned(json_writer *j, uint64_t n) {
    char b[32];
    snprintf(b, sizeof(b), "%" PRIu64, n);
    write_literal(j, b);
}
static void write_bool(json_writer *j, bool b) { write_literal(j, b ? "true" : "false"); }
static void write_string(json_writer *j, const char *value) {
    /* All values in this exporter come from fixed trusted strings, or from
     * the validated VIN. No untrusted filenames or arbitrary ECU bytes. */
    write_literal(j, "\""); write_literal(j, value); write_literal(j, "\"");
}
static void write_hex(json_writer *j, const uint8_t *bytes, size_t n) {
    static const char alphabet[] = "0123456789ABCDEF";
    char local[7] = {0};
    if (!bytes || n > 3) { j->failed = true; return; }
    for (size_t i = 0; i < n; ++i) {
        local[2*i] = alphabet[bytes[i] >> 4];
        local[2*i+1] = alphabet[bytes[i] & 15];
    }
    write_string(j, local);
}
static void write_can_hex(json_writer *j, uint32_t value) {
    char local[9];
    snprintf(local, sizeof(local), "%X", value);
    write_string(j, local);
}
static void write_array(json_writer *j, const uint8_t *values, size_t n) {
    write_literal(j, "[");
    for (size_t i = 0; i < n; ++i) {
        if (i) write_literal(j, ",");
        write_unsigned(j, values[i]);
    }
    write_literal(j, "]");
}
static void write_prefix(json_writer *j, const cf1_evidence *e, const char *capture_digest) {
    write_literal(j, "{\n"
        "\"schema\":\"camarodiag-j1979-native-freeze-prefix-linux-c-v1\","
        "\"protocol\":\"SAE-J1979\","
        "\"source\":\"OFFLINE_CF1_PREFIX\","
        "\"profile\":{"
        "\"request_can_id\":");
    write_can_hex(j, e->plan.tx);
    write_literal(j, ",\"response_can_id\":"); write_can_hex(j, e->plan.rx);
    write_literal(j, ",\"extended\":"); write_bool(j, e->plan.extended);
    write_literal(j, ",\"plan\":{\"expected_vin\":"); write_string(j, e->plan.vin);
    write_literal(j, ",\"frames\":"); write_array(j, e->plan.frames, e->plan.frame_count);
    write_literal(j, ",\"selected_pids\":"); write_array(j, e->plan.pids, e->plan.pid_count);
    write_literal(j, ",\"budget_ms\":"); write_unsigned(j, e->plan.budget_ms);
    write_literal(j, "}},"
        "\"expected_vin_independently_supplied\":true,"
        "\"prefix_integrity_verified\":true,"
        "\"capture_integrity_verified\":false,"
        "\"execution_completed\":false,"
        "\"terminal_present\":false,"
        "\"physical_vehicle_verified\":false,"
        "\"hardware_provenance_verified\":false,"
        "\"evidence_authenticated\":false,"
        "\"permits_programming\":false,"
        "\"unobserved_request_transmitted\":false,"
        "\"frame_states_are_provisional\":true,"
        "\"timing_independently_verified\":false,"
        "\"authenticated\":false,"
        "\"diagnostic_verdict\":null,"
        "\"complete_records\":");
    write_unsigned(j, e->record_count);
    write_literal(j, ",\"complete_observations\":");
    write_unsigned(j, e->observation_count);
    write_literal(j, ",\"last_complete_offset\":");
    write_unsigned(j, e->last_complete_offset);
    write_literal(j, ",\"discarded_tail_bytes\":");
    write_unsigned(j, e->discarded_tail_bytes);
    write_literal(j, ",\"interruption\":");
    write_string(j, e->interruption);
    write_literal(j, ",\"replay_terminated_by_missing_observation\":");
    write_bool(j, e->replay_missing_observation);
    write_literal(j, ",\"last_observed_request_hex\":");
    if (e->has_last_request) write_hex(j, e->last_request, e->last_request_length);
    else write_literal(j, "null");
    write_literal(j, ",\"next_unobserved_request_hex\":");
    if (e->has_next_request) write_hex(j, e->next_request, e->next_request_length);
    else write_literal(j, "null");
    write_literal(j, ",\"observed_initial_vin_in_prefix\":");
    if (e->initial_vin_observed) write_string(j, e->initial_vin);
    else write_literal(j, "null");
    write_literal(j, ",\"observed_final_vin_in_prefix\":");
    if (e->final_vin_observed) write_string(j, e->final_vin);
    else write_literal(j, "null");
    write_literal(j, ",\"visited_frame_numbers_in_prefix\":[");
    for (size_t i = 0; i < e->visited_frames; ++i) {
        if (i) write_literal(j, ",");
        write_unsigned(j, e->progress[i].number);
    }
    write_literal(j, "],\"unvisited_frame_numbers_in_prefix\":[");
    for (size_t i = e->visited_frames; i < e->plan.frame_count; ++i) {
        if (i != e->visited_frames) write_literal(j, ",");
        write_unsigned(j, e->plan.frames[i]);
    }
    write_literal(j, "],\"observed_frame_progress\":[");
    for (size_t i = 0; i < e->visited_frames; ++i) {
        const cf1_frame_progress *f = &e->progress[i];
        if (i) write_literal(j, ",");
        write_literal(j, "{\"frame\":"); write_unsigned(j, f->number);
        write_literal(j, ",\"provisional_status\":");
        write_string(j, f->provisional_status);
        write_literal(j, ",\"status_finalized\":false,\"inventory_complete_in_prefix\":");
        write_bool(j, f->inventory_complete);
        write_literal(j, ",\"trigger_stable_in_prefix\":");
        write_bool(j, f->trigger_stable);
        write_literal(j, ",\"advertised_measurement_count\":");
        write_unsigned(j, f->advertised_count);
        write_literal(j, ",\"measurement_observation_count\":");
        write_unsigned(j, f->measurement_count);
        write_literal(j, ",\"transmitted_measurement_count\":");
        write_unsigned(j, f->transmitted_count);
        write_literal(j, "}");
    }
    write_literal(j, "],\"recorded_observation_duration_ms\":");
    write_unsigned(j, e->total_duration_ms);
    write_literal(j, ",\"capture_sha256\":");
    write_string(j, capture_digest);
    write_literal(j, ",\"replay_stop_reason\":");
    if (e->recipe_stopped) write_string(j, e->recipe_stop_reason);
    else write_literal(j, "null");
    write_literal(j, "}\n");
}
static void write_complete(json_writer *j, const cf1_evidence *e, const char *digest,
                           bool live, bool authenticated, bool proof_sent) {
    write_literal(j, "{\n"
        "\"schema\":\"camarodiag-functional-j1979-native-freeze-linux-c-v1\","
        "\"protocol\":\"SAE-J1979\","
        "\"source\":");
    write_string(j, live ? "LIVE_USB_CU2" : "OFFLINE_CF1");
    write_literal(j, ",\"capture_integrity_verified\":");
    write_bool(j, e->capture_integrity_verified);
    write_literal(j, ",\"execution_completed\":"); write_bool(j, e->execution_completed);
    write_literal(j,
        ",\"terminal_present\":true,"
        "\"device_credential_authenticated\":");
    write_bool(j, authenticated);
    write_literal(j, ",\"client_proof_sent\":");
    write_bool(j, proof_sent);
    write_literal(j, ",\"physical_vehicle_verified\":false,"
        "\"hardware_provenance_verified\":false,"
        "\"permits_programming\":false,"
        "\"evidence_authenticated\":false,"
        "\"profile\":{\"request_can_id\":");
    write_can_hex(j, e->plan.tx);
    write_literal(j, ",\"response_can_id\":"); write_can_hex(j, e->plan.rx);
    write_literal(j, ",\"extended\":"); write_bool(j, e->plan.extended);
    write_literal(j, ",\"plan\":{\"expected_vin\":"); write_string(j, e->plan.vin);
    write_literal(j, ",\"frames\":"); write_array(j, e->plan.frames, e->plan.frame_count);
    write_literal(j, ",\"selected_pids\":"); write_array(j, e->plan.pids, e->plan.pid_count);
    write_literal(j, ",\"budget_ms\":"); write_unsigned(j, e->plan.budget_ms);
    write_literal(j, "}},\"observations\":"); write_unsigned(j, e->observation_count);
    write_literal(j, ",\"duration_ms\":"); write_unsigned(j, e->terminal_duration_ms);
    write_literal(j, ",\"stop_reason\":");
    if (e->recipe_stopped) write_string(j, e->terminal_stop_reason);
    else write_literal(j, "null");
    write_literal(j, ",\"initial_vin\":");
    if (e->initial_vin_observed) write_string(j, e->initial_vin);
    else write_literal(j, "null");
    write_literal(j, ",\"final_vin\":");
    if (e->final_vin_observed) write_string(j, e->final_vin);
    else write_literal(j, "null");
    write_literal(j, ",\"frame_states\":[");
    for (size_t i = 0; i < e->visited_frames; ++i) {
        if (i) write_literal(j, ",");
        write_string(j, e->progress[i].provisional_status);
    }
    write_literal(j, "],\"capture_sha256\":");
    write_string(j, digest);
    write_literal(j, "}\n");
}
static bool atomic_create(const char *path, const char *content, size_t n) {
    int fd = open(path, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600);
    if (fd < 0) return false;
    size_t written = 0;
    bool ok = true;
    while (written < n) {
        ssize_t w = write(fd, content + written, n - written);
        if (w < 0 && errno == EINTR) continue;
        if (w <= 0) { ok = false; break; }
        written += (size_t)w;
    }
    if (ok) ok = fsync(fd) == 0;
    if (close(fd) < 0) ok = false;
    if (!ok) unlink(path);
    return ok;
}
static bool report_prefix(const input *capture, const char *vin,
                          std::unique_ptr<char[]> *json_out, size_t *size_out) {
    cf1_evidence e{};
    char err[256] = {0};
    if (cf1_inspect_prefix(capture->bytes.get(), capture->length, vin, &e, err, sizeof(err))) {
        fprintf(stderr, "CF/1 rejected: %s\n", err);
        cf1_evidence_free(&e);
        return false;
    }
    json_writer writer{65536};
    writer.bytes.reset(new (std::nothrow) char[writer.capacity]{});
    if (writer.bytes) write_prefix(&writer, &e, capture->digest);
    bool ok = writer.bytes.get() && !writer.failed;
    cf1_evidence_free(&e);
    if (!ok) { writer.bytes.reset(); return false; }
    *json_out = std::move(writer.bytes);
    *size_out = writer.used;
    return true;
}
static int inspect(const char *capture_path, const char *vin, const char *output) {
    input original{}, again{};
    std::unique_ptr<char[]> report;
    size_t report_size = 0;
    bool ok = load_regular(capture_path, CF1_MAX_BYTES, &original);
    if (ok) ok = report_prefix(&original, vin, &report, &report_size);
    if (ok) ok = load_regular(capture_path, CF1_MAX_BYTES, &again) &&
                 same_input(&original, &again);
    if (ok) ok = atomic_create(output, report.get(), report_size);
    report.reset(); free_input(&again); free_input(&original);
    return ok ? 0 : error("CF/1 inspection failed, source changed, or output exists");
}
static int inspect_complete(const char *capture_path, const char *vin, const char *output) {
    input original{}, again{};
    bool ok = load_regular(capture_path, CF1_MAX_BYTES, &original);
    cf1_evidence evidence{};
    char err[256] = {0};
    if (ok) ok = cf1_inspect_complete(original.bytes.get(), original.length, vin,
                                     &evidence, err, sizeof(err)) == 0;
    if (!ok && *err) fprintf(stderr, "CF/1 rejected: %s\n", err);
    json_writer writer{65536};
    if (ok) {
        writer.bytes.reset(new (std::nothrow) char[writer.capacity]{});
        ok = writer.bytes.get() != NULL;
        if (ok) write_complete(&writer, &evidence, original.digest, false, false, false);
        if (writer.failed) ok = false;
    }
    if (ok) ok = load_regular(capture_path, CF1_MAX_BYTES, &again) &&
                 same_input(&original, &again);
    if (ok) ok = atomic_create(output, writer.bytes.get(), writer.used);
    writer.bytes.reset();
    cf1_evidence_free(&evidence);
    free_input(&again); free_input(&original);
    return ok ? 0 : error("Full CF/1 inspection failed or output exists");
}
/* Full-terminal verification also replays every observation. It cannot use
 * prefix verification (which rejects terminal records by design), and it
 * never inherits live USB authentication claims from a JSON report.
 */
static int verify_complete(const char *capture_path, const char *report_path,
                           const char *output_path, const char *vin) {
    input capture{}, saved{}, capture_again{}, report_again{};
    cf1_evidence evidence{};
    json_writer projection{65536};
    char failure[256] = {0};
    bool valid = load_regular(capture_path, CF1_MAX_BYTES, &capture) &&
                 load_regular(report_path, 67108864, &saved);
    if (valid) valid = separate(&capture, &saved);
    if (valid) valid = cf1_inspect_complete(capture.bytes.get(), capture.length,
                                          vin, &evidence,
                                          failure, sizeof(failure)) == 0;
    if (valid) {
        projection.bytes.reset(new (std::nothrow) char[projection.capacity]{});
        valid = projection.bytes.get() != NULL;
    }
    if (valid) write_complete(&projection, &evidence, capture.digest,
                              false, false, false);
    if (valid) valid = !projection.failed &&
                       projection.used == saved.length &&
                       !memcmp(projection.bytes.get(), saved.bytes.get(), projection.used);
    if (valid) valid = load_regular(capture_path, CF1_MAX_BYTES, &capture_again) &&
                       load_regular(report_path, 67108864, &report_again) &&
                       same_input(&capture, &capture_again) &&
                       same_input(&saved, &report_again);
    if (valid) {
        char proof[1200];
        int n = snprintf(proof, sizeof(proof),
            "{\"schema\":\"camarodiag-j1979-native-freeze-complete-verification-linux-c-v1\","
            "\"consistent\":true,\"capture_integrity_verified\":true,"
            "\"execution_completed\":%s,"
            "\"physical_vehicle_verified\":false,"
            "\"device_credential_authenticated\":false,"
            "\"permits_programming\":false,"
            "\"capture_sha256\":\"%s\",\"report_sha256\":\"%s\","
            "\"verification_scope\":\"CANONICAL_OFFLINE_CF1\"}\n",
            evidence.execution_completed ? "true" : "false",
            capture.digest, saved.digest);
        valid = n > 0 && (size_t)n < sizeof(proof) &&
                atomic_create(output_path, proof, (size_t)n);
    }
    if (!valid && failure[0]) fprintf(stderr, "CF/1 full verification rejected: %s\n", failure);
    projection.bytes.reset();
    cf1_evidence_free(&evidence);
    free_input(&capture_again); free_input(&report_again);
    free_input(&capture); free_input(&saved);
    return valid ? 0 : error("Full CF/1 verification refused: altered or inconsistent evidence");
}
static int verify(const char *capture_path, const char *report_path,
                  const char *output_path, const char *vin) {
    input capture{}, stored{}, recheck_capture{}, recheck_report{};
    std::unique_ptr<char[]> replayed;
    size_t length = 0;
    bool ok = load_regular(capture_path, CF1_MAX_BYTES, &capture) &&
              load_regular(report_path, 67108864, &stored);
    if (ok) ok = separate(&capture, &stored);
    if (ok) ok = report_prefix(&capture, vin, &replayed, &length);
    /* Native canonical verification deliberately compares the entire original
     * byte stream, not an untrusted subset of JSON fields. Alternative JSON
     * formatting/schema require an explicit, separately reviewed importer. */
    if (ok) ok = stored.length == length && !memcmp(stored.bytes.get(), replayed.get(), length);
    if (ok) ok = load_regular(capture_path, CF1_MAX_BYTES, &recheck_capture) &&
                 load_regular(report_path, 67108864, &recheck_report) &&
                 same_input(&capture, &recheck_capture) &&
                 same_input(&stored, &recheck_report);
    if (ok) {
        char verification[1600] = {0};
        int n = snprintf(verification, sizeof(verification),
            "{\"schema\":\"camarodiag-j1979-native-freeze-prefix-verification-linux-c-v1\","
            "\"consistent\":true,\"prefix_integrity_verified\":true,"
            "\"capture_integrity_verified\":false,\"execution_completed\":false,"
            "\"physical_vehicle_verified\":false,\"permits_programming\":false,"
            "\"capture_sha256\":\"%s\",\"report_sha256\":\"%s\","
            "\"verification_scope\":\"CANONICAL_NATIVE_PREFIX_ONLY\"}\n",
            capture.digest, stored.digest);
        ok = n > 0 && (size_t)n < sizeof(verification) &&
             atomic_create(output_path, verification, (size_t)n);
    }
    replayed.reset();
    free_input(&recheck_capture); free_input(&recheck_report);
    free_input(&capture); free_input(&stored);
    return ok ? 0 : error("CF/1 verification refused: wrong report, unstable inputs, or output exists");
}
typedef struct {
    cf1_plan plan;
    const char *port, *credential, *capture, *report;
    bool approved;
} capture_options;
static bool hex_uint32(const char *value, uint32_t *out) {
    if (!value || !*value || *value == '-' || *value == '+') return false;
    if (value[0] == '0' && (value[1] == 'x' || value[1] == 'X'))
        value += 2;
    if (!*value) return false;
    for (const char *p = value; *p; ++p)
        if (!((*p >= '0' && *p <= '9') ||
              (*p >= 'a' && *p <= 'f') ||
              (*p >= 'A' && *p <= 'F'))) return false;
    errno = 0;
    char *end = NULL;
    unsigned long n = strtoul(value, &end, 16);
    if (errno || *end || n > UINT32_MAX) return false;
    *out = (uint32_t)n; return true;
}
static bool decimal_uint32(const char *value, uint32_t *out) {
    if (!value || !*value) return false;
    for (const char *p = value; *p; ++p)
        if (*p < '0' || *p > '9') return false;
    errno = 0;
    char *end = NULL;
    unsigned long n = strtoul(value, &end, 10);
    if (errno || *end || n > UINT32_MAX) return false;
    *out = (uint32_t)n; return true;
}
static bool parse_capture_options(int argc, char **argv, capture_options *o,bool socketcan=false) {
    memset(o, 0, sizeof(*o));
    o->plan.budget_ms = 180000;
    bool tx = false, rx = false, budget = false, vin = false, extended = false;
    for (int i = 2; i < argc; ++i) {
        const char *name = argv[i];
        if (!strcmp(name, "--extended")) {
            if (extended) return false;
            extended = o->plan.extended = true;
            continue;
        }
        if (!strcmp(name, "--confirm-physical-channel")) {
            if (o->approved) return false;
            o->approved = true; continue;
        }
        if (i + 1 >= argc || argv[i+1][0] == '-') return false;
        const char *value = argv[++i];
        if (!strcmp(name, socketcan?"--interface":"--port")) {
            if (o->port) return false;
            o->port = value;
        } else if (!socketcan&&!strcmp(name, "--key-file")) {
            if (o->credential) return false;
            o->credential = value;
        } else if (!strcmp(name, "--capture")) {
            if (o->capture) return false;
            o->capture = value;
        } else if (!strcmp(name, "--output")) {
            if (o->report) return false;
            o->report = value;
        } else if (!strcmp(name, "--tx")) {
            if (tx || !hex_uint32(value, &o->plan.tx)) return false;
            tx = true;
        } else if (!strcmp(name, "--rx")) {
            if (rx || !hex_uint32(value, &o->plan.rx)) return false;
            rx = true;
        } else if (!strcmp(name, "--vin")) {
            if (vin || !cf1_valid_vin(value)) return false;
            memcpy(o->plan.vin, value, 18);
            vin = true;
        } else if (!strcmp(name, "--budget-ms")) {
            if (budget || !decimal_uint32(value, &o->plan.budget_ms))
                return false;
            budget = true;
        } else if (!strcmp(name, "--frame") || !strcmp(name, "--pid")) {
            uint32_t byte = 0;
            if (!hex_uint32(value, &byte) || byte > 255) return false;
            if (!strcmp(name, "--frame")) {
                if (o->plan.frame_count >= CF1_MAX_FRAMES) return false;
                o->plan.frames[o->plan.frame_count++] = (uint8_t)byte;
            } else {
                if (o->plan.pid_count >= CF1_MAX_PIDS) return false;
                o->plan.pids[o->plan.pid_count++] = (uint8_t)byte;
            }
        } else return false;
    }
    return o->approved && tx && rx && vin &&
           o->port && (socketcan?j1979_isotp_valid_interface(o->port):o->credential!=nullptr) && o->capture && o->report &&
           strcmp(o->capture, o->report) &&
           (socketcan||(strcmp(o->credential, o->report) && strcmp(o->credential, o->capture))) &&
           cf1_valid_plan(&o->plan);
}
static bool read_key_file(const char *path, uint8_t key[32]) {
    int fd = open(path, O_RDONLY | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK);
    if (fd < 0) return false;
    struct stat st;
    bool ok = fstat(fd, &st) == 0 && S_ISREG(st.st_mode) &&
        st.st_size == 32 && (st.st_mode & 0077) == 0;
    if (ok) {
        size_t n = 0;
        while (n < 32) {
            ssize_t got = read(fd, key + n, 32 - n);
            if (got < 0 && errno == EINTR) continue;
            if (got <= 0) { ok = false; break; }
            n += (size_t)got;
        }
    }
    if (close(fd) < 0) ok = false;
    if (!ok) OPENSSL_cleanse(key, 32);
    return ok;
}
static void cancel_handler(int signum) {
    (void)signum;
    cf1_usb_request_cancel();
}
static int capture_native(int argc, char **argv) {
    capture_options o{};
    if (!parse_capture_options(argc, argv, &o))
        return error("Explicit physical scope, key, port, VIN, frame and --confirm-physical-channel required");
    if (!isatty(STDIN_FILENO))
        return error("CU/2 requires an interactive local operator approval");
    fprintf(stderr,
        "Selected real USB connector and physical ECU; read-only Mode 02, stationary vehicle? [yes/no] ");
    char answer[16] = {0};
    if (!fgets(answer, sizeof(answer), stdin) || strcmp(answer, "yes\n"))
        return error("No live CU/2 session without explicit confirmation");
    uint8_t key[32] = {0};
    if (!read_key_file(o.credential, key))
        return error("Invalid 32-byte credential file or unsafe file permissions");
    struct sigaction action{}, oldint{}, oldterm{};
    action.sa_handler = cancel_handler;
    sigemptyset(&action.sa_mask);
    sigaction(SIGINT, &action, &oldint);
    sigaction(SIGTERM, &action, &oldterm);
    cf1_usb_capture_result usb{};
    char failure[256] = {0};
    int transport = cf1_usb_capture(o.port, &o.plan, key, o.capture,
                                     &usb, failure, sizeof(failure));
    sigaction(SIGINT, &oldint, NULL);
    sigaction(SIGTERM, &oldterm, NULL);
    OPENSSL_cleanse(key, sizeof(key));
    input capture{}, again{};
    cf1_evidence evidence{};
    bool valid = transport == 0 &&
                 load_regular(o.capture, CF1_MAX_BYTES, &capture);
    if (valid) {
        valid = cf1_inspect_complete(capture.bytes.get(), capture.length,
                                    o.plan.vin, &evidence,
                                    failure, sizeof(failure)) == 0;
    }
    if (valid) valid = load_regular(o.capture, CF1_MAX_BYTES, &again) &&
                       same_input(&capture, &again);
    json_writer writer{65536};
    writer.bytes.reset(new (std::nothrow) char[writer.capacity]{});
    if (!writer.bytes) valid = false;
    if (writer.bytes.get() && valid) {
        write_complete(&writer, &evidence, capture.digest, true,
                       usb.authenticated, usb.client_proof_sent);
    } else if (writer.bytes) {
        write_literal(&writer,
            "{\"schema\":\"camarodiag-functional-j1979-native-freeze-linux-c-v1\","
            "\"protocol\":\"SAE-J1979\","
            "\"source\":\"LIVE_USB_CU2\","
            "\"capture_integrity_verified\":false,"
            "\"execution_completed\":false,"
            "\"physical_vehicle_verified\":false,"
            "\"permits_programming\":false,"
            "\"device_credential_authenticated\":");
        write_bool(&writer, usb.authenticated);
        write_literal(&writer, ",\"client_proof_sent\":");
        write_bool(&writer, usb.client_proof_sent);
        write_literal(&writer, ",\"observations_received\":0,\"error\":");
        write_string(&writer, failure[0] ? failure : "Capture not fully validated");
        write_literal(&writer, "}\n");
    }
    bool persisted = writer.bytes.get() && !writer.failed &&
        atomic_create(o.report, writer.bytes.get(), writer.used);
    bool all_frames_stable = valid && evidence.execution_completed;
    for (size_t i = 0; i < evidence.visited_frames && all_frames_stable; ++i)
        if (strcmp(evidence.progress[i].provisional_status, "COMPLETE") &&
            strcmp(evidence.progress[i].provisional_status, "ABSENT"))
            all_frames_stable = false;
    int status = all_frames_stable && persisted ? 0 : 2;
    if (!persisted)
        fprintf(stderr, "Native CF/1 report could not be created; original capture is preserved\n");
    if (!valid)
        fprintf(stderr, "Native CF/1 not validated: %s\n", failure[0] ? failure : "Unexpected failure");
    writer.bytes.reset(); free_input(&capture); free_input(&again);
    cf1_evidence_free(&evidence);
    return status;
}

/* Native CL/1 offline replay reuses the same secure input loading, source
 * reread and exclusive 0600 publication as CF/1. It never contacts an ECU.
 * Prefix observations cannot be promoted to a complete terminal receipt.
 */
static bool cl1_project(const input *capture, const char *vin, bool prefix,
                        std::unique_ptr<char[]> *json_out, size_t *size_out, cl1_result *state) {
    char reason[256] = {0};
    memset(state, 0, sizeof(*state));
    int replay = prefix ?
        cl1_inspect_prefix(capture->bytes.get(), capture->length, vin,
                           state, reason, sizeof(reason)) :
        cl1_inspect_complete(capture->bytes.get(), capture->length, vin,
                             state, reason, sizeof(reason));
    if (replay != 0) {
        fprintf(stderr, "CL/1 replay rejected: %s\n", reason);
        return false;
    }
    json_writer w{65536};
    w.bytes.reset(new (std::nothrow) char[w.capacity]{});
    if (!w.bytes) return false;
    write_literal(&w, "{\"schema\":");
    write_string(&w, prefix ? "camarodiag-j1979-live-prefix-linux-c-v1" :
                             "camarodiag-j1979-live-complete-linux-c-v1");
    write_literal(&w, ",\"protocol\":\"SAE-J1979\","
        "\"transport\":\"OFFLINE_CL1\",\"source\":");
    write_string(&w, prefix ? "OFFLINE_CL1_PREFIX" : "OFFLINE_CL1");
    write_literal(&w, ",\"physical_vehicle_verified\":false,"
        "\"device_credential_authenticated\":false,"
        "\"hardware_provenance_verified\":false,"
        "\"permits_programming\":false,"
        "\"raw_only\":true,\"profile\":{\"request_can_id\":");
    write_can_hex(&w,state->plan.tx);
    write_literal(&w,",\"response_can_id\":");
    write_can_hex(&w,state->plan.rx);
    write_literal(&w,",\"extended\":");
    write_bool(&w,state->plan.extended);
    write_literal(&w,",\"expected_vin\":");
    write_string(&w,state->plan.vin);
    write_literal(&w,",\"selected_pids\":");
    write_array(&w,state->plan.pids,state->plan.pid_count);
    write_literal(&w,",\"requested_cycles\":");
    write_unsigned(&w,state->plan.cycles);
    write_literal(&w,",\"interval_ms\":");
    write_unsigned(&w,state->plan.interval_ms);
    write_literal(&w,",\"budget_ms\":");
    write_unsigned(&w,state->plan.budget_ms);
    write_literal(&w,"},\"terminal_present\":");
    write_bool(&w,state->terminal_present);
    write_literal(&w,",\"prefix_integrity_verified\":");
    write_bool(&w,state->prefix_integrity_verified);
    write_literal(&w,",\"capture_integrity_verified\":");
    write_bool(&w,state->capture_integrity_verified);
    write_literal(&w,",\"execution_completed\":");
    write_bool(&w,state->completed && !prefix);
    write_literal(&w,",\"initial_vin_verified\":");
    write_bool(&w,state->initial_vin);
    write_literal(&w,",\"final_vin_verified\":");
    write_bool(&w,state->final_vin);
    write_literal(&w,",\"inventory_complete\":");
    write_bool(&w,state->inventory_complete);
    write_literal(&w,",\"complete_records\":");
    write_unsigned(&w,state->records);
    write_literal(&w,",\"observed_steps\":");
    write_unsigned(&w,state->steps);
    write_literal(&w,",\"completed_cycles\":");
    write_unsigned(&w,state->completed_cycles);
    write_literal(&w,",\"samples\":");
    write_unsigned(&w,state->samples);
    write_literal(&w,",\"raw_samples\":");
    write_unsigned(&w,state->raw_samples);
    write_literal(&w,",\"negative_samples\":");
    write_unsigned(&w,state->negative_samples);
    write_literal(&w,",\"timeout_samples\":");
    write_unsigned(&w,state->timeout_samples);
    write_literal(&w,",\"raw_bytes\":");
    write_unsigned(&w,state->raw_bytes);
    write_literal(&w,",\"recorded_duration_ms\":");
    write_unsigned(&w,state->duration_ms);
    write_literal(&w,",\"last_complete_offset\":");
    write_unsigned(&w,state->last_complete_offset);
    write_literal(&w,",\"discarded_tail_bytes\":");
    write_unsigned(&w,state->discarded_tail_bytes);
    write_literal(&w,",\"interruption\":");
    if (prefix) write_string(&w,state->interruption);
    else write_literal(&w,"null");
    write_literal(&w,",\"stop_code\":");
    write_unsigned(&w,state->stop);
    write_literal(&w,",\"raw_per_selected_pid\":[");
    for (unsigned i=0;i<state->plan.pid_count;++i) {
        if (i) write_literal(&w,",");
        write_unsigned(&w,state->raw_per_pid[i]);
    }
    write_literal(&w,"],\"capture_sha256\":");
    write_string(&w,capture->digest);
    write_literal(&w,"}\n");
    if (w.failed) {w.bytes.reset();return false;}
    *json_out = std::move(w.bytes);
    *size_out=w.used;
    return true;
}
static int cl1_inspect_cli(const char *capture_path, const char *vin,
                           const char *output, bool prefix) {
    input capture{}, again{};
    cl1_result state{};
    std::unique_ptr<char[]> report;
    size_t n=0;
    bool ok=load_regular(capture_path,CL1_MAX_CAPTURE,&capture);
    if (ok) ok=cl1_project(&capture,vin,prefix,&report,&n,&state);
    if (ok) ok=load_regular(capture_path,CL1_MAX_CAPTURE,&again) &&
               same_input(&capture,&again);
    if (ok) ok=atomic_create(output,report.get(),n);
    report.reset();free_input(&capture);free_input(&again);
    return ok?0:error("CL/1 inspection refused: invalid capture, source changed or output exists");
}
static int cl1_verify_cli(const char *capture_path, const char *report_path,
                          const char *proof_path, const char *vin, bool prefix) {
    input capture{}, stored{}, reread_capture{}, reread_report{};
    cl1_result state{};
    std::unique_ptr<char[]> canonical;
    size_t size=0;
    bool ok=load_regular(capture_path,CL1_MAX_CAPTURE,&capture) &&
            load_regular(report_path,65536,&stored);
    if (ok) ok=separate(&capture,&stored);
    if (ok) ok=cl1_project(&capture,vin,prefix,&canonical,&size,&state);
    /* No JSON field can be independently asserted by an untrusted saved
     * report: exact canonical equality is required after strict CL/1 replay.
     */
    if (ok) ok=stored.length==size && !memcmp(stored.bytes.get(),canonical.get(),size);
    if (ok) ok=load_regular(capture_path,CL1_MAX_CAPTURE,&reread_capture) &&
               load_regular(report_path,65536,&reread_report) &&
               same_input(&capture,&reread_capture) &&
               same_input(&stored,&reread_report);
    if (ok) {
        char proof[1024];
        int n=snprintf(proof,sizeof(proof),
            "{\"schema\":\"camarodiag-j1979-live-verification-linux-c-v1\","
            "\"consistent\":true,\"prefix\":%s,"
            "\"capture_integrity_verified\":%s,"
            "\"execution_completed\":%s,"
            "\"physical_vehicle_verified\":false,"
            "\"device_credential_authenticated\":false,"
            "\"permits_programming\":false,"
            "\"capture_sha256\":\"%s\",\"report_sha256\":\"%s\","
            "\"verification_scope\":\"CANONICAL_OFFLINE_CL1\"}\n",
            prefix?"true":"false",
            state.capture_integrity_verified?"true":"false",
            state.completed && !prefix?"true":"false",
            capture.digest,stored.digest);
        ok=n>0 && (size_t)n<sizeof(proof) &&
            atomic_create(proof_path,proof,(size_t)n);
    }
    canonical.reset();
    free_input(&capture);free_input(&stored);
    free_input(&reread_capture);free_input(&reread_report);
    return ok?0:error("CL/1 verification refused: altered report, unstable input or output exists");
}


typedef struct {
    cl1_plan plan;
    const char *port,*credential,*capture,*output;
    bool approved,tx_set,rx_set,vin_set,budget_set,cycles_set,interval_set,extended_set;
} cl1_capture_options;
static bool parse_cl1_capture(int argc,char **argv,cl1_capture_options *o) {
    memset(o,0,sizeof(*o));
    o->plan.budget_ms=180000;
    o->plan.cycles=30;
    o->plan.interval_ms=1000;
    for (int i=2;i<argc;++i) {
        const char *flag=argv[i];
        if (!strcmp(flag,"--extended")) {
            if (o->extended_set) return false;
            o->extended_set=true;
            o->plan.extended=true;
            continue;
        }
        if (!strcmp(flag,"--confirm-physical-channel")) {
            if (o->approved) return false;
            o->approved=true;
            continue;
        }
        if (i+1>=argc || argv[i+1][0]=='-') return false;
        const char *value=argv[++i];
        if (!strcmp(flag,"--port")) {
            if (o->port) return false;
            o->port=value;
        } else if (!strcmp(flag,"--key-file")) {
            if (o->credential) return false;
            o->credential=value;
        } else if (!strcmp(flag,"--capture")) {
            if (o->capture) return false;
            o->capture=value;
        } else if (!strcmp(flag,"--output")) {
            if (o->output) return false;
            o->output=value;
        } else if (!strcmp(flag,"--tx")) {
            if (o->tx_set || !hex_uint32(value,&o->plan.tx)) return false;
            o->tx_set=true;
        } else if (!strcmp(flag,"--rx")) {
            if (o->rx_set || !hex_uint32(value,&o->plan.rx)) return false;
            o->rx_set=true;
        } else if (!strcmp(flag,"--vin")) {
            if (o->vin_set || !cf1_valid_vin(value)) return false;
            o->vin_set=true;
            memcpy(o->plan.vin,value,18);
        } else if (!strcmp(flag,"--budget-ms")) {
            if (o->budget_set || !decimal_uint32(value,&o->plan.budget_ms))
                return false;
            o->budget_set=true;
        } else if (!strcmp(flag,"--cycles")) {
            uint32_t n=0;
            if (o->cycles_set || !decimal_uint32(value,&n) || n>1000)
                return false;
            o->cycles_set=true;
            o->plan.cycles=(uint16_t)n;
        } else if (!strcmp(flag,"--interval-ms")) {
            uint32_t n=0;
            if (o->interval_set || !decimal_uint32(value,&n) || n>60000)
                return false;
            o->interval_set=true;
            o->plan.interval_ms=(uint16_t)n;
        } else if (!strcmp(flag,"--pid")) {
            uint32_t n=0;
            if (o->plan.pid_count>=CL1_MAX_PIDS ||
                !hex_uint32(value,&n) || n>255) return false;
            o->plan.pids[o->plan.pid_count++]=(uint8_t)n;
        } else return false;
    }
    return o->approved && o->tx_set && o->rx_set && o->vin_set &&
        o->port && o->credential && o->capture && o->output &&
        strcmp(o->capture,o->output) && strcmp(o->capture,o->credential) &&
        strcmp(o->output,o->credential) &&
        cl1_valid_plan(&o->plan);
}
static int capture_cl1_native(int argc,char **argv) {
    cl1_capture_options o{};
    if (!parse_cl1_capture(argc,argv,&o))
        return error("CU/3 requires explicit approved physical pair, VIN, PID, key, port, capture, output, cycles and interval");
    if (!isatty(STDIN_FILENO))
        return error("CU/3 requires a local interactive operator and a stationary vehicle");
    fprintf(stderr,
        "CU/3 native Mode 01 RAW recording, selected real USB connector and ECU; stationary vehicle? Type yes: ");
    char confirmation[16]={0};
    if (!fgets(confirmation,sizeof(confirmation),stdin) ||
        strcmp(confirmation,"yes\n"))
        return error("CU/3 physical consent refused, no bytes transmitted");
    uint8_t key[32]={0};
    if (!read_key_file(o.credential,key))
        return error("CU/3 requires a private provisioned 32-byte key, no fallback");
    struct sigaction action{},oldint{},oldterm{};
    action.sa_handler=cancel_handler;
    sigemptyset(&action.sa_mask);
    bool registered=sigaction(SIGINT,&action,&oldint)==0;
    if (registered && sigaction(SIGTERM,&action,&oldterm)!=0) {
        sigaction(SIGINT,&oldint,NULL);
        registered=false;
    }
    cf1_usb_capture_result transport{};
    char failure[256]={0};
    int received=registered ?
        cl1_usb_capture(o.port,&o.plan,key,o.capture,
                         &transport,failure,sizeof(failure)) : -1;
    if (!registered)
        snprintf(failure,sizeof(failure),"Cannot register cancellation handlers");
    if (registered) {
        sigaction(SIGINT,&oldint,NULL);
        sigaction(SIGTERM,&oldterm,NULL);
    }
    OPENSSL_cleanse(key,sizeof(key));
    /* Live credential proof is not an offline hardware attestation.
     * The published schema records only independently replayed CL/1 bytes.
     */
    input capture{},again{};
    std::unique_ptr<char[]> report;
    size_t size=0;
    cl1_result replay{};
    bool complete=received==0 &&
        load_regular(o.capture,CL1_MAX_CAPTURE,&capture);
    if (complete) complete=cl1_project(&capture,o.plan.vin,false,
                                      &report,&size,&replay) &&
                           replay.completed && replay.initial_vin &&
                           replay.final_vin && replay.inventory_complete;
    if (complete) complete=load_regular(o.capture,CL1_MAX_CAPTURE,&again) &&
                           same_input(&capture,&again);
    if (complete) complete=atomic_create(o.output,report.get(),size);
    if (!complete) {
        /* A failed live capture is still a valuable forensic prefix.
         * Never turn its incomplete/unsafe status into a successful report.
         */
        char failed_report[1200];
        int count=snprintf(failed_report,sizeof(failed_report),
            "{\"schema\":\"camarodiag-j1979-live-capture-failure-linux-c-v1\","
            "\"protocol\":\"SAE-J1979\",\"source\":\"LIVE_USB_CU3\","
            "\"execution_completed\":false,\"capture_integrity_verified\":false,"
            "\"physical_vehicle_verified\":false,\"permits_programming\":false,"
            "\"client_proof_sent\":%s,\"terminal_received\":%s,"
            "\"retained_bytes\":%zu,\"failure\":",
            transport.client_proof_sent?"true":"false",
            transport.terminal_received?"true":"false",
            transport.raw_bytes_received);
        if (count>0 && (size_t)count<sizeof(failed_report)-3) {
            size_t position=(size_t)count;
            /* Do not quote raw path, key, or external error text. */
            const char *reason=received!=0 ? "USB_CAPTURE_INCOMPLETE" :
                               "INDEPENDENT_CL1_REPLAY_FAILED";
            int written=snprintf(failed_report+position,
                sizeof(failed_report)-position,"\"%s\"}\n",reason);
            if (written>0 && (size_t)written<sizeof(failed_report)-position)
                atomic_create(o.output,failed_report,position+(size_t)written);
        }
        fprintf(stderr,"CU/3: no qualified complete report; raw capture retained when created (%s)\n",
                failure[0]?failure:"independent replay failed or output unavailable");
    }
    report.reset();
    free_input(&capture);free_input(&again);
    return complete?0:2;
}

static int cj1_inspect_cli(const char* capture_path,const char* vin,const char* output) {
    input original{},again{};
    try {
        if(!load_regular(capture_path,cd::cj1::max_capture,&original))return error("CJ/1 input invalid");
        const auto evidence=cd::cj1::inspect({original.bytes.get(),original.length});
        const auto report=cd::cj1::report(evidence,vin,original.digest);
        if(!load_regular(capture_path,cd::cj1::max_capture,&again)||!same_input(&original,&again))
            return error("CJ/1 source changed during inspection");
        if(!atomic_create(output,report.data(),report.size()))return error("CJ/1 output already exists or unavailable");
        return evidence.integrity_verified&&evidence.end==1&&evidence.vin==vin?0:2;
    }catch(const std::exception& e){return error(e.what());}
}

static int cm1_cli(const char* journal_path,const char* report_path,const char* proof_path,
                   const char* vin,bool prefix) {
    input original{},again{},stored{},stored_again{};
    try {
        if (!load_regular(journal_path,cd::cm1::max_journal,&original))
            return error("CM/1 input is not a bounded regular file");
        const auto evidence=cd::cm1::inspect({original.bytes.get(),original.length},vin,prefix);
        const auto canonical=cd::cm1::report(evidence,original.digest);
        if (proof_path) {
            if (!load_regular(report_path,cd::cm1::max_report,&stored)||!separate(&original,&stored)||
                stored.length!=canonical.size()||memcmp(stored.bytes.get(),canonical.data(),canonical.size()))
                return error("CM/1 report differs from canonical independent replay");
        }
        if (!load_regular(journal_path,cd::cm1::max_journal,&again)||!same_input(&original,&again))
            return error("CM/1 original changed");
        if (proof_path) {
            if (!load_regular(report_path,cd::cm1::max_report,&stored_again)||!same_input(&stored,&stored_again))
                return error("CM/1 report changed");
            const std::string proof=std::string("{\"schema\":\"camarodiag-j1979-native-maintenance-verification-linux-v1\","
                "\"consistent\":true,\"verification_scope\":\"CANONICAL_OFFLINE_CM1\",\"journal_sha256\":\"")+
                original.digest+"\",\"report_sha256\":\""+stored.digest+"\",\"recovery_required\":"+
                (evidence.recovery_required()?"true":"false")+
                ",\"physical_vehicle_verified\":false,\"permits_automatic_retry\":false,\"replayed\":"+
                canonical.substr(0,canonical.size()-1)+"}\n";
            if (proof.size()>cd::cm1::max_report||!atomic_create(proof_path,proof.data(),proof.size()))
                return error("CM/1 proof path unavailable");
        } else if (!atomic_create(report_path,canonical.data(),canonical.size())) {
            return error("CM/1 report path unavailable");
        }
        return evidence.recovery_required()?7:0;
    } catch(const std::exception& e) {
        return error(e.what());
    }
}

struct Cu1Options {
    cd::cj1::Profile profile{0,0,60000,false};
    const char *port{},*credential{},*capture{},*output{},*vin{};
    bool approved{},tx_set{},rx_set{},budget_set{},ext_set{};
};
static bool parse_cu1(int argc,char** argv,Cu1Options& o) {
    for(int i=2;i<argc;++i){
        const std::string_view flag=argv[i];
        if(flag=="--extended"){if(o.ext_set)return false;o.ext_set=true;o.profile.extended=true;continue;}
        if(flag=="--confirm-physical-channel"){if(o.approved)return false;o.approved=true;continue;}
        if(i+1>=argc||argv[i+1][0]=='-')return false;
        const char* value=argv[++i];
        if(flag=="--tx"){if(o.tx_set||!hex_uint32(value,&o.profile.tx))return false;o.tx_set=true;}
        else if(flag=="--rx"){if(o.rx_set||!hex_uint32(value,&o.profile.rx))return false;o.rx_set=true;}
        else if(flag=="--budget-ms"){if(o.budget_set||!decimal_uint32(value,&o.profile.budget))return false;o.budget_set=true;}
        else {
            const char** target=nullptr;
            if(flag=="--port")target=&o.port;
            else if(flag=="--key-file")target=&o.credential;
            else if(flag=="--capture")target=&o.capture;
            else if(flag=="--output")target=&o.output;
            else if(flag=="--vin")target=&o.vin;
            if(!target||*target||!*value)return false;
            *target=value;
        }
    }
    return o.approved&&o.tx_set&&o.rx_set&&o.port&&o.credential&&o.capture&&o.output&&o.vin&&
        cf1_valid_vin(o.vin)&&cd::cj1::valid_profile(o.profile)&&
        strcmp(o.capture,o.output)&&strcmp(o.credential,o.capture)&&strcmp(o.credential,o.output);
}
static int capture_cu1(int argc,char** argv) {
    Cu1Options options;
    if(!parse_cu1(argc,argv,options))return error("CU/1 requires explicit physical profile, independent VIN, key, port and separate output paths");
    if(!isatty(STDIN_FILENO))return error("Local interactive confirmation required");
    fputs("Read-only CU/1 test: confirm the selected physical ECU and stationary vehicle. Type yes: ",stderr);
    char answer[16]{};
    if(!fgets(answer,sizeof(answer),stdin)||strcmp(answer,"yes\n"))return error("CU/1 consent refused");
    struct Key {
        std::array<uint8_t,32> data{};
        ~Key(){OPENSSL_cleanse(data.data(),data.size());}
    } key;
    if(!read_key_file(options.credential,key.data.data()))return error("Private 32-byte CU/1 key required");
    // Reserve the report before transmission, and never truncate an existing report.
    const int fd=open(options.output,O_WRONLY|O_CREAT|O_EXCL|O_NOFOLLOW|O_CLOEXEC,0600);
    if(fd<0)return error("CU/1 report destination unavailable");
    struct ReportFile {int fd;~ReportFile(){if(fd>=0)close(fd);}} output{fd};
    struct sigaction action{},oldint{},oldterm{};
    action.sa_handler=cancel_handler;
    sigemptyset(&action.sa_mask);
    const bool interrupt=sigaction(SIGINT,&action,&oldint)==0;
    const bool terminate=interrupt&&sigaction(SIGTERM,&action,&oldterm)==0;
    cf1_usb_capture_result transport{};
    char failure[256]{};
    const int status=terminate?cd::cu1::capture(options.port,options.profile,key.data,
        options.capture,transport,failure,sizeof(failure)):-1;
    if(terminate)sigaction(SIGTERM,&oldterm,nullptr);
    if(interrupt)sigaction(SIGINT,&oldint,nullptr);
    OPENSSL_cleanse(key.data.data(),key.data.size());
    try {
        input raw{},again{};
        cd::cj1::Evidence evidence{};
        char digest[65]{};
        const bool available=transport.capture_file_created&&load_regular(options.capture,cd::cj1::max_capture,&raw);
        if(available){
            evidence=cd::cj1::inspect({raw.bytes.get(),raw.length});
            memcpy(digest,raw.digest,sizeof(digest));
        } else {
            sha256_hex(reinterpret_cast<const unsigned char*>(""),0,digest);
        }
        const bool stable=available&&load_regular(options.capture,cd::cj1::max_capture,&again)&&same_input(&raw,&again);
        if(status!=0||!stable||!(evidence.profile==options.profile)){
            evidence.integrity_verified=false;
            evidence.error=!terminate?"Cannot register cancellation handlers":
                status!=0?"CU/1 capture interrupted or refused":!stable?"CJ/1 original missing or changed":"CJ/1 scope mismatch";
        }
        auto report=cd::cj1::report(evidence,options.vin,digest,true,transport.authenticated,transport.client_proof_sent);
        report.resize(report.size()-2);
        report+=",\"capture_file_created\":";
        report+=transport.capture_file_created?"true":"false";
        report+=",\"retained_bytes\":"+std::to_string(transport.raw_bytes_received)+
            ",\"raw_capture_digest_available\":"+(available?std::string("true"):std::string("false"))+"}\n";
        std::size_t offset=0;
        while(offset<report.size()){
            const auto n=write(output.fd,report.data()+offset,report.size()-offset);
            if(n<0&&errno==EINTR)continue;
            if(n<=0)return error("CU/1 report write failed");
            offset+=static_cast<std::size_t>(n);
        }
        if(fsync(output.fd)!=0)return error("CU/1 report sync failed");
        const int close_status=close(output.fd);output.fd=-1;
        if(close_status)return error("CU/1 report close failed");
        return evidence.integrity_verified&&evidence.end==1&&evidence.vin==options.vin?0:2;
    }catch(const std::exception& e){return error(e.what());}
}
static int verify_cj1_cli(const char* capture_path,const char* report_path,const char* proof_path,const char* vin){
    input raw{},stored{},again{},stored_again{};
    try{
        if(!cf1_valid_vin(vin)||!load_regular(capture_path,cd::cj1::max_capture,&raw)||
           !load_regular(report_path,cd::cj1::max_report,&stored)||!separate(&raw,&stored))
            return error("CJ/1 verification requires independent VIN and distinct regular inputs");
        const auto evidence=cd::cj1::inspect({raw.bytes.get(),raw.length});
        if(!evidence.integrity_verified)return error("CJ/1 independent replay failed");
        const auto canonical=cd::cj1::report(evidence,vin,raw.digest);
        if(stored.length!=canonical.size()||memcmp(stored.bytes.get(),canonical.data(),canonical.size())||
           !load_regular(capture_path,cd::cj1::max_capture,&again)||!same_input(&raw,&again)||
           !load_regular(report_path,cd::cj1::max_report,&stored_again)||!same_input(&stored,&stored_again))
            return error("CJ/1 canonical report mismatch or input changed");
        const std::string proof=std::string("{\"schema\":\"camarodiag-j1979-campaign-verification-linux-v1\","
            "\"verification_scope\":\"CANONICAL_OFFLINE_CJ1\",\"consistent\":true,"
            "\"physical_vehicle_verified\":false,\"permits_programming\":false,\"capture_sha256\":\"")+
            raw.digest+"\",\"report_sha256\":\""+stored.digest+"\",\"vin_match\":\""+
            (evidence.vin.empty()?"UNVERIFIED":evidence.vin==vin?"MATCH":"MISMATCH")+"\"}\n";
        if(!atomic_create(proof_path,proof.data(),proof.size()))return error("CJ/1 proof path unavailable");
        return evidence.end==1&&evidence.vin==vin?0:2;
    }catch(const std::exception& e){return error(e.what());}
}

static volatile sig_atomic_t legacy_cancelled=0;
static void legacy_signal(int){legacy_cancelled=1;}
struct LegacyOptions {
    const char* port{};
    const char* output{},*capture{};
    cd::legacy::Protocol protocol{};
    uint32_t baud{38400},budget{8000},source{};
    const char* vin{};bool source_set{};
    bool confirmed{},protocol_set{},baud_set{},budget_set{};
};
static bool parse_legacy(int argc,char** argv,LegacyOptions& o,bool scan){
    if(scan)o.budget=180000;
    for(int i=2;i<argc;++i){
        std::string_view flag=argv[i];
        if(flag=="--confirm-physical-channel"){
            if(o.confirmed)return false;
            o.confirmed=true;continue;
        }
        if(i+1>=argc||argv[i+1][0]=='-')return false;
        const char* value=argv[++i];
        if(flag=="--port"){if(o.port)return false;o.port=value;}
        else if(flag=="--output"){if(o.output)return false;o.output=value;}
        else if(flag=="--capture"&&scan){if(o.capture)return false;o.capture=value;}
        else if(flag=="--protocol"){
            uint32_t n=0;if(o.protocol_set||!decimal_uint32(value,&n))return false;
            o.protocol=static_cast<cd::legacy::Protocol>(n);o.protocol_set=true;
        }else if(flag=="--source"&&scan){
            if(o.source_set||strlen(value)!=2||!hex_uint32(value,&o.source)||!cd::legacy::valid_source(o.source))return false;
            o.source_set=true;
        }else if(flag=="--vin"&&scan){
            if(o.vin||!cf1_valid_vin(value))return false;
            o.vin=value;
        }else if(flag=="--baud"){
            if(o.baud_set||!decimal_uint32(value,&o.baud))return false;
            o.baud_set=true;
        }else if(flag=="--budget-ms"){
            if(o.budget_set||!decimal_uint32(value,&o.budget))return false;
            o.budget_set=true;
        }else return false;
    }
    return o.port&&o.output&&o.confirmed&&o.protocol_set&&cd::legacy::valid_protocol(o.protocol)
        &&o.budget>0&&o.budget<=(scan?600000u:60000u)&&(!scan||(o.source_set&&o.vin&&(!o.capture||strcmp(o.capture,o.output))));
}
static bool journal_matches(const std::string& path,cd::legacy::Protocol protocol,unsigned source,
    std::string_view vin,cd::legacy::Bytes raw){
    input original{},again{};
    if(!load_regular(path.c_str(),cd::legacy::journal_max_bytes,&original))return false;
    const auto evidence=cd::legacy::inspect_journal({original.bytes.get(),original.length},protocol,source,vin);
    return evidence.terminal&&evidence.capture.size()==raw.size()&&
        std::equal(evidence.capture.begin(),evidence.capture.end(),raw.begin())&&
        load_regular(path.c_str(),cd::legacy::journal_max_bytes,&again)&&same_input(&original,&again);
}
static int legacy_discovery_cli(int argc,char** argv,bool scan=false){
    LegacyOptions o;
    if(!parse_legacy(argc,argv,o,scan))return error("Legacy requires port, protocol 1..5, output, explicit confirmation and selected source plus VIN for scan");
    if(!isatty(STDIN_FILENO))return error("Local interactive consent required");
    fputs(scan?"Confirm selected legacy ECU, documented VIN and stationary vehicle. Type yes: ":
        "Confirm dedicated adapter and stationary vehicle, read-only PID00 discovery. Type yes: ",stderr);
    char answer[16]{};
    if(!fgets(answer,sizeof(answer),stdin)||strcmp(answer,"yes\n"))return error("Consent refused");
    const int fd=open(o.output,O_WRONLY|O_CREAT|O_EXCL|O_NOFOLLOW|O_CLOEXEC,0600);
    if(fd<0)return error("Report destination unavailable");
    struct Output{int fd;~Output(){if(fd>=0)close(fd);}} output{fd};
    Output capture{o.capture?open(o.capture,O_WRONLY|O_CREAT|O_EXCL|O_NOFOLLOW|O_CLOEXEC,0600):-1};
    if(o.capture&&capture.fd<0)return error("Scan capture destination unavailable");
    legacy_cancelled=0;
    struct Signals{
        struct sigaction oldint{},oldterm{};
        bool interrupt{},terminate{};
        Signals(){
            struct sigaction act{};act.sa_handler=legacy_signal;sigemptyset(&act.sa_mask);
            interrupt=sigaction(SIGINT,&act,&oldint)==0;
            terminate=interrupt&&sigaction(SIGTERM,&act,&oldterm)==0;
        }
        ~Signals(){if(terminate)sigaction(SIGTERM,&oldterm,nullptr);if(interrupt)sigaction(SIGINT,&oldint,nullptr);}
    } signals;
    cd::legacy::Discovery d{};d.protocol=o.protocol;
    cd::legacy::Scan s{};s.protocol=o.protocol;s.source=o.source;
    if(o.vin)s.expected_vin=o.vin;
    try{
        if(!signals.terminate)throw cd::legacy::TransportError("Signal handlers unavailable");
        const auto current=[](){return legacy_cancelled==0;};
        std::unique_ptr<cd::legacy::CaptureJournal> journal;
        if(scan){
            const auto name=std::string(o.capture?o.capture:o.output)+".lj";
            journal=std::make_unique<cd::legacy::CaptureJournal>(name.c_str(),
                cd::legacy::ScanCapture{o.protocol,o.source,o.budget,o.vin,{}});
        }
        auto link=cd::legacy::serial_link(o.port,o.baud,current);
        cd::legacy::Elm elm(*link,o.protocol,true,current);
        elm.configure();
        if(scan){
            s=cd::legacy::scan_selected(elm,o.source,o.vin,o.budget,cd::legacy::monotonic_ms,current,
                [&](const auto& row){journal->append(row);});
            journal->finish();
            const auto raw=cd::legacy::encode_scan_capture({s.protocol,s.source,s.budget_ms,s.expected_vin,s.trace});
            if(!journal_matches(std::string(o.capture?o.capture:o.output)+".lj",o.protocol,o.source,o.vin,raw))
                throw std::runtime_error("Legacy scan journal differs from capture");
        }
        else d=cd::legacy::discover(elm,o.budget,cd::legacy::monotonic_ms,current);
    }catch(const cd::legacy::Cancelled&){d.error="CANCELLED";s.error=d.error;}
    catch(const std::exception& e){d.error=e.what();s.error=d.error;s.completed=false;s.trace.clear();}
    try{
        if(scan&&o.capture&&!s.trace.empty()){
            const auto raw=cd::legacy::encode_scan_capture({s.protocol,s.source,s.budget_ms,s.expected_vin,s.trace});
            std::size_t offset=0;
            while(offset<raw.size()){
                const auto n=write(capture.fd,raw.data()+offset,raw.size()-offset);
                if(n<0&&errno==EINTR)continue;
                if(n<=0)return error("Scan capture write failed");
                offset+=static_cast<std::size_t>(n);
            }
            if(fsync(capture.fd))return error("Scan capture persistence failed");
            const int closed=close(capture.fd);capture.fd=-1;
            if(closed)return error("Scan capture close failed");
            input original{},again{};
            if(!load_regular(o.capture,cd::legacy::scan_max_capture,&original)||original.length!=raw.size()||
                memcmp(original.bytes.get(),raw.data(),raw.size())||
                !load_regular(o.capture,cd::legacy::scan_max_capture,&again)||!same_input(&original,&again))
                return error("Persisted scan capture changed");
            const auto replay=cd::legacy::inspect_scan_capture({original.bytes.get(),original.length},s.protocol,s.source,s.expected_vin);
            if(cd::legacy::scan_report(s)!=cd::legacy::scan_report(replay))return error("Scan result differs from independent replay");
        }
        const auto report=scan?cd::legacy::scan_report(s):cd::legacy::discovery_report(d);
        size_t written=0;
        while(written<report.size()){
            const auto n=write(output.fd,report.data()+written,report.size()-written);
            if(n<0&&errno==EINTR)continue;
            if(n<=0)return error("Discovery report write failed");
            written+=static_cast<size_t>(n);
        }
        if(fsync(output.fd))return error("Discovery report sync failed");
        const int result=close(output.fd);output.fd=-1;
        if(result)return error("Discovery report close failed");
        return (scan?s.completed:d.complete)?0:2;
    }catch(const std::exception& e){return error(e.what());}
}
static int replay_legacy_discovery(const char* protocol,const char* response,const char* probe,const char* output){
    uint32_t n=0;
    if(!decimal_uint32(protocol,&n)||!cd::legacy::valid_protocol(static_cast<cd::legacy::Protocol>(n)))
        return error("Explicit legacy protocol 1..5 required");
    input r{},p{},r_again{},p_again{};
    try{
        if(!load_regular(response,32768,&r)||!load_regular(probe,4096,&p)||!separate(&r,&p))
            return error("Distinct raw response and ATDPN evidence files required");
        const auto d=cd::legacy::replay_discovery(static_cast<cd::legacy::Protocol>(n),
            {reinterpret_cast<const char*>(r.bytes.get()),r.length},{reinterpret_cast<const char*>(p.bytes.get()),p.length});
        auto report=cd::legacy::discovery_report(d);
        report.resize(report.size()-2);
        report+=std::string(",\"source\":\"OFFLINE_REPLAY\",\"raw_response_sha256\":\"")+r.digest+
            "\",\"raw_probe_sha256\":\""+p.digest+"\"}\n";
        if(!load_regular(response,32768,&r_again)||!same_input(&r,&r_again)||
            !load_regular(probe,4096,&p_again)||!same_input(&p,&p_again))return error("Raw evidence changed");
        if(!atomic_create(output,report.data(),report.size()))return error("Replay report destination unavailable");
        return d.complete?0:2;
    }catch(const std::exception& e){return error(e.what());}
}

struct Cu45Options{
    cd::cm1::Scope scope{};
    const char* port{},*key{},*journal{},*report{};
    bool operation{},tx{},rx{},budget{},extended{},confirmed{},warning{};
};
static bool parse_cu45(int argc,char** argv,Cu45Options& o,cd::cu45::Mode mode){
    for(int i=2;i<argc;++i){
        const std::string_view flag=argv[i];
        if(flag=="--extended"){
            if(o.extended)return false;
            o.extended=true;o.scope.extended=true;continue;
        }
        if(flag=="--confirm-physical-channel"){
            if(o.confirmed)return false;
            o.confirmed=true;continue;
        }
        if(flag=="--acknowledge-maintenance-warning"){
            if(o.warning||mode!=cd::cu45::Mode::maintenance)return false;
            o.warning=true;continue;
        }
        if(i+1>=argc||argv[i+1][0]=='-')return false;
        const char* value=argv[++i];
        if(!*value)return false;
        if(flag=="--operation"){
            if(o.operation)return false;
            o.operation=true;
            if(!strcmp(value,"clear"))o.scope.operation=1;
            else if(!strcmp(value,"evap"))o.scope.operation=2;
            else return false;
        }else if(flag=="--tx"){
            if(o.tx||!hex_uint32(value,&o.scope.tx))return false;
            o.tx=true;
        }else if(flag=="--rx"){
            if(o.rx||!hex_uint32(value,&o.scope.rx))return false;
            o.rx=true;
        }else if(flag=="--budget-ms"){
            if(o.budget||!decimal_uint32(value,&o.scope.budget))return false;
            o.budget=true;
        }else if(flag=="--vin"){
            if(!o.scope.vin.empty())return false;
            o.scope.vin=value;
        }else{
            const char** slot=nullptr;
            if(flag=="--port")slot=&o.port;
            else if(flag=="--key-file")slot=&o.key;
            else if(flag=="--journal")slot=&o.journal;
            else if(flag=="--output")slot=&o.report;
            if(!slot||*slot)return false;
            *slot=value;
        }
    }
    return o.operation&&o.tx&&o.rx&&o.budget&&o.confirmed&&o.port&&o.key&&o.journal&&o.report&&
        (mode!=cd::cu45::Mode::maintenance||o.warning)&&cd::cu45::valid_scope(o.scope)&&
        strcmp(o.key,o.journal)&&strcmp(o.key,o.report)&&strcmp(o.journal,o.report);
}
static int capture_cu45(int argc,char** argv,cd::cu45::Mode mode){
    Cu45Options o;
    if(!parse_cu45(argc,argv,o,mode))return error("Explicit primary ECM scope, journal, key, report, budget and confirmation required");
    if(!isatty(STDIN_FILENO))return error("Interactive local approval required");
    if(mode==cd::cu45::Mode::maintenance){
        fputs("WARNING: clearing emissions DTCs can erase diagnostic and readiness data; EVAP is an active test. "
              "Use only the stationary, authorized vehicle, with manufacturer safety conditions verified. "
              "No automatic retry. Type AUTHORIZE MAINTENANCE: ",stderr);
    }else fputs("Read-only recovery of an existing journal, no maintenance command. Type READ ARCHIVE: ",stderr);
    char answer[64]{};
    const char* expected=mode==cd::cu45::Mode::maintenance?"AUTHORIZE MAINTENANCE\n":"READ ARCHIVE\n";
    if(!fgets(answer,sizeof(answer),stdin)||strcmp(answer,expected))return error("Explicit approval refused");
    struct Key{
        std::array<uint8_t,32> data{};
        ~Key(){OPENSSL_cleanse(data.data(),data.size());}
    } key;
    if(!read_key_file(o.key,key.data.data()))return error("Private 32-byte device credential required");
    // Reserve both destinations before the proof can reach the connector.
    const int fd=open(o.report,O_WRONLY|O_CREAT|O_EXCL|O_NOFOLLOW|O_CLOEXEC,0600);
    if(fd<0)return error("Report destination unavailable");
    struct File{int fd;~File(){if(fd>=0)close(fd);}} out{fd};
    legacy_cancelled=0;
    struct Signals{
        struct sigaction oldint{},oldterm{};
        bool intr{},term{};
        Signals(){
            struct sigaction act{};act.sa_handler=legacy_signal;sigemptyset(&act.sa_mask);
            intr=sigaction(SIGINT,&act,&oldint)==0;
            term=intr&&sigaction(SIGTERM,&act,&oldterm)==0;
        }
        ~Signals(){if(term)sigaction(SIGTERM,&oldterm,nullptr);if(intr)sigaction(SIGINT,&oldint,nullptr);}
    } signals;
    try{
        cd::cu45::Result r{};r.scope=o.scope;r.mode=mode;
        if(signals.term)r=cd::cu45::capture_serial(mode,o.scope,key.data,o.port,o.journal,true,[]{return !legacy_cancelled;});
        else r.error="SIGNAL_HANDLERS_UNAVAILABLE";
        OPENSSL_cleanse(key.data.data(),key.data.size());
        input first{},second{};
        std::string digest;
        if(r.file_created&&!r.journal.empty()){
            const bool stable=load_regular(o.journal,cd::cm1::max_journal,&first)&&
                first.length==r.journal.size()&&!memcmp(first.bytes.get(),r.journal.data(),first.length)&&
                load_regular(o.journal,cd::cm1::max_journal,&second)&&same_input(&first,&second);
            if(stable)digest=first.digest;
            else{
                r.inspection.reset();
                if(r.error.empty())r.error="PERSISTED_JOURNAL_MISSING_OR_CHANGED";
            }
        }else if(r.verified()){
            r.inspection.reset();r.error="NO_PERSISTED_JOURNAL";
        }
        if(legacy_cancelled){
            r.cancelled=true;r.inspection.reset();
            if(r.error.empty())r.error="CONTEXT_REVOKED";
        }
        auto report=cd::cu45::report(r,digest);
        std::size_t offset=0;
        while(offset<report.size()){
            const auto n=write(out.fd,report.data()+offset,report.size()-offset);
            if(n<0&&errno==EINTR)continue;
            if(n<=0)return error("Report write failed; inspect retained journal, do not retry");
            offset+=static_cast<std::size_t>(n);
        }
        if(fsync(out.fd))return error("Report persistence failed; do not retry");
        const auto closed=close(out.fd);out.fd=-1;
        if(closed)return error("Report close failed; do not retry");
        if(!r.verified()||r.recovery_required())return 2;
        if(mode==cd::cu45::Mode::maintenance){
            const auto terminal=r.inspection->terminal;
            return terminal&&(terminal->outcome==5||terminal->outcome==6)&&terminal->reason==0?0:2;
        }
        return 0;
    }catch(const std::exception& e){return error(e.what());}
}

struct LegacyFreezeOptions{
    cd::legacy::Protocol protocol{};
    unsigned source{};
    uint32_t baud{38400};
    cd::legacy::FreezePlan plan;
    const char* port{},*capture{},*output{};
    bool protocol_set{},source_set{},baud_set{},budget_set{},approved{};
};
static bool parse_legacy_freeze(int argc,char** argv,LegacyFreezeOptions& o){
    for(int i=2;i<argc;++i){
        const std::string_view flag=argv[i];
        if(flag=="--confirm-physical-channel"){
            if(o.approved)return false;
            o.approved=true;continue;
        }
        if(i+1>=argc||argv[i+1][0]=='-')return false;
        const char* value=argv[++i];
        if(!*value)return false;
        if(flag=="--protocol"){
            uint32_t n=0;
            if(o.protocol_set||!decimal_uint32(value,&n))return false;
            o.protocol=static_cast<cd::legacy::Protocol>(n);o.protocol_set=true;
        }else if(flag=="--source"){
            uint32_t n=0;
            if(o.source_set||strlen(value)!=2||!hex_uint32(value,&n)||!cd::legacy::valid_source(n))return false;
            o.source=n;o.source_set=true;
        }else if(flag=="--baud"){
            if(o.baud_set||!decimal_uint32(value,&o.baud))return false;
            o.baud_set=true;
        }else if(flag=="--budget-ms"){
            uint32_t n=0;
            if(o.budget_set||!decimal_uint32(value,&n))return false;
            o.plan.budget_ms=n;o.budget_set=true;
        }else if(flag=="--vin"){
            if(!o.plan.vin.empty())return false;
            o.plan.vin=value;
        }else if(flag=="--frame"||flag=="--pid"){
            uint32_t n=0;
            if(strlen(value)!=2||!hex_uint32(value,&n)||n>255)return false;
            auto& list=flag=="--frame"?o.plan.frames:o.plan.pids;
            if(list.size()>=(flag=="--frame"?8u:64u))return false;
            list.push_back(static_cast<uint8_t>(n));
        }else{
            const char** slot=nullptr;
            if(flag=="--port")slot=&o.port;
            else if(flag=="--capture")slot=&o.capture;
            else if(flag=="--output")slot=&o.output;
            if(!slot||*slot)return false;
            *slot=value;
        }
    }
    return o.approved&&o.protocol_set&&o.source_set&&o.port&&o.capture&&o.output&&
        strcmp(o.capture,o.output)&&cd::legacy::valid_protocol(o.protocol)&&o.plan.valid();
}
static bool write_owned_file(int& fd,const void* bytes,std::size_t size){
    const auto* data=static_cast<const unsigned char*>(bytes);
    while(size){
        const auto n=write(fd,data,size);
        if(n<0&&errno==EINTR)continue;
        if(n<=0)return false;
        data+=n;size-=static_cast<std::size_t>(n);
    }
    const bool synced=fsync(fd)==0;
    const bool closed=close(fd)==0;
    fd=-1;return synced&&closed;
}
static int legacy_freeze_cli(int argc,char** argv){
    LegacyFreezeOptions o;
    if(!parse_legacy_freeze(argc,argv,o))return error("Explicit selected legacy ECU, VIN, frames, capture and report required");
    if(!isatty(STDIN_FILENO))return error("Interactive confirmation required");
    fputs("Legacy freeze: functional reads only, selected checksum-verified response source, stationary vehicle. Type yes: ",stderr);
    char answer[16]{};
    if(!fgets(answer,sizeof(answer),stdin)||strcmp(answer,"yes\n"))return error("Consent refused");
    struct File{int fd;~File(){if(fd>=0)close(fd);}};
    File capture{open(o.capture,O_WRONLY|O_CREAT|O_EXCL|O_NOFOLLOW|O_CLOEXEC,0600)};
    if(capture.fd<0)return error("Capture destination unavailable");
    File output{open(o.output,O_WRONLY|O_CREAT|O_EXCL|O_NOFOLLOW|O_CLOEXEC,0600)};
    if(output.fd<0)return error("Report destination unavailable");
    legacy_cancelled=0;
    struct Signals{
        struct sigaction oldint{},oldterm{};
        bool intr{},term{};
        Signals(){
            struct sigaction act{};act.sa_handler=legacy_signal;sigemptyset(&act.sa_mask);
            intr=sigaction(SIGINT,&act,&oldint)==0;
            term=intr&&sigaction(SIGTERM,&act,&oldterm)==0;
        }
        ~Signals(){if(term)sigaction(SIGTERM,&oldterm,nullptr);if(intr)sigaction(SIGINT,&oldint,nullptr);}
    } signals;
    try{
        if(!signals.term)return error("Cancellation handlers unavailable");
        const auto current=[](){return legacy_cancelled==0;};
        const auto journal_path=std::string(o.capture)+".lj";
        cd::legacy::CaptureJournal journal(journal_path.c_str(),cd::legacy::FreezeCapture{o.protocol,o.source,o.plan,{}});
        cd::legacy::FreezeResult result;
        {
            auto link=cd::legacy::serial_link(o.port,o.baud,current);
            cd::legacy::Elm elm(*link,o.protocol,true,current);
            elm.configure();
            result=cd::legacy::acquire_freeze(elm,o.source,o.plan,cd::legacy::monotonic_ms,current,[&](const auto& row){journal.append(row);});
        }
        journal.finish();
        const auto raw=cd::legacy::encode_freeze_capture({o.protocol,o.source,o.plan,result.trace});
        if(!journal_matches(journal_path,o.protocol,o.source,o.plan.vin,raw))return error("Legacy journal differs from capture");
        if(!write_owned_file(capture.fd,raw.data(),raw.size()))return error("Freeze capture persistence failed");
        input original{},again{};
        if(!load_regular(o.capture,cd::legacy::freeze_max_capture,&original)||original.length!=raw.size()||
           memcmp(original.bytes.get(),raw.data(),raw.size())||
           !load_regular(o.capture,cd::legacy::freeze_max_capture,&again)||!same_input(&original,&again))
            return error("Persisted legacy freeze capture changed");
        const auto replay=cd::legacy::inspect_freeze_capture({original.bytes.get(),original.length},o.protocol,o.source,o.plan.vin);
        const auto report=cd::legacy::freeze_report(replay);
        if(report!=cd::legacy::freeze_report(result))return error("Live result differs from independent raw replay");
        if(!write_owned_file(output.fd,report.data(),report.size()))return error("Freeze report persistence failed");
        return replay.completed&&replay.all_frames_stable?0:2;
    }catch(const std::exception& e){return error(e.what());}
}
static int legacy_freeze_replay(const char* protocol,const char* source,const char* vin,
    const char* capture_path,const char* report_path,const char* proof_path){
    uint32_t p=0,n=0;
    if(!decimal_uint32(protocol,&p)||!cd::legacy::valid_protocol(static_cast<cd::legacy::Protocol>(p))||
        strlen(source)!=2||!hex_uint32(source,&n)||!cd::legacy::valid_source(n)||!cf1_valid_vin(vin))
        return error("Independent protocol, selected source and VIN required");
    input original{},again{},stored{},stored_again{};
    try{
        if(!load_regular(capture_path,cd::legacy::freeze_max_capture,&original))
            return error("Invalid bounded LFR/1 capture");
        const auto replay=cd::legacy::inspect_freeze_capture({original.bytes.get(),original.length},
            static_cast<cd::legacy::Protocol>(p),n,vin);
        const auto canonical=cd::legacy::freeze_report(replay);
        if(proof_path){
            if(!load_regular(report_path,cd::legacy::freeze_max_capture*6,&stored)||!separate(&original,&stored)||
                stored.length!=canonical.size()||memcmp(stored.bytes.get(),canonical.data(),canonical.size())||
                !load_regular(report_path,cd::legacy::freeze_max_capture*6,&stored_again)||!same_input(&stored,&stored_again))
                return error("Legacy freeze report differs from independent replay");
        }
        if(!load_regular(capture_path,cd::legacy::freeze_max_capture,&again)||!same_input(&original,&again))
            return error("Legacy freeze capture changed during replay");
        if(proof_path){
            const auto proof=std::string("{\"schema\":\"camarodiag-j1979-legacy-freeze-verification-linux-v1\","
                "\"consistent\":true,\"physical_vehicle_verified\":false,\"capture_sha256\":\"")+original.digest+
                "\",\"report_sha256\":\""+stored.digest+"\",\"completed\":"+(replay.completed?"true":"false")+
                ",\"all_frames_stable\":"+(replay.all_frames_stable?"true":"false")+
                ",\"permits_programming\":false}\n";
            if(!atomic_create(proof_path,proof.data(),proof.size()))return error("Legacy freeze proof destination unavailable");
        }else if(!atomic_create(report_path,canonical.data(),canonical.size()))return error("Legacy freeze report destination unavailable");
        return replay.completed&&replay.all_frames_stable?0:2;
    }catch(const std::exception& e){return error(e.what());}
}

struct LegacyLiveOptions{
    cd::legacy::Protocol protocol{};
    unsigned source{};
    uint32_t baud{38400};
    cd::legacy::LivePlan plan;
    const char* port{},*capture{},*output{};
    bool protocol_set{},source_set{},baud_set{},budget_set{},approved{},cycles_set{},interval_set{};
};
static bool parse_legacy_live(int argc,char** argv,LegacyLiveOptions& o){
    for(int i=2;i<argc;++i){
        const std::string_view flag=argv[i];
        if(flag=="--raw-only"){
            if(o.plan.raw_only)return false;
            o.plan.raw_only=true;continue;
        }
        if(flag=="--confirm-physical-channel"){
            if(o.approved)return false;
            o.approved=true;continue;
        }
        if(i+1>=argc||argv[i+1][0]=='-')return false;
        const char* value=argv[++i];
        if(!*value)return false;
        if(flag=="--protocol"){
            uint32_t n=0;
            if(o.protocol_set||!decimal_uint32(value,&n))return false;
            o.protocol=static_cast<cd::legacy::Protocol>(n);o.protocol_set=true;
        }else if(flag=="--source"){
            uint32_t n=0;
            if(o.source_set||strlen(value)!=2||!hex_uint32(value,&n)||!cd::legacy::valid_source(n))return false;
            o.source=n;o.source_set=true;
        }else if(flag=="--baud"){
            if(o.baud_set||!decimal_uint32(value,&o.baud))return false;
            o.baud_set=true;
        }else if(flag=="--budget-ms"){
            uint32_t n=0;
            if(o.budget_set||!decimal_uint32(value,&n))return false;
            o.plan.budget_ms=n;o.budget_set=true;
        }else if(flag=="--vin"){
            if(!o.plan.vin.empty())return false;
            o.plan.vin=value;
        }else if(flag=="--cycles"||flag=="--interval-ms"){
            auto& seen=flag=="--cycles"?o.cycles_set:o.interval_set;
            uint32_t n=0;if(seen||!decimal_uint32(value,&n))return false;
            seen=true;if(flag=="--cycles")o.plan.cycles=n;else o.plan.interval_ms=n;
        }else if(flag=="--pid"){
            uint32_t n=0;
            if(strlen(value)!=2||!hex_uint32(value,&n)||n>255)return false;
            auto& list=o.plan.pids;
            if(list.size()>=32)return false;
            list.push_back(static_cast<uint8_t>(n));
        }else{
            const char** slot=nullptr;
            if(flag=="--port")slot=&o.port;
            else if(flag=="--capture")slot=&o.capture;
            else if(flag=="--output")slot=&o.output;
            if(!slot||*slot)return false;
            *slot=value;
        }
    }
    return o.approved&&o.protocol_set&&o.source_set&&o.port&&o.capture&&o.output&&
        strcmp(o.capture,o.output)&&cd::legacy::valid_protocol(o.protocol)&&o.plan.valid();
}
static int legacy_live_cli(int argc,char** argv){
    LegacyLiveOptions o;
    if(!parse_legacy_live(argc,argv,o))return error("Explicit selected legacy ECU, VIN, PIDs, capture and report required");
    if(!isatty(STDIN_FILENO))return error("Interactive confirmation required");
    fputs("Legacy live: functional reads only, selected checksum-verified response source, stationary vehicle. Type yes: ",stderr);
    char answer[16]{};
    if(!fgets(answer,sizeof(answer),stdin)||strcmp(answer,"yes\n"))return error("Consent refused");
    struct File{int fd;~File(){if(fd>=0)close(fd);}};
    File capture{open(o.capture,O_WRONLY|O_CREAT|O_EXCL|O_NOFOLLOW|O_CLOEXEC,0600)};
    if(capture.fd<0)return error("Capture destination unavailable");
    File output{open(o.output,O_WRONLY|O_CREAT|O_EXCL|O_NOFOLLOW|O_CLOEXEC,0600)};
    if(output.fd<0)return error("Report destination unavailable");
    legacy_cancelled=0;
    struct Signals{
        struct sigaction oldint{},oldterm{};
        bool intr{},term{};
        Signals(){
            struct sigaction act{};act.sa_handler=legacy_signal;sigemptyset(&act.sa_mask);
            intr=sigaction(SIGINT,&act,&oldint)==0;
            term=intr&&sigaction(SIGTERM,&act,&oldterm)==0;
        }
        ~Signals(){if(term)sigaction(SIGTERM,&oldterm,nullptr);if(intr)sigaction(SIGINT,&oldint,nullptr);}
    } signals;
    try{
        if(!signals.term)return error("Cancellation handlers unavailable");
        const auto current=[](){return legacy_cancelled==0;};
        const auto journal_path=std::string(o.capture)+".lj";
        cd::legacy::CaptureJournal journal(journal_path.c_str(),cd::legacy::LiveCapture{o.protocol,o.source,o.plan,{}});
        cd::legacy::LiveResult result;
        {
            auto link=cd::legacy::serial_link(o.port,o.baud,current);
            cd::legacy::Elm elm(*link,o.protocol,true,current);
            elm.configure();
            result=cd::legacy::acquire_live(elm,o.source,o.plan,cd::legacy::monotonic_ms,current,[](unsigned ms){
                struct timespec delay{static_cast<time_t>(ms/1000),static_cast<long>((ms%1000)*1000000)};
                nanosleep(&delay,nullptr);
            },[&](const auto& row){journal.append(row);});
        }
        journal.finish();
        const auto raw=cd::legacy::encode_live_capture({o.protocol,o.source,o.plan,result.trace});
        if(!journal_matches(journal_path,o.protocol,o.source,o.plan.vin,raw))return error("Legacy journal differs from capture");
        if(!write_owned_file(capture.fd,raw.data(),raw.size()))return error("Live capture persistence failed");
        input original{},again{};
        if(!load_regular(o.capture,cd::legacy::live_max_capture,&original)||original.length!=raw.size()||
           memcmp(original.bytes.get(),raw.data(),raw.size())||
           !load_regular(o.capture,cd::legacy::live_max_capture,&again)||!same_input(&original,&again))
            return error("Persisted legacy live capture changed");
        const auto replay=cd::legacy::inspect_live_capture({original.bytes.get(),original.length},o.protocol,o.source,o.plan.vin);
        const auto report=cd::legacy::live_report(replay);
        if(report!=cd::legacy::live_report(result))return error("Live result differs from independent raw replay");
        if(!write_owned_file(output.fd,report.data(),report.size()))return error("Live report persistence failed");
        return replay.completed&&replay.identity_rechecked?0:2;
    }catch(const std::exception& e){return error(e.what());}
}
static int legacy_live_replay(const char* protocol,const char* source,const char* vin,
    const char* capture_path,const char* report_path,const char* proof_path){
    uint32_t p=0,n=0;
    if(!decimal_uint32(protocol,&p)||!cd::legacy::valid_protocol(static_cast<cd::legacy::Protocol>(p))||
        strlen(source)!=2||!hex_uint32(source,&n)||!cd::legacy::valid_source(n)||!cf1_valid_vin(vin))
        return error("Independent protocol, selected source and VIN required");
    input original{},again{},stored{},stored_again{};
    try{
        if(!load_regular(capture_path,cd::legacy::live_max_capture,&original))
            return error("Invalid bounded LLR/1 capture");
        const auto replay=cd::legacy::inspect_live_capture({original.bytes.get(),original.length},
            static_cast<cd::legacy::Protocol>(p),n,vin);
        const auto canonical=cd::legacy::live_report(replay);
        if(proof_path){
            if(!load_regular(report_path,cd::legacy::live_max_capture*6,&stored)||!separate(&original,&stored)||
                stored.length!=canonical.size()||memcmp(stored.bytes.get(),canonical.data(),canonical.size())||
                !load_regular(report_path,cd::legacy::live_max_capture*6,&stored_again)||!same_input(&stored,&stored_again))
                return error("Legacy live report differs from independent replay");
        }
        if(!load_regular(capture_path,cd::legacy::live_max_capture,&again)||!same_input(&original,&again))
            return error("Legacy live capture changed during replay");
        if(proof_path){
            const auto proof=std::string("{\"schema\":\"camarodiag-j1979-legacy-live-verification-linux-v1\","
                "\"consistent\":true,\"physical_vehicle_verified\":false,\"capture_sha256\":\"")+original.digest+
                "\",\"report_sha256\":\""+stored.digest+"\",\"completed\":"+(replay.completed?"true":"false")+
                ",\"identity_rechecked\":"+(replay.identity_rechecked?"true":"false")+
                ",\"permits_programming\":false}\n";
            if(!atomic_create(proof_path,proof.data(),proof.size()))return error("Legacy live proof destination unavailable");
        }else if(!atomic_create(report_path,canonical.data(),canonical.size()))return error("Legacy live report destination unavailable");
        return replay.completed&&replay.identity_rechecked?0:2;
    }catch(const std::exception& e){return error(e.what());}
}

static int legacy_scan_replay(const char* protocol,const char* source,const char* vin,
    const char* capture_path,const char* report_path,const char* proof_path){
    uint32_t p=0,n=0;
    if(!decimal_uint32(protocol,&p)||!cd::legacy::valid_protocol(static_cast<cd::legacy::Protocol>(p))||
        strlen(source)!=2||!hex_uint32(source,&n)||!cd::legacy::valid_source(n)||!cf1_valid_vin(vin))
        return error("Independent protocol, selected source and VIN required");
    input original{},again{},stored{},stored_again{};
    try{
        if(!load_regular(capture_path,cd::legacy::scan_max_capture,&original))
            return error("Invalid bounded LSR/1 capture");
        const auto replay=cd::legacy::inspect_scan_capture({original.bytes.get(),original.length},
            static_cast<cd::legacy::Protocol>(p),n,vin);
        const auto canonical=cd::legacy::scan_report(replay);
        if(proof_path){
            if(!load_regular(report_path,cd::legacy::scan_max_capture*6,&stored)||!separate(&original,&stored)||
                stored.length!=canonical.size()||memcmp(stored.bytes.get(),canonical.data(),canonical.size())||
                !load_regular(report_path,cd::legacy::scan_max_capture*6,&stored_again)||!same_input(&stored,&stored_again))
                return error("Legacy scan report differs from independent replay");
        }
        if(!load_regular(capture_path,cd::legacy::scan_max_capture,&again)||!same_input(&original,&again))
            return error("Legacy scan capture changed during replay");
        if(proof_path){
            const auto proof=std::string("{\"schema\":\"camarodiag-j1979-legacy-scan-verification-linux-v1\","
                "\"consistent\":true,\"physical_vehicle_verified\":false,\"capture_sha256\":\"")+original.digest+
                "\",\"report_sha256\":\""+stored.digest+"\",\"completed\":"+(replay.completed?"true":"false")+
                ",\"identity_rechecked\":"+(replay.identity_rechecked?"true":"false")+
                ",\"permits_programming\":false}\n";
            if(!atomic_create(proof_path,proof.data(),proof.size()))return error("Legacy scan proof destination unavailable");
        }else if(!atomic_create(report_path,canonical.data(),canonical.size()))return error("Legacy scan report destination unavailable");
        return replay.completed&&replay.identity_rechecked?0:2;
    }catch(const std::exception& e){return error(e.what());}
}

struct LegacyMaintenanceOptions{
    cd::legacy::MutationPlan plan;
    const char* port{},*baseline{},*intent{},*output{};
    uint32_t baud{38400};
    bool protocol{},operation{},engine{},baud_set{},budget_set{},confirmed{},warning{};
};
static bool parse_legacy_maintenance(int argc,char** argv,LegacyMaintenanceOptions& o){
    for(int i=2;i<argc;++i){
        const std::string_view flag=argv[i];
        if(flag=="--confirm-functional-scope"||flag=="--acknowledge-maintenance-warning"){
            auto& seen=flag=="--confirm-functional-scope"?o.confirmed:o.warning;
            if(seen)return false;
            seen=true;continue;
        }
        if(i+1>=argc||argv[i+1][0]=='-'||!*argv[i+1])return false;
        const char* value=argv[++i];
        if(flag=="--protocol"){
            uint32_t n=0;if(o.protocol||!decimal_uint32(value,&n)||n<1||n>5)return false;
            o.protocol=true;o.plan.protocol=static_cast<cd::legacy::Protocol>(n);
        }else if(flag=="--operation"){
            if(o.operation)return false;
            o.operation=true;
            if(!strcmp(value,"clear"))o.plan.operation=cd::legacy::MutationOperation::clear;
            else if(!strcmp(value,"evap"))o.plan.operation=cd::legacy::MutationOperation::evap;
            else return false;
        }else if(flag=="--engine-source"||flag=="--source"){
            uint32_t n=0;
            if(strlen(value)!=2||!hex_uint32(value,&n)||!cd::legacy::valid_source(n))return false;
            if(flag=="--engine-source"){
                if(o.engine)return false;
                o.engine=true;o.plan.engine=n;
            }else if(o.plan.sources.size()>=32||!o.plan.sources.insert(n).second)return false;
        }else if(flag=="--vin"){
            if(!o.plan.vin.empty())return false;
            o.plan.vin=value;
        }else if(flag=="--budget-ms"||flag=="--baud"){
            auto& seen=flag=="--baud"?o.baud_set:o.budget_set;
            uint32_t n=0;if(seen||!decimal_uint32(value,&n))return false;
            seen=true;if(flag=="--baud")o.baud=n;else o.plan.budget_ms=n;
        }else{
            const char** slot=nullptr;
            if(flag=="--port")slot=&o.port;
            else if(flag=="--baseline")slot=&o.baseline;
            else if(flag=="--intent")slot=&o.intent;
            else if(flag=="--output")slot=&o.output;
            if(!slot||*slot)return false;
            *slot=value;
        }
    }
    return o.protocol&&o.operation&&o.engine&&o.confirmed&&o.warning&&o.port&&o.baseline&&o.intent&&o.output&&
        strcmp(o.baseline,o.intent)&&strcmp(o.baseline,o.output)&&strcmp(o.intent,o.output)&&o.plan.valid();
}
static bool sync_parent(const char* path){
    const std::string name(path);
    const auto slash=name.find_last_of('/');
    const auto dir=slash==std::string::npos?std::string("."):slash==0?std::string("/"):name.substr(0,slash);
    const int fd=open(dir.c_str(),O_RDONLY|O_DIRECTORY|O_CLOEXEC);
    if(fd<0)return false;
    const bool synced=fsync(fd)==0;const bool closed=close(fd)==0;return synced&&closed;
}
static int legacy_maintenance_cli(int argc,char** argv){
    LegacyMaintenanceOptions o;
    if(!parse_legacy_maintenance(argc,argv,o))return error("Explicit functional scope, documented ECU set, VIN, separate journals and warnings required");
    if(!isatty(STDIN_FILENO))return error("Interactive local consent required");
    fputs("WARNING: functional legacy requests may affect ECUs outside the observed set. "
        "Clearing DTCs can erase diagnostic and readiness data; EVAP is an active test. "
        "Verify manufacturer safety conditions and authorized stationary vehicle. "
        "No automatic retry. Type AUTHORIZE VEHICLE WIDE: ",stderr);
    char answer[64]{};
    if(!fgets(answer,sizeof(answer),stdin)||strcmp(answer,"AUTHORIZE VEHICLE WIDE\n"))return error("Consent refused");
    struct File{
        const char* path;
        int fd{-1};
        explicit File(const char* p):path(p),fd(open(p,O_WRONLY|O_CREAT|O_EXCL|O_NOFOLLOW|O_CLOEXEC,0600)){}
        ~File(){if(fd>=0)close(fd);}
        input saved{};
        bool unchanged() const {
            input fresh{};
            return saved.length&&load_regular(path,saved.length,&fresh)&&same_input(&saved,&fresh);
        }
        bool persist(std::string_view text){
            struct stat identity{};
            if(fd<0||text.empty()||fstat(fd,&identity)||!S_ISREG(identity.st_mode)||
                !write_owned_file(fd,text.data(),text.size())||!sync_parent(path))return false;
            if(!load_regular(path,text.size(),&saved)||saved.length!=text.size()||
                saved.device!=identity.st_dev||saved.inode!=identity.st_ino||
                memcmp(saved.bytes.get(),text.data(),text.size()))return false;
            return unchanged();
        }
    };
    // All three unique destinations must be reserved before adapter access.
    File baseline(o.baseline),intent(o.intent),output(o.output);
    if(baseline.fd<0||intent.fd<0||output.fd<0)return error("Maintenance journal paths unavailable");
    legacy_cancelled=0;
    struct Signals{
        struct sigaction oldint{},oldterm{};
        bool intr{},term{};
        Signals(){
            struct sigaction act{};act.sa_handler=legacy_signal;sigemptyset(&act.sa_mask);
            intr=sigaction(SIGINT,&act,&oldint)==0;
            term=intr&&sigaction(SIGTERM,&act,&oldterm)==0;
        }
        ~Signals(){if(term)sigaction(SIGTERM,&oldterm,nullptr);if(intr)sigaction(SIGINT,&oldint,nullptr);}
    } signals;
    cd::legacy::MutationResult r{};r.plan=o.plan;
    try{
        if(!signals.term)throw cd::legacy::TransportError("SIGNAL_HANDLERS_UNAVAILABLE");
        const auto current=[](){return legacy_cancelled==0;};
        auto link=cd::legacy::serial_link(o.port,o.baud,current);
        cd::legacy::Elm elm(*link,o.plan.protocol,true,current);elm.configure();
        cd::legacy::MutationSession session(elm);
        r=session.run(o.plan,true,true,cd::legacy::monotonic_ms,current,
            [&](std::string_view stage,const cd::legacy::MutationResult& observed){
                auto snapshot=observed;snapshot.outcome=stage;
                auto& file=stage=="BASELINE"?baseline:intent;
                if((stage!="BASELINE"&&!baseline.unchanged())||
                    !file.persist(cd::legacy::mutation_report(snapshot))||
                    (stage!="BASELINE"&&!baseline.unchanged()))
                    throw cd::legacy::TransportError("DURABLE_JOURNAL_WRITE_FAILED");
            });
    }catch(const std::exception& e){r.reason=e.what();}
    try{
        const auto report=cd::legacy::mutation_report(r);
        if(!output.persist(report))return error("Final maintenance report failed; retain intent and never retry automatically");
        return (r.outcome=="ACKNOWLEDGED_READBACK"&&r.reason.empty())||r.outcome=="ACKNOWLEDGED"?0:2;
    }catch(const std::exception& e){return error(e.what());}
}

struct NetworkOptions{
    const char* interface{},*output{},*vin{};
    unsigned window_ms{1500},budget_ms{180000};
    bool extended{},confirmed{},window_set{},budget_set{};
    std::vector<std::uint32_t> ids;
};
static bool parse_network(int argc,char** argv,NetworkOptions& o,bool scan){
    for(int i=2;i<argc;++i){
        const std::string_view flag=argv[i];
        if(flag=="--extended"||flag=="--confirm-functional-scope"){
            auto& seen=flag=="--extended"?o.extended:o.confirmed;
            if(seen)return false;
            seen=true;continue;
        }
        if(i+1>=argc||argv[i+1][0]=='-'||!*argv[i+1])return false;
        const char* value=argv[++i];
        if(flag=="--interface"){if(o.interface)return false;o.interface=value;}
        else if(flag=="--output"){if(o.output)return false;o.output=value;}
        else if(flag=="--vin"&&scan){if(o.vin||!cf1_valid_vin(value))return false;o.vin=value;}
        else if(flag=="--window-ms"){
            uint32_t n=0;if(o.window_set||!decimal_uint32(value,&n)||n<1||n>30000)return false;
            o.window_set=true;o.window_ms=n;
        }else if(flag=="--budget-ms"&&scan){
            uint32_t n=0;if(o.budget_set||!decimal_uint32(value,&n)||n<1||n>600000)return false;
            o.budget_set=true;o.budget_ms=n;
        }else if(flag=="--rx"&&scan){
            uint32_t n=0;
            if(o.ids.size()>=cd::network::max_targets||!hex_uint32(value,&n)||
               std::find(o.ids.begin(),o.ids.end(),n)!=o.ids.end())return false;
            o.ids.push_back(n);
        }else return false;
    }
    return o.interface&&j1979_isotp_valid_interface(o.interface)&&o.output&&o.confirmed&&
        (!scan||(o.vin&&!o.ids.empty()&&std::all_of(o.ids.begin(),o.ids.end(),[&](auto id){
            return cd::network::response_pair(id,o.extended).has_value();
        })));
}
static int network_cli(int argc,char** argv,bool scan){
    NetworkOptions o;
    if(!parse_network(argc,argv,o,scan))return error("Explicit CAN interface, addressing, scope, output and selected responders required");
    if(!isatty(STDIN_FILENO))return error("Interactive local consent required");
    fputs(scan?"Confirm stationary authorized vehicle, one functional PID00 discovery and subsequent selected physical read-only scans. Type yes: ":
        "Confirm stationary authorized vehicle and a single functional PID00 read on selected classic CAN interface. Type yes: ",stderr);
    char answer[16]{};
    if(!fgets(answer,sizeof(answer),stdin)||strcmp(answer,"yes\n"))return error("Consent refused");
    struct File{int fd;~File(){if(fd>=0)close(fd);}} output{
        open(o.output,O_WRONLY|O_CREAT|O_EXCL|O_NOFOLLOW|O_CLOEXEC,0600)};
    if(output.fd<0)return error("Network evidence destination unavailable");
    legacy_cancelled=0;
    struct Signals{
        struct sigaction oldint{},oldterm{};
        bool intr{},term{};
        Signals(){
            struct sigaction act{};act.sa_handler=legacy_signal;sigemptyset(&act.sa_mask);
            intr=sigaction(SIGINT,&act,&oldint)==0;
            term=intr&&sigaction(SIGTERM,&act,&oldterm)==0;
        }
        ~Signals(){if(term)sigaction(SIGTERM,&oldterm,nullptr);if(intr)sigaction(SIGINT,&oldint,nullptr);}
    } signals;
    try{
        if(!signals.term)return error("Cancellation handlers unavailable");
        const auto current=[](){return legacy_cancelled==0;};
        const auto discovery=cd::network::discover_socket(o.interface,o.extended,o.window_ms,current);
        std::string json;bool completed=false;
        if(scan){
            const auto result=cd::network::scan_selected(discovery,o.ids,o.vin,o.budget_ms,cd::legacy::monotonic_ms,
                current,[&](cd::network::Pair pair,unsigned budget){
                    return cd::network::scan_socket(pair,budget,o.interface,o.vin,&legacy_cancelled);
                });
            json=cd::network::report(result);completed=result.completed;
        }else{json=cd::network::report(discovery);completed=discovery.completed;}
        if(!write_owned_file(output.fd,json.data(),json.size()))return error("Network evidence persistence failed");
        return completed?0:2;
    }catch(const std::exception& e){return error(e.what());}
}

struct ReferenceStamp{
    dev_t device{};
    ino_t inode{};
    off_t size{};
    timespec modified{},changed{};
    std::string digest;
    bool same(const ReferenceStamp& other)const{
        return device==other.device&&inode==other.inode&&size==other.size&&digest==other.digest&&
            modified.tv_sec==other.modified.tv_sec&&modified.tv_nsec==other.modified.tv_nsec&&
            changed.tv_sec==other.changed.tv_sec&&changed.tv_nsec==other.changed.tv_nsec;
    }
    bool separate_from(const input& other)const{return device!=other.device||inode!=other.inode;}
};
static ReferenceStamp reference_stamp(const char* path){
    struct File{int fd;~File(){if(fd>=0)close(fd);}} file{open(path,O_RDONLY|O_NOFOLLOW|O_CLOEXEC|O_NONBLOCK)};
    struct stat first{},last{};
    if(file.fd<0||fstat(file.fd,&first)!=0||!S_ISREG(first.st_mode)||first.st_size<=0||
        static_cast<std::uint64_t>(first.st_size)>cd::catalogue::max_reference)
        throw std::invalid_argument("Bounded regular reference file required");
    std::unique_ptr<EVP_MD_CTX,decltype(&EVP_MD_CTX_free)> context(EVP_MD_CTX_new(),EVP_MD_CTX_free);
    if(!context||EVP_DigestInit_ex(context.get(),EVP_sha256(),nullptr)!=1)
        throw std::runtime_error("Reference digest unavailable");
    std::array<unsigned char,65536> block{};
    std::size_t total=0;
    for(;;){
        const auto n=read(file.fd,block.data(),block.size());
        if(n<0&&errno==EINTR)continue;
        if(n<0)throw std::runtime_error("Reference read failed");
        if(!n)break;
        const auto got=static_cast<std::size_t>(n);
        if(got>static_cast<std::size_t>(first.st_size)-total||
            EVP_DigestUpdate(context.get(),block.data(),got)!=1)throw std::runtime_error("Reference changed or digest failed");
        total+=got;
    }
    if(total!=static_cast<std::size_t>(first.st_size)||fstat(file.fd,&last)||first.st_dev!=last.st_dev||
        first.st_ino!=last.st_ino||first.st_size!=last.st_size||
        first.st_mtim.tv_sec!=last.st_mtim.tv_sec||first.st_mtim.tv_nsec!=last.st_mtim.tv_nsec||
        first.st_ctim.tv_sec!=last.st_ctim.tv_sec||first.st_ctim.tv_nsec!=last.st_ctim.tv_nsec)
        throw std::runtime_error("Reference changed during digest");
    std::array<unsigned char,EVP_MAX_MD_SIZE> digest{};
    unsigned size=0;
    if(EVP_DigestFinal_ex(context.get(),digest.data(),&size)!=1||size!=32)throw std::runtime_error("Reference digest failed");
    constexpr char alphabet[]="0123456789abcdef";
    std::string hex;hex.reserve(64);
    for(unsigned i=0;i<size;++i){hex+=alphabet[digest[i]>>4];hex+=alphabet[digest[i]&15];}
    return {first.st_dev,first.st_ino,first.st_size,first.st_mtim,first.st_ctim,std::move(hex)};
}
static bool recheck_input(const char* path,std::size_t max,const input& original){
    input again{};return load_regular(path,max,&again)&&same_input(&original,&again);
}
static int catalogue_inventory_cli(const char* mode,const char* catalogue_path,const char* reference_path,const char* pin,const char* output){
    const std::string_view kind=mode;
    if(kind!="live"&&kind!="freeze")return error("Catalogue mode must be live or freeze");
    try{
        if(!cd::catalogue::valid_digest(pin))return error("Independent lowercase reference SHA-256 required");
        input catalogue{};
        if(!load_regular(catalogue_path,cd::catalogue::max_catalogue,&catalogue))return error("Invalid catalogue file");
        const auto ref=reference_stamp(reference_path);
        if(ref.digest!=pin||!ref.separate_from(catalogue))return error("Reference does not match pin or aliases catalogue");
        const auto parsed=cd::catalogue::parse_catalogue(
            {reinterpret_cast<const char*>(catalogue.bytes.get()),catalogue.length},
            kind=="live"?cd::catalogue::Mode::live:cd::catalogue::Mode::freeze,pin);
        const auto json=cd::catalogue::inventory(parsed,catalogue.digest);
        if(!recheck_input(catalogue_path,cd::catalogue::max_catalogue,catalogue)||!ref.same(reference_stamp(reference_path)))
            return error("Catalogue/reference changed");
        if(!atomic_create(output,json.data(),json.size()))return error("Catalogue inventory destination unavailable");
        return 0;
    }catch(const std::exception& e){return error(e.what());}
}
static int catalogue_projection_cli(int argc,char** argv,bool verify){
    const int required=verify?10:9;
    if(argc!=required&&argc!=required+2)return error("Invalid catalogue projection arguments");
    const std::string_view kind=argv[2];
    const bool legacy=kind=="llr1";
    if((kind!="cl1"&&kind!="cf1"&&!legacy)||legacy!=(argc==required+2))
        return error("Explicit cl1, cf1 or llr1 source type required");
    const char* catalogue_path=argv[3],*reference_path=argv[4],*pin=argv[5],*capture_path=argv[6],
        *vin=argv[7],*output=argv[8],*proof=verify?argv[9]:nullptr;
    unsigned protocol=0,source=0;
    if(legacy){
        if(!decimal_uint32(argv[required],&protocol)||protocol<1||protocol>5||
            strlen(argv[required+1])!=2||!hex_uint32(argv[required+1],&source)||!cd::legacy::valid_source(source))
            return error("Legacy protocol and response source required");
    }
    if(!cd::catalogue::valid_digest(pin)||!cf1_valid_vin(vin))return error("Independent reference pin and VIN required");
    try{
        input catalogue{},capture{},stored{};
        const std::size_t capture_max=kind=="cf1"?static_cast<std::size_t>(CF1_MAX_BYTES):
            kind=="cl1"?static_cast<std::size_t>(CL1_MAX_CAPTURE):cd::legacy::live_max_capture;
        if(!load_regular(catalogue_path,cd::catalogue::max_catalogue,&catalogue)||
            !load_regular(capture_path,capture_max,&capture)||!separate(&catalogue,&capture))
            return error("Separate bounded catalogue and original capture required");
        const auto ref=reference_stamp(reference_path);
        if(ref.digest!=pin||!ref.separate_from(catalogue)||!ref.separate_from(capture))
            return error("Reference digest mismatch or input alias");
        const auto parsed=cd::catalogue::parse_catalogue(
            {reinterpret_cast<const char*>(catalogue.bytes.get()),catalogue.length},
            kind=="cf1"?cd::catalogue::Mode::freeze:cd::catalogue::Mode::live,pin);
        const cd::catalogue::Bytes raw{capture.bytes.get(),capture.length};
        const auto projected=kind=="cf1"?cd::catalogue::project_cf1(raw,vin,parsed,capture.digest,catalogue.digest):
            legacy?cd::catalogue::project_llr1(raw,vin,protocol,source,parsed,capture.digest,catalogue.digest):
            cd::catalogue::project_cl1(raw,vin,parsed,capture.digest,catalogue.digest);
        const auto canonical=cd::catalogue::report(parsed,projected);
        if(verify){
            if(!load_regular(output,cd::catalogue::max_projection,&stored)||!separate(&stored,&capture)||
                !separate(&stored,&catalogue)||!ref.separate_from(stored)||stored.length!=canonical.size()||
                memcmp(stored.bytes.get(),canonical.data(),canonical.size())||
                !recheck_input(output,cd::catalogue::max_projection,stored))
                return error("Stored projection differs from independently replayed canonical native report");
        }
        if(!recheck_input(catalogue_path,cd::catalogue::max_catalogue,catalogue)||
            !recheck_input(capture_path,capture_max,capture)||!ref.same(reference_stamp(reference_path)))
            return error("Projection inputs changed");
        if(verify){
            const auto result=std::string("{\"schema\":\"camarodiag-j1979-catalogue-verification-linux-v1\","
                "\"verified\":true,\"source_capture_sha256\":\"")+capture.digest+"\",\"catalogue_sha256\":\""+
                catalogue.digest+"\",\"reference_sha256\":\""+ref.digest+"\",\"projection_sha256\":\""+stored.digest+
                "\",\"normative_compliance_verified\":false,\"physical_vehicle_verified\":false}\n";
            if(!atomic_create(proof,result.data(),result.size()))return error("Projection proof destination unavailable");
        }else if(!atomic_create(output,canonical.data(),canonical.size()))return error("Projection destination unavailable");
        return 0;
    }catch(const std::exception& e){return error(e.what());}
}

struct DirectMaintenanceOptions {
    cd::hostmaint::Scope scope{};
    const char* interface{},*directory{},*journal{},*output{};
    bool operation{},tx{},rx{},budget{},extended{},vin{},confirmed{},warning{};
};
static bool parse_direct_maintenance(int argc,char** argv,DirectMaintenanceOptions& o){
    for(int i=2;i<argc;++i){
        const std::string_view flag=argv[i];
        if(flag=="--extended"||flag=="--confirm-physical-channel"||flag=="--acknowledge-maintenance-warning"){
            auto& seen=flag=="--extended"?o.extended:flag=="--confirm-physical-channel"?o.confirmed:o.warning;
            if(seen)return false;
            seen=true;continue;
        }
        if(i+1>=argc||!*argv[i+1]||argv[i+1][0]=='-')return false;
        const char* value=argv[++i];
        if(flag=="--operation"){
            if(o.operation)return false;
            o.operation=true;
            if(!strcmp(value,"clear"))o.scope.operation=cd::hostmaint::Scope::Operation::Clear;
            else if(!strcmp(value,"evap"))o.scope.operation=cd::hostmaint::Scope::Operation::Evap;
            else return false;
        }else if(flag=="--tx"||flag=="--rx"){
            auto& seen=flag=="--tx"?o.tx:o.rx;
            auto& id=flag=="--tx"?o.scope.tx:o.scope.rx;
            if(seen||!hex_uint32(value,&id))return false;
            seen=true;
        }else if(flag=="--budget-ms"){
            if(o.budget||!decimal_uint32(value,&o.scope.budgetMs))return false;
            o.budget=true;
        }else if(flag=="--vin"){
            if(o.vin||!cf1_valid_vin(value))return false;
            o.vin=true;memcpy(o.scope.vin,value,17);
        }else{
            const char** slot=nullptr;
            if(flag=="--interface")slot=&o.interface;
            else if(flag=="--journal-directory")slot=&o.directory;
            else if(flag=="--journal-name")slot=&o.journal;
            else if(flag=="--output")slot=&o.output;
            if(!slot||*slot)return false;
            *slot=value;
        }
    }
    o.scope.extended=o.extended;
    return o.operation&&o.tx&&o.rx&&o.budget&&o.vin&&o.confirmed&&o.warning&&o.directory&&o.journal&&
        o.output&&j1979_isotp_valid_interface(o.interface)&&o.scope.valid();
}
static int direct_maintenance_cli(int argc,char** argv){
    DirectMaintenanceOptions o;
    if(!parse_direct_maintenance(argc,argv,o))return error("Dedicated primary ECM, private journal directory, VIN and explicit maintenance authorization required");
    if(!isatty(STDIN_FILENO))return error("Interactive local approval required");
    fputs("WARNING: clearing DTCs erases diagnostic data and readiness; EVAP is an active test. "
        "Use only an authorized stationary vehicle with manufacturer conditions verified. "
        "Local socket closure is not proof of physical transceiver shutdown. "
        "The recovery marker requires review; never retry automatically. Type AUTHORIZE MAINTENANCE: ",stderr);
    char answer[64]{};
    if(!fgets(answer,sizeof(answer),stdin)||strcmp(answer,"AUTHORIZE MAINTENANCE\n"))return error("Consent refused");
    struct Output{int fd;~Output(){if(fd>=0)close(fd);}} out{
        open(o.output,O_WRONLY|O_CREAT|O_EXCL|O_NOFOLLOW|O_CLOEXEC,0600)};
    if(out.fd<0)return error("Report destination unavailable");
    legacy_cancelled=0;
    struct Signals{
        struct sigaction oldint{},oldterm{};
        bool intr{},term{};
        Signals(){
            struct sigaction act{};act.sa_handler=legacy_signal;sigemptyset(&act.sa_mask);
            intr=sigaction(SIGINT,&act,&oldint)==0;
            term=intr&&sigaction(SIGTERM,&act,&oldterm)==0;
        }
        ~Signals(){if(term)sigaction(SIGTERM,&oldterm,nullptr);if(intr)sigaction(SIGINT,&oldint,nullptr);}
    } signals;
    cd::hostmaint::Result r{};r.evidence.scope=o.scope;
    try{
        if(!signals.term)throw std::runtime_error("SIGNAL_HANDLERS_UNAVAILABLE");
        r=cd::hostmaint::run_socket(o.scope,o.interface,o.directory,o.journal,true,true,&legacy_cancelled);
    }catch(const std::exception& e){r.error=e.what();}
    try{
        std::string digest,replay;
        if(r.journal_created){
            const auto path=std::string(o.directory)+"/"+o.journal;
            const std::string vin(reinterpret_cast<const char*>(o.scope.vin),17);
            input raw{};
            if(load_regular(path.c_str(),cd::cm1::max_journal,&raw)){
                digest=raw.digest;
                try{
                    const auto evidence=cd::cm1::inspect({raw.bytes.get(),raw.length},vin,!r.evidence.terminalPersisted);
                    const cd::cm1::Scope expected{static_cast<unsigned>(o.scope.operation),o.scope.extended,o.scope.tx,o.scope.rx,o.scope.budgetMs,vin};
                    if(evidence.scope!=expected||!recheck_input(path.c_str(),cd::cm1::max_journal,raw))
                        throw std::runtime_error("JOURNAL_SCOPE_OR_BYTES_CHANGED");
                    if(r.evidence.terminalPersisted){
                        const auto& summary=r.evidence.summary;
                        if(!evidence.terminal||evidence.terminal->outcome!=static_cast<unsigned>(summary.outcome)||
                            evidence.terminal->reason!=static_cast<unsigned>(summary.reason)||
                            evidence.terminal->count!=r.evidence.count)
                            throw std::runtime_error("LIVE_AND_REPLAY_OUTCOME_DIFFER");
                    }
                    replay=cd::cm1::report(evidence,digest);
                }catch(const std::exception& e){r.recovery_latched=true;r.error=e.what();}
            }else{r.recovery_latched=true;r.error="JOURNAL_MISSING_OR_EMPTY";}
        }
        const auto json=cd::hostmaint::report(r,digest,replay);
        if(!write_owned_file(out.fd,json.data(),json.size())||!sync_parent(o.output))
            return error("Report persistence failed; recovery marker retained");
        using Outcome=cd::hostmaint::Campaign::Outcome;
        return r.error.empty()&&!r.recovery_latched&&r.evidence.terminalPersisted&&!replay.empty()&&
            (r.evidence.summary.outcome==Outcome::AcknowledgedReadback||
             r.evidence.summary.outcome==Outcome::AcknowledgedEvap)&&
            r.evidence.summary.reason==cd::hostmaint::Campaign::Reason::None?0:2;
    }catch(const std::exception& e){return error(e.what());}
}

struct ElmCanOptions{
    cd::elmcan::Scope scope;
    const char* port{},*vin{},*output{};
    unsigned baud{38400},budget{180000},cycles{30},interval{1000};
    std::vector<uint8_t> pids;
    bool profile{},tx{},rx{},confirmed{},baud_set{},budget_set{},cycles_set{},interval_set{};
};
static bool parse_elm_can(int argc,char** argv,ElmCanOptions& o,bool live){
    for(int i=2;i<argc;++i){
        std::string_view flag=argv[i];
        if(flag=="--confirm-physical-channel"){
            if(o.confirmed)return false;
            o.confirmed=true;continue;
        }
        if(i+1>=argc||!*argv[i+1]||argv[i+1][0]=='-')return false;
        const char* value=argv[++i];
        if(flag=="--tx"||flag=="--rx"){
            auto& seen=flag=="--tx"?o.tx:o.rx;
            auto& id=flag=="--tx"?o.scope.tx:o.scope.rx;
            if(seen||!hex_uint32(value,&id))return false;
            seen=true;
        }else if(flag=="--pid"&&live){
            uint32_t pid=0;
            if(o.pids.size()>=J1979_LIVE_MAX_PIDS||!hex_uint32(value,&pid)||!pid||pid>255||pid%32==0||
                std::find(o.pids.begin(),o.pids.end(),pid)!=o.pids.end())return false;
            o.pids.push_back(static_cast<uint8_t>(pid));
        }else if(flag=="--profile"||flag=="--baud"||flag=="--budget-ms"||
                 (live&&(flag=="--cycles"||flag=="--interval-ms"))){
            bool* seen=nullptr;unsigned* dest=nullptr;
            if(flag=="--profile"){seen=&o.profile;dest=&o.scope.profile;}
            else if(flag=="--baud"){seen=&o.baud_set;dest=&o.baud;}
            else if(flag=="--budget-ms"){seen=&o.budget_set;dest=&o.budget;}
            else if(flag=="--cycles"){seen=&o.cycles_set;dest=&o.cycles;}
            else{seen=&o.interval_set;dest=&o.interval;}
            uint32_t n=0;if(*seen||!decimal_uint32(value,&n))return false;
            *seen=true;*dest=n;
        }else{
            const char** dest=nullptr;
            if(flag=="--port")dest=&o.port;
            else if(flag=="--vin")dest=&o.vin;
            else if(flag=="--output")dest=&o.output;
            if(!dest||*dest)return false;
            *dest=value;
        }
    }
    return o.profile&&o.tx&&o.rx&&o.confirmed&&o.port&&o.vin&&cf1_valid_vin(o.vin)&&o.output&&o.scope.valid()&&
        o.budget>=1000&&o.budget<=600000&&(!live||(!o.pids.empty()&&o.cycles>=1&&o.cycles<=1000&&
        o.interval>=100&&o.interval<=60000&&o.pids.size()*o.cycles<=J1979_LIVE_MAX_SAMPLES));
}
static int elm_can_cli(int argc,char** argv,bool live){
    ElmCanOptions o;
    if(!parse_elm_can(argc,argv,o,live))return error("Explicit fixed ELM CAN profile, port, physical ECU, VIN and output required");
    if(!isatty(STDIN_FILENO))return error("Interactive local consent required");
    fputs("Confirm dedicated ELM adapter, documented physical ECU and stationary vehicle. Read only. Type yes: ",stderr);
    char answer[16]{};
    if(!fgets(answer,sizeof(answer),stdin)||strcmp(answer,"yes\n"))return error("Consent refused");
    struct File{int fd;~File(){if(fd>=0)close(fd);}} out{open(o.output,O_WRONLY|O_CREAT|O_EXCL|O_NOFOLLOW|O_CLOEXEC,0600)};
    if(out.fd<0)return error("ELM CAN report destination unavailable");
    legacy_cancelled=0;
    struct Signals{
        struct sigaction oldint{},oldterm{};
        bool intr{},term{};
        Signals(){
            struct sigaction act{};act.sa_handler=legacy_signal;sigemptyset(&act.sa_mask);
            intr=sigaction(SIGINT,&act,&oldint)==0;
            term=intr&&sigaction(SIGTERM,&act,&oldterm)==0;
        }
        ~Signals(){if(term)sigaction(SIGTERM,&oldterm,nullptr);if(intr)sigaction(SIGINT,&oldint,nullptr);}
    } signals;
    std::unique_ptr<cd::legacy::Link> link;
    std::unique_ptr<cd::elmcan::Session> session;
    std::string native_report,reason;
    bool completed=false;
    try{
        if(!signals.term)throw std::runtime_error("SIGNAL_HANDLERS_UNAVAILABLE");
        const auto current=[](){return legacy_cancelled==0;};
        link=cd::legacy::serial_link(o.port,o.baud,current);
        session=std::make_unique<cd::elmcan::Session>(*link,o.scope,current);
        session->configure();
        j1979_can_plan plan{cd::elmcan::Session::exchange_callback,nullptr,cd::elmcan::Session::current_callback,
            session.get(),o.scope.tx,o.scope.rx,o.budget,o.vin};
        char* buffer=nullptr;size_t size=0;
        FILE* stream=open_memstream(&buffer,&size);
        if(!stream)throw std::runtime_error("REPORT_ALLOCATION_FAILED");
        bool serialized=false;
        if(live){
            struct Result{j1979_live_result value{};~Result(){j1979_live_free(&value);}} r;
            j1979_live_plan lp{};lp.channel=plan;lp.pid_count=static_cast<unsigned>(o.pids.size());
            lp.cycles=o.cycles;lp.interval_ms=o.interval;std::copy(o.pids.begin(),o.pids.end(),lp.pids);
            const int status=j1979_live_record(&lp,&r.value);
            if(status==0&&r.value.steps){
                serialized=j1979_live_write_json_transport(stream,&lp,&r.value,o.port,"ELM_SERIAL_CLASSIC_ISOTP");
                completed=r.value.completed&&r.value.initial_vin_valid&&r.value.final_vin_valid;
                if(!completed)reason=r.value.stop_reason;
            }
        }else{
            struct Result{j1979_can_result value{};~Result(){j1979_can_result_free(&value);}} r;
            const int status=j1979_can_scan(&plan,&r.value);
            if(status==0&&r.value.steps){
                serialized=j1979_can_write_json_transport(stream,&plan,&r.value,o.port,"ELM_SERIAL_CLASSIC_ISOTP");
                completed=r.value.completed&&r.value.vin_valid&&r.value.final_vin_valid;
                if(!completed)reason=r.value.stop_reason;
            }
        }
        const bool closed=fclose(stream)==0;
        std::unique_ptr<char,decltype(&std::free)> owned(buffer,&std::free);
        if(!serialized||!closed||!buffer||!size||size>64*1024*1024){
            completed=false;throw std::runtime_error("NATIVE_REPORT_UNAVAILABLE");
        }
        native_report.assign(buffer,size);
    }catch(const std::exception& e){reason=e.what();completed=false;}
    if(legacy_cancelled){completed=false;reason="CANCELLED";}
    try{
        const std::span<const cd::elmcan::Observation> evidence=session?
            std::span<const cd::elmcan::Observation>(session->evidence()):std::span<const cd::elmcan::Observation>{};
        const auto json=cd::elmcan::report(o.scope,o.vin,live?"LIVE":"SCAN",native_report,evidence,reason,completed);
        if(!write_owned_file(out.fd,json.data(),json.size())||!sync_parent(o.output))return error("ELM CAN report persistence failed");
        return completed?0:2;
    }catch(const std::exception& e){return error(e.what());}
}

static int socket_freeze_cli(int argc,char** argv){
    capture_options o{};
    if(!parse_capture_options(argc,argv,&o,true))return error("Explicit physical SocketCAN, VIN, frame list and output required");
    if(!isatty(STDIN_FILENO))return error("Interactive local consent required");
    fputs("Confirm dedicated SocketCAN interface and documented stationary vehicle. Read-only Mode 02. Type yes: ",stderr);
    char answer[16]{};
    if(!fgets(answer,sizeof(answer),stdin)||strcmp(answer,"yes\n"))return error("Consent refused");
    struct File{int fd;~File(){if(fd>=0)close(fd);}};
    File capture{open(o.capture,O_WRONLY|O_CREAT|O_EXCL|O_NOFOLLOW|O_CLOEXEC,0600)};
    if(capture.fd<0)return error("Capture destination unavailable");
    File report{open(o.report,O_WRONLY|O_CREAT|O_EXCL|O_NOFOLLOW|O_CLOEXEC,0600)};
    if(report.fd<0)return error("Report destination unavailable");
    if(!sync_parent(o.capture)||!sync_parent(o.report))return error("Output reservation not durable");
    legacy_cancelled=0;
    struct Signals{
        struct sigaction oldint{},oldterm{};
        bool intr{},term{};
        Signals(){
            struct sigaction act{};act.sa_handler=legacy_signal;sigemptyset(&act.sa_mask);
            intr=sigaction(SIGINT,&act,&oldint)==0;
            term=intr&&sigaction(SIGTERM,&act,&oldterm)==0;
        }
        ~Signals(){if(term)sigaction(SIGTERM,&oldterm,nullptr);if(intr)sigaction(SIGINT,&oldint,nullptr);}
    } signals;
    try{
        if(!signals.term)return error("Signal handlers unavailable");
        const auto result=cd::hostfreeze::run_socket(o.plan,o.port,capture.fd,&legacy_cancelled);
        const bool synced=fsync(capture.fd)==0;
        const bool closed=close(capture.fd)==0;capture.fd=-1;
        if(!synced||!closed||!result.capture_started)return error("CF/1 capture incomplete or unavailable");
        input raw{},again{};
        if(!load_regular(o.capture,CF1_MAX_BYTES,&raw))return error("No persisted CF/1 evidence");
        struct Evidence{cf1_evidence value{};~Evidence(){cf1_evidence_free(&value);}} e;
        char failure[256]{};
        const int status=result.terminal_written?
            cf1_inspect_complete(raw.bytes.get(),raw.length,o.plan.vin,&e.value,failure,sizeof(failure)):
            cf1_inspect_prefix(raw.bytes.get(),raw.length,o.plan.vin,&e.value,failure,sizeof(failure));
        if(status)return error(failure);
        const auto& p=e.value.plan;
        if(p.tx!=o.plan.tx||p.rx!=o.plan.rx||p.extended!=o.plan.extended||p.budget_ms!=o.plan.budget_ms||
            p.frame_count!=o.plan.frame_count||p.pid_count!=o.plan.pid_count||
            memcmp(p.frames,o.plan.frames,p.frame_count)||memcmp(p.pids,o.plan.pids,p.pid_count))
            return error("Replayed CF/1 scope differs from approved plan");
        if(!load_regular(o.capture,CF1_MAX_BYTES,&again)||!same_input(&raw,&again))
            return error("Persisted CF/1 capture changed");
        json_writer writer{65536};
        writer.bytes.reset(new (std::nothrow) char[writer.capacity]{});
        if(!writer.bytes)return error("Report allocation failed");
        // Canonical offline report: never claim USB authentication for a
        // local SocketCAN recording. Existing verification command applies.
        if(result.terminal_written)write_complete(&writer,&e.value,raw.digest,false,false,false);
        else write_prefix(&writer,&e.value,raw.digest);
        if(writer.failed||!write_owned_file(report.fd,writer.bytes.get(),writer.used)||!sync_parent(o.report))
            return error("CF/1 report not persisted");
        return result.terminal_written&&e.value.execution_completed&&result.summary.allFramesStable&&!legacy_cancelled?0:2;
    }catch(const std::exception& e){return error(e.what());}
}

static int legacy_journal_cli(const char* protocol,const char* source,const char* vin,const char* path,const char* output,bool recover){
    uint32_t p=0,n=0;
    if(!decimal_uint32(protocol,&p)||!cd::legacy::valid_protocol(static_cast<cd::legacy::Protocol>(p))||
        strlen(source)!=2||!hex_uint32(source,&n)||!cd::legacy::valid_source(n)||!cf1_valid_vin(vin))
        return error("Independent protocol, selected source and VIN required");
    input original{},again{};
    try{
        if(!load_regular(path,cd::legacy::journal_max_bytes,&original))return error("Invalid bounded LJ/1 journal");
        const auto evidence=cd::legacy::inspect_journal({original.bytes.get(),original.length},
            static_cast<cd::legacy::Protocol>(p),n,vin);
        if(!load_regular(path,cd::legacy::journal_max_bytes,&again)||!same_input(&original,&again))
            return error("Legacy journal changed during inspection");
        if(recover){
            if(!evidence.terminal||evidence.capture.empty())return error("Interrupted journal cannot become a completed capture");
            if(!atomic_create(output,reinterpret_cast<const char*>(evidence.capture.data()),evidence.capture.size()))
                return error("Recovery output unavailable");
        }else{
            const std::string json=std::string("{\"schema\":\"camarodiag-j1979-legacy-journal-v1\",\"journal_sha256\":\"")+
                original.digest+"\",\"kind\":"+std::to_string(static_cast<unsigned>(evidence.kind))+
                ",\"observations\":"+std::to_string(evidence.observations)+
                ",\"verified_bytes\":"+std::to_string(evidence.verified_bytes)+
                ",\"unverified_tail_bytes\":"+std::to_string(evidence.torn_bytes)+
                ",\"terminal_present\":"+(evidence.terminal?"true":"false")+
                ",\"physical_vehicle_verified\":false,\"permits_programming\":false}\n";
            if(!atomic_create(output,json.data(),json.size()))return error("Journal report unavailable");
        }
        return evidence.terminal?0:2;
    }catch(const std::exception& e){return error(e.what());}
}

static void usage(FILE *stream) {
    fprintf(stream,
        "CamaroDiag Linux native C++20 — SAE J1979\n"
        "Usage:\n"
        "  camarodiag-native --j1979-can-discover --interface can0 --window-ms 1500 --output responders.json --confirm-functional-scope [--extended]\n"
        "  camarodiag-native --j1979-elm-can --profile 6 --port /dev/ttyUSB0 --tx 7E0 --rx 7E8 --vin VIN --output scan.json --confirm-physical-channel\n"
        "  camarodiag-native --j1979-elm-live --profile 6 --port /dev/ttyUSB0 --tx 7E0 --rx 7E8 --vin VIN --pid 0C --output live.json --confirm-physical-channel\n"
        "  camarodiag-native --j1979-can-network --interface can0 --rx 7E8 --rx 7E9 --vin VIN --output network.json --confirm-functional-scope [--extended]\n"
        "  camarodiag-native --j1979-can --interface can0 --tx 7E0 --rx 7E8 --output evidence.json --vin VIN --budget-ms 180000 --confirm-physical-channel\n"
        "  camarodiag-native --j1979-live-usb-native --port /dev/ttyACM0 --key-file key.bin --capture session.cl --output report.json --tx 7E0 --rx 7E8 --vin VIN --pid 0C --cycles 30 --interval-ms 1000 --budget-ms 180000 --confirm-physical-channel\n"
        "  camarodiag-native --j1979-live --interface can0 --tx 7E0 --rx 7E8 --vin VIN --pid 0C --cycles 30 --interval-ms 1000 --budget-ms 180000 --output live.json --confirm-physical-channel\n"
        "  camarodiag-native --inspect-j1979-live-native-prefix CAPTURE.cl VIN PREFIX.json\n"
        "  camarodiag-native --inspect-j1979-live-native CAPTURE.cl VIN REPORT.json\n"
        "  camarodiag-native --verify-j1979-live-native-prefix CAPTURE.cl PREFIX.json PROOF.json VIN\n"
        "  camarodiag-native --verify-j1979-live-native CAPTURE.cl REPORT.json PROOF.json VIN\n"
        "  camarodiag-native --j1979-freeze-can --interface can0 --tx 7E0 --rx 7E8 --vin VIN --frame 00 --pid 0C --capture capture.cf --output report.json --confirm-physical-channel\n"
        "  camarodiag-native --j1979-freeze-native --port /dev/ttyACM0 --key-file key.bin --capture capture.cf --output rapport.json --tx 7E0 --rx 7E8 --vin VIN --frame 00 --pid 0C --budget-ms 60000 --confirm-physical-channel\n"
        "  camarodiag-native --inspect-j1979-freeze-native-prefix CAPTURE.cf VIN PREFIX.json\n"
        "  camarodiag-native --inspect-j1979-freeze-native CAPTURE.cf VIN REPORT.json\n"
        "  camarodiag-native --verify-j1979-freeze-native-prefix CAPTURE.cf PREFIX.json PROOF.json VIN\n"
        "  camarodiag-native --verify-j1979-freeze-native CAPTURE.cf REPORT.json PROOF.json VIN\n"
        "  camarodiag-native --j1979-maintenance-can --operation clear --interface can0 --tx 7E0 --rx 7E8 --vin VIN --budget-ms 60000 --journal-directory PRIVATE_DIR --journal-name session.cm --output report.json --confirm-physical-channel --acknowledge-maintenance-warning\n"
        "  camarodiag-native --j1979-maintenance-usb --operation clear --port /dev/ttyACM0 --key-file key.bin --journal session.cm --output report.json --tx 7E0 --rx 7E8 --vin VIN --budget-ms 60000 --confirm-physical-channel --acknowledge-maintenance-warning\n"
        "  camarodiag-native --j1979-maintenance-archive --operation clear --port /dev/ttyACM0 --key-file key.bin --journal recovered.cm --output recovery.json --tx 7E0 --rx 7E8 --vin VIN --budget-ms 60000 --confirm-physical-channel\n"
        "  camarodiag-native --inspect-j1979-maintenance JOURNAL.cm VIN REPORT.json\n"
        "  camarodiag-native --inspect-j1979-maintenance-prefix JOURNAL.cm VIN REPORT.json\n"
        "  camarodiag-native --verify-j1979-maintenance JOURNAL.cm REPORT.json PROOF.json VIN\n"
        "  camarodiag-native --verify-j1979-maintenance-prefix JOURNAL.cm REPORT.json PROOF.json VIN\n"
        "  camarodiag-native --j1979-usb --port /dev/ttyACM0 --key-file key.bin --capture session.cj --output report.json --tx 7E0 --rx 7E8 --vin VIN --budget-ms 60000 --confirm-physical-channel\n"
        "  camarodiag-native --inspect-j1979-campaign CAPTURE.cj VIN REPORT.json\n"
        "  camarodiag-native --verify-j1979-campaign CAPTURE.cj REPORT.json PROOF.json VIN\n"
        "  camarodiag-native --j1979-legacy-discover --port /dev/ttyUSB0 --protocol 2 --baud 38400 --output responders.json --confirm-physical-channel\n"
        "  camarodiag-native --j1979-legacy --port /dev/ttyUSB0 --protocol 2 --source 10 --vin VIN --capture scan.lsr --output scan.json --confirm-physical-channel\n"
        "  camarodiag-native --j1979-legacy-freeze --port /dev/ttyUSB0 --protocol 2 --source 10 --vin VIN --frame 00 --pid 0C --capture freeze.lfr --output freeze.json --confirm-physical-channel\n"
        "  camarodiag-native --inspect-j1979-legacy-freeze PROTOCOL SOURCE VIN CAPTURE.lfr REPORT.json\n"
        "  camarodiag-native --verify-j1979-legacy-freeze PROTOCOL SOURCE VIN CAPTURE.lfr REPORT.json PROOF.json\n"
        "  camarodiag-native --j1979-legacy-live --port /dev/ttyUSB0 --protocol 2 --source 10 --vin VIN --pid 0C --cycles 30 --interval-ms 1000 --capture session.llr --output live.json --confirm-physical-channel [--raw-only]\n"
        "  camarodiag-native --inspect-j1979-legacy-live PROTOCOL SOURCE VIN CAPTURE.llr REPORT.json\n"
        "  camarodiag-native --verify-j1979-legacy-live PROTOCOL SOURCE VIN CAPTURE.llr REPORT.json PROOF.json\n"
        "  camarodiag-native --inspect-j1979-legacy-journal PROTOCOL SOURCE VIN JOURNAL.lj REPORT.json\n"
        "  camarodiag-native --recover-j1979-legacy-journal PROTOCOL SOURCE VIN JOURNAL.lj CAPTURE\n"
        "  camarodiag-native --inspect-j1979-legacy-scan PROTOCOL SOURCE VIN CAPTURE.lsr REPORT.json\n"
        "  camarodiag-native --verify-j1979-legacy-scan PROTOCOL SOURCE VIN CAPTURE.lsr REPORT.json PROOF.json\n"
        "  camarodiag-native --j1979-legacy-maintenance --operation clear --port /dev/ttyUSB0 --protocol 2 --engine-source 10 --source 10 --vin VIN --baseline baseline.json --intent intent.json --output result.json --confirm-functional-scope --acknowledge-maintenance-warning\n"
        "  camarodiag-native --inspect-j1979-legacy-discovery PROTOCOL RAW_RESPONSE RAW_ATDPN REPORT.json\n"
        "  camarodiag-native --inspect-j1979-catalogue live|freeze CATALOGUE REFERENCE SHA256 OUTPUT\n"
        "  camarodiag-native --project-j1979-catalogue cl1|cf1|llr1 CATALOGUE REFERENCE SHA256 CAPTURE VIN OUTPUT [PROTOCOL SOURCE]\n"
        "  camarodiag-native --verify-j1979-catalogue cl1|cf1|llr1 CATALOGUE REFERENCE SHA256 CAPTURE VIN PROJECTION PROOF [PROTOCOL SOURCE]\n"
        "  camarodiag-native --help\n"
        "Examples:\n"
        "  camarodiag-native --inspect-j1979-freeze-native-prefix incomplete.cf 2G1FA1E30D7654321 partial.json\n"
        "  camarodiag-native --verify-j1979-freeze-native-prefix incomplete.cf partial.json proof.json 2G1FA1E30D7654321\n"
        "Live acquisition: explicit CU/2 proof and local confirmation; offline commands send nothing.\n"
        "Never treats a CRC as cryptographic ECU proof. No automatic retry or write service.\n");
}
int main(int argc, char **argv) {
    if (argc == 2 && (!strcmp(argv[1], "--help") || !strcmp(argv[1], "-h"))) {
        usage(stdout); return 0;
    }
    if (argc==7 && !strcmp(argv[1],"--inspect-j1979-catalogue"))return catalogue_inventory_cli(argv[2],argv[3],argv[4],argv[5],argv[6]);
    if (argc>=3 && !strcmp(argv[1],"--project-j1979-catalogue"))return catalogue_projection_cli(argc,argv,false);
    if (argc>=3 && !strcmp(argv[1],"--verify-j1979-catalogue"))return catalogue_projection_cli(argc,argv,true);
    if (argc>=3 && !strcmp(argv[1],"--j1979-legacy-live"))return legacy_live_cli(argc,argv);
    if (argc==7 && !strcmp(argv[1],"--inspect-j1979-legacy-live"))return legacy_live_replay(argv[2],argv[3],argv[4],argv[5],argv[6],nullptr);
    if (argc==8 && !strcmp(argv[1],"--verify-j1979-legacy-live"))return legacy_live_replay(argv[2],argv[3],argv[4],argv[5],argv[6],argv[7]);
    if (argc>=3 && !strcmp(argv[1],"--j1979-legacy-freeze"))return legacy_freeze_cli(argc,argv);
    if (argc==7 && !strcmp(argv[1],"--inspect-j1979-legacy-freeze"))return legacy_freeze_replay(argv[2],argv[3],argv[4],argv[5],argv[6],nullptr);
    if (argc==8 && !strcmp(argv[1],"--verify-j1979-legacy-freeze"))return legacy_freeze_replay(argv[2],argv[3],argv[4],argv[5],argv[6],argv[7]);
    if (argc==7 && !strcmp(argv[1],"--inspect-j1979-legacy-journal"))return legacy_journal_cli(argv[2],argv[3],argv[4],argv[5],argv[6],false);
    if (argc==7 && !strcmp(argv[1],"--recover-j1979-legacy-journal"))return legacy_journal_cli(argv[2],argv[3],argv[4],argv[5],argv[6],true);
    if (argc==7 && !strcmp(argv[1],"--inspect-j1979-legacy-scan"))return legacy_scan_replay(argv[2],argv[3],argv[4],argv[5],argv[6],nullptr);
    if (argc==8 && !strcmp(argv[1],"--verify-j1979-legacy-scan"))return legacy_scan_replay(argv[2],argv[3],argv[4],argv[5],argv[6],argv[7]);
    if (argc>=3 && !strcmp(argv[1],"--j1979-legacy-maintenance"))return legacy_maintenance_cli(argc,argv);
    if (argc>=3 && !strcmp(argv[1],"--j1979-legacy"))return legacy_discovery_cli(argc,argv,true);
    if (argc>=3 && !strcmp(argv[1],"--j1979-legacy-discover"))return legacy_discovery_cli(argc,argv);
    if (argc==6 && !strcmp(argv[1],"--inspect-j1979-legacy-discovery"))return replay_legacy_discovery(argv[2],argv[3],argv[4],argv[5]);
    if (argc>=3 && !strcmp(argv[1],"--j1979-usb"))return capture_cu1(argc,argv);
    if (argc==6 && !strcmp(argv[1],"--verify-j1979-campaign"))return verify_cj1_cli(argv[2],argv[3],argv[4],argv[5]);
    if (argc==5 && !strcmp(argv[1],"--inspect-j1979-campaign"))
        return cj1_inspect_cli(argv[2],argv[3],argv[4]);
    if (argc>=3 && !strcmp(argv[1],"--j1979-maintenance-can"))return direct_maintenance_cli(argc,argv);
    if (argc>=3 && !strcmp(argv[1],"--j1979-maintenance-usb"))return capture_cu45(argc,argv,cd::cu45::Mode::maintenance);
    if (argc>=3 && !strcmp(argv[1],"--j1979-maintenance-archive"))return capture_cu45(argc,argv,cd::cu45::Mode::archive);
    if (argc==5 && !strcmp(argv[1],"--inspect-j1979-maintenance"))
        return cm1_cli(argv[2],argv[4],nullptr,argv[3],false);
    if (argc==5 && !strcmp(argv[1],"--inspect-j1979-maintenance-prefix"))
        return cm1_cli(argv[2],argv[4],nullptr,argv[3],true);
    if (argc==6 && !strcmp(argv[1],"--verify-j1979-maintenance"))
        return cm1_cli(argv[2],argv[3],argv[4],argv[5],false);
    if (argc==6 && !strcmp(argv[1],"--verify-j1979-maintenance-prefix"))
        return cm1_cli(argv[2],argv[3],argv[4],argv[5],true);
    if (argc >= 3 && !strcmp(argv[1], "--j1979-live-usb-native"))
        return capture_cl1_native(argc,argv);
    if (argc>=3 && !strcmp(argv[1],"--j1979-elm-can"))return elm_can_cli(argc,argv,false);
    if (argc>=3 && !strcmp(argv[1],"--j1979-elm-live"))return elm_can_cli(argc,argv,true);
    if (argc>=3 && !strcmp(argv[1],"--j1979-can-discover"))return network_cli(argc,argv,false);
    if (argc>=3 && !strcmp(argv[1],"--j1979-can-network"))return network_cli(argc,argv,true);
    if (argc >= 3 && (!strcmp(argv[1], "--j1979-can") ||
                      !strcmp(argv[1], "--j1979-live")))
        return j1979_can_cli(argc, argv);
    if (argc >= 3 && !strcmp(argv[1], "--j1979-freeze-can"))return socket_freeze_cli(argc,argv);
    if (argc >= 3 && !strcmp(argv[1], "--j1979-freeze-native"))
        return capture_native(argc, argv);
    if (argc == 5 && !strcmp(argv[1], "--inspect-j1979-live-native-prefix"))
        return cl1_inspect_cli(argv[2],argv[3],argv[4],true);
    if (argc == 5 && !strcmp(argv[1], "--inspect-j1979-live-native"))
        return cl1_inspect_cli(argv[2],argv[3],argv[4],false);
    if (argc == 6 && !strcmp(argv[1], "--verify-j1979-live-native-prefix"))
        return cl1_verify_cli(argv[2],argv[3],argv[4],argv[5],true);
    if (argc == 6 && !strcmp(argv[1], "--verify-j1979-live-native"))
        return cl1_verify_cli(argv[2],argv[3],argv[4],argv[5],false);
    if (argc == 5 && !strcmp(argv[1], "--inspect-j1979-freeze-native"))
        return inspect_complete(argv[2], argv[3], argv[4]);
    if (argc == 5 && !strcmp(argv[1], "--inspect-j1979-freeze-native-prefix"))
        return inspect(argv[2], argv[3], argv[4]);
    if (argc == 6 && !strcmp(argv[1], "--verify-j1979-freeze-native"))
        return verify_complete(argv[2], argv[3], argv[4], argv[5]);
    if (argc == 6 && !strcmp(argv[1], "--verify-j1979-freeze-native-prefix"))
        return verify(argv[2], argv[3], argv[4], argv[5]);
    usage(stderr);
    return 2;
}
