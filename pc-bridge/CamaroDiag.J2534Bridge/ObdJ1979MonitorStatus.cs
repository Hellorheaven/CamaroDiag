namespace CamaroDiag.J2534Bridge;

/// <summary>
/// SAE J1979 Mode 01 PID 01 monitor state: MIL and DTC count are standard
/// across spark/compression ignition. Decodes supported continuous and ignition-specific
/// readiness monitors while retaining the raw bytes.
/// </summary>
internal static class ObdJ1979MonitorStatus
{
    internal sealed record Report(bool MilOn, byte ConfirmedDtcCount,
        bool CompressionIgnition, byte MonitorByteB,
        byte MonitorByteC, byte MonitorByteD);

    internal static Report Decode(ReadOnlySpan<byte> response)
    {
        if (response.Length != 6 || response[0] != 0x41 ||
            response[1] != 0x01)
            throw new InvalidDataException("Expected SAE J1979 Mode 01 PID 01 plus four data bytes.");

        var a = response[2];
        var b = response[3];
        return new Report(
            (a & 0x80) != 0, (byte)(a & 0x7F),
            (b & 0x08) != 0, b, response[4], response[5]);
    }

    // SAE J1979 Table B2. The same C/D bit can represent different
    // monitors depending on the ignition type. Reserved bits remain raw.
    private static readonly string[] Continuous = ["misfire", "fuel-system", "components"];
    private static readonly string?[] Spark =
        ["catalyst", "heated-catalyst", "evaporative-system", "secondary-air",
         null, "oxygen-sensor", "oxygen-sensor-heater", "egr-vvt"];
    private static readonly string?[] Compression =
        ["nmhc-catalyst", "nox-aftertreatment", null, "boost-pressure",
         null, "exhaust-gas-sensor", "particulate-filter", "egr-vvt"];

    private static IEnumerable<string> SupportedStates(string?[] names, int supported, int incomplete)
    {
        for (var bit = 0; bit < names.Length; bit++)
        {
            var mask = 1 << bit;
            if (names[bit] is string name && (supported & mask) != 0)
                yield return name + ((incomplete & mask) == 0 ? "=ready" : "=incomplete");
        }
    }

    internal static string Format(Report report)
    {
        var states = SupportedStates(Continuous, report.MonitorByteB, report.MonitorByteB >> 4)
            .Concat(SupportedStates(report.CompressionIgnition ? Compression : Spark,
                report.MonitorByteC, report.MonitorByteD));
        return $"SAE J1979 MIL={(report.MilOn ? "ON" : "OFF")} " +
            $"confirmed-DTCs={report.ConfirmedDtcCount} " +
            $"ignition={(report.CompressionIgnition ? "compression" : "spark")} " +
            $"readiness-BCD={report.MonitorByteB:X2}{report.MonitorByteC:X2}{report.MonitorByteD:X2} " +
            $"supported-monitors=[{string.Join(",", states)}]";
    }
}
