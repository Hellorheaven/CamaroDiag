// SAE J1979 Mode 06: C++20 bounded numeric projection.
#include "j1979_monitor_scaling.h"
#include <array>
#include <cstdint>
#include <span>

/* Exact curated engineering multipliers formerly in
 * connectors/host/python/camarodiagconnectors/j1979_monitor_scaling.py.
 * Keep the original byte encoding as authoritative evidence.
 */
struct definition {
    uint8_t id;
    const char *unit;
    double factor;
    bool signed_value;
    double offset;
};
static constexpr definition definitions[]={
    {0x01, "1", 1, false, 0},
    {0x02, "1", 0.1, false, 0},
    {0x03, "1", 0.01, false, 0},
    {0x04, "1", 0.001, false, 0},
    {0x05, "1", 0.0000305, false, 0},
    {0x06, "1", 0.000305, false, 0},
    {0x07, "rpm", 0.25, false, 0},
    {0x08, "km/h", 0.01, false, 0},
    {0x09, "km/h", 1, false, 0},
    {0x0a, "mV", 0.122, false, 0},
    {0x0b, "V", 0.001, false, 0},
    {0x0c, "V", 0.01, false, 0},
    {0x0d, "mA", 0.00390625, false, 0},
    {0x0e, "A", 0.001, false, 0},
    {0x0f, "A", 0.01, false, 0},
    {0x10, "ms", 1, false, 0},
    {0x11, "ms", 100, false, 0},
    {0x12, "s", 1, false, 0},
    {0x13, "mOhm", 1, false, 0},
    {0x14, "ohm", 1, false, 0},
    {0x15, "kOhm", 1, false, 0},
    {0x16, "degC", 0.1, false, -40},
    {0x17, "kPa", 0.01, false, 0},
    {0x18, "kPa", 0.0117, false, 0},
    {0x19, "kPa", 0.079, false, 0},
    {0x1a, "kPa", 1, false, 0},
    {0x1b, "kPa", 10, false, 0},
    {0x1c, "deg", 0.01, false, 0},
    {0x1d, "deg", 0.5, false, 0},
    {0x1e, "ratio", 0.0000305, false, 0},
    {0x1f, "ratio", 0.05, false, 0},
    {0x20, "ratio", 0.00390625, false, 0},
    {0x21, "mHz", 1, false, 0},
    {0x22, "Hz", 1, false, 0},
    {0x23, "kHz", 1, false, 0},
    {0x24, "count", 1, false, 0},
    {0x25, "km", 1, false, 0},
    {0x26, "mV/ms", 0.1, false, 0},
    {0x27, "g/s", 0.01, false, 0},
    {0x28, "g/s", 1, false, 0},
    {0x29, "Pa/s", 0.25, false, 0},
    {0x2a, "kg/h", 0.001, false, 0},
    {0x2b, "count", 1, false, 0},
    {0x2c, "g/cyl", 0.01, false, 0},
    {0x2d, "mg/stroke", 0.01, false, 0},
    {0x2e, "bool", 1, false, 0},
    {0x2f, "%", 0.01, false, 0},
    {0x30, "%", 0.001526, false, 0},
    {0x31, "L", 0.001, false, 0},
    {0x32, "in", 0.0000305, false, 0},
    {0x33, "ratio", 0.00024414, false, 0},
    {0x34, "min", 1, false, 0},
    {0x35, "ms", 10, false, 0},
    {0x36, "g", 0.01, false, 0},
    {0x37, "g", 0.1, false, 0},
    {0x38, "g", 1, false, 0},
    {0x39, "%", 0.01, false, -327.68},
    {0x81, "1", 1, true, 0},
    {0x82, "1", 0.1, true, 0},
    {0x83, "1", 0.01, true, 0},
    {0x84, "1", 0.001, true, 0},
    {0x85, "1", 0.0000305, true, 0},
    {0x86, "1", 0.000305, true, 0},
    {0x8a, "mV", 0.122, true, 0},
    {0x8b, "V", 0.001, true, 0},
    {0x8c, "V", 0.01, true, 0},
    {0x8d, "mA", 0.00390625, true, 0},
    {0x8e, "A", 0.001, true, 0},
    {0x90, "ms", 1, true, 0},
    {0x96, "degC", 0.1, true, 0},
    {0x9c, "deg", 0.01, true, 0},
    {0x9d, "deg", 0.5, true, 0},
    {0xa8, "g/s", 1, true, 0},
    {0xa9, "Pa/s", 0.25, true, 0},
    {0xaf, "%", 0.01, true, 0},
    {0xb0, "%", 0.003052, true, 0},
    {0xb1, "mV/s", 2, true, 0},
    {0xfd, "kPa", 0.001, true, 0},
    {0xfe, "Pa", 0.25, true, 0},
};
static const definition *lookup(uint8_t id) {
    for (const definition &entry : definitions)
        if (entry.id == id) return &entry;
    return NULL;
}
static double convert(const definition *d,uint16_t encoded) {
    int raw=d->signed_value && encoded>=32768 ?
        (int)encoded-65536:(int)encoded;
    return (double)raw*d->factor+d->offset;
}
bool j1979_monitor_scale(uint8_t id,uint16_t raw,uint16_t low,
                         uint16_t high,j1979_monitor_record *out) {
    if (!out) return false;
    *out = {};
    const definition *d=lookup(id);
    if (!d || (id==0x2e && (raw>1 || low>1 || high>1))) return false;
    out->value=convert(d,raw);
    out->minimum=convert(d,low);
    out->maximum=convert(d,high);
    out->unit=d->unit;
    out->scaled=true;
    out->relation=out->minimum>out->maximum ? J1979_MONITOR_INVERTED :
        out->value<out->minimum ? J1979_MONITOR_BELOW :
        out->value>out->maximum ? J1979_MONITOR_ABOVE : J1979_MONITOR_WITHIN;
    return true;
}
const char *j1979_monitor_relation_name(j1979_monitor_relation relation) {
    switch (relation) {
        case J1979_MONITOR_INVERTED:return "INVERTED_LIMITS";
        case J1979_MONITOR_BELOW:return "BELOW";
        case J1979_MONITOR_WITHIN:return "WITHIN";
        case J1979_MONITOR_ABOVE:return "ABOVE";
        default:return "UNVERIFIED";
    }
}
static uint16_t be16(const uint8_t *p) {
    return (uint16_t)(((uint16_t)p[0]<<8)|p[1]);
}
size_t j1979_monitor_count(const uint8_t request[2],
                           const uint8_t *response,size_t length) {
    if (!request || !response || request[0]!=6 || request[1]%32u==0 ||
        length<10 || (length-1u)%9u!=0 ||
        response[0]!=0x46) return 0;
    for (size_t i=1;i<length;i+=9)
        if (response[i]!=request[1]) return 0;
    return (length-1u)/9u;
}
bool j1979_monitor_decode(const uint8_t request[2],const uint8_t *response,
                          size_t length,size_t index,j1979_monitor_record *out) {
    if (!out) return false;
    *out = {};
    const size_t count=j1979_monitor_count(request,response,length);
    if (!count || index>=count) return false;
    const std::span<const uint8_t> bytes{response,length};
    const std::span<const uint8_t> entry=bytes.subspan(1u+9u*index,9u);
    const uint8_t *p=entry.data();
    out->monitor_id=p[0];
    out->test_id=p[1];
    out->scaling_id=p[2];
    out->encoded_value=be16(p+3);
    out->encoded_minimum=be16(p+5);
    out->encoded_maximum=be16(p+7);
    /* Unknown or reserved scaling stays visible as raw numbers, never
     * upgraded to a measurement or a diagnostic PASS/FAIL verdict.
     */
    j1979_monitor_scale(out->scaling_id,out->encoded_value,
                        out->encoded_minimum,out->encoded_maximum,out);
    return true;
}
