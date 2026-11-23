namespace CamaroDiag.J2534Bridge;

/// <summary>
/// SAE J1979 Mode 01 PID 01 monitor state: MIL and DTC count are standard
/// across spark/compression ignition. Retains the remaining readiness bytes
/// without pretending to decode every ignition-specific monitor definition.
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

    internal static string Format(Report report) =>
        $"SAE J1979 MIL={(report.MilOn ? "ON" : "OFF")} " +
        $"confirmed-DTCs={report.ConfirmedDtcCount} " +
        $"ignition={(report.CompressionIgnition ? "compression" : "spark")} " +
        $"readiness-BCD={report.MonitorByteB:X2}{report.MonitorByteC:X2}{report.MonitorByteD:X2} " +
        "(monitor-specific interpretation not yet implemented)";
}
