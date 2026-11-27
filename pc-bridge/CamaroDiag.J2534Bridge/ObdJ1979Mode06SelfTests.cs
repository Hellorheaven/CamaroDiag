namespace CamaroDiag.J2534Bridge;

/// <summary>Offline SAE J1979 Mode 06 CAN vectors; no adapter or vehicle.</summary>
internal static class ObdJ1979Mode06SelfTests
{
    internal static int Run()
    {
        var checks = 0;
        void Check(bool ok, string name)
        {
            checks++;
            if (!ok) throw new InvalidOperationException("J1979 Mode 06: " + name);
        }
        void Reject<T>(Action action, string name) where T : Exception
        {
            var rejected = false;
            try { action(); }
            catch (T) { rejected = true; }
            Check(rejected, name);
        }

        var availability = ObdJ1979Mode06.DecodeAvailability(
            [0x06, 0x00], [0x46, 0x00, 0x80, 0, 0, 0x01]);
        Check(availability.BaseId == 0 &&
            availability.MonitorIds.SequenceEqual(new byte[] { 1, 0x20 }) &&
            availability.HasNextPage, "MID bitmap and continuation bit.");
        var last = ObdJ1979Mode06.DecodeAvailability(
            [0x06, 0xE0], [0x46, 0xE0, 0x80, 0, 0, 0]);
        Check(last.MonitorIds.SequenceEqual(new byte[] { 0xE1 }) &&
            !last.HasNextPage, "Terminal MID page does not wrap.");
        Reject<InvalidDataException>(() =>
            ObdJ1979Mode06.DecodeAvailability(
                [0x06, 0xE0], [0x46, 0xE0, 0, 0, 0, 1]),
            "MID beyond FF is not silently wrapped.");
        Reject<InvalidDataException>(() =>
            ObdJ1979Mode06.DecodeAvailability(
                [0x06, 0x20], [0x46, 0x00, 0x80, 0, 0, 0]),
            "Wrong echoed availability MID rejected.");
        Reject<InvalidDataException>(() =>
            ObdJ1979Mode06.DecodeAvailability(
                [0x06, 0x00], [0x46, 0x00, 0, 0, 0]),
            "Truncated monitor availability rejected.");

        var records = ObdJ1979Mode06.DecodeTests([0x06, 0x21],
            [0x46, 0x21, 0x80, 0x24, 0x00, 0x64, 0x00, 0, 0x00, 0xC8,
                   0x21, 0x81, 0x24, 0x00, 0x65, 0x00, 0, 0x00, 0xC8]);
        Check(records.Count == 2 &&
            records[0] is { MonitorId: 0x21, TestId: 0x80, UnitScalingId: 0x24,
                Value: 100, Minimum: 0, Maximum: 200 } &&
            records[1] is { TestId: 0x81, Value: 101 },
            "Two nine-byte test records are decoded without assumed scaling.");
        Reject<InvalidDataException>(() =>
            ObdJ1979Mode06.DecodeTests([0x06, 0x21],
                [0x46, 0x22, 0x80, 0x24, 0x00, 0x64, 0, 0, 0, 0xC8]),
            "Wrong MID in Mode 06 test is rejected.");
        Reject<InvalidDataException>(() =>
            ObdJ1979Mode06.DecodeTests([0x06, 0x21],
                [0x46, 0x21, 0x80, 0x24, 0x00, 0x64, 0, 0, 0]),
            "Truncated monitor test record rejected.");
        Reject<ArgumentException>(() =>
            ObdJ1979Mode06.DecodeTests([0x06, 0x20],
                [0x46, 0x20, 0x80, 0x24, 0, 0, 0, 0, 0, 0]),
            "Availability MID is not decoded as test record.");
        Check(records[0].Scaled is { Unit: "count", Value: 100,
            Minimum: 0, Maximum: 200, NumericRelation: "WITHIN" },
            "Known count scale is decoded independently from monitor verdict.");
        Check(ObdJ1979Mode06.DecodeScale(0x90, 0xFFFF, 0x8000, 0x0000)
            is { Unit: "ms", Value: -1, Minimum: -32768, Maximum: 0, NumericRelation: "WITHIN" },
            "Signed monitor values and signed limits are interpreted as two's complement.");
        Check(ObdJ1979Mode06.DecodeScale(0x24, 2, 3, 1)?.NumericRelation=="INVERTED_LIMITS",
            "Inverted limits have no in-range comparison.");
        Check(ObdJ1979Mode06.DecodeScale(0xFF, 1, 0, 2) is null,
            "Unknown scaling remains raw.");
        Check(Math.Abs(ObdJ1979Mode06.DecodeScale(0x0A, 1000, 0, 2000)!.Value - 122d)<1e-10,
            "Voltage scale converts encoded counts into mV.");
        Check(ObdJ1979Interpretation.Interpret([6,0x21],
            [0x46,0x21,0x80,0x24,0,100,0,0,0,200]).Description.Contains("unit=count"),
            "Windows console includes the decoded unit.");
        return checks;
    }
}
