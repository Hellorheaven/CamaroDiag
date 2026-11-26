#pragma once
#include "j1979_usb_auth.h"

namespace cd {
// Key provisioning is external: the local owner resolves a specific host
// credential and never accepts keys over CU/1. No secret is compiled in.
bool j1979UsbHmacSha256(const uint8_t* key,size_t keySize,const uint8_t* data,
    size_t dataSize,uint8_t* tag);
bool j1979UsbRandomNonce(uint8_t* nonce,size_t size);
}
