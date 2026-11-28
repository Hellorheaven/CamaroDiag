#ifndef CAMARODIAG_LINUX_CL1_USB_H
#define CAMARODIAG_LINUX_CL1_USB_H
#include "cf1_usb.h"
#include "cl1_transport.h"
/* CU/3 capture uses the same exclusively owned Linux serial port as CU/2.
 * Caller must authorize the scope before invocation. Partial raw records
 * remain persisted; returning success requires a valid terminal framing,
 * but only independent CL/1 replay can qualify application completeness.
 */
int cl1_usb_capture(const char *port_path,const cl1_plan *plan,
                    const uint8_t key[32],const char *capture_path,
                    cf1_usb_capture_result *result,
                    char *error,size_t error_capacity);
#endif
