#define _POSIX_C_SOURCE 200809L
#include "cf1_usb.h"
#include "cl1_usb.h"

#include <errno.h>
#include <fcntl.h>
#include <openssl/crypto.h>
#include <poll.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <termios.h>
#include <time.h>
#include <unistd.h>
#include <memory>
#include <new>

#ifndef O_NOFOLLOW
#error "Native CU/2 capture refuses symlink serial and evidence outputs"
#endif
#ifndef O_CLOEXEC
#error "Native CU/2 capture requires Linux close-on-exec"
#endif

static volatile sig_atomic_t cancelled;

void cf1_usb_request_cancel(void) { cancelled = 1; }
static int failed(char *error, size_t n, const char *why) {
    if (n) snprintf(error, n, "%s", why);
    return -1;
}
static uint64_t monotonic_ms(void) {
    struct timespec now;
    if (clock_gettime(CLOCK_MONOTONIC, &now) != 0) return 0;
    return (uint64_t)now.tv_sec * 1000u + (uint64_t)now.tv_nsec / 1000000u;
}
static bool tty_name(const char *path) {
    const char *suffix = NULL;
    if (path && strncmp(path, "/dev/ttyACM", 11) == 0) suffix = path + 11;
    else if (path && strncmp(path, "/dev/ttyUSB", 11) == 0) suffix = path + 11;
    if (!suffix || !*suffix || strlen(suffix) > 3) return false;
    while (*suffix) {
        if (*suffix < '0' || *suffix > '9') return false;
        ++suffix;
    }
    return true;
}
static int wait_ready(int fd, short events, uint64_t deadline) {
    while (!cancelled) {
        uint64_t now = monotonic_ms();
        if (!now || now >= deadline) return -1;
        uint64_t left = deadline - now;
        int timeout = (int)(left > 150 ? 150 : left);
        struct pollfd p{fd, events, 0};
        int count = poll(&p, 1, timeout);
        if (count < 0 && errno == EINTR) continue;
        if (count < 0) return -1;
        if (count == 0) continue;
        if (p.revents & (POLLERR | POLLHUP | POLLNVAL)) return -1;
        if (p.revents & events) return 0;
    }
    return -1;
}
static int write_deadline(int fd, const uint8_t *bytes, size_t n, uint64_t deadline) {
    size_t offset = 0;
    while (offset < n && !cancelled) {
        if (wait_ready(fd, POLLOUT, deadline)) return -1;
        ssize_t count = write(fd, bytes + offset, n - offset);
        if (count < 0 && (errno == EINTR || errno == EAGAIN)) continue;
        if (count <= 0) return -1;
        offset += (size_t)count;
    }
    /* The last successful write can itself cross a deadline. */
    return cancelled || monotonic_ms() >= deadline ? -1 : 0;
}
static int read_deadline(int fd, uint8_t *bytes, size_t n, uint64_t deadline) {
    size_t offset = 0;
    while (offset < n && !cancelled) {
        if (wait_ready(fd, POLLIN, deadline)) return -1;
        ssize_t count = read(fd, bytes + offset, n - offset);
        if (count < 0 && (errno == EINTR || errno == EAGAIN)) continue;
        if (count <= 0) continue; /* VMIN=0 permits an empty read. */
        offset += (size_t)count;
    }
    /* A complete final read that arrives late still exceeds the CU/2 deadline. */
    return cancelled || monotonic_ms() >= deadline ? -1 : 0;
}
static bool configure_serial(int fd, struct termios *saved) {
    if (!isatty(fd) || tcgetattr(fd, saved) != 0) return false;
    struct termios t = *saved;
    t.c_iflag = IGNPAR;
    t.c_oflag = 0;
    t.c_lflag = 0;
    tcflag_t flow_mask = 0;
#ifdef CRTSCTS
    flow_mask = CRTSCTS;
#endif
    t.c_cflag = (t.c_cflag & ~(CSIZE | PARENB | CSTOPB | flow_mask)) |
                CS8 | CREAD | CLOCAL;
    t.c_cc[VMIN] = 0; t.c_cc[VTIME] = 0;
    if (cfsetispeed(&t, B115200) != 0 ||
        cfsetospeed(&t, B115200) != 0 ||
        tcsetattr(fd, TCSANOW, &t) != 0) return false;
    if (tcflush(fd, TCIOFLUSH) == 0) return true;
    tcsetattr(fd, TCSANOW, saved);
    return false;
}
static int write_capture(int fd, const uint8_t *bytes, size_t n) {
    size_t position = 0;
    while (position < n) {
        ssize_t count = write(fd, bytes + position, n - position);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) return -1;
        position += (size_t)count;
    }
    return 0;
}
/* During a deadline or cable interruption, persist every received byte,
 * not only the last fully assembled CF/1 record. Never replay uncommitted
 * tails: they remain forensic evidence until an independent full parser.
 */
static int receive_to_capture(int serial, int output, uint8_t *destination,
                              size_t requested, uint64_t deadline,
                              cf1_usb_capture_result *result) {
    size_t received = 0;
    while (received < requested && !cancelled) {
        if (wait_ready(serial, POLLIN, deadline)) return -1;
        ssize_t n = read(serial, destination + received, requested - received);
        if (n < 0 && (errno == EINTR || errno == EAGAIN)) continue;
        if (n < 0) return -1;
        if (n == 0) continue;
        if (write_capture(output, destination + received, (size_t)n)) return -1;
        received += (size_t)n;
        result->raw_bytes_received += (size_t)n;
    }
    return cancelled ? -1 : 0;
}
static uint16_t little16(const uint8_t *p) {
    return (uint16_t)((uint16_t)p[0] | ((uint16_t)p[1] << 8));
}
static uint32_t little32(const uint8_t *p) {
    return (uint32_t)p[0] | ((uint32_t)p[1] << 8) |
        ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
}
int cf1_usb_capture(const char *port_path, const cf1_plan *plan,
                    const uint8_t key[32], const char *capture_path,
                    cf1_usb_capture_result *out, char *err, size_t cap) {
    if (!out) return failed(err, cap, "Missing native CU/2 capture state");
    memset(out, 0, sizeof(*out));
    cancelled = 0;
    if (!tty_name(port_path) || !cf1_valid_plan(plan) || !key || !capture_path)
        return failed(err, cap, "Explicit Linux CDC device and qualified CU/2 scope required");
    uint8_t nonce[32] = {0}, hello[CF1_CU2_HELLO_SIZE] = {0};
    uint8_t challenge[CF1_CU2_CHALLENGE_SIZE] = {0};
    uint8_t proof[CF1_CU2_PROOF_SIZE] = {0};
    uint8_t profile[CF1_PLAN_SIZE] = {0};
    int serial = -1, file = -1, result = -1;
    struct termios old{};
    bool has_old = false;
    std::unique_ptr<uint8_t[]> raw;
    const char *why = "Native CU/2 session interrupted";
    {
    if (!cf1_cu2_random_nonce(nonce) ||
        !cf1_cu2_hello(plan, nonce, hello, err, cap) ||
        !cf1_encode_profile(plan, profile, err, cap)) {
        why = "Unable to initialize CU/2 proof and physical scope";
        goto cleanup;
    }
    serial = open(port_path, O_RDWR | O_NOCTTY | O_NONBLOCK | O_NOFOLLOW | O_CLOEXEC);
    if (serial < 0 || !configure_serial(serial, &old)) {
        why = "Cannot open/configure selected USB CDC serial port";
        goto cleanup;
    }
    has_old = true;
    file = open(capture_path, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600);
    if (file < 0) {
        why = "Capture path unavailable: cannot overwrite existing evidence";
        goto cleanup;
    }
    out->capture_file_created = true;
    raw.reset(new (std::nothrow) uint8_t[CF1_MAX_BYTES]);
    if (!raw) { why = "Insufficient bounded CF/1 capture memory"; goto cleanup; }
    const uint64_t auth_started = monotonic_ms();
    if (!auth_started) { why = "CU/2 monotonic clock unavailable"; goto cleanup; }
    const uint64_t auth_deadline = auth_started + 5000;
    if (write_deadline(serial, hello, sizeof(hello), auth_deadline) ||
        read_deadline(serial, challenge, sizeof(challenge), auth_deadline)) {
        why = "CU/2 challenge timeout or interrupted transmission; no retry";
        goto cleanup;
    }
    if (!cf1_cu2_proof(hello, challenge, key, proof, err, cap)) {
        why = "CU/2 authentication refused; client proof not transmitted";
        goto cleanup;
    }
    out->authenticated = true;
    if (write_deadline(serial, proof, sizeof(proof), auth_deadline)) {
        why = "CU/2 client proof not fully transmitted; no retry";
        goto cleanup;
    }
    out->client_proof_sent = true;
    const uint64_t capture_started = monotonic_ms();
    if (!capture_started) { why = "CU/2 capture monotonic clock unavailable"; goto cleanup; }
    const uint64_t deadline = capture_started + plan->budget_ms + 15000u;
    for (size_t seq = 0; seq < CF1_MAX_STEPS + 2 && !cancelled; ++seq) {
        if (out->raw_bytes_received + 8 > CF1_MAX_BYTES) {
            why = "CF/1 input exceeds the preapproved maximum";
            goto cleanup;
        }
        uint8_t *h = raw.get() + out->raw_bytes_received;
        if (receive_to_capture(serial, file, h, 8, deadline, out)) {
            why = "CF/1 header interrupted; any received bytes retained";
            goto cleanup;
        }
        uint16_t length = little16(h + 6);
        if (h[0] != 'C' || h[1] != 'F' || h[2] != 1 ||
            little16(h + 4) != seq || (seq == 0 && h[3] != 1) ||
            (seq && h[3] != 2 && h[3] != 3) ||
            length < 1 || length > CF1_MAX_BODY ||
            (seq == 0 && length != CF1_PLAN_SIZE) ||
            (h[3] == 2 && length < 12) ||
            (h[3] == 3 && length != 20)) {
            why = "Invalid live CF/1 framing, size, sequence or kind";
            goto cleanup;
        }
        if (out->raw_bytes_received + length + 4 > CF1_MAX_BYTES) {
            why = "CF/1 capture exceeds maximum allocation";
            goto cleanup;
        }
        uint8_t *tail = raw.get() + out->raw_bytes_received;
        if (receive_to_capture(serial, file, tail, length + 4, deadline, out)) {
            why = "CF/1 payload interrupted; any received bytes retained";
            goto cleanup;
        }
        if (cf1_crc32(h, (size_t)length + 8) != little32(tail + length)) {
            why = "Live CF/1 record CRC mismatch";
            goto cleanup;
        }
        if (seq == 0 && memcmp(h + 8, profile, CF1_PLAN_SIZE)) {
            why = "CF/1 plan differs from approved CU/2 authenticated scope";
            goto cleanup;
        }
        if (h[3] == 3) {
            out->terminal_received = true;
            struct pollfd p{serial, POLLIN, 0};
            int seen;
            do { seen = poll(&p, 1, 100); } while (seen < 0 && errno == EINTR && !cancelled);
            if (seen < 0 || cancelled || (seen > 0 && (p.revents & (POLLIN | POLLERR | POLLHUP | POLLNVAL)))) {
                why = "Untrusted bytes or error after CF/1 terminal";
                goto cleanup;
            }
            if (monotonic_ms() >= deadline) {
                why = "CF/1 terminal deadline exceeded";
                goto cleanup;
            }
            if (fsync(file) != 0) {
                why = "Persisted CF/1 evidence could not be synchronized";
                goto cleanup;
            }
            result = 0;
            break;
        }
    }
    if (result != 0) why = cancelled ? "Operator cancelled CU/2 capture" :
                                 "CF/1 record limit exceeded without terminal";
    }
cleanup:
    if (file >= 0) {
        if (result != 0) fsync(file); /* Preserve partial evidence if possible. */
        close(file);
    }
    if (serial >= 0) {
        if (has_old) tcsetattr(serial, TCSANOW, &old);
        close(serial);
    }
    if (raw) { OPENSSL_cleanse(raw.get(), out->raw_bytes_received); }
    OPENSSL_cleanse(nonce, sizeof(nonce));
    OPENSSL_cleanse(hello, sizeof(hello));
    OPENSSL_cleanse(challenge, sizeof(challenge));
    OPENSSL_cleanse(proof, sizeof(proof));
    OPENSSL_cleanse(profile, sizeof(profile));
    if (result != 0) {
        failed(err, cap, why);
        return -1;
    }
    return 0;
}

/* Native C++20 CU/3 CL/1 capture, transport only. The same bound, locally
 * approved USB CDC port and existing fail-closed serial deadline primitives
 * are deliberately shared with the CU/2 implementation above. This
 * cannot silently fall back to CU/1, CU/2, Python, or another ECU.
 */
int cl1_usb_capture(const char *port_path,const cl1_plan *plan,
                    const uint8_t key[32],const char *capture_path,
                    cf1_usb_capture_result *out,char *err,size_t cap) {
    if (!out) return failed(err,cap,"Missing native CU/3 capture state");
    memset(out,0,sizeof(*out));
    cancelled=0;
    if (!tty_name(port_path) || !cl1_valid_plan(plan) || !key || !capture_path)
        return failed(err,cap,"Selected USB CDC, approved VIN and CU/3 scope required");
    uint8_t nonce[32]={0};
    uint8_t hello[CL1_CU3_HELLO_SIZE]={0};
    uint8_t challenge[CL1_CU3_CHALLENGE_SIZE]={0};
    uint8_t proof[CL1_CU3_PROOF_SIZE]={0};
    uint8_t canonical[CL1_PLAN_SIZE]={0};
    int serial=-1,file=-1,result=-1;
    struct termios previous{};
    bool has_previous=false;
    std::unique_ptr<uint8_t[]> raw;
    const char *why="CU/3 CL/1 recording interrupted";
    {
    if (!cl1_cu3_random_nonce(nonce) ||
        !cl1_cu3_hello(plan,nonce,hello,err,cap) ||
        !cl1_encode_plan(plan,canonical,err,cap)) {
        why="Cannot prepare canonical CU/3 authorization";
        goto cleanup;
    }
    serial=open(port_path,O_RDWR|O_NOCTTY|O_NONBLOCK|O_NOFOLLOW|O_CLOEXEC);
    if (serial<0 || !configure_serial(serial,&previous)) {
        why="Selected native USB CDC port cannot be configured";
        goto cleanup;
    }
    has_previous=true;
    file=open(capture_path,O_WRONLY|O_CREAT|O_EXCL|O_NOFOLLOW|O_CLOEXEC,0600);
    if (file<0) {
        why="CL/1 evidence destination already exists or is unsafe";
        goto cleanup;
    }
    out->capture_file_created=true;
    raw.reset(new (std::nothrow) uint8_t[CL1_MAX_CAPTURE]);
    if (!raw) {
        why="CL/1 bounded capture allocation unavailable";
        goto cleanup;
    }
    const uint64_t now=monotonic_ms();
    if (!now) {
        why="CU/3 monotonic clock unavailable";
        goto cleanup;
    }
    const uint64_t auth_deadline=now+5000u;
    if (write_deadline(serial,hello,sizeof(hello),auth_deadline) ||
        read_deadline(serial,challenge,sizeof(challenge),auth_deadline)) {
        why="CU/3 hello/challenge interrupted or expired; no retry";
        goto cleanup;
    }
    if (!cl1_cu3_proof(hello,challenge,key,proof,err,cap)) {
        why="CU/3 server HMAC proof rejected; client proof not transmitted";
        goto cleanup;
    }
    out->authenticated=true;
    if (write_deadline(serial,proof,sizeof(proof),auth_deadline)) {
        why="CU/3 client proof uncertain; no retry or channel recovery";
        goto cleanup;
    }
    out->client_proof_sent=true;
    const uint64_t capture_started=monotonic_ms();
    if (!capture_started) {
        why="CU/3 capture monotonic clock unavailable";
        goto cleanup;
    }
    const uint64_t deadline=capture_started+plan->budget_ms+15000u;
    for (size_t seq=0;seq<CL1_MAX_STEPS+2u && !cancelled;++seq) {
        if (out->raw_bytes_received+8u>CL1_MAX_CAPTURE) {
            why="CL/1 input exceeds the preapproved maximum";
            goto cleanup;
        }
        uint8_t *h=raw.get() + out->raw_bytes_received;
        if (receive_to_capture(serial,file,h,8,deadline,out)) {
            why="CU/3 CL/1 header interrupted; received bytes preserved";
            goto cleanup;
        }
        const uint16_t length=little16(h+6);
        if (h[0]!='C' || h[1]!='L' || h[2]!=1 ||
            little16(h+4)!=seq ||
            (seq==0 ? h[3]!=1 : (h[3]!=2 && h[3]!=3)) ||
            length<1 || length>CL1_MAX_BODY ||
            (h[3]==1 && length!=CL1_PLAN_SIZE) ||
            (h[3]==2 && length<CL1_EVENT_SIZE) ||
            (h[3]==3 && length!=CL1_TERMINAL_SIZE)) {
            why="CU/3 CL/1 record framing, sequence or size mismatch";
            goto cleanup;
        }
        if (out->raw_bytes_received+(size_t)length+4u>CL1_MAX_CAPTURE) {
            why="CL/1 evidence exceeds retained byte bound";
            goto cleanup;
        }
        uint8_t *body=raw.get() + out->raw_bytes_received;
        if (receive_to_capture(serial,file,body,(size_t)length+4u,
                               deadline,out)) {
            why="CU/3 CL/1 body interrupted; received bytes preserved";
            goto cleanup;
        }
        if (cf1_crc32(h,(size_t)length+8u)!=little32(body+length)) {
            why="CL/1 observed record CRC mismatch";
            goto cleanup;
        }
        if (seq==0 && memcmp(body,canonical,CL1_PLAN_SIZE)) {
            why="CL/1 plan differs from authenticated CU/3 read scope";
            goto cleanup;
        }
        if (h[3]==3) {
            out->terminal_received=true;
            struct pollfd poller{serial, POLLIN, 0};
            int extra;
            do {extra=poll(&poller,1,100);}
            while (extra<0 && errno==EINTR && !cancelled);
            if (extra<0 || cancelled ||
                (extra>0 && (poller.revents&(POLLIN|POLLERR|POLLHUP|POLLNVAL)))) {
                why="CU/3 trailing data or channel failure after CL/1 terminal";
                goto cleanup;
            }
            if (monotonic_ms()>=deadline) {
                why="CU/3 terminal verification exceeded deadline";
                goto cleanup;
            }
            if (fsync(file)!=0) {
                why="CL/1 complete capture cannot be durably synchronized";
                goto cleanup;
            }
            result=0;
            break;
        }
    }
    if (result!=0)
        why=cancelled?"CU/3 operator cancelled":"CL/1 sequence bound reached without terminal";
    }
cleanup:
    if (file>=0) {
        if (result!=0) fsync(file); /* Persist partial evidence if possible. */
        close(file);
    }
    if (serial>=0) {
        if (has_previous) tcsetattr(serial,TCSANOW,&previous);
        close(serial);
    }
    if (raw) {
        OPENSSL_cleanse(raw.get(), out->raw_bytes_received);
    }
    OPENSSL_cleanse(nonce,sizeof(nonce));
    OPENSSL_cleanse(hello,sizeof(hello));
    OPENSSL_cleanse(challenge,sizeof(challenge));
    OPENSSL_cleanse(proof,sizeof(proof));
    OPENSSL_cleanse(canonical,sizeof(canonical));
    if (result!=0) {
        failed(err,cap,why);
        return -1;
    }
    return 0;
}
