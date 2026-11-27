#define _POSIX_C_SOURCE 200809L
#include "cf1.h"
#include "cf1_usb.h"

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

#ifndef O_NOFOLLOW
#error "Native evidence input requires O_NOFOLLOW (POSIX Linux)"
#endif
#ifndef O_CLOEXEC
#error "Native evidence input requires O_CLOEXEC (POSIX Linux)"
#endif

typedef struct {
    unsigned char *bytes;
    size_t length;
    dev_t device;
    ino_t inode;
    char digest[65];
} input;
typedef struct { char *bytes; size_t used, capacity; bool failed; } json_writer;

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
    memset(out, 0, sizeof(*out));
    int fd = open(path, O_RDONLY | O_NOFOLLOW | O_CLOEXEC | O_NONBLOCK);
    if (fd < 0) return false;
    struct stat st;
    bool ok = fstat(fd, &st) == 0 && S_ISREG(st.st_mode) &&
              st.st_size > 0 && (uint64_t)st.st_size <= maximum;
    if (ok) {
        out->length = (size_t)st.st_size;
        out->device = st.st_dev; out->inode = st.st_ino;
        out->bytes = malloc(out->length);
        ok = out->bytes != NULL;
    }
    size_t n = 0;
    while (ok && n < out->length) {
        ssize_t got = read(fd, out->bytes + n, out->length - n);
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
        free(out->bytes); memset(out, 0, sizeof(*out));
        return false;
    }
    sha256_hex(out->bytes, out->length, out->digest);
    return true;
}
static void free_input(input *v) { free(v->bytes); memset(v, 0, sizeof(*v)); }
static bool same_input(const input *a, const input *b) {
    return a->length == b->length && a->device == b->device &&
        a->inode == b->inode && !memcmp(a->bytes, b->bytes, a->length);
}
static bool separate(const input *a, const input *b) {
    return a->device != b->device || a->inode != b->inode;
}
static void write_literal(json_writer *j, const char *text) {
    size_t length = strlen(text);
    if (j->failed || j->used + length >= j->capacity) { j->failed = true; return; }
    memcpy(j->bytes + j->used, text, length);
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
                          char **json_out, size_t *size_out) {
    cf1_evidence e = {0};
    char err[256] = {0};
    if (cf1_inspect_prefix(capture->bytes, capture->length, vin, &e, err, sizeof(err))) {
        fprintf(stderr, "CF/1 rejected: %s\n", err);
        cf1_evidence_free(&e);
        return false;
    }
    json_writer writer = { .capacity = 65536 };
    writer.bytes = calloc(1, writer.capacity);
    if (writer.bytes) write_prefix(&writer, &e, capture->digest);
    bool ok = writer.bytes && !writer.failed;
    cf1_evidence_free(&e);
    if (!ok) { free(writer.bytes); return false; }
    *json_out = writer.bytes;
    *size_out = writer.used;
    return true;
}
static int inspect(const char *capture_path, const char *vin, const char *output) {
    input original = {0}, again = {0};
    char *report = NULL;
    size_t report_size = 0;
    bool ok = load_regular(capture_path, CF1_MAX_BYTES, &original);
    if (ok) ok = report_prefix(&original, vin, &report, &report_size);
    if (ok) ok = load_regular(capture_path, CF1_MAX_BYTES, &again) &&
                 same_input(&original, &again);
    if (ok) ok = atomic_create(output, report, report_size);
    free(report); free_input(&again); free_input(&original);
    return ok ? 0 : error("CF/1 inspection failed, source changed, or output exists");
}
static int inspect_complete(const char *capture_path, const char *vin, const char *output) {
    input original = {0}, again = {0};
    bool ok = load_regular(capture_path, CF1_MAX_BYTES, &original);
    cf1_evidence evidence = {0};
    char err[256] = {0};
    if (ok) ok = cf1_inspect_complete(original.bytes, original.length, vin,
                                     &evidence, err, sizeof(err)) == 0;
    if (!ok && *err) fprintf(stderr, "CF/1 rejected: %s\n", err);
    json_writer writer = { .capacity = 65536 };
    if (ok) {
        writer.bytes = calloc(1, writer.capacity);
        ok = writer.bytes != NULL;
        if (ok) write_complete(&writer, &evidence, original.digest, false, false, false);
        if (writer.failed) ok = false;
    }
    if (ok) ok = load_regular(capture_path, CF1_MAX_BYTES, &again) &&
                 same_input(&original, &again);
    if (ok) ok = atomic_create(output, writer.bytes, writer.used);
    free(writer.bytes);
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
    input capture = {0}, saved = {0}, capture_again = {0}, report_again = {0};
    cf1_evidence evidence = {0};
    json_writer projection = { .capacity = 65536 };
    char failure[256] = {0};
    bool valid = load_regular(capture_path, CF1_MAX_BYTES, &capture) &&
                 load_regular(report_path, 67108864, &saved);
    if (valid) valid = separate(&capture, &saved);
    if (valid) valid = cf1_inspect_complete(capture.bytes, capture.length,
                                          vin, &evidence,
                                          failure, sizeof(failure)) == 0;
    if (valid) {
        projection.bytes = calloc(1, projection.capacity);
        valid = projection.bytes != NULL;
    }
    if (valid) write_complete(&projection, &evidence, capture.digest,
                              false, false, false);
    if (valid) valid = !projection.failed &&
                       projection.used == saved.length &&
                       !memcmp(projection.bytes, saved.bytes, projection.used);
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
    free(projection.bytes);
    cf1_evidence_free(&evidence);
    free_input(&capture_again); free_input(&report_again);
    free_input(&capture); free_input(&saved);
    return valid ? 0 : error("Full CF/1 verification refused: altered or inconsistent evidence");
}
static int verify(const char *capture_path, const char *report_path,
                  const char *output_path, const char *vin) {
    input capture = {0}, stored = {0}, recheck_capture = {0}, recheck_report = {0};
    char *replayed = NULL;
    size_t length = 0;
    bool ok = load_regular(capture_path, CF1_MAX_BYTES, &capture) &&
              load_regular(report_path, 67108864, &stored);
    if (ok) ok = separate(&capture, &stored);
    if (ok) ok = report_prefix(&capture, vin, &replayed, &length);
    /* Native canonical verification deliberately compares the entire original
     * byte stream, not an untrusted subset of JSON fields. Alternative JSON
     * formatting/schema require an explicit, separately reviewed importer. */
    if (ok) ok = stored.length == length && !memcmp(stored.bytes, replayed, length);
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
    free(replayed);
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
    capture_options o = {0};
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
    struct sigaction action = {0}, oldint = {0}, oldterm = {0};
    action.sa_handler = cancel_handler;
    sigemptyset(&action.sa_mask);
    sigaction(SIGINT, &action, &oldint);
    sigaction(SIGTERM, &action, &oldterm);
    cf1_usb_capture_result usb = {0};
    char failure[256] = {0};
    int transport = cf1_usb_capture(o.port, &o.plan, key, o.capture,
                                     &usb, failure, sizeof(failure));
    sigaction(SIGINT, &oldint, NULL);
    sigaction(SIGTERM, &oldterm, NULL);
    OPENSSL_cleanse(key, sizeof(key));
    input capture = {0}, again = {0};
    cf1_evidence evidence = {0};
    bool valid = transport == 0 &&
                 load_regular(o.capture, CF1_MAX_BYTES, &capture);
    if (valid) {
        valid = cf1_inspect_complete(capture.bytes, capture.length,
                                    o.plan.vin, &evidence,
                                    failure, sizeof(failure)) == 0;
    }
    if (valid) valid = load_regular(o.capture, CF1_MAX_BYTES, &again) &&
                       same_input(&capture, &again);
    json_writer writer = { .capacity = 65536 };
    writer.bytes = calloc(1, writer.capacity);
    if (!writer.bytes) valid = false;
    if (writer.bytes && valid) {
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
    bool persisted = writer.bytes && !writer.failed &&
        atomic_create(o.report, writer.bytes, writer.used);
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
    free(writer.bytes); free_input(&capture); free_input(&again);
    cf1_evidence_free(&evidence);
    return status;
}
static void usage(FILE *stream) {
    fprintf(stream,
        "CamaroDiag Linux native C — SAE J1979 Mode 02 CF/1\n"
        "Usage:\n"
        "  camarodiag-native --j1979-freeze-native --port /dev/ttyACM0 --key-file key.bin --capture capture.cf --output rapport.json --tx 7E0 --rx 7E8 --vin VIN --frame 00 --pid 0C --budget-ms 60000 --confirm-physical-channel\n"
        "  camarodiag-native --inspect-j1979-freeze-native-prefix CAPTURE.cf VIN PREFIX.json\n"
        "  camarodiag-native --inspect-j1979-freeze-native CAPTURE.cf VIN REPORT.json\n"
        "  camarodiag-native --verify-j1979-freeze-native-prefix CAPTURE.cf PREFIX.json PROOF.json VIN\n"
        "  camarodiag-native --verify-j1979-freeze-native CAPTURE.cf REPORT.json PROOF.json VIN\n"
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
    if (argc >= 3 && !strcmp(argv[1], "--j1979-freeze-native"))
        return capture_native(argc, argv);
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
