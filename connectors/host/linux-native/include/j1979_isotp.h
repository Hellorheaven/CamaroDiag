#ifndef CAMARODIAG_LINUX_NATIVE_J1979_ISOTP_H
#define CAMARODIAG_LINUX_NATIVE_J1979_ISOTP_H
#include <stdbool.h>
#include <signal.h>
#include <stddef.h>
#include <stdint.h>

typedef struct {
    int socket_fd;
    unsigned interface_index;
    char interface_name[16];
    uint32_t tx_can_id, rx_can_id;
    const volatile sig_atomic_t *cancel_flag;
} j1979_isotp_link;

bool j1979_isotp_valid_interface(const char *name);
bool j1979_isotp_open(j1979_isotp_link *link, const char *interface_name,
                      uint32_t tx, uint32_t rx,
                      const volatile sig_atomic_t *cancel_flag);
bool j1979_isotp_current(void *context);
int j1979_isotp_exchange(void *context, const uint8_t *request,
                         size_t request_size, uint8_t *response,
                         size_t *response_size, unsigned timeout_ms);
void j1979_isotp_close(j1979_isotp_link *link);
#endif
