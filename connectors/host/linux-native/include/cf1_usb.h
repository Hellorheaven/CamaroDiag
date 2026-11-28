#ifndef CAMARODIAG_LINUX_CF1_USB_H
#define CAMARODIAG_LINUX_CF1_USB_H

#include "cf1_transport.h"
#include <signal.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

typedef struct {
    bool authenticated, client_proof_sent, terminal_received;
    bool capture_file_created;
    size_t raw_bytes_received;
} cf1_usb_capture_result;

/* Physical USB CDC port must be explicitly selected, local operator approval
 * must be checked by the CLI before this call. Read-only CU/2 campaign only.
 * A syntactically valid terminal does NOT imply final replay validation.
 *
 * An interrupted capture file is deliberately retained for forensic analysis.
 * Cancellation never triggers automatic replay or ECU retransmission.
 */
int cf1_usb_capture(const char *port_path, const cf1_plan *plan,
                    const uint8_t key[32], const char *capture_path,
                    cf1_usb_capture_result *result,
                    char *error, size_t error_capacity);
void cf1_usb_request_cancel(void);
#endif
