namespace CamaroDiag.J2534Bridge;

/// <summary>
/// SAE J1939-22 CAN FD Transport Protocol broadcast (BAM) using
/// FD.TP.CM / FD.TP.DT / EOM_STATUS. This is an explicit software transport
/// and not a physical OEM programming, recovery, or address-claim qualification.
/// The CMDT/RTS/CTS unicast variant is a separate work item.
/// </summary>
internal static class J1939FdBamSession
{
    internal const int MaximumPayload = 1024 * 1024;
    internal const int SegmentBytes = 60;

    internal static async Task SendAsync(
        IsoTpCanFdSession.ICanLink link, byte localSource,
        int transportedPgn, byte session, byte[] payload,
        CancellationToken token, byte priority = 7)
    {
        ArgumentNullException.ThrowIfNull(link);
        ArgumentNullException.ThrowIfNull(payload);
        if (localSource >= 0xFE || session > 3 || priority > 7 ||
            payload.Length is <= SegmentBytes or > MaximumPayload ||
            !IsValidPgn(transportedPgn) ||
            ((transportedPgn >> 8) & 0xFF) < 240)
            throw new ArgumentException("Invalid J1939-22 broadcast PGN, session or payload.");
        var count = (payload.Length + SegmentBytes - 1) / SegmentBytes;
        var start = new J1939FdTransportFrames.Control(
            localSource, 0xFF, session,
            J1939FdTransportFrames.BroadcastAnnouncement,
            payload.Length, count, 0xFF, 0, transportedPgn, priority);
        await link.SendAsync(J1939FdTransportFrames.EncodeControl(start), token);
        for (var number = 1; number <= count; number++)
        {
            token.ThrowIfCancellationRequested();
            // J1939-22 BAM uses paced 60-byte FD segments. No synchronous
            // flood of TP.DT packets on a real shared network.
            await link.WaitMinimumSeparationAsync(
                TimeSpan.FromMilliseconds(10), token);
            var offset = (number - 1) * SegmentBytes;
            var segment = new J1939FdTransportFrames.Segment(
                localSource, 0xFF, session, number, 0,
                payload.AsSpan(offset,
                    Math.Min(SegmentBytes, payload.Length - offset)).ToArray(), priority);
            await link.SendAsync(J1939FdTransportFrames.EncodeData(segment), token);
        }
        var done = start with
        {
            Type = J1939FdTransportFrames.EndOfMessageStatus,
            Aux7 = 0,
            Aux8 = 0
        };
        await link.SendAsync(J1939FdTransportFrames.EncodeControl(done), token);
    }

    internal static async Task<byte[]> ReceiveAsync(
        IsoTpCanFdSession.ICanLink link, byte remoteSource,
        int transportedPgn, TimeSpan timeout, CancellationToken token)
    {
        ArgumentNullException.ThrowIfNull(link);
        if (remoteSource >= 0xFE || !IsValidPgn(transportedPgn) ||
            ((transportedPgn >> 8) & 0xFF) < 240 ||
            timeout <= TimeSpan.Zero || timeout > TimeSpan.FromMinutes(10))
            throw new ArgumentException("Invalid J1939-22 BAM peer, PGN or receive timeout.");

        // A single deadline covers the complete BAM message, not 30 seconds
        // re-armed for every packet on a noisy or malicious shared bus.
        var stopwatch = System.Diagnostics.Stopwatch.StartNew();
        J1939FdTransportFrames.Control? start = null;
        for (var attempts = 0; attempts < 128; attempts++)
        {
            var frame = await ReceiveUntilAsync(
                link, timeout, stopwatch, token);
            if (!Matches(frame, remoteSource, 0xFF,
                    J1939FdTransportFrames.ControlPgn))
                continue;
            var cm = J1939FdTransportFrames.DecodeControl(frame);
            if (cm.TransportedPgn != transportedPgn ||
                cm.Type != J1939FdTransportFrames.BroadcastAnnouncement)
                continue;
            start = cm;
            break;
        }
        if (start is null)
            throw new TimeoutException("No matching J1939-22 FD.TP BAM announce.");
        var announced = start.MessageLength;
        var count = start.SegmentCount;
        if (start.Session > 3 || announced is <= SegmentBytes or > MaximumPayload ||
            count != (announced + SegmentBytes - 1) / SegmentBytes ||
            start.Aux7 != 0xFF || start.Aux8 != 0)
            throw new InvalidDataException("Invalid J1939-22 BAM session length, segment count or options.");

        var result = new byte[announced];
        for (var next = 1; next <= count; next++)
        {
            J1939FdTransportFrames.Segment? segment = null;
            for (var tries = 0; tries < 128; tries++)
            {
                var frame = await ReceiveUntilAsync(
                link, timeout, stopwatch, token);
                if (!Matches(frame, remoteSource, 0xFF,
                        J1939FdTransportFrames.DataPgn))
                    continue;
                var candidate = J1939FdTransportFrames.DecodeData(frame);
                if (candidate.Session != start.Session)
                    continue;
                segment = candidate;
                break;
            }
            if (segment is null)
                throw new TimeoutException("Missing correlated J1939-22 broadcast FD.TP.DT segment.");
            if (segment.Number != next)
                throw new InvalidDataException("Wrong J1939-22 FD.TP.DT segment number.");
            var offset = (next - 1) * SegmentBytes;
            var useful = Math.Min(SegmentBytes, announced - offset);
            if (segment.Data.Length < useful ||
                (next < count && segment.Data.Length != SegmentBytes))
                throw new InvalidDataException("Truncated J1939-22 FD.TP.DT segment.");
            segment.Data.AsSpan(0, useful).CopyTo(result.AsSpan(offset));
        }

        for (var attempts = 0; attempts < 128; attempts++)
        {
            var frame = await ReceiveUntilAsync(
                link, timeout, stopwatch, token);
            if (!Matches(frame, remoteSource, 0xFF,
                    J1939FdTransportFrames.ControlPgn))
                continue;
            var cm = J1939FdTransportFrames.DecodeControl(frame);
            if (cm.TransportedPgn != transportedPgn ||
                cm.Session != start.Session) continue;
            if (cm.Type != J1939FdTransportFrames.EndOfMessageStatus ||
                cm.MessageLength != announced || cm.SegmentCount != count ||
                cm.Aux7 != 0 || cm.Aux8 != 0)
                throw new InvalidDataException("J1939-22 BAM EOM_STATUS does not match transfer.");
            return result;
        }
        throw new TimeoutException("Missing correlated J1939-22 broadcast EOM_STATUS.");
    }

    private static Task<IsoTpCanFdSession.CanFrame> ReceiveUntilAsync(
        IsoTpCanFdSession.ICanLink link, TimeSpan limit,
        System.Diagnostics.Stopwatch clock, CancellationToken token)
    {
        var remaining = limit - clock.Elapsed;
        if (remaining <= TimeSpan.Zero)
            throw new TimeoutException(
                "J1939-22 BAM overall receive deadline expired.");
        return link.ReceiveAsync(
            remaining > TimeSpan.FromSeconds(30) ?
                TimeSpan.FromSeconds(30) : remaining, token);
    }

    private static bool Matches(IsoTpCanFdSession.CanFrame frame,
        byte source, byte destination, int expectedPgn)
    {
        if (frame.IsFd != true || frame.Id > 0x1FFFFFFF ||
            frame.Data is null) return false;
        var id = StandardVehicleProtocols.ParseJ1939Id(frame.Id);
        return id.Source == source && id.Pgn == expectedPgn &&
            id.Destination == destination;
    }

    private static bool IsValidPgn(int pgn) =>
        pgn is >= 0 and <= 0x3FFFF &&
        (((pgn >> 8) & 0xFF) >= 240 || (pgn & 0xFF) == 0);
}
