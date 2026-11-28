#include "j1979_policy.h"
#include <array>
#include <span>
#include <string_view>

/* C++20, no transport side effects, no heap allocation or Python.
 * Firmware keeps its existing C++ validation path; no duplicate C shim.
 */
bool cd_j1979_native_physical_pair(uint32_t tx,uint32_t rx,bool extended) {
    if (!extended)
        return tx>=0x7e0u && tx<=0x7e7u && rx==tx+8u;
    if (tx>0x1fffffffu || rx>0x1fffffffu ||
        (tx & 0x1fff00ffu)!=0x18da00f1u)
        return false;
    uint32_t ecu=(tx>>8)&255u;
    return ecu<0xf0u && rx==(0x18daf100u|ecu);
}
bool cd_j1979_native_vin(const char *vin,size_t length) {
    if (!vin || length!=17) return false;
    const std::string_view value{vin,length};
    for (unsigned char c : value) {
        if (!((c>='A'&&c<='Z'&&c!='I'&&c!='O'&&c!='Q') ||
              (c>='0'&&c<='9'))) return false;
    }
    return true;
}
bool cd_j1979_native_read_only(const uint8_t *req,size_t n) {
    if (!req || n==0 || n>3) return false;
    const std::span<const uint8_t> request{req,n};
    if (n==1) return request[0]==3 || request[0]==7 || request[0]==10;
    if (n==2) {
        if (request[0]==1 || request[0]==6 || request[0]==9) return true;
        /* Mode 08 is not a generic read service; only information bitmap
         * pages are permitted, never actuator / component control.
         */
        return request[0]==8 && request[1]%32u==0;
    }
    return request[0]==2 && request[2]==0;
}
bool cd_j1979_native_dtc_code(const uint8_t bytes[2],char out[6]) {
    if (!bytes || !out || (!bytes[0] && !bytes[1])) return false;
    const std::span<const uint8_t,2> code{bytes,2};
    static const char class_letters[]="PCBU";
    static const char hex[]="0123456789ABCDEF";
    out[0]=class_letters[code[0]>>6];
    out[1]=hex[(code[0]>>4)&3];
    out[2]=hex[code[0]&15];
    out[3]=hex[code[1]>>4];
    out[4]=hex[code[1]&15];
    out[5]='\0';
    return true;
}
cd_j1979_native_shape cd_j1979_native_classify(const uint8_t *req,
    size_t qlen,const uint8_t *response,size_t n) {
    if (!cd_j1979_native_read_only(req,qlen) || !response ||
        n==0 || n>4095) return CD_J1979_NATIVE_INVALID;
    const std::span<const uint8_t> request{req,qlen};
    const std::span<const uint8_t> answer{response,n};
    if (answer[0]==0x7f) {
        if (n!=3 || answer[1]!=request[0] || answer[2]==0)
            return CD_J1979_NATIVE_INVALID;
        return answer[2]==0x78 ? CD_J1979_NATIVE_PENDING :
                                   CD_J1979_NATIVE_NEGATIVE;
    }
    if (answer[0]!=(uint8_t)(request[0]+0x40u))
        return CD_J1979_NATIVE_INVALID;
    if (qlen==1 && (request[0]==3 || request[0]==7 || request[0]==10)) {
        if (n<2 || n!=2u+2u*answer[1])
            return CD_J1979_NATIVE_INVALID;
        for (size_t i=2;i<n;i+=2)
            if (answer[i]==0 && answer[i+1]==0)
                return CD_J1979_NATIVE_INVALID;
        return CD_J1979_NATIVE_DTC;
    }
    if (n<qlen+1 || answer[1]!=request[1] ||
        (qlen==3 && answer[2]!=request[2]))
        return CD_J1979_NATIVE_INVALID;
    if (request[0]==9 && request[1]==2) {
        return n==20 && answer[2]==1 &&
            cd_j1979_native_vin(reinterpret_cast<const char *>(answer.data()+3),17) ?
            CD_J1979_NATIVE_VIN:CD_J1979_NATIVE_INVALID;
    }
    if (request[1]%32u==0 &&
        (request[0]==1 || request[0]==2 || request[0]==6 ||
         request[0]==8 || request[0]==9)) {
        return n==qlen+4 && (request[1]!=0xe0 ||
               (answer[n-1]&1u)==0) ?
               CD_J1979_NATIVE_BITMAP:CD_J1979_NATIVE_INVALID;
    }
    if (request[0]==2 && request[1]==2)
        return n==5 ? CD_J1979_NATIVE_RAW:CD_J1979_NATIVE_INVALID;
    return CD_J1979_NATIVE_RAW;
}
