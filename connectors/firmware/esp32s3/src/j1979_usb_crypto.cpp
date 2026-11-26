#include "j1979_usb_crypto.h"
#include <mbedtls/md.h>
#include <esp_system.h>

namespace cd {
bool j1979UsbHmacSha256(const uint8_t* key,size_t keySize,const uint8_t* data,
    size_t dataSize,uint8_t* tag){
  if(!key||keySize!=32||!data||dataSize!=J1979UsbAuthSession::TranscriptSize||!tag)return false;
  const auto* md=mbedtls_md_info_from_type(MBEDTLS_MD_SHA256);
  return md&&mbedtls_md_hmac(md,key,keySize,data,dataSize,tag)==0;
}
bool j1979UsbRandomNonce(uint8_t* nonce,size_t size){
  // Board owner must verify the platform's hardware entropy prerequisites
  // for this build before admission. A PRNG-only boot state is insufficient.
  if(!nonce||size!=J1979UsbAuthSession::NonceSize)return false;
  esp_fill_random(nonce,size);return true;
}
}
