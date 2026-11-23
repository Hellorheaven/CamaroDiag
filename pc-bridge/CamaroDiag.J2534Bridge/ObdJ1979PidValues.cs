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
        var needed = pid is 0x0C or 0x10 ? 2 : 1;
        if (pid is not (0x05 or 0x06 or 0x07 or 0x08 or 0x09 or
            0x0C or 0x0D or 0x10 or 0x11 or 0x52))
            return null;
        if (values.Length != needed)
            throw new InvalidDataException("Incorrect byte count for the selected SAE J1979 PID.");
        var a = values[0];
        var b = needed == 2 ? values[1] : (byte)0;
        var (name, number, unit) = pid switch
        {
            0x05 => ("coolant-temperature", (double)a - 40, "degC"),
            0x06 => ("short-term-fuel-trim-bank-1", a * 100d / 128 - 100, "%"),
            0x07 => ("long-term-fuel-trim-bank-1", a * 100d / 128 - 100, "%"),
            0x08 => ("short-term-fuel-trim-bank-2", a * 100d / 128 - 100, "%"),
            0x09 => ("long-term-fuel-trim-bank-2", a * 100d / 128 - 100, "%"),
            0x0C => ("engine-rpm", (a * 256d + b) / 4, "rpm"),
            0x0D => ("vehicle-speed", (double)a, "km/h"),
            0x10 => ("mass-air-flow", (a * 256d + b) / 100, "g/s"),
            0x11 => ("throttle-position", a * 100d / 255, "%"),
            0x52 => ("ethanol-content", a * 100d / 255, "%"),
            _ => throw new InvalidOperationException("Unknown PID formula.")
        };
        return new(request[0], pid, name, number, unit);
    }

    internal static string Format(Value value) =>
        $"SAE J1979 mode=0x{value.Mode:X2} PID=0x{value.Pid:X2} " +
        $"{value.Name}={value.Number.ToString("0.###", CultureInfo.InvariantCulture)} " +
        value.Unit;
}
