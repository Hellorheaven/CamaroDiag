#define _POSIX_C_SOURCE 200809L
#include "j1979_isotp.h"
#include "j1979_can.h"

#include <errno.h>
#include <fcntl.h>
#include <linux/can.h>
#include <linux/can/isotp.h>
#include <net/if.h>
#include <poll.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>

/* Linux ISO 15765-2 kernel socket: the only active transmitter is the
 * caller-selected CAN interface and physical ECU pair. No functional CAN
 * address, automatic discovery, 04/08 actuator operation or session retry.
 */
static uint64_t monotonic_ms(void) {
    struct timespec ts = {0};
    if (clock_gettime(CLOCK_MONOTONIC, &ts) != 0) return 0;
    return (uint64_t)ts.tv_sec * 1000u + (uint64_t)ts.tv_nsec / 1000000u;
}
bool j1979_isotp_valid_interface(const char *name) {
    if (!name) return false;
    size_t length = strlen(name);
    if (length == 0 || length >= IF_NAMESIZE) return false;
    for (size_t i = 0; i < length; ++i) {
        const char c = name[i];
        if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
            (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.')
            continue;
        return false;
    }
    return true;
}
bool j1979_isotp_open(j1979_isotp_link *link, const char *name,
                      uint32_t tx, uint32_t rx,
                      const volatile sig_atomic_t *cancel_flag) {
    if (!link) return false;
    memset(link, 0, sizeof(*link));
    link->socket_fd = -1;
    if (!j1979_isotp_valid_interface(name) ||
        !j1979_can_physical_pair(tx, rx) ||
        (cancel_flag && *cancel_flag))
        return false;
    unsigned index = if_nametoindex(name);
    if (!index) return false;
    int fd = socket(PF_CAN, SOCK_DGRAM | SOCK_NONBLOCK | SOCK_CLOEXEC, CAN_ISOTP);
    if (fd < 0) return false;
    /* Explicit physical IDs only; 29-bit extended CAN identity must be
     * encoded with the Linux CAN_EFF_FLAG on both ends. */
    uint32_t ext = tx > CAN_SFF_MASK ? CAN_EFF_FLAG : 0;
    struct sockaddr_can address = {0};
    address.can_family = AF_CAN;
    address.can_ifindex = (int)index;
    address.can_addr.tp.rx_id = rx | ext;
    address.can_addr.tp.tx_id = tx | ext;
    if (bind(fd, (struct sockaddr *)&address, sizeof(address)) != 0) {
        close(fd);
        return false;
    }
    link->socket_fd = fd;
    link->interface_index = index;
    link->tx_can_id = tx;
    link->rx_can_id = rx;
    link->cancel_flag = cancel_flag;
    memcpy(link->interface_name, name, strlen(name) + 1u);
    return true;
}
bool j1979_isotp_current(void *context) {
    const j1979_isotp_link *link = context;
    return link && link->socket_fd >= 0 && link->interface_index &&
        (!link->cancel_flag || !*link->cancel_flag) &&
        if_nametoindex(link->interface_name) == link->interface_index;
}
static int ready(const j1979_isotp_link *link, short events,
                 uint64_t deadline) {
    while (j1979_isotp_current((void *)link)) {
        uint64_t now = monotonic_ms();
        if (!now || now >= deadline) return 1; /* timeout */
        uint64_t remaining = deadline - now;
        int wait_ms = remaining < 100u ? (int)remaining : 100;
        if (!wait_ms) wait_ms = 1;
        struct pollfd pfd = {.fd = link->socket_fd, .events = events};
        int rc = poll(&pfd, 1, wait_ms);
        if (rc < 0 && errno == EINTR) continue;
        if (rc < 0 || (pfd.revents & (POLLERR | POLLHUP | POLLNVAL))) return -1;
        if (rc == 0) continue;
        if (pfd.revents & events) return 0;
        return -1;
    }
    return -1;
}
int j1979_isotp_exchange(void *context, const uint8_t *request,
                         size_t n, uint8_t *response,
                         size_t *response_size, unsigned timeout_ms) {
    j1979_isotp_link *link = context;
    if (!j1979_isotp_current(link) ||
        !j1979_can_allowed_read(request, n) ||
        !response || !response_size || *response_size == 0 ||
        *response_size > J1979_CAN_MAX_PAYLOAD + 1u ||
        timeout_ms == 0 || timeout_ms > 2500)
        return -1;
    const uint64_t start = monotonic_ms();
    if (!start) return -1;
    const uint64_t deadline = start + timeout_ms;
    /* A queued prior response cannot be attributed to the current request.
     * Fail on a continuously busy socket rather than silently mixing ECUs. */
    for (unsigned drained = 0; drained < 64; ++drained) {
        uint8_t stale[J1979_CAN_MAX_PAYLOAD + 1] = {0};
        ssize_t got = recv(link->socket_fd, stale, sizeof(stale), MSG_DONTWAIT);
        if (got < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) break;
        if (got < 0 && errno == EINTR) continue;
        if (got < 0 || !j1979_isotp_current(link) || monotonic_ms() >= deadline)
            return -1;
        if (drained == 63) return -1;
    }
    if (ready(link, POLLOUT, deadline) != 0 || !j1979_isotp_current(link))
        return -1;
    ssize_t sent = send(link->socket_fd, request, n, MSG_DONTWAIT);
    if (sent < 0 || (size_t)sent != n) return -1; /* No retry after uncertain TX */
    unsigned pending = 0;
    while (j1979_isotp_current(link)) {
        int state = ready(link, POLLIN, deadline);
        if (state == 1) return pending ? -1 : 1;
        if (state != 0) return -1;
        ssize_t received = recv(link->socket_fd, response, *response_size, MSG_DONTWAIT);
        if (received < 0 && errno == EINTR) continue;
        if (received < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) continue;
        if (received <= 0 || (size_t)received > J1979_CAN_MAX_PAYLOAD)
            return -1;
        if (!j1979_isotp_current(link) || monotonic_ms() >= deadline)
            return -1;
        if (received == 3 && response[0] == 0x7f &&
            response[1] == request[0] && response[2] == 0x78) {
            if (++pending > 8) return -1;
            continue;
        }
        *response_size = (size_t)received;
        return 0;
    }
    return -1;
}
void j1979_isotp_close(j1979_isotp_link *link) {
    if (!link) return;
    if (link->socket_fd >= 0) close(link->socket_fd);
    memset(link, 0, sizeof(*link));
    link->socket_fd = -1;
}
