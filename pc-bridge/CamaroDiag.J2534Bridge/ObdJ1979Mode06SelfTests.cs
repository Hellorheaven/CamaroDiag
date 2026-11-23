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
        return checks;
    }
}
