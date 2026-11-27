// SAE J1979 decoded measurements: C++20 bounded spans and value semantics.
#include "j1979_measurement.h"
#include <array>
#include <cstddef>
#include <cstdint>
#include <span>

static constexpr unsigned known_width(uint8_t pid) {
    switch (pid) {
        case 0x0c: case 0x10: case 0x1f: case 0x21: case 0x22:
        case 0x23: case 0x31: case 0x32: case 0x3c: case 0x3d:
        case 0x3e: case 0x3f: case 0x42: case 0x43: case 0x44:
        case 0x4d: case 0x4e: case 0x53: case 0x54: case 0x59:
        case 0x5d: case 0x5e: case 0x63:
            return 2;
        case 0xa6:return 4;
        default:return 1;
    }
}
unsigned j1979_measurement_width(uint8_t pid){
    switch(pid){

        case 0x04: case 0x05: case 0x06: case 0x07: case 0x08:
        case 0x09: case 0x0c: case 0x0d: case 0x0e: case 0x0f:
        case 0x10: case 0x11: case 0x1f: case 0x2f: case 0x33:
        case 0x42: case 0x46: case 0x52: case 0x5c: case 0x5e:
        case 0x0a: case 0x0b: case 0x21: case 0x22: case 0x23:
        case 0x2c: case 0x2d: case 0x2e: case 0x30: case 0x31:
        case 0x32: case 0x3c: case 0x3d: case 0x3e: case 0x3f:
        case 0x43: case 0x44: case 0x45: case 0x47: case 0x48:
        case 0x49: case 0x4a: case 0x4b: case 0x4c: case 0x4d:
        case 0x4e: case 0x53: case 0x54: case 0x59: case 0x5a:
        case 0x5b: case 0x5d: case 0x61: case 0x62: case 0x63:
        case 0xa6:
            return known_width(pid);
        default:return 0;
    }
}
bool j1979_measurement_decode(const uint8_t *req,size_t qlen,
                              const uint8_t *res,size_t n,
                              j1979_measurement *out) {
    if (!out) return false;
    *out = {};
    if (!req || !res || (qlen!=2 && qlen!=3) ||
        (req[0]!=1 && req[0]!=2) ||
        (req[0]==1 && qlen!=2) ||
        (req[0]==2 && (qlen!=3 || req[2]!=0))) return false;
    const std::span<const uint8_t> response{res,n};
    const size_t offset=req[0]==2 ? 3u:2u;
    if (n<offset+1u || res[0]!=(uint8_t)(req[0]+0x40) ||
        res[1]!=req[1] || (req[0]==2 && res[2]!=0))
        return false;
    unsigned width=j1979_measurement_width(req[1]);
    if(!width)return false;
    bool trim=req[1]>=6 && req[1]<=9;
    if (n!=offset+width && !(trim && n==offset+2)) return false;
    const std::span<const uint8_t> bytes=response.subspan(offset);
    const double a=bytes[0];
    const double b=width>=2 ? bytes[1] : 0;
    const int pair=(int)bytes[0]*256+(width>=2?bytes[1]:0);
    const int signed_ab=pair>=32768?pair-65536:pair;
    out->mode=req[0];out->pid=req[1];
    out->frame=req[0]==2?req[2]:0;
    out->upper_trim_unverified=trim && n==offset+2u;
    if (req[1]==0xa6) {
        uint32_t value=((uint32_t)bytes[0]<<24) |
                       ((uint32_t)bytes[1]<<16) |
                       ((uint32_t)bytes[2]<<8) | bytes[3];
        out->name="odometer";out->unit="km";
        out->number=(double)value/10.0;out->interpreted=true;
        return true;
    }
    switch (req[1]) {
        case 0x04:out->name="calculated-engine-load";out->unit="%";out->number=a * 100.0 / 255;break;
        case 0x05:out->name="coolant-temperature";out->unit="degC";out->number=a - 40;break;
        case 0x06:out->name="short-term-fuel-trim-bank-1";out->unit="%";out->number=a * 100.0 / 128 - 100;break;
        case 0x07:out->name="long-term-fuel-trim-bank-1";out->unit="%";out->number=a * 100.0 / 128 - 100;break;
        case 0x08:out->name="short-term-fuel-trim-bank-2";out->unit="%";out->number=a * 100.0 / 128 - 100;break;
        case 0x09:out->name="long-term-fuel-trim-bank-2";out->unit="%";out->number=a * 100.0 / 128 - 100;break;
        case 0x0c:out->name="engine-rpm";out->unit="rpm";out->number=(a * 256.0 + b) / 4;break;
        case 0x0d:out->name="vehicle-speed";out->unit="km/h";out->number=a;break;
        case 0x0e:out->name="timing-advance";out->unit="degBTDC";out->number=a / 2.0 - 64;break;
        case 0x0f:out->name="intake-air-temperature";out->unit="degC";out->number=a - 40;break;
        case 0x10:out->name="mass-air-flow";out->unit="g/s";out->number=(a * 256.0 + b) / 100;break;
        case 0x11:out->name="throttle-position";out->unit="%";out->number=a * 100.0 / 255;break;
        case 0x1f:out->name="engine-runtime";out->unit="s";out->number=a * 256.0 + b;break;
        case 0x2f:out->name="fuel-tank-level";out->unit="%";out->number=a * 100.0 / 255;break;
        case 0x33:out->name="barometric-pressure";out->unit="kPa";out->number=a;break;
        case 0x42:out->name="control-module-voltage";out->unit="V";out->number=(a * 256.0 + b) / 1000;break;
        case 0x46:out->name="ambient-air-temperature";out->unit="degC";out->number=a - 40;break;
        case 0x52:out->name="ethanol-content";out->unit="%";out->number=a * 100.0 / 255;break;
        case 0x5c:out->name="engine-oil-temperature";out->unit="degC";out->number=a - 40;break;
        case 0x5e:out->name="engine-fuel-rate";out->unit="L/h";out->number=(a * 256.0 + b) / 20;break;
        case 0x0a:out->name="fuel-pressure-gauge";out->unit="kPa";out->number=a * 3.0;break;
        case 0x0b:out->name="intake-manifold-absolute-pressure";out->unit="kPa";out->number=a;break;
        case 0x21:out->name="distance-with-mil-on";out->unit="km";out->number=a * 256.0 + b;break;
        case 0x22:out->name="fuel-rail-pressure-relative-vacuum";out->unit="kPa";out->number=(a * 256.0 + b) * 0.079;break;
        case 0x23:out->name="fuel-rail-pressure-direct-injection";out->unit="kPa";out->number=(a * 256.0 + b) * 10;break;
        case 0x2c:out->name="commanded-egr";out->unit="%";out->number=a * 100.0 / 255;break;
        case 0x2d:out->name="egr-error";out->unit="%";out->number=(a - 128) * 100.0 / 128;break;
        case 0x2e:out->name="commanded-evaporative-purge";out->unit="%";out->number=a * 100.0 / 255;break;
        case 0x30:out->name="warm-ups-since-dtc-clear";out->unit="count";out->number=a;break;
        case 0x31:out->name="distance-since-dtc-clear";out->unit="km";out->number=a * 256.0 + b;break;
        case 0x32:out->name="evap-vapor-pressure";out->unit="Pa";out->number=signed_ab / 4.0;break;
        case 0x3c:out->name="catalyst-temperature-bank-1-sensor-1";out->unit="degC";out->number=(a * 256.0 + b) / 10 - 40;break;
        case 0x3d:out->name="catalyst-temperature-bank-2-sensor-1";out->unit="degC";out->number=(a * 256.0 + b) / 10 - 40;break;
        case 0x3e:out->name="catalyst-temperature-bank-1-sensor-2";out->unit="degC";out->number=(a * 256.0 + b) / 10 - 40;break;
        case 0x3f:out->name="catalyst-temperature-bank-2-sensor-2";out->unit="degC";out->number=(a * 256.0 + b) / 10 - 40;break;
        case 0x43:out->name="absolute-engine-load";out->unit="%";out->number=(a * 256.0 + b) * 100 / 255;break;
        case 0x44:out->name="commanded-equivalence-ratio";out->unit="ratio";out->number=(a * 256.0 + b) / 32768;break;
        case 0x45:out->name="relative-throttle-position";out->unit="%";out->number=a * 100.0 / 255;break;
        case 0x47:out->name="absolute-throttle-position-b";out->unit="%";out->number=a * 100.0 / 255;break;
        case 0x48:out->name="absolute-throttle-position-c";out->unit="%";out->number=a * 100.0 / 255;break;
        case 0x49:out->name="accelerator-pedal-position-d";out->unit="%";out->number=a * 100.0 / 255;break;
        case 0x4a:out->name="accelerator-pedal-position-e";out->unit="%";out->number=a * 100.0 / 255;break;
        case 0x4b:out->name="accelerator-pedal-position-f";out->unit="%";out->number=a * 100.0 / 255;break;
        case 0x4c:out->name="commanded-throttle-actuator";out->unit="%";out->number=a * 100.0 / 255;break;
        case 0x4d:out->name="time-run-with-mil-on";out->unit="min";out->number=a * 256.0 + b;break;
        case 0x4e:out->name="time-since-dtc-clear";out->unit="min";out->number=a * 256.0 + b;break;
        case 0x53:out->name="absolute-evap-vapor-pressure";out->unit="kPa";out->number=(a * 256.0 + b) / 200;break;
        case 0x54:out->name="evap-vapor-pressure-offset";out->unit="Pa";out->number=(a * 256.0 + b) - 32767;break;
        case 0x59:out->name="fuel-rail-pressure-absolute";out->unit="kPa";out->number=(a * 256.0 + b) * 10;break;
        case 0x5a:out->name="relative-accelerator-pedal-position";out->unit="%";out->number=a * 100.0 / 255;break;
        case 0x5b:out->name="hybrid-battery-pack-remaining-life";out->unit="%";out->number=a * 100.0 / 255;break;
        case 0x5d:out->name="fuel-injection-timing";out->unit="deg";out->number=((a * 256.0 + b) - 26880) / 128;break;
        case 0x61:out->name="driver-demand-engine-torque";out->unit="%";out->number=a - 125;break;
        case 0x62:out->name="actual-engine-torque";out->unit="%";out->number=a - 125;break;
        case 0x63:out->name="engine-reference-torque";out->unit="Nm";out->number=a * 256.0 + b;break;
        default:return false; /* Unknown revision remains RAW only. */
    }
    /* ISO 15031 ECU-specific scaling needs independently observed Mode 01
     * PID 4F/50 configuration. Never infer a range from a single sample.
     */
    if (req[1]==0x0b || req[1]==0x10 || req[1]==0x44 ||
        (req[1]>=0x24 && req[1]<=0x2b) ||
        (req[1]>=0x34 && req[1]<=0x3b)) {
        out->context_required=true;out->number=0;out->interpreted=false;
        return true;
    }
    out->interpreted=true;
    return true;
}
