using System.Globalization;

namespace CamaroDiag.J2534Bridge;

/// <summary>
/// Selected, explicitly defined SAE J1979 Mode 01 / 02 signal formulas.
/// Returns no value for unknown PIDs rather than guessing SAE J1979DA data
/// definitions or inferring a vehicle's sensor presence.
/// </summary>
internal static class ObdJ1979PidValues
{
    internal sealed record Value(byte Mode, byte Pid,
        string Name, double Number, string Unit);

    internal static int? DataLength(byte pid) => pid switch
    {
        0xA6 => 4,
        0x0C or 0x10 or 0x1F or 0x21 or 0x22 or 0x23 or 0x31 or
            0x32 or 0x3C or 0x3D or 0x3E or 0x3F or 0x42 or 0x43 or
            0x44 or 0x4D or 0x4E or 0x53 or 0x54 or 0x59 or 0x5D or
            0x5E or 0x63 => 2,
        0x04 or 0x05 or 0x06 or 0x07 or 0x08 or 0x09 or 0x0A or
            0x0B or 0x0D or 0x0E or 0x0F or 0x11 or 0x2C or 0x2D or
            0x2E or 0x2F or 0x30 or 0x33 or 0x45 or 0x46 or 0x47 or
            0x48 or 0x49 or 0x4A or 0x4B or 0x4C or 0x52 or 0x5A or
            0x5B or 0x5C or 0x61 or 0x62 => 1,
        _ => null
    };

    internal static Value? Decode(ReadOnlySpan<byte> request,
        ReadOnlySpan<byte> response)
    {
        if (request.Length < 2 || request[0] is not (0x01 or 0x02) ||
            request.Length != (request[0] == 0x02 ? 3 : 2))
            throw new ArgumentException("Expected one Mode 01/02 PID request.", nameof(request));

        var result = ObdJ1979Transaction.Decode(
            request, response, expectIdentifierEcho: true);
        if (result.Kind != ObdJ1979Transaction.ReplyKind.Positive)
            throw new InvalidDataException("SAE J1979 ECU rejected the PID request.");
        var offset = request[0] == 0x02 ? 2 : 1;
        if (result.Data.Length < offset ||
            (request[0] == 0x02 && result.Data[1] != request[2]))
            throw new InvalidDataException("PID or freeze-frame reply identity differs.");
        var values = result.Data.AsSpan(offset);
        var pid = request[1];
        var needed = DataLength(pid);
        if (needed is null) return null;
        if (values.Length != needed)
            throw new InvalidDataException("Incorrect byte count for the selected SAE J1979 PID.");
        var a = values[0];
        var b = needed >= 2 ? values[1] : (byte)0;
        var (name, number, unit) = pid switch
        {
            0x04 => ("calculated-engine-load", a * 100d / 255, "%"),
            0x05 => ("coolant-temperature", (double)a - 40, "degC"),
            0x06 => ("short-term-fuel-trim-bank-1", a * 100d / 128 - 100, "%"),
            0x07 => ("long-term-fuel-trim-bank-1", a * 100d / 128 - 100, "%"),
            0x08 => ("short-term-fuel-trim-bank-2", a * 100d / 128 - 100, "%"),
            0x09 => ("long-term-fuel-trim-bank-2", a * 100d / 128 - 100, "%"),
            0x0C => ("engine-rpm", (a * 256d + b) / 4, "rpm"),
            0x0D => ("vehicle-speed", (double)a, "km/h"),
            0x0E => ("timing-advance", a / 2d - 64, "degBTDC"),
            0x0F => ("intake-air-temperature", (double)a - 40, "degC"),
            0x10 => ("mass-air-flow", (a * 256d + b) / 100, "g/s"),
            0x11 => ("throttle-position", a * 100d / 255, "%"),
            0x1F => ("engine-runtime", a * 256d + b, "s"),
            0x2F => ("fuel-tank-level", a * 100d / 255, "%"),
            0x33 => ("barometric-pressure", (double)a, "kPa"),
            0x42 => ("control-module-voltage", (a * 256d + b) / 1000, "V"),
            0x46 => ("ambient-air-temperature", (double)a - 40, "degC"),
            0x52 => ("ethanol-content", a * 100d / 255, "%"),
            0x5C => ("engine-oil-temperature", (double)a - 40, "degC"),
            0x5E => ("engine-fuel-rate", (a * 256d + b) / 20, "L/h"),

            // These formulae are defined for classic J1979 PID bytes, not
            // the distinct SAE J1979-2 / OBDonUDS DID namespace.
            0x0A => ("fuel-pressure-gauge", a * 3d, "kPa"),
            0x0B => ("intake-manifold-absolute-pressure", (double)a, "kPa"),
            0x21 => ("distance-with-mil-on", a * 256d + b, "km"),
            0x22 => ("fuel-rail-pressure-relative-vacuum", (a * 256d + b) * 0.079, "kPa"),
            0x23 => ("fuel-rail-pressure-direct-injection", (a * 256d + b) * 10, "kPa"),
            0x2C => ("commanded-egr", a * 100d / 255, "%"),
            0x2D => ("egr-error", (a - 128) * 100d / 128, "%"),
            0x2E => ("commanded-evaporative-purge", a * 100d / 255, "%"),
            0x30 => ("warm-ups-since-dtc-clear", (double)a, "count"),
            0x31 => ("distance-since-dtc-clear", a * 256d + b, "km"),
            0x32 => ("evap-vapor-pressure", unchecked((short)(a * 256 + b)) / 4d, "Pa"),
            0x3C => ("catalyst-temperature-bank-1-sensor-1", (a * 256d + b) / 10 - 40, "degC"),
            0x3D => ("catalyst-temperature-bank-2-sensor-1", (a * 256d + b) / 10 - 40, "degC"),
            0x3E => ("catalyst-temperature-bank-1-sensor-2", (a * 256d + b) / 10 - 40, "degC"),
            0x3F => ("catalyst-temperature-bank-2-sensor-2", (a * 256d + b) / 10 - 40, "degC"),
            0x43 => ("absolute-engine-load", (a * 256d + b) * 100 / 255, "%"),
            0x44 => ("commanded-equivalence-ratio", (a * 256d + b) / 32768, "ratio"),
            0x45 => ("relative-throttle-position", a * 100d / 255, "%"),
            0x47 => ("absolute-throttle-position-b", a * 100d / 255, "%"),
            0x48 => ("absolute-throttle-position-c", a * 100d / 255, "%"),
            0x49 => ("accelerator-pedal-position-d", a * 100d / 255, "%"),
            0x4A => ("accelerator-pedal-position-e", a * 100d / 255, "%"),
            0x4B => ("accelerator-pedal-position-f", a * 100d / 255, "%"),
            0x4C => ("commanded-throttle-actuator", a * 100d / 255, "%"),
            0x4D => ("time-run-with-mil-on", a * 256d + b, "min"),
            0x4E => ("time-since-dtc-clear", a * 256d + b, "min"),
            0x53 => ("absolute-evap-vapor-pressure", (a * 256d + b) / 200, "kPa"),
            0x54 => ("evap-vapor-pressure-offset", (a * 256d + b) - 32767, "Pa"),
            0x59 => ("fuel-rail-pressure-absolute", (a * 256d + b) * 10, "kPa"),
            0x5A => ("relative-accelerator-pedal-position", a * 100d / 255, "%"),
            0x5B => ("hybrid-battery-pack-remaining-life", a * 100d / 255, "%"),
            0x5D => ("fuel-injection-timing", ((a * 256d + b) - 26880) / 128, "deg"),
            0x61 => ("driver-demand-engine-torque", (double)a - 125, "%"),
            0x62 => ("actual-engine-torque", (double)a - 125, "%"),
            0x63 => ("engine-reference-torque", a * 256d + b, "Nm"),
            0xA6 => ("odometer", System.Buffers.Binary.BinaryPrimitives.ReadUInt32BigEndian(values)/10d, "km"),
            _ => throw new InvalidOperationException("Unknown PID formula.")
        };
        return new(request[0], pid, name, number, unit);
    }

    internal static string Format(Value value) =>
        $"SAE J1979 mode=0x{value.Mode:X2} PID=0x{value.Pid:X2} " +
        $"{value.Name}={value.Number.ToString("0.###", CultureInfo.InvariantCulture)} " +
        value.Unit;
}
