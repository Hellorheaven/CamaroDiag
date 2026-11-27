#include "j1979_multi.hpp"
#include <array>
#include <algorithm>
#include <iomanip>

namespace cd::j1979 {
namespace {
struct Field {
    std::string_view name,unit;
    unsigned offset,width;
    int multiplier,divisor,bias,bit;
    bool signed_value;
    std::int64_t unavailable;
};
struct Definition {
    unsigned pid,width,support_mask;
    std::span<const Field> fields;
};
// Port of the repository's existing selected definitions, not an assertion
// of complete J1979DA coverage. Names, field widths and scale constants are
// kept in one immutable C++ table. No Python runtime or generated build step.
constexpr Field fields_14[]{
    {"o2-sensor-1-voltage", "V", 0, 1, 1, 200, 0, -1, false, -1},
    {"o2-sensor-1-short-term-trim", "%", 1, 1, 100, 128, -100, -1, false, 255}
};
constexpr Field fields_15[]{
    {"o2-sensor-2-voltage", "V", 0, 1, 1, 200, 0, -1, false, -1},
    {"o2-sensor-2-short-term-trim", "%", 1, 1, 100, 128, -100, -1, false, 255}
};
constexpr Field fields_16[]{
    {"o2-sensor-3-voltage", "V", 0, 1, 1, 200, 0, -1, false, -1},
    {"o2-sensor-3-short-term-trim", "%", 1, 1, 100, 128, -100, -1, false, 255}
};
constexpr Field fields_17[]{
    {"o2-sensor-4-voltage", "V", 0, 1, 1, 200, 0, -1, false, -1},
    {"o2-sensor-4-short-term-trim", "%", 1, 1, 100, 128, -100, -1, false, 255}
};
constexpr Field fields_18[]{
    {"o2-sensor-5-voltage", "V", 0, 1, 1, 200, 0, -1, false, -1},
    {"o2-sensor-5-short-term-trim", "%", 1, 1, 100, 128, -100, -1, false, 255}
};
constexpr Field fields_19[]{
    {"o2-sensor-6-voltage", "V", 0, 1, 1, 200, 0, -1, false, -1},
    {"o2-sensor-6-short-term-trim", "%", 1, 1, 100, 128, -100, -1, false, 255}
};
constexpr Field fields_1A[]{
    {"o2-sensor-7-voltage", "V", 0, 1, 1, 200, 0, -1, false, -1},
    {"o2-sensor-7-short-term-trim", "%", 1, 1, 100, 128, -100, -1, false, 255}
};
constexpr Field fields_1B[]{
    {"o2-sensor-8-voltage", "V", 0, 1, 1, 200, 0, -1, false, -1},
    {"o2-sensor-8-short-term-trim", "%", 1, 1, 100, 128, -100, -1, false, 255}
};
constexpr Field fields_24[]{
    {"wideband-o2-sensor-1-equivalence-ratio", "ratio", 0, 2, 1, 32768, 0, -1, false, -1},
    {"wideband-o2-sensor-1-voltage", "V", 2, 2, 1, 8192, 0, -1, false, -1}
};
constexpr Field fields_25[]{
    {"wideband-o2-sensor-2-equivalence-ratio", "ratio", 0, 2, 1, 32768, 0, -1, false, -1},
    {"wideband-o2-sensor-2-voltage", "V", 2, 2, 1, 8192, 0, -1, false, -1}
};
constexpr Field fields_26[]{
    {"wideband-o2-sensor-3-equivalence-ratio", "ratio", 0, 2, 1, 32768, 0, -1, false, -1},
    {"wideband-o2-sensor-3-voltage", "V", 2, 2, 1, 8192, 0, -1, false, -1}
};
constexpr Field fields_27[]{
    {"wideband-o2-sensor-4-equivalence-ratio", "ratio", 0, 2, 1, 32768, 0, -1, false, -1},
    {"wideband-o2-sensor-4-voltage", "V", 2, 2, 1, 8192, 0, -1, false, -1}
};
constexpr Field fields_28[]{
    {"wideband-o2-sensor-5-equivalence-ratio", "ratio", 0, 2, 1, 32768, 0, -1, false, -1},
    {"wideband-o2-sensor-5-voltage", "V", 2, 2, 1, 8192, 0, -1, false, -1}
};
constexpr Field fields_29[]{
    {"wideband-o2-sensor-6-equivalence-ratio", "ratio", 0, 2, 1, 32768, 0, -1, false, -1},
    {"wideband-o2-sensor-6-voltage", "V", 2, 2, 1, 8192, 0, -1, false, -1}
};
constexpr Field fields_2A[]{
    {"wideband-o2-sensor-7-equivalence-ratio", "ratio", 0, 2, 1, 32768, 0, -1, false, -1},
    {"wideband-o2-sensor-7-voltage", "V", 2, 2, 1, 8192, 0, -1, false, -1}
};
constexpr Field fields_2B[]{
    {"wideband-o2-sensor-8-equivalence-ratio", "ratio", 0, 2, 1, 32768, 0, -1, false, -1},
    {"wideband-o2-sensor-8-voltage", "V", 2, 2, 1, 8192, 0, -1, false, -1}
};
constexpr Field fields_34[]{
    {"wideband-o2-sensor-1-equivalence-ratio", "ratio", 0, 2, 1, 32768, 0, -1, false, -1},
    {"wideband-o2-sensor-1-current", "mA", 2, 2, 1, 256, -128, -1, false, -1}
};
constexpr Field fields_35[]{
    {"wideband-o2-sensor-2-equivalence-ratio", "ratio", 0, 2, 1, 32768, 0, -1, false, -1},
    {"wideband-o2-sensor-2-current", "mA", 2, 2, 1, 256, -128, -1, false, -1}
};
constexpr Field fields_36[]{
    {"wideband-o2-sensor-3-equivalence-ratio", "ratio", 0, 2, 1, 32768, 0, -1, false, -1},
    {"wideband-o2-sensor-3-current", "mA", 2, 2, 1, 256, -128, -1, false, -1}
};
constexpr Field fields_37[]{
    {"wideband-o2-sensor-4-equivalence-ratio", "ratio", 0, 2, 1, 32768, 0, -1, false, -1},
    {"wideband-o2-sensor-4-current", "mA", 2, 2, 1, 256, -128, -1, false, -1}
};
constexpr Field fields_38[]{
    {"wideband-o2-sensor-5-equivalence-ratio", "ratio", 0, 2, 1, 32768, 0, -1, false, -1},
    {"wideband-o2-sensor-5-current", "mA", 2, 2, 1, 256, -128, -1, false, -1}
};
constexpr Field fields_39[]{
    {"wideband-o2-sensor-6-equivalence-ratio", "ratio", 0, 2, 1, 32768, 0, -1, false, -1},
    {"wideband-o2-sensor-6-current", "mA", 2, 2, 1, 256, -128, -1, false, -1}
};
constexpr Field fields_3A[]{
    {"wideband-o2-sensor-7-equivalence-ratio", "ratio", 0, 2, 1, 32768, 0, -1, false, -1},
    {"wideband-o2-sensor-7-current", "mA", 2, 2, 1, 256, -128, -1, false, -1}
};
constexpr Field fields_3B[]{
    {"wideband-o2-sensor-8-equivalence-ratio", "ratio", 0, 2, 1, 32768, 0, -1, false, -1},
    {"wideband-o2-sensor-8-current", "mA", 2, 2, 1, 256, -128, -1, false, -1}
};
constexpr Field fields_64[]{
    {"engine-torque-idle", "%", 0, 1, 1, 1, -125, -1, false, -1},
    {"engine-torque-point-2", "%", 1, 1, 1, 1, -125, -1, false, -1},
    {"engine-torque-point-3", "%", 2, 1, 1, 1, -125, -1, false, -1},
    {"engine-torque-point-4", "%", 3, 1, 1, 1, -125, -1, false, -1},
    {"engine-torque-point-5", "%", 4, 1, 1, 1, -125, -1, false, -1}
};
constexpr Field fields_66[]{
    {"maf-a", "g/s", 1, 2, 1, 32, 0, 0, false, -1},
    {"maf-b", "g/s", 3, 2, 1, 32, 0, 1, false, -1}
};
constexpr Field fields_67[]{
    {"coolant-1", "degC", 1, 1, 1, 1, -40, 0, false, -1},
    {"coolant-2", "degC", 2, 1, 1, 1, -40, 1, false, -1}
};
constexpr Field fields_68[]{
    {"intake-temperature-bank-1-sensor-1", "degC", 1, 1, 1, 1, -40, 0, false, -1},
    {"intake-temperature-bank-1-sensor-2", "degC", 2, 1, 1, 1, -40, 1, false, -1},
    {"intake-temperature-bank-1-sensor-3", "degC", 3, 1, 1, 1, -40, 2, false, -1},
    {"intake-temperature-bank-2-sensor-1", "degC", 4, 1, 1, 1, -40, 3, false, -1},
    {"intake-temperature-bank-2-sensor-2", "degC", 5, 1, 1, 1, -40, 4, false, -1},
    {"intake-temperature-bank-2-sensor-3", "degC", 6, 1, 1, 1, -40, 5, false, -1}
};
constexpr Field fields_69[]{
    {"egr-a-command", "%", 1, 1, 100, 255, 0, 0, false, -1},
    {"egr-a-actual", "%", 2, 1, 100, 255, 0, 1, false, -1},
    {"egr-a-error", "%", 3, 1, 100, 128, -100, 2, false, -1},
    {"egr-b-command", "%", 4, 1, 100, 255, 0, 3, false, -1},
    {"egr-b-actual", "%", 5, 1, 100, 255, 0, 4, false, -1},
    {"egr-b-error", "%", 6, 1, 100, 128, -100, 5, false, -1}
};
constexpr Field fields_6A[]{
    {"diesel-intake-a-command", "%", 1, 1, 100, 255, 0, 0, false, -1},
    {"diesel-intake-a-actual", "%", 2, 1, 100, 255, 0, 1, false, -1},
    {"diesel-intake-b-command", "%", 3, 1, 100, 255, 0, 2, false, -1},
    {"diesel-intake-b-actual", "%", 4, 1, 100, 255, 0, 3, false, -1}
};
constexpr Field fields_6C[]{
    {"throttle-a-command", "%", 1, 1, 100, 255, 0, 0, false, -1},
    {"throttle-a-actual", "%", 2, 1, 100, 255, 0, 1, false, -1},
    {"throttle-b-command", "%", 3, 1, 100, 255, 0, 2, false, -1},
    {"throttle-b-actual", "%", 4, 1, 100, 255, 0, 3, false, -1}
};
constexpr Field fields_6D[]{
    {"fuel-rail-a-command", "kPa", 1, 2, 10, 1, 0, 0, false, -1},
    {"fuel-rail-a-pressure", "kPa", 3, 2, 10, 1, 0, 1, false, -1},
    {"fuel-rail-a-temperature", "degC", 5, 1, 1, 1, -40, 2, false, -1},
    {"fuel-rail-b-command", "kPa", 6, 2, 10, 1, 0, 3, false, -1},
    {"fuel-rail-b-pressure", "kPa", 8, 2, 10, 1, 0, 4, false, -1},
    {"fuel-rail-b-temperature", "degC", 10, 1, 1, 1, -40, 5, false, -1}
};
constexpr Field fields_6E[]{
    {"injection-pressure-a-command", "kPa", 1, 2, 10, 1, 0, 0, false, -1},
    {"injection-pressure-a-actual", "kPa", 3, 2, 10, 1, 0, 1, false, -1},
    {"injection-pressure-b-command", "kPa", 5, 2, 10, 1, 0, 2, false, -1},
    {"injection-pressure-b-actual", "kPa", 7, 2, 10, 1, 0, 3, false, -1}
};
constexpr Field fields_70[]{
    {"boost-a-command", "kPa", 1, 2, 1, 32, 0, 0, false, -1},
    {"boost-a-actual", "kPa", 3, 2, 1, 32, 0, 1, false, -1},
    {"boost-b-command", "kPa", 5, 2, 1, 32, 0, 3, false, -1},
    {"boost-b-actual", "kPa", 7, 2, 1, 32, 0, 4, false, -1}
};
constexpr Field fields_71[]{
    {"vgt-a-command", "%", 1, 1, 100, 255, 0, 0, false, -1},
    {"vgt-a-actual", "%", 2, 1, 100, 255, 0, 1, false, -1},
    {"vgt-b-command", "%", 3, 1, 100, 255, 0, 3, false, -1},
    {"vgt-b-actual", "%", 4, 1, 100, 255, 0, 4, false, -1}
};
constexpr Field fields_72[]{
    {"wastegate-a-command", "%", 1, 1, 100, 255, 0, 0, false, -1},
    {"wastegate-a-actual", "%", 2, 1, 100, 255, 0, 1, false, -1},
    {"wastegate-b-command", "%", 3, 1, 100, 255, 0, 2, false, -1},
    {"wastegate-b-actual", "%", 4, 1, 100, 255, 0, 3, false, -1}
};
constexpr Field fields_73[]{
    {"exhaust-pressure-bank-1", "kPa", 1, 2, 1, 100, 0, 0, false, -1},
    {"exhaust-pressure-bank-2", "kPa", 3, 2, 1, 100, 0, 1, false, -1}
};
constexpr Field fields_74[]{
    {"turbo-a-rpm", "rpm", 1, 2, 10, 1, 0, 0, false, -1},
    {"turbo-b-rpm", "rpm", 3, 2, 10, 1, 0, 1, false, -1}
};
constexpr Field fields_75[]{
    {"turbo-a-compressor-inlet", "degC", 1, 1, 1, 1, -40, 0, false, -1},
    {"turbo-a-compressor-outlet", "degC", 2, 1, 1, 1, -40, 1, false, -1},
    {"turbo-a-turbine-inlet", "degC", 3, 2, 1, 10, -40, 2, false, -1},
    {"turbo-a-turbine-outlet", "degC", 5, 2, 1, 10, -40, 3, false, -1}
};
constexpr Field fields_76[]{
    {"turbo-b-compressor-inlet", "degC", 1, 1, 1, 1, -40, 0, false, -1},
    {"turbo-b-compressor-outlet", "degC", 2, 1, 1, 1, -40, 1, false, -1},
    {"turbo-b-turbine-inlet", "degC", 3, 2, 1, 10, -40, 2, false, -1},
    {"turbo-b-turbine-outlet", "degC", 5, 2, 1, 10, -40, 3, false, -1}
};
constexpr Field fields_77[]{
    {"charge-air-temperature-bank-1-sensor-1", "degC", 1, 1, 1, 1, -40, 0, false, -1},
    {"charge-air-temperature-bank-1-sensor-2", "degC", 2, 1, 1, 1, -40, 1, false, -1},
    {"charge-air-temperature-bank-2-sensor-1", "degC", 3, 1, 1, 1, -40, 2, false, -1},
    {"charge-air-temperature-bank-2-sensor-2", "degC", 4, 1, 1, 1, -40, 3, false, -1}
};
constexpr Field fields_78[]{
    {"egt-bank-1-sensor-1", "degC", 1, 2, 1, 10, -40, 0, false, -1},
    {"egt-bank-1-sensor-2", "degC", 3, 2, 1, 10, -40, 1, false, -1},
    {"egt-bank-1-sensor-3", "degC", 5, 2, 1, 10, -40, 2, false, -1},
    {"egt-bank-1-sensor-4", "degC", 7, 2, 1, 10, -40, 3, false, -1}
};
constexpr Field fields_79[]{
    {"egt-bank-2-sensor-1", "degC", 1, 2, 1, 10, -40, 0, false, -1},
    {"egt-bank-2-sensor-2", "degC", 3, 2, 1, 10, -40, 1, false, -1},
    {"egt-bank-2-sensor-3", "degC", 5, 2, 1, 10, -40, 2, false, -1},
    {"egt-bank-2-sensor-4", "degC", 7, 2, 1, 10, -40, 3, false, -1}
};
constexpr Field fields_7A[]{
    {"dpf-bank-1-delta", "kPa", 1, 2, 1, 100, 0, 0, true, -1},
    {"dpf-bank-1-inlet", "kPa", 3, 2, 1, 100, 0, 1, false, -1},
    {"dpf-bank-1-outlet", "kPa", 5, 2, 1, 100, 0, 2, false, -1}
};
constexpr Field fields_7B[]{
    {"dpf-bank-2-delta", "kPa", 1, 2, 1, 100, 0, 0, true, -1},
    {"dpf-bank-2-inlet", "kPa", 3, 2, 1, 100, 0, 1, false, -1},
    {"dpf-bank-2-outlet", "kPa", 5, 2, 1, 100, 0, 2, false, -1}
};
constexpr Field fields_7C[]{
    {"dpf-bank-1-inlet-temperature", "degC", 1, 2, 1, 10, -40, 0, false, -1},
    {"dpf-bank-1-outlet-temperature", "degC", 3, 2, 1, 10, -40, 1, false, -1},
    {"dpf-bank-2-inlet-temperature", "degC", 5, 2, 1, 10, -40, 2, false, -1},
    {"dpf-bank-2-outlet-temperature", "degC", 7, 2, 1, 10, -40, 3, false, -1}
};
constexpr Field fields_7F[]{
    {"engine-total-runtime", "s", 1, 4, 1, 1, 0, 0, false, -1},
    {"engine-idle-runtime", "s", 5, 4, 1, 1, 0, 1, false, -1},
    {"engine-pto-runtime", "s", 9, 4, 1, 1, 0, 2, false, -1}
};
constexpr Field fields_98[]{
    {"egt-bank-1-sensor-5", "degC", 1, 2, 1, 10, -40, 0, false, -1},
    {"egt-bank-1-sensor-6", "degC", 3, 2, 1, 10, -40, 1, false, -1},
    {"egt-bank-1-sensor-7", "degC", 5, 2, 1, 10, -40, 2, false, -1},
    {"egt-bank-1-sensor-8", "degC", 7, 2, 1, 10, -40, 3, false, -1}
};
constexpr Field fields_99[]{
    {"egt-bank-2-sensor-5", "degC", 1, 2, 1, 10, -40, 0, false, -1},
    {"egt-bank-2-sensor-6", "degC", 3, 2, 1, 10, -40, 1, false, -1},
    {"egt-bank-2-sensor-7", "degC", 5, 2, 1, 10, -40, 2, false, -1},
    {"egt-bank-2-sensor-8", "degC", 7, 2, 1, 10, -40, 3, false, -1}
};
constexpr Field fields_6B[]{
    {"egr-temperature-a", "degC", 1, 1, 1, 1, -40, 0, false, -1},
    {"egr-temperature-c", "degC", 2, 1, 1, 1, -40, 1, false, -1},
    {"egr-temperature-b", "degC", 3, 1, 1, 1, -40, 2, false, -1},
    {"egr-temperature-d", "degC", 4, 1, 1, 1, -40, 3, false, -1}
};
constexpr Field fields_6F[]{
    {"compressor-inlet-pressure-a", "kPa", 1, 1, 1, 1, 0, 0, false, -1},
    {"compressor-inlet-pressure-b", "kPa", 2, 1, 1, 1, 0, 1, false, -1}
};
constexpr Field fields_81[]{
    {"aecd-1-timer-1", "s", 1, 4, 1, 1, 0, 0, false, -1},
    {"aecd-1-timer-2", "s", 5, 4, 1, 1, 0, 0, false, -1},
    {"aecd-2-timer-1", "s", 9, 4, 1, 1, 0, 1, false, -1},
    {"aecd-2-timer-2", "s", 13, 4, 1, 1, 0, 1, false, -1},
    {"aecd-3-timer-1", "s", 17, 4, 1, 1, 0, 2, false, -1},
    {"aecd-3-timer-2", "s", 21, 4, 1, 1, 0, 2, false, -1},
    {"aecd-4-timer-1", "s", 25, 4, 1, 1, 0, 3, false, -1},
    {"aecd-4-timer-2", "s", 29, 4, 1, 1, 0, 3, false, -1},
    {"aecd-5-timer-1", "s", 33, 4, 1, 1, 0, 4, false, -1},
    {"aecd-5-timer-2", "s", 37, 4, 1, 1, 0, 4, false, -1}
};
constexpr Field fields_82[]{
    {"aecd-6-timer-1", "s", 1, 4, 1, 1, 0, 0, false, -1},
    {"aecd-6-timer-2", "s", 5, 4, 1, 1, 0, 0, false, -1},
    {"aecd-7-timer-1", "s", 9, 4, 1, 1, 0, 1, false, -1},
    {"aecd-7-timer-2", "s", 13, 4, 1, 1, 0, 1, false, -1},
    {"aecd-8-timer-1", "s", 17, 4, 1, 1, 0, 2, false, -1},
    {"aecd-8-timer-2", "s", 21, 4, 1, 1, 0, 2, false, -1},
    {"aecd-9-timer-1", "s", 25, 4, 1, 1, 0, 3, false, -1},
    {"aecd-9-timer-2", "s", 29, 4, 1, 1, 0, 3, false, -1},
    {"aecd-10-timer-1", "s", 33, 4, 1, 1, 0, 4, false, -1},
    {"aecd-10-timer-2", "s", 37, 4, 1, 1, 0, 4, false, -1}
};
constexpr Field fields_83[]{
    {"nox-bank-1-sensor-1", "ppm", 1, 2, 1, 1, 0, 0, false, -1},
    {"nox-bank-1-sensor-2", "ppm", 3, 2, 1, 1, 0, 1, false, -1},
    {"nox-bank-2-sensor-1", "ppm", 5, 2, 1, 1, 0, 2, false, -1},
    {"nox-bank-2-sensor-2", "ppm", 7, 2, 1, 1, 0, 3, false, -1}
};
constexpr Field fields_84[]{
    {"intake-manifold-surface-temperature", "degC", 0, 1, 1, 1, -40, -1, false, -1}
};
constexpr Field fields_85[]{
    {"reagent-consumption", "L/h", 1, 2, 1, 200, 0, 0, false, -1},
    {"reagent-demand", "L/h", 3, 2, 1, 200, 0, 1, false, -1},
    {"reagent-level", "%", 5, 1, 100, 255, 0, 2, false, -1},
    {"nox-warning-runtime", "s", 6, 4, 1, 1, 0, 3, false, -1}
};
constexpr Field fields_87[]{
    {"manifold-pressure-a", "kPa", 1, 2, 1, 32, 0, 0, false, -1},
    {"manifold-pressure-b", "kPa", 3, 2, 1, 32, 0, 1, false, -1}
};
constexpr Field fields_89[]{
    {"aecd-11-timer-1", "s", 1, 4, 1, 1, 0, 0, false, -1},
    {"aecd-11-timer-2", "s", 5, 4, 1, 1, 0, 0, false, -1},
    {"aecd-12-timer-1", "s", 9, 4, 1, 1, 0, 1, false, -1},
    {"aecd-12-timer-2", "s", 13, 4, 1, 1, 0, 1, false, -1},
    {"aecd-13-timer-1", "s", 17, 4, 1, 1, 0, 2, false, -1},
    {"aecd-13-timer-2", "s", 21, 4, 1, 1, 0, 2, false, -1},
    {"aecd-14-timer-1", "s", 25, 4, 1, 1, 0, 3, false, -1},
    {"aecd-14-timer-2", "s", 29, 4, 1, 1, 0, 3, false, -1},
    {"aecd-15-timer-1", "s", 33, 4, 1, 1, 0, 4, false, -1},
    {"aecd-15-timer-2", "s", 37, 4, 1, 1, 0, 4, false, -1}
};
constexpr Field fields_8A[]{
    {"aecd-16-timer-1", "s", 1, 4, 1, 1, 0, 0, false, -1},
    {"aecd-16-timer-2", "s", 5, 4, 1, 1, 0, 0, false, -1},
    {"aecd-17-timer-1", "s", 9, 4, 1, 1, 0, 1, false, -1},
    {"aecd-17-timer-2", "s", 13, 4, 1, 1, 0, 1, false, -1},
    {"aecd-18-timer-1", "s", 17, 4, 1, 1, 0, 2, false, -1},
    {"aecd-18-timer-2", "s", 21, 4, 1, 1, 0, 2, false, -1},
    {"aecd-19-timer-1", "s", 25, 4, 1, 1, 0, 3, false, -1},
    {"aecd-19-timer-2", "s", 29, 4, 1, 1, 0, 3, false, -1},
    {"aecd-20-timer-1", "s", 33, 4, 1, 1, 0, 4, false, -1},
    {"aecd-20-timer-2", "s", 37, 4, 1, 1, 0, 4, false, -1}
};
constexpr Field fields_8B[]{
    {"dpf-regeneration-trigger", "%", 2, 1, 100, 255, 0, 4, false, -1},
    {"dpf-regeneration-interval", "min", 3, 2, 1, 1, 0, 5, false, -1},
    {"dpf-regeneration-distance", "km", 5, 2, 1, 1, 0, 6, false, -1}
};
constexpr Field fields_8C[]{
    {"oxygen-concentration-bank-1-sensor-1", "%", 1, 2, 1526, 1000000, 0, 0, false, -1},
    {"oxygen-concentration-bank-1-sensor-2", "%", 3, 2, 1526, 1000000, 0, 1, false, -1},
    {"oxygen-concentration-bank-2-sensor-1", "%", 5, 2, 1526, 1000000, 0, 2, false, -1},
    {"oxygen-concentration-bank-2-sensor-2", "%", 7, 2, 1526, 1000000, 0, 3, false, -1},
    {"oxygen-lambda-bank-1-sensor-1", "ratio", 9, 2, 122, 1000000, 0, 4, false, -1},
    {"oxygen-lambda-bank-1-sensor-2", "ratio", 11, 2, 122, 1000000, 0, 5, false, -1},
    {"oxygen-lambda-bank-2-sensor-1", "ratio", 13, 2, 122, 1000000, 0, 6, false, -1},
    {"oxygen-lambda-bank-2-sensor-2", "ratio", 15, 2, 122, 1000000, 0, 7, false, -1}
};
constexpr Field fields_8D[]{
    {"absolute-throttle-g", "%", 0, 1, 100, 255, 0, -1, false, -1}
};
constexpr Field fields_8E[]{
    {"engine-friction-torque", "%", 0, 1, 1, 1, -125, -1, false, -1}
};
constexpr Field fields_8F[]{
    {"pm-sensor-bank-1-output", "%", 2, 2, 1, 100, 0, 1, false, -1},
    {"pm-sensor-bank-2-output", "%", 5, 2, 1, 100, 0, 3, false, -1}
};
constexpr Field fields_93[]{
    {"cumulative-continuous-mi", "h", 1, 2, 1, 1, 0, 0, false, -1}
};
constexpr Field fields_94[]{
    {"reagent-quality-counter", "h", 2, 2, 1, 1, 0, 1, false, -1},
    {"reagent-consumption-counter", "h", 4, 2, 1, 1, 0, 2, false, -1},
    {"reagent-dosing-counter", "h", 6, 2, 1, 1, 0, 3, false, -1},
    {"egr-valve-counter", "h", 8, 2, 1, 1, 0, 4, false, -1},
    {"nox-monitoring-counter", "h", 10, 2, 1, 1, 0, 5, false, -1}
};
constexpr Field fields_9C[]{
    {"oxygen-concentration-bank-1-sensor-3", "%", 1, 2, 1526, 1000000, 0, 0, false, -1},
    {"oxygen-concentration-bank-1-sensor-4", "%", 3, 2, 1526, 1000000, 0, 1, false, -1},
    {"oxygen-concentration-bank-2-sensor-3", "%", 5, 2, 1526, 1000000, 0, 2, false, -1},
    {"oxygen-concentration-bank-2-sensor-4", "%", 7, 2, 1526, 1000000, 0, 3, false, -1},
    {"oxygen-lambda-bank-1-sensor-3", "ratio", 9, 2, 122, 1000000, 0, 4, false, -1},
    {"oxygen-lambda-bank-1-sensor-4", "ratio", 11, 2, 122, 1000000, 0, 5, false, -1},
    {"oxygen-lambda-bank-2-sensor-3", "ratio", 13, 2, 122, 1000000, 0, 6, false, -1},
    {"oxygen-lambda-bank-2-sensor-4", "ratio", 15, 2, 122, 1000000, 0, 7, false, -1}
};
constexpr Definition definitions[]{
    {0x14, 2, 0x00, fields_14},
    {0x15, 2, 0x00, fields_15},
    {0x16, 2, 0x00, fields_16},
    {0x17, 2, 0x00, fields_17},
    {0x18, 2, 0x00, fields_18},
    {0x19, 2, 0x00, fields_19},
    {0x1A, 2, 0x00, fields_1A},
    {0x1B, 2, 0x00, fields_1B},
    {0x24, 4, 0x00, fields_24},
    {0x25, 4, 0x00, fields_25},
    {0x26, 4, 0x00, fields_26},
    {0x27, 4, 0x00, fields_27},
    {0x28, 4, 0x00, fields_28},
    {0x29, 4, 0x00, fields_29},
    {0x2A, 4, 0x00, fields_2A},
    {0x2B, 4, 0x00, fields_2B},
    {0x34, 4, 0x00, fields_34},
    {0x35, 4, 0x00, fields_35},
    {0x36, 4, 0x00, fields_36},
    {0x37, 4, 0x00, fields_37},
    {0x38, 4, 0x00, fields_38},
    {0x39, 4, 0x00, fields_39},
    {0x3A, 4, 0x00, fields_3A},
    {0x3B, 4, 0x00, fields_3B},
    {0x64, 5, 0x00, fields_64},
    {0x66, 5, 0x03, fields_66},
    {0x67, 3, 0x03, fields_67},
    {0x68, 7, 0x3F, fields_68},
    {0x69, 7, 0x3F, fields_69},
    {0x6A, 5, 0x0F, fields_6A},
    {0x6C, 5, 0x0F, fields_6C},
    {0x6D, 11, 0x3F, fields_6D},
    {0x6E, 9, 0x0F, fields_6E},
    {0x70, 10, 0x3F, fields_70},
    {0x71, 6, 0x3F, fields_71},
    {0x72, 5, 0x0F, fields_72},
    {0x73, 5, 0x03, fields_73},
    {0x74, 5, 0x03, fields_74},
    {0x75, 7, 0x0F, fields_75},
    {0x76, 7, 0x0F, fields_76},
    {0x77, 5, 0x0F, fields_77},
    {0x78, 9, 0x0F, fields_78},
    {0x79, 9, 0x0F, fields_79},
    {0x7A, 7, 0x07, fields_7A},
    {0x7B, 7, 0x07, fields_7B},
    {0x7C, 9, 0x0F, fields_7C},
    {0x7F, 13, 0x07, fields_7F},
    {0x98, 9, 0x0F, fields_98},
    {0x99, 9, 0x0F, fields_99},
    {0x6B, 5, 0xFF, fields_6B},
    {0x6F, 3, 0x0F, fields_6F},
    {0x81, 41, 0x1F, fields_81},
    {0x82, 41, 0x1F, fields_82},
    {0x83, 9, 0x0F, fields_83},
    {0x84, 1, 0x00, fields_84},
    {0x85, 10, 0x0F, fields_85},
    {0x87, 5, 0x03, fields_87},
    {0x89, 41, 0x1F, fields_89},
    {0x8A, 41, 0x1F, fields_8A},
    {0x8B, 7, 0x7F, fields_8B},
    {0x8C, 17, 0xFF, fields_8C},
    {0x8D, 1, 0x00, fields_8D},
    {0x8E, 1, 0x00, fields_8E},
    {0x8F, 7, 0x0F, fields_8F},
    {0x93, 3, 0x01, fields_93},
    {0x94, 12, 0x3F, fields_94},
    {0x9C, 17, 0xFF, fields_9C}
};
consteval bool valid_definitions() {
    std::array<bool,256> seen{};
    for(const auto& d:definitions){
        if(d.pid>255||seen[d.pid]||!d.width||d.fields.empty())return false;
        seen[d.pid]=true;
        for(const auto& f:d.fields){
            if(f.name.empty()||f.unit.empty()||f.width<1||f.width>4||
               f.offset+f.width>d.width||f.divisor<=0||f.bit < -1||f.bit>7||
               (f.bit>=0 && !(d.support_mask&(1u<<f.bit))))return false;
        }
    }
    return true;
}
static_assert(valid_definitions());
const Definition* find(unsigned pid) {
    for(const auto& d:definitions)if(d.pid==pid)return &d;
    return nullptr;
}
std::uint64_t raw_value(Bytes data,unsigned offset,unsigned width) {
    std::uint64_t result=0;
    for(unsigned i=0;i<width;++i)result=(result<<8)|data[offset+i];
    return result;
}
void quote(std::ostream& out,std::string_view value) {
    out<<'"';
    for(const char c:value){if(c=='"'||c=='\\')out<<'\\';out<<c;}
    out<<'"';
}
void hex(std::ostream& out,Bytes data) {
    constexpr std::string_view digits="0123456789ABCDEF";
    out<<'"';for(auto v:data)out<<digits[v>>4]<<digits[v&15];out<<'"';
}
}
std::optional<unsigned> multi_width(unsigned pid) {
    const auto* d=find(pid);return d?std::optional<unsigned>{d->width}:std::nullopt;
}
std::optional<MultiRecord> multi_signal(const Observation& row,const ScalingContext* context) {
    if(row.request.size()!=2||row.request[0]!=1)return {};
    const auto data=payload(row);
    const unsigned pid=row.request[1];
    const auto* d=find(pid);
    if(!d||!data||data->size()!=d->width)return {};
    const unsigned support=(*data)[0];
    if(d->support_mask&&(support&~d->support_mask))return {};
    const unsigned alternate=pid==0x6b?4:pid==0x6f?2:0;
    if(alternate&&(support&(support>>alternate)&((1u<<alternate)-1)))return {};
    const bool contextual=dependency(pid).has_value();
    const auto decoded=contextual&&context?scale(row,*context):std::optional<ScalingRecord>{};
    MultiRecord result{pid,{}};
    for(const auto& field:d->fields){
        int multiplier=field.multiplier;
        if(alternate){
            if(support&(1u<<(field.bit+alternate)))multiplier=pid==0x6b?4:8;
            else if(!(support&(1u<<field.bit)))continue;
        }else if(field.bit>=0&&!(support&(1u<<field.bit)))continue;
        const auto raw=raw_value(*data,field.offset,field.width);
        if(field.unavailable>=0&&raw==static_cast<std::uint64_t>(field.unavailable))continue;
        std::int64_t encoded=static_cast<std::int64_t>(raw);
        if(field.signed_value&&raw>=(std::uint64_t{1}<<(8*field.width-1)))
            encoded-=std::int64_t{1}<<(8*field.width);
        std::optional<double> number;
        if(contextual){
            if(decoded){
                const auto it=std::find_if(decoded->signals.begin(),decoded->signals.end(),
                    [&](const Signal& value){return value.name==field.name&&value.raw==raw;});
                if(it!=decoded->signals.end())number=it->number;
            }
        }else number=static_cast<double>(encoded)*multiplier/field.divisor+field.bias;
        result.signals.push_back({field.name,field.unit,raw,number,contextual});
    }
    return result;
}
void write_multi_json(std::ostream& out,std::span<const Observation> rows,const ScalingContext* context) {
    out<<'[';
    bool first=true;
    for(const auto& row:rows){
        const auto decoded=multi_signal(row,context);
        if(!decoded)continue;
        if(!first)out<<',';
        first=false;
        out<<"{\"request_hex\":";hex(out,row.request);out<<",\"signals\":[";
        bool first_signal=true;
        for(const auto& value:decoded->signals){
            if(!first_signal)out<<',';
            first_signal=false;
            out<<"{\"pid\":"<<decoded->pid<<",\"name\":";quote(out,value.name);
            out<<",\"unit\":";quote(out,value.unit);out<<",\"raw_encoded\":"<<value.raw
                <<",\"context_required\":"<<(value.context_required?"true":"false")<<",\"number\":";
            if(value.number)out<<std::setprecision(15)<<*value.number;else out<<"null";
            out<<'}';
        }
        out<<"]}";
    }
    out<<']';
}
}
