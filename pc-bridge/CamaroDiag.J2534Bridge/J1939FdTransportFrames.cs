namespace CamaroDiag.J2534Bridge;

/// <summary>
/// SAE J1939-22 CAN-FD transport frame codecs: FD.TP.CM PGN 0x4D00 and
/// FD.TP.DT PGN 0x4E00. This is framing only, not a qualified FD.TP
/// transaction state machine, NAME claim, OEM assurance, or flash service.
/// </summary>
internal static class J1939FdTransportFrames
{
    internal const int ControlPgn = 0x4D00;
    internal const int DataPgn = 0x4E00;
    internal const byte RequestToSend = 0;
    internal const byte ClearToSend = 1;
    internal const byte EndOfMessageStatus = 2;
    internal const byte EndOfMessageAck = 3;
    internal const byte BroadcastAnnouncement = 4;
    internal const byte ConnectionAbort = 15;

    internal sealed record Control(byte Source, byte Destination,
        byte Session, byte Type, int MessageLength, int SegmentCount,
        byte Aux7, byte Aux8, int TransportedPgn, byte Priority);

    internal sealed record Segment(byte Source, byte Destination,
        byte Session, int Number, byte FormatIndicator, byte[] Data,
        byte Priority);

    internal static IsoTpCanFdSession.CanFrame EncodeControl(Control c)
    {
        ArgumentNullException.ThrowIfNull(c);
        if (c.Session > 15 || c.Priority > 7 ||
            c.Source >= 0xFE || c.Destination == 0xFE ||
            !KnownControl(c.Type) ||
            c.MessageLength is < 0 or > 0xFFFFFF ||
            c.SegmentCount is < 0 or > 0xFFFFFF ||
            !ValidPgn(c.TransportedPgn))
            throw new ArgumentException("Invalid J1939-22 FD.TP.CM fields.");

        var data = new byte[12];
        data[0] = (byte)((c.Session << 4) | c.Type);
        Write24(data.AsSpan(1), c.MessageLength);
        Write24(data.AsSpan(4), c.SegmentCount);
        data[7] = c.Aux7;
        data[8] = c.Aux8;
        Write24(data.AsSpan(9), c.TransportedPgn);
        return new(J1939ClassicTpSession.ArbitrationId(
            ControlPgn, c.Priority, c.Source, c.Destination),
            true, data);
    }

    internal static Control DecodeControl(IsoTpCanFdSession.CanFrame frame)
    {
        var (id, dest) = Correlate(frame, ControlPgn);
        if (frame.Data.Length != 12)
            throw new InvalidDataException("FD.TP.CM requires 12 CAN FD data bytes.");
        var control = (byte)(frame.Data[0] & 0x0F);
        var session = (byte)(frame.Data[0] >> 4);
        var pgn = Read24(frame.Data.AsSpan(9));
        if (!KnownControl(control) || !ValidPgn(pgn))
            throw new InvalidDataException("Unsupported FD.TP.CM control or transported PGN.");
        return new Control((byte)id.Source, dest, session, control,
            Read24(frame.Data.AsSpan(1)), Read24(frame.Data.AsSpan(4)),
            frame.Data[7], frame.Data[8], pgn, (byte)id.Priority);
    }

    internal static IsoTpCanFdSession.CanFrame EncodeData(Segment segment)
    {
        ArgumentNullException.ThrowIfNull(segment);
        if (segment.Source >= 0xFE || segment.Destination == 0xFE ||
            segment.Priority > 7 || segment.Session > 15 ||
            segment.FormatIndicator != 0 ||
            segment.Number is < 1 or > 0xFFFFFF ||
            segment.Data is null || segment.Data.Length is < 1 or > 60)
            throw new ArgumentException("Invalid J1939-22 FD.TP.DT segment.");

        var bytes = new List<byte>(64)
        {
            (byte)(segment.Session << 4),
            (byte)segment.Number, (byte)(segment.Number >> 8),
            (byte)(segment.Number >> 16)
        };
        bytes.AddRange(segment.Data);
        var dlc = Enumerable.Range(0, 16)
            .Select(StandardVehicleProtocols.CanFdDlcToLength)
            .First(length => length >= Math.Max(8, bytes.Count));
        while (bytes.Count < dlc)
            bytes.Add(0xFF);
        return new(J1939ClassicTpSession.ArbitrationId(
            DataPgn, segment.Priority, segment.Source, segment.Destination),
            true, bytes.ToArray());
    }

    internal static Segment DecodeData(IsoTpCanFdSession.CanFrame frame)
    {
        var (id, dest) = Correlate(frame, DataPgn);
        if (frame.Data.Length < 8)
            throw new InvalidDataException("FD.TP.DT frame too short.");
        var dtfi = (byte)(frame.Data[0] & 0x0F);
        var number = Read24(frame.Data.AsSpan(1));
        if (number == 0 || dtfi != 0)
            throw new InvalidDataException(
                "Unsupported FD.TP.DT format or segment number.");
        // The last frame may contain 0xFF padding. Trimming its useful
        // bytes is the responsibility of the reassembly session using the
        // advertised total message length; never strip valid 0xFF data here.
        return new Segment((byte)id.Source, dest,
            (byte)(frame.Data[0] >> 4), number, dtfi,
            frame.Data.AsSpan(4).ToArray(), (byte)id.Priority);
    }

    private static ( (int Priority, int Pgn, int Source, int? Destination) Id,
        byte Destination) Correlate(IsoTpCanFdSession.CanFrame frame, int expectedPgn)
    {
        if (!frame.IsFd || frame.Data is null ||
            frame.Id > 0x1FFFFFFF ||
            frame.Data.Length is not (8 or 12 or 16 or 20 or 24 or 32 or 48 or 64))
            throw new InvalidDataException("Invalid raw J1939-22 CAN FD frame.");
        var id = StandardVehicleProtocols.ParseJ1939Id(frame.Id);
        if (id.Pgn != expectedPgn || id.Source >= 0xFE ||
            id.Destination is not int destination || destination == 0xFE)
            throw new InvalidDataException("Unexpected J1939-22 transport PGN or destination.");
        return (id, (byte)destination);
    }

    private static bool KnownControl(int code) =>
        code is RequestToSend or ClearToSend or EndOfMessageStatus or
            EndOfMessageAck or BroadcastAnnouncement or ConnectionAbort;

    private static bool ValidPgn(int pgn) =>
        pgn is >= 0 and <= 0x3FFFF &&
        (((pgn >> 8) & 0xFF) >= 240 || (pgn & 0xFF) == 0);

    private static int Read24(ReadOnlySpan<byte> data) =>
        data[0] | data[1] << 8 | data[2] << 16;

    private static void Write24(Span<byte> bytes, int number)
    {
        bytes[0] = (byte)number;
        bytes[1] = (byte)(number >> 8);
        bytes[2] = (byte)(number >> 16);
    }
}
