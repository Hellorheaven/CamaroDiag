namespace CamaroDiag.J2534Bridge;

/// <summary>
/// SAE J1939-21 classic-CAN transport protocol (9..1785 payload bytes):
/// broadcast BAM/TP.DT and peer-to-peer RTS/CTS/TP.DT/EOM_ACK, in both
/// directions. Unlike ISO-TP this is a PGN-level transport using 29-bit CAN.
/// J1939-22 CAN-FD TP is a different protocol and is not impersonated here.
/// Hardware timing and node NAME/address claims are delegated to the vehicle
/// link and its qualified J2534 implementation.
/// </summary>
internal static class J1939ClassicTpSession
{
    internal const int MaxPayload = 1785;
    internal const int TpCmPgn = 0xEC00;
    internal const int TpDtPgn = 0xEB00;
    internal const byte Rts = 0x10;
    internal const byte Cts = 0x11;
    internal const byte EomAck = 0x13;
    internal const byte Bam = 0x20;
    internal const byte Abort = 0xFF;

    internal sealed record Options(byte LocalAddress, byte RemoteAddress, int Pgn,
        byte Priority = 6, byte ReceiveWindow = 16)
    {
        internal void Validate()
        {
            if (LocalAddress >= 254 || RemoteAddress >= 254 ||
                LocalAddress == RemoteAddress || Priority > 7 ||
                ReceiveWindow == 0 || Pgn is < 0 or > 0x3FFFF ||
                ((Pgn >> 8) & 0xFF) < 240 && (Pgn & 0xFF) != 0 ||
                Pgn is TpCmPgn or TpDtPgn)
                throw new ArgumentException("Invalid J1939 source/peer address or data PGN.");
        }
    }

    internal sealed record Received(int Pgn, byte[] Payload, bool Broadcast);

    internal static uint ArbitrationId(int pgn, byte priority,
        byte source, byte? destination = null)
    {
        if (priority > 7 || source >= 254 || pgn is < 0 or > 0x3FFFF)
            throw new ArgumentOutOfRangeException(nameof(pgn));
        var pf = (pgn >> 8) & 0xFF;
        if (pf < 240)
        {
            if (!destination.HasValue || (pgn & 0xFF) != 0)
                throw new ArgumentException("PDU1 PGN requires separate destination and zero group extension.");
            return ((uint)priority << 26) | ((uint)pgn << 8) |
                ((uint)destination.Value << 8) | source;
        }
        if (destination.HasValue)
            throw new ArgumentException("PDU2 PGN encodes its group extension, not a destination.");
        return ((uint)priority << 26) | ((uint)pgn << 8) | source;
    }

    internal static IsoTpCanFdSession.CanFrame MakeControl(
        Options o, byte code, int length, int packets, byte fifth, bool broadcast,
        bool reply)
    {
        o.Validate();
        if (length is < 9 or > MaxPayload ||
            packets != (length + 6) / 7 || packets > 255)
            throw new ArgumentOutOfRangeException(nameof(length));
        var origin = reply ? o.LocalAddress : o.RemoteAddress;
        var destination = reply ? o.RemoteAddress :
            broadcast ? (byte)0xFF : o.LocalAddress;
        // caller supplies the J1939 control word in byte 0 and fifth byte.
        return new(ArbitrationId(TpCmPgn, o.Priority, origin, destination), false,
            [code, (byte)length, (byte)(length >> 8), (byte)packets, fifth,
             (byte)o.Pgn, (byte)(o.Pgn >> 8), (byte)(o.Pgn >> 16)]);
    }

    internal static IsoTpCanFdSession.CanFrame MakeData(
        Options o, byte source, byte destination, int packetNumber,
        ReadOnlySpan<byte> payload)
    {
        o.Validate();
        if (source >= 254 || packetNumber is < 1 or > 255 ||
            payload.Length is < 1 or > 7)
            throw new ArgumentOutOfRangeException(nameof(packetNumber));
        var result = Enumerable.Repeat((byte)0xFF, 8).ToArray();
        result[0] = (byte)packetNumber;
        payload.CopyTo(result.AsSpan(1));
        return new(ArbitrationId(TpDtPgn, o.Priority, source, destination), false, result);
    }

    internal static IsoTpCanFdSession.CanFrame MakeCts(Options o,
        int packetCount, int nextSequence)
    {
        o.Validate();
        if (packetCount is < 1 or > 255 || nextSequence is < 1 or > 255)
            throw new ArgumentOutOfRangeException(nameof(packetCount));
        return new(ArbitrationId(TpCmPgn, o.Priority, o.LocalAddress, o.RemoteAddress),
            false, [Cts, (byte)packetCount, (byte)nextSequence, 0xFF, 0xFF,
                (byte)o.Pgn, (byte)(o.Pgn >> 8), (byte)(o.Pgn >> 16)]);
    }

    internal static IsoTpCanFdSession.CanFrame MakeAck(Options o, int length)
    {
        var total = (length + 6) / 7;
        return MakeControl(o, EomAck, length, total, 0xFF,
            broadcast: false, reply: true);
    }

    internal static async Task SendAsync(IsoTpCanFdSession.ICanLink link,
        Options o, byte[] payload, bool broadcast, TimeSpan timeout,
        CancellationToken token)
    {
        ArgumentNullException.ThrowIfNull(link);
        ArgumentNullException.ThrowIfNull(payload);
        o.Validate();
        if (timeout <= TimeSpan.Zero || payload.Length is < 1 or > MaxPayload)
            throw new ArgumentOutOfRangeException(nameof(payload));
        if (payload.Length <= 8)
        {
            var pf = (o.Pgn >> 8) & 0xFF;
            byte? directDestination = pf < 240 ?
                broadcast ? (byte)0xFF : o.RemoteAddress : null;
            var direct = Enumerable.Repeat((byte)0xFF, 8).ToArray();
            payload.CopyTo(direct, 0);
            await link.SendAsync(new(ArbitrationId(o.Pgn, o.Priority,
                o.LocalAddress, directDestination), false, direct), token);
            return;
        }
        var count = (payload.Length + 6) / 7;
        var destination = broadcast ? (byte)0xFF : o.RemoteAddress;
        var first = new IsoTpCanFdSession.CanFrame(
            ArbitrationId(TpCmPgn, o.Priority, o.LocalAddress, destination),
            false, [broadcast ? Bam : Rts, (byte)payload.Length,
                (byte)(payload.Length >> 8), (byte)count,
                broadcast ? (byte)0xFF : o.ReceiveWindow,
                (byte)o.Pgn, (byte)(o.Pgn >> 8), (byte)(o.Pgn >> 16)]);
        await link.SendAsync(first, token);
        var sequence = 1;
        while (sequence <= count)
        {
            var window = broadcast ? count - sequence + 1 :
                await AwaitCtsAsync(link, o, timeout, count, sequence, token);
            for (var transmitted = 0; transmitted < window; transmitted++)
            {
                token.ThrowIfCancellationRequested();
                if (broadcast)
                    await link.WaitMinimumSeparationAsync(
                        TimeSpan.FromMilliseconds(50), token);
                var start = (sequence - 1) * 7;
                var take = Math.Min(payload.Length - start, 7);
                await link.SendAsync(MakeData(o, o.LocalAddress, destination,
                    sequence, payload.AsSpan(start, take)), token);
                sequence++;
            }
        }
        if (!broadcast)
        {
            var ack = await ReadControlAsync(link, o, fromPeer: true,
                timeout, token);
            if (ack.Data[0] == Abort)
                throw new IOException("J1939 peer aborted transport after data transfer.");
            if (ack.Data[0] != EomAck || ack.Data[1] != (byte)payload.Length ||
                ack.Data[2] != (byte)(payload.Length >> 8) ||
                ack.Data[3] != count || ack.Data[4] != 0xFF)
                throw new InvalidDataException("J1939 EOM_ACK size/packet count mismatch.");
        }
    }

    internal static async Task<Received> ReceiveAsync(
        IsoTpCanFdSession.ICanLink link, Options o,
        TimeSpan timeout, CancellationToken token)
    {
        ArgumentNullException.ThrowIfNull(link);
        o.Validate();
        if (timeout <= TimeSpan.Zero)
            throw new ArgumentOutOfRangeException(nameof(timeout));
        var start = await ReadStartAsync(link, o, timeout, token);
        var physical = StandardVehicleProtocols.ParseJ1939Id(start.Id);
        if (physical.Pgn == o.Pgn)
            return new(o.Pgn, start.Data.ToArray(),
                physical.Destination is null or 0xFF);
        if (start.Data[0] == Abort)
            throw new IOException("J1939 peer aborted transport before data.");
        if (start.Data[0] is not (Rts or Bam))
            throw new InvalidDataException("Expected J1939 RTS or BAM.");
        var broadcast = start.Data[0] == Bam;
        var actualDestination = StandardVehicleProtocols.ParseJ1939Id(start.Id).Destination;
        if (actualDestination != (broadcast ? 0xFF : o.LocalAddress))
            throw new InvalidDataException("J1939 BAM/RTS destination mismatch.");
        var length = start.Data[1] | start.Data[2] << 8;
        var count = start.Data[3];
        if (length is < 9 or > MaxPayload || count != (length + 6) / 7 ||
            (broadcast && start.Data[4] != 0xFF) ||
            (!broadcast && start.Data[4] == 0))
            throw new InvalidDataException("Inconsistent J1939 TP session size/window.");
        // For a peer-to-peer RTS, byte 4 advertises the maximum number of
        // TP.DT packets the sender permits per CTS. Never grant a larger
        // window, even if our local receive buffer supports more packets.
        // BAM byte 4 is reserved (0xFF), not an RTS window.
        var peerWindow = broadcast ? count : start.Data[4];
        var result = new byte[length];
        var sequence = 1;
        while (sequence <= count)
        {
            var window = broadcast ? count - sequence + 1 :
                Math.Min(Math.Min(o.ReceiveWindow, peerWindow),
                    count - sequence + 1);
            if (!broadcast)
                await link.SendAsync(MakeCts(o, window, sequence), token);
            for (var part = 0; part < window; part++)
            {
                var frame = await ReadDataAsync(link, o, timeout, token,
                    broadcast);
                if (frame.Data[0] != sequence)
                    throw new InvalidDataException("J1939 TP.DT sequence number changed.");
                var offset = (sequence - 1) * 7;
                var take = Math.Min(7, length - offset);
                frame.Data.AsSpan(1, take).CopyTo(result.AsSpan(offset));
                sequence++;
            }
        }
        if (!broadcast)
            await link.SendAsync(MakeAck(o, length), token);
        return new(o.Pgn, result, broadcast);
    }

    private static async Task<IsoTpCanFdSession.CanFrame> ReadStartAsync(
        IsoTpCanFdSession.ICanLink link, Options o,
        TimeSpan timeout, CancellationToken token)
    {
        for (var tries = 0; tries < 64; tries++)
        {
            var frame = await link.ReceiveAsync(timeout, token);
            if (frame.IsFd || frame.Data.Length != 8) continue;
            var id = StandardVehicleProtocols.ParseJ1939Id(frame.Id);
            if (id.Source != o.RemoteAddress) continue;
            if (id.Pgn == o.Pgn)
            {
                var pf = (o.Pgn >> 8) & 0xFF;
                if (pf < 240 && id.Destination != o.LocalAddress &&
                    id.Destination != 0xFF)
                    continue;
                return frame;
            }
            if (id.Pgn != TpCmPgn ||
                id.Destination != o.LocalAddress && id.Destination != 0xFF)
                continue;
            if ((frame.Data[5] | (frame.Data[6] << 8) |
                frame.Data[7] << 16) != o.Pgn)
                throw new InvalidDataException("J1939 RTS/BAM target PGN mismatch.");
            return frame;
        }
        throw new TimeoutException("No J1939 transport start frame from expected ECU.");
    }

    private static async Task<int> AwaitCtsAsync(
        IsoTpCanFdSession.ICanLink link, Options o,
        TimeSpan timeout, int total, int next, CancellationToken token)
    {
        for (var waits = 0; waits < 5; waits++)
        {
            var frame = await ReadControlAsync(link, o, fromPeer: true,
                timeout, token);
            if (frame.Data[0] == Abort)
                throw new IOException("J1939 peer aborted RTS/CTS transfer.");
            if (frame.Data[0] != Cts)
                throw new InvalidDataException("Expected J1939 CTS.");
            if (frame.Data[3] != 0xFF || frame.Data[4] != 0xFF)
                throw new InvalidDataException("Invalid J1939 CTS reserved bytes.");
            if (frame.Data[1] == 0) continue; // receiver hold, bounded
            var count = frame.Data[1];
            if (frame.Data[2] != next || count > total - next + 1 ||
                count > o.ReceiveWindow)
                throw new InvalidDataException("Wrong J1939 CTS block window/next sequence.");
            return count;
        }
        throw new TimeoutException("Too many J1939 CTS hold responses.");
    }

    private static async Task<IsoTpCanFdSession.CanFrame> ReadControlAsync(
        IsoTpCanFdSession.ICanLink link, Options o, bool fromPeer,
        TimeSpan timeout, CancellationToken token, bool permitBroadcast = false)
    {
        for (var count = 0; count < 64; count++)
        {
            var frame = await link.ReceiveAsync(timeout, token);
            var id = StandardVehicleProtocols.ParseJ1939Id(frame.Id);
            if (frame.IsFd || frame.Data.Length != 8) continue;
            if (id.Pgn != TpCmPgn || id.Source != (fromPeer ?
                o.RemoteAddress : o.LocalAddress)) continue;
            if (id.Destination != (fromPeer ? o.LocalAddress :
                o.RemoteAddress) &&
                (!permitBroadcast || id.Destination != 0xFF)) continue;
            if ((frame.Data[5] | (frame.Data[6] << 8) |
                (frame.Data[7] << 16)) != o.Pgn)
                throw new InvalidDataException("J1939 TP.CM PGN does not match active operation.");
            return frame;
        }
        throw new TimeoutException("No matching J1939 TP.CM frame.");
    }

    private static async Task<IsoTpCanFdSession.CanFrame> ReadDataAsync(
        IsoTpCanFdSession.ICanLink link, Options o,
        TimeSpan timeout, CancellationToken token, bool broadcast)
    {
        for (var attempts = 0; attempts < 64; attempts++)
        {
            var frame = await link.ReceiveAsync(timeout, token);
            var id = StandardVehicleProtocols.ParseJ1939Id(frame.Id);
            if (frame.IsFd || frame.Data.Length != 8) continue;
            // Only an abort for this peer, destination and PGN belongs to
            // the active transport session. Other nodes may transfer different
            // PGNs at the same time on the shared J1939 bus.
            if (id.Pgn == TpCmPgn && id.Source == o.RemoteAddress &&
                id.Destination == (broadcast ? 0xFF : o.LocalAddress) &&
                frame.Data[0] == Abort &&
                (frame.Data[5] | frame.Data[6] << 8 |
                    frame.Data[7] << 16) == o.Pgn)
                throw new IOException("J1939 peer aborted active TP.DT session.");
            if (id.Pgn == TpDtPgn && id.Source == o.RemoteAddress &&
                id.Destination == (broadcast ? 0xFF : o.LocalAddress))
                return frame;
        }
        throw new TimeoutException("No correlated J1939 TP.DT frame.");
    }
}
