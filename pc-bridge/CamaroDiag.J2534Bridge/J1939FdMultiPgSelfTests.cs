namespace CamaroDiag.J2534Bridge;

/// <summary>
/// Offline SAE J1939-22 FEFF Multi-PG checks. These tests do not exercise
/// J1939 FD.TP, real arbitration, J2534 drivers, or an OEM ECU.
/// </summary>
internal static class J1939FdMultiPgSelfTests
{
    internal static int Run()
    {
        var checks = 0;
        void Check(bool result, string name)
        {
            checks++;
            if (!result) throw new InvalidOperationException(
                "J1939-22 FEFF Multi-PG test failed: " + name);
        }
        void Reject<T>(Action action, string name) where T : Exception
        {
            var threw = false;
            try { action(); }
            catch (T) { threw = true; }
            Check(threw, name);
        }
        static J1939FdMultiPg.ContainedGroup Group(int pgn, params byte[] data) =>
            new(pgn, data);

        var first = J1939FdMultiPg.Encode(0xF1, 0xFF, 6,
            [Group(0xF004, 1, 2, 3, 4, 5)]);
        Check(first.IsFd && first.Data.Length == 12 &&
            first.Data[0] == 0x40 &&
            first.Data[1] == 0xF0 && first.Data[2] == 0x04 &&
            first.Data[3] == 5 &&
            first.Data.AsSpan(4, 5).SequenceEqual(
                new byte[] {1, 2, 3, 4, 5}) &&
            first.Data[9] == 0 && first.Data[10] == 0 &&
            first.Data[11] == 0,
            "FEFF Multi-PG C-PG header uses 4 bytes and CAN FD 12-byte DLC.");
        var decoded = J1939FdMultiPg.Decode(first);
        Check(decoded.Groups.Count == 1 &&
            decoded.Groups[0].Pgn == 0xF004 &&
            decoded.Groups[0].Payload.SequenceEqual(
                new byte[] {1, 2, 3, 4, 5}) &&
            decoded.SourceAddress == 0xF1 && decoded.DestinationAddress == 0xFF,
            "FEFF Multi-PG single group round-trip.");

        var packed = J1939FdMultiPg.Encode(0xF1, 0x10, 6,
            [Group(0xF004, 1, 2, 3),
             Group(0xFECA, 0xC7, 0xFF, 0x00, 0x00)]);
        var groups = J1939FdMultiPg.Decode(packed);
        Check(packed.Data.Length == 16 &&
            groups.Groups.Count == 2 &&
            groups.Groups[0].Pgn == 0xF004 &&
            groups.Groups[1].Pgn == 0xFECA &&
            groups.Groups[1].Payload.Length == 4 &&
            groups.DestinationAddress == 0x10,
            "Two C-PGs with explicit payload sizes are preserved.");

        var full = J1939FdMultiPg.Encode(0xF1, 0xFF, 6,
            [Group(0xF004, Enumerable.Repeat((byte)0xAA, 60).ToArray())]);
        Check(full.Data.Length == 64 &&
            J1939FdMultiPg.Decode(full).Groups[0].Payload.Length == 60,
            "Maximum 60-byte single C-PG fills a 64-byte CAN FD frame.");

        Reject<ArgumentException>(() =>
            J1939FdMultiPg.Encode(0xF1, 0xFF, 6,
                [Group(0xF004, new byte[61])]),
            "Multi-PG refuses payload larger than one 64-byte CAN FD frame.");
        Reject<ArgumentException>(() =>
            J1939FdMultiPg.Encode(0xF1, 0xFF, 6,
                [Group(0xEF01, 0x22)]),
            "Invalid PDU1 C-PG group extension refused.");

        var badLength = packed.Data.ToArray();
        badLength[3] = 63;
        Reject<InvalidDataException>(() =>
            J1939FdMultiPg.Decode(new(packed.Id, true, badLength)),
            "Truncated C-PG payload must not overread FD data.");
        var badTos = packed.Data.ToArray();
        badTos[0] = 0x60;
        Reject<NotSupportedException>(() =>
            J1939FdMultiPg.Decode(new(packed.Id, true, badTos)),
            "Unknown assurance service cannot be interpreted as plain J1939.");
        Reject<InvalidDataException>(() =>
            J1939FdMultiPg.Decode(new(packed.Id, false, packed.Data)),
            "Classical CAN frame cannot masquerade as a J1939-22 FD Multi-PG.");
        Reject<InvalidDataException>(() =>
            J1939FdMultiPg.Decode(new(packed.Id, true,
                packed.Data.AsSpan(0, 9).ToArray())),
            "Noncanonical CAN FD DLC is rejected.");
        return checks;
    }
}
