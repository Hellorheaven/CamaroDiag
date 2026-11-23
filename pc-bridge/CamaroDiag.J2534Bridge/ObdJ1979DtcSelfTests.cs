namespace CamaroDiag.J2534Bridge;

/// <summary>Offline J1979/J2012 DTC pair checks, no vehicle communications.</summary>
internal static class ObdJ1979DtcSelfTests
{
    internal static int Run()
    {
        var checks = 0;
        void Check(bool ok, string name)
        {
            checks++;
            if (!ok) throw new InvalidOperationException("J1979 DTC: " + name);
        }
        void Reject<T>(Action action, string name) where T : Exception
        {
            var threw = false;
            try { action(); }
            catch (T) { threw = true; }
            Check(threw, name);
        }

        Check(ObdJ1979Dtc.Decode(0x0133).Display == "P0133",
            "Powertrain code.");
        Check(ObdJ1979Dtc.Decode(0x4300).Display == "C0300",
            "Chassis code.");
        Check(ObdJ1979Dtc.Decode(0x8200).Display == "B0200",
            "Body code.");
        Check(ObdJ1979Dtc.Decode(0xC100).Display == "U0100",
            "Network code.");
        Check(ObdJ1979Dtc.Decode(0x3AFF).Display == "P3AFF",
            "Manufacturer and hexadecimal nibble digits preserved.");
        var codes = ObdJ1979Dtc.DecodePairs([0x01, 0x33, 0, 0, 0xC1, 0x00]);
        Check(codes.Count == 2 &&
            codes[0].Display == "P0133" && codes[1].Display == "U0100",
            "Zero padding skipped; code order preserved.");
        Check(ObdJ1979Dtc.DecodePairs([]).Count == 0,
            "No-DTC payload is valid.");
        Reject<InvalidDataException>(() => ObdJ1979Dtc.DecodePairs([0x01]),
            "Incomplete final code pair rejected.");
        // ISO-TP has removed PCI and frame padding. SAE J1979 CAN DTC
        // replies contain a count byte before the two-byte code pairs.
        var counted = ObdJ1979Dtc.DecodeCanCounted(
            [0x02, 0x01, 0x33, 0xC1, 0x00]);
        Check(counted.Count == 2 &&
            counted[0].Display == "P0133" &&
            counted[1].Display == "U0100",
            "Counted CAN DTC reply decodes exactly two faults.");
        Check(ObdJ1979Dtc.DecodeCanCounted([0x00]).Count == 0,
            "CAN count zero is an empty result, not an invented DTC.");
        Check(ObdJ1979Dtc.DecodeCanCounted(
                [0x01, 0x01, 0x33, 0x00, 0x00]).Count == 1,
            "Zero-pair padding beyond declared count is permitted.");
        Reject<InvalidDataException>(() =>
            ObdJ1979Dtc.DecodeCanCounted([]),
            "CAN DTC count is mandatory.");
        Reject<InvalidDataException>(() =>
            ObdJ1979Dtc.DecodeCanCounted([0x02, 0x01, 0x33]),
            "A truncated count cannot become a partial DTC list.");
        Reject<InvalidDataException>(() =>
            ObdJ1979Dtc.DecodeCanCounted([0x01, 0x00, 0x00]),
            "A declared fault cannot contain zero code.");
        Reject<InvalidDataException>(() =>
            ObdJ1979Dtc.DecodeCanCounted([0x01, 0x01, 0x33, 0xC1, 0x00]),
            "Undeclared nonzero additional DTC must be rejected.");
        Reject<InvalidDataException>(() =>
            ObdJ1979Dtc.DecodeCanCounted([0x01, 0x01, 0x33, 0x00]),
            "Odd trailing CAN DTC bytes must be rejected.");
        return checks;
    }
}
