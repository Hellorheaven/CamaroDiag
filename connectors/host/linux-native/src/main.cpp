#define _POSIX_C_SOURCE 200809L
#include "cf1.h"
#include "cf1_usb.h"
#include "cl1.h"
#include "cl1_usb.h"
#include "j1979_can_cli.h"
#include "j1979_cm1.hpp"
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
static bool parse_capture_options(int argc, char **argv, capture_options *o) {
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
        if (!strcmp(name, "--port")) {
            if (o->port) return false;
            o->port = value;
        } else if (!strcmp(name, "--key-file")) {
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
           o->port && o->credential && o->capture && o->report &&
           strcmp(o->capture, o->report) &&
           strcmp(o->credential, o->report) &&
           strcmp(o->credential, o->capture) &&
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

static void usage(FILE *stream) {
    fprintf(stream,
        "CamaroDiag Linux native C++20 — SAE J1979\n"
        "Usage:\n"
        "  camarodiag-native --j1979-can --interface can0 --tx 7E0 --rx 7E8 --output evidence.json --vin VIN --budget-ms 180000 --confirm-physical-channel\n"
        "  camarodiag-native --j1979-live-usb-native --port /dev/ttyACM0 --key-file key.bin --capture session.cl --output report.json --tx 7E0 --rx 7E8 --vin VIN --pid 0C --cycles 30 --interval-ms 1000 --budget-ms 180000 --confirm-physical-channel\n"
        "  camarodiag-native --j1979-live --interface can0 --tx 7E0 --rx 7E8 --vin VIN --pid 0C --cycles 30 --interval-ms 1000 --budget-ms 180000 --output live.json --confirm-physical-channel\n"
        "  camarodiag-native --inspect-j1979-live-native-prefix CAPTURE.cl VIN PREFIX.json\n"
        "  camarodiag-native --inspect-j1979-live-native CAPTURE.cl VIN REPORT.json\n"
        "  camarodiag-native --verify-j1979-live-native-prefix CAPTURE.cl PREFIX.json PROOF.json VIN\n"
        "  camarodiag-native --verify-j1979-live-native CAPTURE.cl REPORT.json PROOF.json VIN\n"
        "  camarodiag-native --j1979-freeze-native --port /dev/ttyACM0 --key-file key.bin --capture capture.cf --output rapport.json --tx 7E0 --rx 7E8 --vin VIN --frame 00 --pid 0C --budget-ms 60000 --confirm-physical-channel\n"
        "  camarodiag-native --inspect-j1979-freeze-native-prefix CAPTURE.cf VIN PREFIX.json\n"
        "  camarodiag-native --inspect-j1979-freeze-native CAPTURE.cf VIN REPORT.json\n"
        "  camarodiag-native --verify-j1979-freeze-native-prefix CAPTURE.cf PREFIX.json PROOF.json VIN\n"
        "  camarodiag-native --verify-j1979-freeze-native CAPTURE.cf REPORT.json PROOF.json VIN\n"
        "  camarodiag-native --inspect-j1979-maintenance JOURNAL.cm VIN REPORT.json\n"
        "  camarodiag-native --inspect-j1979-maintenance-prefix JOURNAL.cm VIN REPORT.json\n"
        "  camarodiag-native --verify-j1979-maintenance JOURNAL.cm REPORT.json PROOF.json VIN\n"
        "  camarodiag-native --verify-j1979-maintenance-prefix JOURNAL.cm REPORT.json PROOF.json VIN\n"
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
    if (argc >= 3 && (!strcmp(argv[1], "--j1979-can") ||
                      !strcmp(argv[1], "--j1979-live")))
        return j1979_can_cli(argc, argv);
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
