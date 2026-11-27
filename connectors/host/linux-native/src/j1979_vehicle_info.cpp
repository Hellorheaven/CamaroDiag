// SAE J1979 Mode 09: C++20 bounded decoded ECU records.
#include "j1979_vehicle_info.h"
#include <algorithm>
#include <array>
#include <cstddef>
#include <cstdint>
#include <span>

static constexpr std::array<const char *,9> spark={
    "catalyst-bank-1","catalyst-bank-2","oxygen-sensor-bank-1",
    "oxygen-sensor-bank-2","egr-vvt","secondary-air","evap",
    "secondary-oxygen-bank-1","secondary-oxygen-bank-2"
};
static constexpr std::array<const char *,8> diesel={
    "nmhc-catalyst","nox-scr","nox-adsorber","particulate-filter",
    "exhaust-gas-sensor","egr-vvt","boost-pressure","fuel-system"
};
static uint16_t be16(const uint8_t *p) {
    return (uint16_t)(((uint16_t)p[0]<<8)|p[1]);
}
bool j1979_vehicle_info_decode(uint8_t kind,const uint8_t *reply,
                               size_t n,j1979_vehicle_info *out) {
    if (!out) return false;
    *out = {};
    if (!reply || n<3 || reply[0]!=0x49 || reply[1]!=kind)
        return false;
    const std::span<const uint8_t> response{reply,n};
    const size_t count=response[2];
    if (kind==8 || kind==11) {
        const bool supported=kind==8 ? (count==16 || count==20) :
                                         (count==16 || count==18);
        if (!supported || n!=3+2*count) return false;
        const std::span<const uint8_t> raw=response.subspan(3);
        out->info_type=kind;out->counter_vector=true;
        out->counter_count=(unsigned)count;
        out->general_denominator=be16(raw);
        out->ignition_cycles=be16(raw+2);
        out->monitor_count=(unsigned)((count-2)/2);
        const char *const *names=kind==8?spark.data():diesel.data();
        for (unsigned i=0;i<out->monitor_count;++i) {
            j1979_info_monitor *m=&out->monitors[i];
            m->name=names[i];
            m->numerator=be16(raw+4+4*i);
            m->denominator=be16(raw+6+4*i);
            m->ratio_valid=m->denominator!=0;
            if (m->ratio_valid) m->ratio=(double)m->numerator/m->denominator;
        }
        return true;
    }
    if ((kind==13 || kind==15) && count==1 && n==20) {
        const std::span<const uint8_t> raw=response.subspan(3);
        size_t leading=0;
        while (leading<17 && raw[leading]==0) ++leading;
        if (leading==17) return false;
        for (size_t i=leading;i<17;++i)
            if (raw[i]<32 || raw[i]>126) return false;
        const size_t len=17-leading;
        std::copy_n(raw.begin()+static_cast<std::ptrdiff_t>(leading),
                    len,out->text);
        out->text[len]=0;
        out->info_type=kind;out->printable_text=true;
        return true;
    }
    return false;
}
