namespace CamaroDiag.J2534Bridge;

/// <summary>
/// SAE J1939-22 FEFF (29-bit) and FBFF (11-bit) Multi-PG on CAN FD.
/// Only TOS=2 (SAE J1939 without assurance data), TF=0 is supported.
/// This is NOT J1939-22 FD.TP for messages larger than one CAN FD frame.
/// The caller must arrange any J1939-81 address claim and qualified FD link.
/// </summary>
internal static class J1939FdMultiPg
{
    internal const int MultiPgPgn = 0x2500;
    internal const byte J1939NoAssurance = 2;

    internal sealed record ContainedGroup(int Pgn, byte[] Payload);

    internal sealed record Received(byte SourceAddress, byte DestinationAddress,
        byte Priority, IReadOnlyList<ContainedGroup> Groups, bool FastBroadcast = false);

    internal static IsoTpCanFdSession.CanFrame Encode(
        byte source, byte destination, byte priority,
        IReadOnlyList<ContainedGroup> groups)
    {
        if (source >= 0xFE || priority > 7 || groups is null ||
            groups.Count == 0)
            throw new ArgumentException("J1939-22 requires a source, priority and at least one contained PG.");

        var bytes = new List<byte>(64);
        foreach (var group in groups)
        {
            if (group is null || group.Payload is null ||
                group.Pgn is < 0 or > 0x3FFFF ||
                ((group.Pgn >> 8) & 0xFF) < 240 &&
                    (group.Pgn & 0xFF) != 0 ||
                ((group.Pgn >> 8) & 0xFF) >= 240 &&
                    destination != 0xFF ||
                group.Payload.Length is < 1 or > 60 ||
                bytes.Count + 4 + group.Payload.Length > 64)
                throw new ArgumentException(
                    "Invalid J1939-22 contained PGN, payload or 64-byte frame length.");
            // The 4-byte C-PG header is TOS(3), trailer(3), PGN(18),
            // then C-PG data length(8). The caller specifies the full
            // PDU1/PDU2 PGN, not a pre-framed CAN arbitration identifier.
            bytes.Add((byte)((J1939NoAssurance << 5) |
                ((group.Pgn >> 16) & 0x03)));
            bytes.Add((byte)(group.Pgn >> 8));
            bytes.Add((byte)group.Pgn);
            bytes.Add((byte)group.Payload.Length);
            bytes.AddRange(group.Payload);
        }

        // CAN-FD only carries valid DLC lengths; use the SAE Multi-PG
        // padding-service marker followed by 0xAA when there is extra room.
        var paddedLength = Math.Max(8, Enumerable.Range(0, 16)
            .Select(StandardVehicleProtocols.CanFdDlcToLength)
            .First(length => length >= bytes.Count));
        var padding = 0;
        while (bytes.Count < paddedLength)
            bytes.Add(padding++ < 3 ? (byte)0x00 : (byte)0xAA);
        return new(J1939ClassicTpSession.ArbitrationId(
            MultiPgPgn, priority, source, destination),
            true, bytes.ToArray());
    }

    /// <summary>
    /// J1939-22 FBFF broadcast uses an 11-bit CAN identifier equal to the
    /// claimed source address and fixed arbitration priority (no CAN extended
    /// ID). All C-PGs are global broadcast. It is never a directed reply.
    /// </summary>
    internal static IsoTpCanFdSession.CanFrame EncodeFastBroadcast(
        byte source, IReadOnlyList<ContainedGroup> groups)
    {
        var feff = Encode(source, 0xFF, 0, groups);
        return new(source, true, feff.Data);
    }

    internal static Received Decode(IsoTpCanFdSession.CanFrame frame)
    {
        if (!frame.IsFd || frame.Id > 0x1FFFFFFF ||
            frame.Data is null || frame.Data.Length is not
                (8 or 12 or 16 or 20 or 24 or 32 or 48 or 64))
            throw new InvalidDataException("Invalid J1939-22 Multi-PG CAN-FD frame.");
        var fbff = frame.Id <= 0x7FF;
        int source;
        int destination;
        int priority;
        if (fbff)
        {
            if (frame.Id >= 0xFE)
                throw new InvalidDataException(
                    "J1939-22 FBFF CAN ID must be a claimed source address.");
            source = (int)frame.Id;
            destination = 0xFF;
            priority = 0;
        }
        else
        {
            var id = StandardVehicleProtocols.ParseJ1939Id(frame.Id);
            if (id.Pgn != MultiPgPgn ||
                id.Destination is not int dest || id.Source >= 0xFE)
                throw new InvalidDataException(
                    "Unexpected J1939-22 FEFF Multi-PG arbitration identifier.");
            source = id.Source;
            destination = dest;
            priority = id.Priority;
        }

        var groups = new List<ContainedGroup>();
        var offset = 0;
        while (offset < frame.Data.Length)
        {
            var remaining = frame.Data.Length - offset;
            if (frame.Data[offset] == 0)
            {
                // Padding service may contain up to 3 initial zero octets
                // followed by 0xAA. Never mistake these for a data PGN.
                if (!IsPadding(frame.Data.AsSpan(offset)))
                    throw new InvalidDataException("Invalid J1939-22 padding service.");
                break;
            }
            if (remaining < 4)
                throw new InvalidDataException("Truncated J1939-22 C-PG header.");
            var tos = frame.Data[offset] >> 5;
            var trailer = (frame.Data[offset] >> 2) & 0x07;
            if (tos != J1939NoAssurance || trailer != 0)
                throw new NotSupportedException(
                    "J1939-22 assurance trailers and non-SAE TOS require a qualified codec.");
            var pgn = ((frame.Data[offset] & 0x03) << 16) |
                (frame.Data[offset + 1] << 8) | frame.Data[offset + 2];
            var length = frame.Data[offset + 3];
            if (length == 0 || length > 60 || length > remaining - 4 ||
                ((pgn >> 8) & 0xFF) < 240 && (pgn & 0xFF) != 0 ||
                ((pgn >> 8) & 0xFF) >= 240 && destination != 0xFF)
                throw new InvalidDataException("Invalid J1939-22 C-PG PGN or payload length.");
            groups.Add(new ContainedGroup(pgn,
                frame.Data.AsSpan(offset + 4, length).ToArray()));
            offset += 4 + length;
        }
        if (groups.Count == 0)
            throw new InvalidDataException("Empty J1939-22 Multi-PG frame.");
        return new Received((byte)source, (byte)destination,
            (byte)priority, groups, fbff);
    }

    private static bool IsPadding(ReadOnlySpan<byte> bytes)
    {
        var i = 0;
        while (i < bytes.Length && i < 3 && bytes[i] == 0) i++;
        for (; i < bytes.Length; i++)
            if (bytes[i] != 0xAA) return false;
        return true;
    }
}
