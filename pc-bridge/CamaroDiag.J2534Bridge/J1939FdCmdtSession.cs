namespace CamaroDiag.J2534Bridge;

/// <summary>
/// SAE J1939-22 FD.TP connection-mode (RTS / CTS / DT / EOM_STATUS /
/// EOM_ACK) for one physical peer and one in-flight PDU1 PGN.
/// This transport does not claim J1939-81 addresses, derive OEM security,
/// authorize a write, or guarantee a recoverable ECU programming sequence.
/// </summary>
internal static class J1939FdCmdtSession
{
    internal sealed record Options(byte Local, byte Peer, int Pgn,
        byte Session = 0, byte Priority = 7, byte Window = 8)
    {
        internal void Validate()
        {
            if (Local >= 0xFE || Peer >= 0xFE || Local == Peer ||
                Session > 7 || Priority > 7 || Window == 0 ||
                Pgn is < 0 or > 0x3FFFF ||
                ((Pgn >> 8) & 0xFF) >= 240 || (Pgn & 0xFF) != 0)
                throw new ArgumentException(
                    "J1939-22 directed FD.TP requires two valid addresses, a PDU1 PGN and a session.");
        }
    }

    internal static async Task SendAsync(IsoTpCanFdSession.ICanLink link,
        Options o, byte[] payload, TimeSpan timeout, CancellationToken token)
    {
        ArgumentNullException.ThrowIfNull(link);
        ArgumentNullException.ThrowIfNull(payload);
        o.Validate();
        CheckLengthAndTimeout(payload.Length, timeout);
        var count = (payload.Length + 59) / 60;
        await link.SendAsync(MakeCm(o, fromLocal: true,
            J1939FdTransportFrames.RequestToSend, payload.Length, count,
            o.Window, 0), token);
        var next = 1;
        try
        {
            while (next <= count)
            {
                var window = await AwaitCtsAsync(link, o, timeout,
                    next, count, token);
                for (var i = 0; i < window; i++)
                {
                    token.ThrowIfCancellationRequested();
                    var offset = (next - 1) * 60;
                    await link.SendAsync(J1939FdTransportFrames.EncodeData(
                        new(o.Local, o.Peer, o.Session, next, 0,
                            payload.AsSpan(offset,
                                Math.Min(60, payload.Length - offset)).ToArray(),
                            o.Priority)), token);
                    next++;
                }
            }
            await link.SendAsync(MakeCm(o, fromLocal: true,
                J1939FdTransportFrames.EndOfMessageStatus,
                payload.Length, count, 0, 0), token);
            var ack = await ReadCmAsync(link, o, timeout, token);
            if (ack.Type == J1939FdTransportFrames.ConnectionAbort)
                throw new IOException("J1939-22 peer aborted the transfer after EOM_STATUS.");
            if (ack.Type != J1939FdTransportFrames.EndOfMessageAck ||
                ack.MessageLength != payload.Length ||
                ack.SegmentCount != count ||
                ack.Aux7 != 0xFF || ack.Aux8 != 0xFF)
                throw new InvalidDataException(
                    "J1939-22 EOM_ACK did not acknowledge the exact transfer.");
        }
        catch (TimeoutException) when (!token.IsCancellationRequested)
        {
            await link.SendAsync(MakeAbort(o, fromLocal: true, 3), token);
            throw;
        }
    }

    internal static async Task<byte[]> ReceiveAsync(
        IsoTpCanFdSession.ICanLink link, Options o,
        TimeSpan timeout, CancellationToken token)
    {
        ArgumentNullException.ThrowIfNull(link);
        o.Validate();
        if (timeout <= TimeSpan.Zero || timeout > TimeSpan.FromSeconds(30))
            throw new ArgumentOutOfRangeException(nameof(timeout));
        J1939FdTransportFrames.Control? start = null;
        for (var attempt = 0; attempt < 128; attempt++)
        {
            var cm = await ReadCmAsync(link, o, timeout, token);
            if (cm.Type != J1939FdTransportFrames.RequestToSend)
                continue;
            start = cm;
            break;
        }
        if (start is null)
            throw new TimeoutException("No matching J1939-22 FD.TP RTS.");

        CheckLengthAndTimeout(start.MessageLength, timeout);
        var count = (start.MessageLength + 59) / 60;
        if (start.SegmentCount != count || start.Aux7 == 0 ||
            start.Aux8 != 0)
            throw new InvalidDataException("Invalid FD.TP RTS size or peer CTS limit.");
        var peerWindow = start.Aux7;
        var result = new byte[start.MessageLength];
        var next = 1;
        try
        {
            while (next <= count)
            {
                var window = Math.Min(Math.Min(o.Window, peerWindow),
                    count - next + 1);
                await link.SendAsync(MakeCm(o, fromLocal: true,
                    J1939FdTransportFrames.ClearToSend, 0xFFFFFF,
                    next, (byte)window, 0), token);
                for (var i = 0; i < window; i++)
                {
                    var segment = await ReadDtAsync(link, o, timeout, token);
                    if (segment.Number != next)
                    {
                        if (!token.IsCancellationRequested)
                            await link.SendAsync(MakeAbort(
                                o, fromLocal: true, reason: 7), token);
                        throw new InvalidDataException(
                            "FD.TP.DT segment sequence changed; CMDT session aborted.");
                    }
                    var offset = (next - 1) * 60;
                    var useful = Math.Min(60, result.Length - offset);
                    if (segment.Data.Length < useful ||
                        (next < count && segment.Data.Length != 60))
                    {
                        if (!token.IsCancellationRequested)
                            await link.SendAsync(MakeAbort(
                                o, fromLocal: true, reason: 7), token);
                        throw new InvalidDataException(
                            "Truncated directed FD.TP.DT; CMDT session aborted.");
                    }
                    segment.Data.AsSpan(0, useful).CopyTo(result.AsSpan(offset));
                    next++;
                }
            }
            var eom = await ReadCmAsync(link, o, timeout, token);
            if (eom.Type != J1939FdTransportFrames.EndOfMessageStatus ||
                eom.MessageLength != result.Length || eom.SegmentCount != count ||
                eom.Aux7 != 0 || eom.Aux8 != 0)
                throw new InvalidDataException("Mismatching J1939-22 EOM_STATUS.");
            await link.SendAsync(MakeCm(o, fromLocal: true,
                J1939FdTransportFrames.EndOfMessageAck,
                result.Length, count, 0xFF, 0xFF), token);
            return result;
        }
        catch (TimeoutException) when (!token.IsCancellationRequested)
        {
            await link.SendAsync(MakeAbort(o, fromLocal: true, 3), token);
            throw;
        }
    }

    private static async Task<int> AwaitCtsAsync(
        IsoTpCanFdSession.ICanLink link, Options o,
        TimeSpan timeout, int next, int total, CancellationToken token)
    {
        for (var holds = 0; holds < 5; holds++)
        {
            var cm = await ReadCmAsync(link, o, timeout, token);
            if (cm.Type == J1939FdTransportFrames.ConnectionAbort)
                throw new IOException("J1939-22 peer aborted RTS/CTS.");
            if (cm.Type != J1939FdTransportFrames.ClearToSend ||
                cm.MessageLength != 0xFFFFFF ||
                cm.Aux8 != 0 || cm.SegmentCount != next)
                throw new InvalidDataException("Invalid FD.TP CTS for the active session.");
            if (cm.Aux7 == 0) continue; // Bounded peer hold.
            if (cm.Aux7 > o.Window || cm.Aux7 > total - next + 1)
                throw new InvalidDataException("FD.TP CTS exceeds available packet window.");
            return cm.Aux7;
        }
        throw new TimeoutException("Too many J1939-22 CTS hold frames.");
    }

    private static async Task<J1939FdTransportFrames.Control> ReadCmAsync(
        IsoTpCanFdSession.ICanLink link, Options o,
        TimeSpan timeout, CancellationToken token)
    {
        for (var tries = 0; tries < 128; tries++)
        {
            var frame = await link.ReceiveAsync(timeout, token);
            if (!Matches(frame, o.Peer, o.Local,
                J1939FdTransportFrames.ControlPgn)) continue;
            var cm = J1939FdTransportFrames.DecodeControl(frame);
            if (cm.Session != o.Session || cm.TransportedPgn != o.Pgn) continue;
            return cm;
        }
        throw new TimeoutException("Missing correlated J1939-22 FD.TP.CM.");
    }

    private static async Task<J1939FdTransportFrames.Segment> ReadDtAsync(
        IsoTpCanFdSession.ICanLink link, Options o,
        TimeSpan timeout, CancellationToken token)
    {
        for (var tries = 0; tries < 128; tries++)
        {
            var frame = await link.ReceiveAsync(timeout, token);
            if (!Matches(frame, o.Peer, o.Local,
                J1939FdTransportFrames.DataPgn)) continue;
            var segment = J1939FdTransportFrames.DecodeData(frame);
            if (segment.Session != o.Session) continue;
            return segment;
        }
        throw new TimeoutException("Missing correlated J1939-22 FD.TP.DT.");
    }

    private static IsoTpCanFdSession.CanFrame MakeCm(
        Options o, bool fromLocal, byte type,
        int size, int count, byte aux7, byte aux8) =>
        J1939FdTransportFrames.EncodeControl(new(
            fromLocal ? o.Local : o.Peer,
            fromLocal ? o.Peer : o.Local,
            o.Session, type, size, count, aux7, aux8, o.Pgn, o.Priority));

    private static IsoTpCanFdSession.CanFrame MakeAbort(
        Options o, bool fromLocal, byte reason) =>
        MakeCm(o, fromLocal, J1939FdTransportFrames.ConnectionAbort,
            0xFFFFFF, 0xFFFFFF, 0xFF, reason);

    private static bool Matches(IsoTpCanFdSession.CanFrame frame,
        byte source, byte destination, int pgn)
    {
        if (!frame.IsFd || frame.Id > 0x1FFFFFFF ||
            frame.Data is null) return false;
        var id = StandardVehicleProtocols.ParseJ1939Id(frame.Id);
        return id.Source == source && id.Destination == destination &&
            id.Pgn == pgn;
    }

    private static void CheckLengthAndTimeout(int size, TimeSpan timeout)
    {
        if (size is <= 60 or > J1939FdBamSession.MaximumPayload ||
            timeout <= TimeSpan.Zero || timeout > TimeSpan.FromSeconds(30))
            throw new ArgumentOutOfRangeException(nameof(size));
    }
}
