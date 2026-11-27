using System.Globalization;

namespace CamaroDiag.J2534Bridge;

/// <summary>Explicit historical SAE J1979DA multi-signal Mode 01 records.
/// The caller binds the physical source and advertised PID. No inference
/// of unsupported fields, bank layout, monitor verdict or OBDonUDS scope.</summary>
internal static class ObdJ1979MultiSignal
{
    private sealed record Field(string Name,string Unit,int Offset,int Width,
        int Multiplier,int Divisor,int Bias,int Bit,bool Signed,long? Unavailable);
    private sealed record Definition(int Width,int SupportMask,Field[] Fields);
    internal sealed record Signal(int Pid,string Name,string Unit,double Number,long RawEncoded);
    internal sealed record Record(string RequestHex,IReadOnlyList<Signal> Signals);

    private static readonly Dictionary<int,Definition> Definitions = new()
    {
        [0x14] = new(2,0x00,[
            new Field("o2-sensor-1-voltage", "V", 0, 1, 1, 200, 0, -1, false, ),
            new Field("o2-sensor-1-short-term-trim", "%", 1, 1, 100, 128, -100, -1, false, 255),
        ]),
        [0x15] = new(2,0x00,[
            new Field("o2-sensor-2-voltage", "V", 0, 1, 1, 200, 0, -1, false, ),
            new Field("o2-sensor-2-short-term-trim", "%", 1, 1, 100, 128, -100, -1, false, 255),
        ]),
        [0x16] = new(2,0x00,[
            new Field("o2-sensor-3-voltage", "V", 0, 1, 1, 200, 0, -1, false, ),
            new Field("o2-sensor-3-short-term-trim", "%", 1, 1, 100, 128, -100, -1, false, 255),
        ]),
        [0x17] = new(2,0x00,[
            new Field("o2-sensor-4-voltage", "V", 0, 1, 1, 200, 0, -1, false, ),
            new Field("o2-sensor-4-short-term-trim", "%", 1, 1, 100, 128, -100, -1, false, 255),
        ]),
        [0x18] = new(2,0x00,[
            new Field("o2-sensor-5-voltage", "V", 0, 1, 1, 200, 0, -1, false, ),
            new Field("o2-sensor-5-short-term-trim", "%", 1, 1, 100, 128, -100, -1, false, 255),
        ]),
        [0x19] = new(2,0x00,[
            new Field("o2-sensor-6-voltage", "V", 0, 1, 1, 200, 0, -1, false, ),
            new Field("o2-sensor-6-short-term-trim", "%", 1, 1, 100, 128, -100, -1, false, 255),
        ]),
        [0x1A] = new(2,0x00,[
            new Field("o2-sensor-7-voltage", "V", 0, 1, 1, 200, 0, -1, false, ),
            new Field("o2-sensor-7-short-term-trim", "%", 1, 1, 100, 128, -100, -1, false, 255),
        ]),
        [0x1B] = new(2,0x00,[
            new Field("o2-sensor-8-voltage", "V", 0, 1, 1, 200, 0, -1, false, ),
            new Field("o2-sensor-8-short-term-trim", "%", 1, 1, 100, 128, -100, -1, false, 255),
        ]),
        [0x24] = new(4,0x00,[
            new Field("wideband-o2-sensor-1-equivalence-ratio", "ratio", 0, 2, 1, 32768, 0, -1, false, ),
            new Field("wideband-o2-sensor-1-voltage", "V", 2, 2, 1, 8192, 0, -1, false, ),
        ]),
        [0x25] = new(4,0x00,[
            new Field("wideband-o2-sensor-2-equivalence-ratio", "ratio", 0, 2, 1, 32768, 0, -1, false, ),
            new Field("wideband-o2-sensor-2-voltage", "V", 2, 2, 1, 8192, 0, -1, false, ),
        ]),
        [0x26] = new(4,0x00,[
            new Field("wideband-o2-sensor-3-equivalence-ratio", "ratio", 0, 2, 1, 32768, 0, -1, false, ),
            new Field("wideband-o2-sensor-3-voltage", "V", 2, 2, 1, 8192, 0, -1, false, ),
        ]),
        [0x27] = new(4,0x00,[
            new Field("wideband-o2-sensor-4-equivalence-ratio", "ratio", 0, 2, 1, 32768, 0, -1, false, ),
            new Field("wideband-o2-sensor-4-voltage", "V", 2, 2, 1, 8192, 0, -1, false, ),
        ]),
        [0x28] = new(4,0x00,[
            new Field("wideband-o2-sensor-5-equivalence-ratio", "ratio", 0, 2, 1, 32768, 0, -1, false, ),
            new Field("wideband-o2-sensor-5-voltage", "V", 2, 2, 1, 8192, 0, -1, false, ),
        ]),
        [0x29] = new(4,0x00,[
            new Field("wideband-o2-sensor-6-equivalence-ratio", "ratio", 0, 2, 1, 32768, 0, -1, false, ),
            new Field("wideband-o2-sensor-6-voltage", "V", 2, 2, 1, 8192, 0, -1, false, ),
        ]),
        [0x2A] = new(4,0x00,[
            new Field("wideband-o2-sensor-7-equivalence-ratio", "ratio", 0, 2, 1, 32768, 0, -1, false, ),
            new Field("wideband-o2-sensor-7-voltage", "V", 2, 2, 1, 8192, 0, -1, false, ),
        ]),
        [0x2B] = new(4,0x00,[
            new Field("wideband-o2-sensor-8-equivalence-ratio", "ratio", 0, 2, 1, 32768, 0, -1, false, ),
            new Field("wideband-o2-sensor-8-voltage", "V", 2, 2, 1, 8192, 0, -1, false, ),
        ]),
        [0x34] = new(4,0x00,[
            new Field("wideband-o2-sensor-1-equivalence-ratio", "ratio", 0, 2, 1, 32768, 0, -1, false, ),
            new Field("wideband-o2-sensor-1-current", "mA", 2, 2, 1, 256, -128, -1, false, ),
        ]),
        [0x35] = new(4,0x00,[
            new Field("wideband-o2-sensor-2-equivalence-ratio", "ratio", 0, 2, 1, 32768, 0, -1, false, ),
            new Field("wideband-o2-sensor-2-current", "mA", 2, 2, 1, 256, -128, -1, false, ),
        ]),
        [0x36] = new(4,0x00,[
            new Field("wideband-o2-sensor-3-equivalence-ratio", "ratio", 0, 2, 1, 32768, 0, -1, false, ),
            new Field("wideband-o2-sensor-3-current", "mA", 2, 2, 1, 256, -128, -1, false, ),
        ]),
        [0x37] = new(4,0x00,[
            new Field("wideband-o2-sensor-4-equivalence-ratio", "ratio", 0, 2, 1, 32768, 0, -1, false, ),
            new Field("wideband-o2-sensor-4-current", "mA", 2, 2, 1, 256, -128, -1, false, ),
        ]),
        [0x38] = new(4,0x00,[
            new Field("wideband-o2-sensor-5-equivalence-ratio", "ratio", 0, 2, 1, 32768, 0, -1, false, ),
            new Field("wideband-o2-sensor-5-current", "mA", 2, 2, 1, 256, -128, -1, false, ),
        ]),
        [0x39] = new(4,0x00,[
            new Field("wideband-o2-sensor-6-equivalence-ratio", "ratio", 0, 2, 1, 32768, 0, -1, false, ),
            new Field("wideband-o2-sensor-6-current", "mA", 2, 2, 1, 256, -128, -1, false, ),
        ]),
        [0x3A] = new(4,0x00,[
            new Field("wideband-o2-sensor-7-equivalence-ratio", "ratio", 0, 2, 1, 32768, 0, -1, false, ),
            new Field("wideband-o2-sensor-7-current", "mA", 2, 2, 1, 256, -128, -1, false, ),
        ]),
        [0x3B] = new(4,0x00,[
            new Field("wideband-o2-sensor-8-equivalence-ratio", "ratio", 0, 2, 1, 32768, 0, -1, false, ),
            new Field("wideband-o2-sensor-8-current", "mA", 2, 2, 1, 256, -128, -1, false, ),
        ]),
        [0x64] = new(5,0x00,[
            new Field("engine-torque-idle", "%", 0, 1, 1, 1, -125, -1, false, ),
            new Field("engine-torque-point-2", "%", 1, 1, 1, 1, -125, -1, false, ),
            new Field("engine-torque-point-3", "%", 2, 1, 1, 1, -125, -1, false, ),
            new Field("engine-torque-point-4", "%", 3, 1, 1, 1, -125, -1, false, ),
            new Field("engine-torque-point-5", "%", 4, 1, 1, 1, -125, -1, false, ),
        ]),
        [0x66] = new(5,0x03,[
            new Field("maf-a", "g/s", 1, 2, 1, 32, 0, 0, false, ),
            new Field("maf-b", "g/s", 3, 2, 1, 32, 0, 1, false, ),
        ]),
        [0x67] = new(3,0x03,[
            new Field("coolant-1", "degC", 1, 1, 1, 1, -40, 0, false, ),
            new Field("coolant-2", "degC", 2, 1, 1, 1, -40, 1, false, ),
        ]),
        [0x68] = new(7,0x3F,[
            new Field("intake-temperature-bank-1-sensor-1", "degC", 1, 1, 1, 1, -40, 0, false, ),
            new Field("intake-temperature-bank-1-sensor-2", "degC", 2, 1, 1, 1, -40, 1, false, ),
            new Field("intake-temperature-bank-1-sensor-3", "degC", 3, 1, 1, 1, -40, 2, false, ),
            new Field("intake-temperature-bank-2-sensor-1", "degC", 4, 1, 1, 1, -40, 3, false, ),
            new Field("intake-temperature-bank-2-sensor-2", "degC", 5, 1, 1, 1, -40, 4, false, ),
            new Field("intake-temperature-bank-2-sensor-3", "degC", 6, 1, 1, 1, -40, 5, false, ),
        ]),
        [0x69] = new(7,0x3F,[
            new Field("egr-a-command", "%", 1, 1, 100, 255, 0, 0, false, ),
            new Field("egr-a-actual", "%", 2, 1, 100, 255, 0, 1, false, ),
            new Field("egr-a-error", "%", 3, 1, 100, 128, -100, 2, false, ),
            new Field("egr-b-command", "%", 4, 1, 100, 255, 0, 3, false, ),
            new Field("egr-b-actual", "%", 5, 1, 100, 255, 0, 4, false, ),
            new Field("egr-b-error", "%", 6, 1, 100, 128, -100, 5, false, ),
        ]),
        [0x6A] = new(5,0x0F,[
            new Field("diesel-intake-a-command", "%", 1, 1, 100, 255, 0, 0, false, ),
            new Field("diesel-intake-a-actual", "%", 2, 1, 100, 255, 0, 1, false, ),
            new Field("diesel-intake-b-command", "%", 3, 1, 100, 255, 0, 2, false, ),
            new Field("diesel-intake-b-actual", "%", 4, 1, 100, 255, 0, 3, false, ),
        ]),
        [0x6C] = new(5,0x0F,[
            new Field("throttle-a-command", "%", 1, 1, 100, 255, 0, 0, false, ),
            new Field("throttle-a-actual", "%", 2, 1, 100, 255, 0, 1, false, ),
            new Field("throttle-b-command", "%", 3, 1, 100, 255, 0, 2, false, ),
            new Field("throttle-b-actual", "%", 4, 1, 100, 255, 0, 3, false, ),
        ]),
        [0x6D] = new(11,0x3F,[
            new Field("fuel-rail-a-command", "kPa", 1, 2, 10, 1, 0, 0, false, ),
            new Field("fuel-rail-a-pressure", "kPa", 3, 2, 10, 1, 0, 1, false, ),
            new Field("fuel-rail-a-temperature", "degC", 5, 1, 1, 1, -40, 2, false, ),
            new Field("fuel-rail-b-command", "kPa", 6, 2, 10, 1, 0, 3, false, ),
            new Field("fuel-rail-b-pressure", "kPa", 8, 2, 10, 1, 0, 4, false, ),
            new Field("fuel-rail-b-temperature", "degC", 10, 1, 1, 1, -40, 5, false, ),
        ]),
        [0x6E] = new(9,0x0F,[
            new Field("injection-pressure-a-command", "kPa", 1, 2, 10, 1, 0, 0, false, ),
            new Field("injection-pressure-a-actual", "kPa", 3, 2, 10, 1, 0, 1, false, ),
            new Field("injection-pressure-b-command", "kPa", 5, 2, 10, 1, 0, 2, false, ),
            new Field("injection-pressure-b-actual", "kPa", 7, 2, 10, 1, 0, 3, false, ),
        ]),
        [0x70] = new(10,0x3F,[
            new Field("boost-a-command", "kPa", 1, 2, 1, 32, 0, 0, false, ),
            new Field("boost-a-actual", "kPa", 3, 2, 1, 32, 0, 1, false, ),
            new Field("boost-b-command", "kPa", 5, 2, 1, 32, 0, 3, false, ),
            new Field("boost-b-actual", "kPa", 7, 2, 1, 32, 0, 4, false, ),
        ]),
        [0x71] = new(6,0x3F,[
            new Field("vgt-a-command", "%", 1, 1, 100, 255, 0, 0, false, ),
            new Field("vgt-a-actual", "%", 2, 1, 100, 255, 0, 1, false, ),
            new Field("vgt-b-command", "%", 3, 1, 100, 255, 0, 3, false, ),
            new Field("vgt-b-actual", "%", 4, 1, 100, 255, 0, 4, false, ),
        ]),
        [0x72] = new(5,0x0F,[
            new Field("wastegate-a-command", "%", 1, 1, 100, 255, 0, 0, false, ),
            new Field("wastegate-a-actual", "%", 2, 1, 100, 255, 0, 1, false, ),
            new Field("wastegate-b-command", "%", 3, 1, 100, 255, 0, 2, false, ),
            new Field("wastegate-b-actual", "%", 4, 1, 100, 255, 0, 3, false, ),
        ]),
        [0x73] = new(5,0x03,[
            new Field("exhaust-pressure-bank-1", "kPa", 1, 2, 1, 100, 0, 0, false, ),
            new Field("exhaust-pressure-bank-2", "kPa", 3, 2, 1, 100, 0, 1, false, ),
        ]),
        [0x74] = new(5,0x03,[
            new Field("turbo-a-rpm", "rpm", 1, 2, 10, 1, 0, 0, false, ),
            new Field("turbo-b-rpm", "rpm", 3, 2, 10, 1, 0, 1, false, ),
        ]),
        [0x75] = new(7,0x0F,[
            new Field("turbo-a-compressor-inlet", "degC", 1, 1, 1, 1, -40, 0, false, ),
            new Field("turbo-a-compressor-outlet", "degC", 2, 1, 1, 1, -40, 1, false, ),
            new Field("turbo-a-turbine-inlet", "degC", 3, 2, 1, 10, -40, 2, false, ),
            new Field("turbo-a-turbine-outlet", "degC", 5, 2, 1, 10, -40, 3, false, ),
        ]),
        [0x76] = new(7,0x0F,[
            new Field("turbo-b-compressor-inlet", "degC", 1, 1, 1, 1, -40, 0, false, ),
            new Field("turbo-b-compressor-outlet", "degC", 2, 1, 1, 1, -40, 1, false, ),
            new Field("turbo-b-turbine-inlet", "degC", 3, 2, 1, 10, -40, 2, false, ),
            new Field("turbo-b-turbine-outlet", "degC", 5, 2, 1, 10, -40, 3, false, ),
        ]),
        [0x77] = new(5,0x0F,[
            new Field("charge-air-temperature-bank-1-sensor-1", "degC", 1, 1, 1, 1, -40, 0, false, ),
            new Field("charge-air-temperature-bank-1-sensor-2", "degC", 2, 1, 1, 1, -40, 1, false, ),
            new Field("charge-air-temperature-bank-2-sensor-1", "degC", 3, 1, 1, 1, -40, 2, false, ),
            new Field("charge-air-temperature-bank-2-sensor-2", "degC", 4, 1, 1, 1, -40, 3, false, ),
        ]),
        [0x78] = new(9,0x0F,[
            new Field("egt-bank-1-sensor-1", "degC", 1, 2, 1, 10, -40, 0, false, ),
            new Field("egt-bank-1-sensor-2", "degC", 3, 2, 1, 10, -40, 1, false, ),
            new Field("egt-bank-1-sensor-3", "degC", 5, 2, 1, 10, -40, 2, false, ),
            new Field("egt-bank-1-sensor-4", "degC", 7, 2, 1, 10, -40, 3, false, ),
        ]),
        [0x79] = new(9,0x0F,[
            new Field("egt-bank-2-sensor-1", "degC", 1, 2, 1, 10, -40, 0, false, ),
            new Field("egt-bank-2-sensor-2", "degC", 3, 2, 1, 10, -40, 1, false, ),
            new Field("egt-bank-2-sensor-3", "degC", 5, 2, 1, 10, -40, 2, false, ),
            new Field("egt-bank-2-sensor-4", "degC", 7, 2, 1, 10, -40, 3, false, ),
        ]),
        [0x7A] = new(7,0x07,[
            new Field("dpf-bank-1-delta", "kPa", 1, 2, 1, 100, 0, 0, true, ),
            new Field("dpf-bank-1-inlet", "kPa", 3, 2, 1, 100, 0, 1, false, ),
            new Field("dpf-bank-1-outlet", "kPa", 5, 2, 1, 100, 0, 2, false, ),
        ]),
        [0x7B] = new(7,0x07,[
            new Field("dpf-bank-2-delta", "kPa", 1, 2, 1, 100, 0, 0, true, ),
            new Field("dpf-bank-2-inlet", "kPa", 3, 2, 1, 100, 0, 1, false, ),
            new Field("dpf-bank-2-outlet", "kPa", 5, 2, 1, 100, 0, 2, false, ),
        ]),
        [0x7C] = new(9,0x0F,[
            new Field("dpf-bank-1-inlet-temperature", "degC", 1, 2, 1, 10, -40, 0, false, ),
            new Field("dpf-bank-1-outlet-temperature", "degC", 3, 2, 1, 10, -40, 1, false, ),
            new Field("dpf-bank-2-inlet-temperature", "degC", 5, 2, 1, 10, -40, 2, false, ),
            new Field("dpf-bank-2-outlet-temperature", "degC", 7, 2, 1, 10, -40, 3, false, ),
        ]),
        [0x7F] = new(13,0x07,[
            new Field("engine-total-runtime", "s", 1, 4, 1, 1, 0, 0, false, ),
            new Field("engine-idle-runtime", "s", 5, 4, 1, 1, 0, 1, false, ),
            new Field("engine-pto-runtime", "s", 9, 4, 1, 1, 0, 2, false, ),
        ]),
        [0x98] = new(9,0x0F,[
            new Field("egt-bank-1-sensor-5", "degC", 1, 2, 1, 10, -40, 0, false, ),
            new Field("egt-bank-1-sensor-6", "degC", 3, 2, 1, 10, -40, 1, false, ),
            new Field("egt-bank-1-sensor-7", "degC", 5, 2, 1, 10, -40, 2, false, ),
            new Field("egt-bank-1-sensor-8", "degC", 7, 2, 1, 10, -40, 3, false, ),
        ]),
        [0x99] = new(9,0x0F,[
            new Field("egt-bank-2-sensor-5", "degC", 1, 2, 1, 10, -40, 0, false, ),
            new Field("egt-bank-2-sensor-6", "degC", 3, 2, 1, 10, -40, 1, false, ),
            new Field("egt-bank-2-sensor-7", "degC", 5, 2, 1, 10, -40, 2, false, ),
            new Field("egt-bank-2-sensor-8", "degC", 7, 2, 1, 10, -40, 3, false, ),
        ]),
        [0x6B] = new(5,0xFF,[
            new Field("egr-temperature-a", "degC", 1, 1, 1, 1, -40, 0, false, null),
            new Field("egr-temperature-c", "degC", 2, 1, 1, 1, -40, 1, false, null),
            new Field("egr-temperature-b", "degC", 3, 1, 1, 1, -40, 2, false, null),
            new Field("egr-temperature-d", "degC", 4, 1, 1, 1, -40, 3, false, null),
        ]),
        [0x6F] = new(3,0x0F,[
            new Field("compressor-inlet-pressure-a", "kPa", 1, 1, 1, 1, 0, 0, false, null),
            new Field("compressor-inlet-pressure-b", "kPa", 2, 1, 1, 1, 0, 1, false, null),
        ]),
        [0x81] = new(41,0x1F,[
            new Field("aecd-1-timer-1", "s", 1, 4, 1, 1, 0, 0, false, null),
            new Field("aecd-1-timer-2", "s", 5, 4, 1, 1, 0, 0, false, null),
            new Field("aecd-2-timer-1", "s", 9, 4, 1, 1, 0, 1, false, null),
            new Field("aecd-2-timer-2", "s", 13, 4, 1, 1, 0, 1, false, null),
            new Field("aecd-3-timer-1", "s", 17, 4, 1, 1, 0, 2, false, null),
            new Field("aecd-3-timer-2", "s", 21, 4, 1, 1, 0, 2, false, null),
            new Field("aecd-4-timer-1", "s", 25, 4, 1, 1, 0, 3, false, null),
            new Field("aecd-4-timer-2", "s", 29, 4, 1, 1, 0, 3, false, null),
            new Field("aecd-5-timer-1", "s", 33, 4, 1, 1, 0, 4, false, null),
            new Field("aecd-5-timer-2", "s", 37, 4, 1, 1, 0, 4, false, null),
        ]),
        [0x82] = new(41,0x1F,[
            new Field("aecd-6-timer-1", "s", 1, 4, 1, 1, 0, 0, false, null),
            new Field("aecd-6-timer-2", "s", 5, 4, 1, 1, 0, 0, false, null),
            new Field("aecd-7-timer-1", "s", 9, 4, 1, 1, 0, 1, false, null),
            new Field("aecd-7-timer-2", "s", 13, 4, 1, 1, 0, 1, false, null),
            new Field("aecd-8-timer-1", "s", 17, 4, 1, 1, 0, 2, false, null),
            new Field("aecd-8-timer-2", "s", 21, 4, 1, 1, 0, 2, false, null),
            new Field("aecd-9-timer-1", "s", 25, 4, 1, 1, 0, 3, false, null),
            new Field("aecd-9-timer-2", "s", 29, 4, 1, 1, 0, 3, false, null),
            new Field("aecd-10-timer-1", "s", 33, 4, 1, 1, 0, 4, false, null),
            new Field("aecd-10-timer-2", "s", 37, 4, 1, 1, 0, 4, false, null),
        ]),
        [0x83] = new(9,0x0F,[
            new Field("nox-bank-1-sensor-1", "ppm", 1, 2, 1, 1, 0, 0, false, null),
            new Field("nox-bank-1-sensor-2", "ppm", 3, 2, 1, 1, 0, 1, false, null),
            new Field("nox-bank-2-sensor-1", "ppm", 5, 2, 1, 1, 0, 2, false, null),
            new Field("nox-bank-2-sensor-2", "ppm", 7, 2, 1, 1, 0, 3, false, null),
        ]),
        [0x84] = new(1,0x00,[
            new Field("intake-manifold-surface-temperature", "degC", 0, 1, 1, 1, -40, -1, false, null),
        ]),
        [0x85] = new(10,0x0F,[
            new Field("reagent-consumption", "L/h", 1, 2, 1, 200, 0, 0, false, null),
            new Field("reagent-demand", "L/h", 3, 2, 1, 200, 0, 1, false, null),
            new Field("reagent-level", "%", 5, 1, 100, 255, 0, 2, false, null),
            new Field("nox-warning-runtime", "s", 6, 4, 1, 1, 0, 3, false, null),
        ]),
        [0x87] = new(5,0x03,[
            new Field("manifold-pressure-a", "kPa", 1, 2, 1, 32, 0, 0, false, null),
            new Field("manifold-pressure-b", "kPa", 3, 2, 1, 32, 0, 1, false, null),
        ]),
        [0x89] = new(41,0x1F,[
            new Field("aecd-11-timer-1", "s", 1, 4, 1, 1, 0, 0, false, null),
            new Field("aecd-11-timer-2", "s", 5, 4, 1, 1, 0, 0, false, null),
            new Field("aecd-12-timer-1", "s", 9, 4, 1, 1, 0, 1, false, null),
            new Field("aecd-12-timer-2", "s", 13, 4, 1, 1, 0, 1, false, null),
            new Field("aecd-13-timer-1", "s", 17, 4, 1, 1, 0, 2, false, null),
            new Field("aecd-13-timer-2", "s", 21, 4, 1, 1, 0, 2, false, null),
            new Field("aecd-14-timer-1", "s", 25, 4, 1, 1, 0, 3, false, null),
            new Field("aecd-14-timer-2", "s", 29, 4, 1, 1, 0, 3, false, null),
            new Field("aecd-15-timer-1", "s", 33, 4, 1, 1, 0, 4, false, null),
            new Field("aecd-15-timer-2", "s", 37, 4, 1, 1, 0, 4, false, null),
        ]),
        [0x8A] = new(41,0x1F,[
            new Field("aecd-16-timer-1", "s", 1, 4, 1, 1, 0, 0, false, null),
            new Field("aecd-16-timer-2", "s", 5, 4, 1, 1, 0, 0, false, null),
            new Field("aecd-17-timer-1", "s", 9, 4, 1, 1, 0, 1, false, null),
            new Field("aecd-17-timer-2", "s", 13, 4, 1, 1, 0, 1, false, null),
            new Field("aecd-18-timer-1", "s", 17, 4, 1, 1, 0, 2, false, null),
            new Field("aecd-18-timer-2", "s", 21, 4, 1, 1, 0, 2, false, null),
            new Field("aecd-19-timer-1", "s", 25, 4, 1, 1, 0, 3, false, null),
            new Field("aecd-19-timer-2", "s", 29, 4, 1, 1, 0, 3, false, null),
            new Field("aecd-20-timer-1", "s", 33, 4, 1, 1, 0, 4, false, null),
            new Field("aecd-20-timer-2", "s", 37, 4, 1, 1, 0, 4, false, null),
        ]),
        [0x8B] = new(7,0x7F,[
            new Field("dpf-regeneration-trigger", "%", 2, 1, 100, 255, 0, 4, false, null),
            new Field("dpf-regeneration-interval", "min", 3, 2, 1, 1, 0, 5, false, null),
            new Field("dpf-regeneration-distance", "km", 5, 2, 1, 1, 0, 6, false, null),
        ]),
        [0x8C] = new(17,0xFF,[
            new Field("oxygen-concentration-bank-1-sensor-1", "%", 1, 2, 1526, 1000000, 0, 0, false, null),
            new Field("oxygen-concentration-bank-1-sensor-2", "%", 3, 2, 1526, 1000000, 0, 1, false, null),
            new Field("oxygen-concentration-bank-2-sensor-1", "%", 5, 2, 1526, 1000000, 0, 2, false, null),
            new Field("oxygen-concentration-bank-2-sensor-2", "%", 7, 2, 1526, 1000000, 0, 3, false, null),
            new Field("oxygen-lambda-bank-1-sensor-1", "ratio", 9, 2, 122, 1000000, 0, 4, false, null),
            new Field("oxygen-lambda-bank-1-sensor-2", "ratio", 11, 2, 122, 1000000, 0, 5, false, null),
            new Field("oxygen-lambda-bank-2-sensor-1", "ratio", 13, 2, 122, 1000000, 0, 6, false, null),
            new Field("oxygen-lambda-bank-2-sensor-2", "ratio", 15, 2, 122, 1000000, 0, 7, false, null),
        ]),
        [0x8D] = new(1,0x00,[
            new Field("absolute-throttle-g", "%", 0, 1, 100, 255, 0, -1, false, null),
        ]),
        [0x8E] = new(1,0x00,[
            new Field("engine-friction-torque", "%", 0, 1, 1, 1, -125, -1, false, null),
        ]),
        [0x8F] = new(7,0x0F,[
            new Field("pm-sensor-bank-1-output", "%", 2, 2, 1, 100, 0, 1, false, null),
            new Field("pm-sensor-bank-2-output", "%", 5, 2, 1, 100, 0, 3, false, null),
        ]),
        [0x93] = new(3,0x01,[
            new Field("cumulative-continuous-mi", "h", 1, 2, 1, 1, 0, 0, false, null),
        ]),
        [0x94] = new(12,0x3F,[
            new Field("reagent-quality-counter", "h", 2, 2, 1, 1, 0, 1, false, null),
            new Field("reagent-consumption-counter", "h", 4, 2, 1, 1, 0, 2, false, null),
            new Field("reagent-dosing-counter", "h", 6, 2, 1, 1, 0, 3, false, null),
            new Field("egr-valve-counter", "h", 8, 2, 1, 1, 0, 4, false, null),
            new Field("nox-monitoring-counter", "h", 10, 2, 1, 1, 0, 5, false, null),
        ]),
        [0x9C] = new(17,0xFF,[
            new Field("oxygen-concentration-bank-1-sensor-3", "%", 1, 2, 1526, 1000000, 0, 0, false, null),
            new Field("oxygen-concentration-bank-1-sensor-4", "%", 3, 2, 1526, 1000000, 0, 1, false, null),
            new Field("oxygen-concentration-bank-2-sensor-3", "%", 5, 2, 1526, 1000000, 0, 2, false, null),
            new Field("oxygen-concentration-bank-2-sensor-4", "%", 7, 2, 1526, 1000000, 0, 3, false, null),
            new Field("oxygen-lambda-bank-1-sensor-3", "ratio", 9, 2, 122, 1000000, 0, 4, false, null),
            new Field("oxygen-lambda-bank-1-sensor-4", "ratio", 11, 2, 122, 1000000, 0, 5, false, null),
            new Field("oxygen-lambda-bank-2-sensor-3", "ratio", 13, 2, 122, 1000000, 0, 6, false, null),
            new Field("oxygen-lambda-bank-2-sensor-4", "ratio", 15, 2, 122, 1000000, 0, 7, false, null),
        ]),
    };
    internal static int? Width(int pid)=>Definitions.TryGetValue(pid,out var definition)?definition.Width:null;
    internal static IReadOnlyList<Signal>? DecodeData(int pid,ReadOnlySpan<byte> data)
    {
        if(!Definitions.TryGetValue(pid,out var definition)||data.Length!=definition.Width)return null;
        if(definition.SupportMask!=0&&(data[0]&~definition.SupportMask)!=0)return null;
        int alternate=pid==0x6B?4:pid==0x6F?2:0;
        if(alternate!=0&&(data[0]&(data[0]>>alternate)&((1<<alternate)-1))!=0)return null;
        var result=new List<Signal>();
        foreach(var field in definition.Fields){
            int multiplier=field.Multiplier;
            if(alternate!=0){
                if((data[0]&(1<<(field.Bit+alternate)))!=0)multiplier=pid==0x6B?4:8;
                else if((data[0]&(1<<field.Bit))==0)continue;
            }else if(field.Bit>=0&&(data[0]&(1<<field.Bit))==0)continue;
            long raw=0;
            for(int i=field.Offset;i<field.Offset+field.Width;i++)raw=(raw<<8)|data[i];
            if(raw==field.Unavailable)continue;
            long encoded=field.Signed&&raw>=(1L<<(field.Width*8-1))?
                raw-(1L<<(field.Width*8)):raw;
            result.Add(new(pid,field.Name,field.Unit,(double)encoded*multiplier/field.Divisor+field.Bias,raw));
        }
        return result.AsReadOnly();
    }

    internal static IReadOnlyList<Signal>? DecodeResponse(ReadOnlySpan<byte> request,ReadOnlySpan<byte> response)
    {
        if(request.Length!=2||request[0]!=1||response.Length<2||response[0]!=0x41||response[1]!=request[1])return null;
        return DecodeData(request[1],response[2..]);
    }

    internal static IReadOnlyList<Signal>? TryRaw(string requestHex,string? responseHex,string status)
    {
        if(status is not ("RAW" or "PASSED")||requestHex.Length!=4||!requestHex.StartsWith("01",StringComparison.Ordinal)||
            !byte.TryParse(requestHex.AsSpan(2),NumberStyles.HexNumber,CultureInfo.InvariantCulture,out var pid)||
            Width(pid) is not int width||responseHex is null||responseHex.Length!=4+width*2)return null;
        try{return DecodeResponse(Convert.FromHexString(requestHex),Convert.FromHexString(responseHex));}
        catch(FormatException){return null;}
    }

    internal static Record? TryRecord(string requestHex,string? responseHex,string status)
    {
        var signals=TryRaw(requestHex,responseHex,status);
        return signals is null?null:new(requestHex,signals);
    }

    internal static object Evidence(Record record)=>new {request_hex=record.RequestHex,
        signals=record.Signals.Select(s=>new{pid=s.Pid,name=s.Name,unit=s.Unit,
            number=s.Number,raw_encoded=s.RawEncoded})};

    internal static string Format(IReadOnlyList<Signal> signals)=>signals.Count==0?"No supported signal in record":
        string.Join("; ",signals.Select(s=>$"{s.Name}={s.Number.ToString("0.######",CultureInfo.InvariantCulture)} {s.Unit}"));
}
