using System.Buffers.Binary;
using System.Diagnostics;

namespace CamaroDiag.J2534Bridge;

/// <summary>
/// ISO 15765-2 multi-frame network transport over raw classic CAN or CAN FD.
/// Handles SF/escaped SF, FF/32-bit FF, CF, FC CTS/WAIT/OVFLW, BS, STmin and
/// strict sequence/identity correlation. Uses real raw CAN frame hardware
/// through ICanLink; do NOT feed its frames to an ISO15765 J2534 channel
/// (which already segments). Supports arbitrary UDS payloads, read AND write.
/// </summary>
internal static class IsoTpCanFdSession
{
    internal const int MaximumPayloadBytes = 1024 * 1024;
    internal sealed record CanFrame(uint Id, bool IsFd, byte[] Data);

    internal sealed record Settings(
        uint TxId, uint RxId, int TxFrameLength, bool CanFd,
        byte BlockSize = 8, byte StMin = 0, byte Padding = 0xCC,
        byte? TxAddress = null, byte? RxAddress = null,
        int MaxWaitFrames = 4, bool MixedAddressing = false,
        int FlowControlTimeoutMs = 1000,
        int ConsecutiveFrameTimeoutMs = 1000)
    {
        internal void Validate()
        {
            if (TxId > 0x1FFFFFFF || RxId > 0x1FFFFFFF ||
                (TxId == RxId && (!TxAddress.HasValue ||
                    !RxAddress.HasValue || TxAddress == RxAddress)) ||
                TxFrameLength is < 8 or > 64 ||
                (!CanFd && TxFrameLength != 8) ||
                (CanFd && StandardVehicleProtocols.CanFdLengthToDlc(TxFrameLength) < 0))
                throw new ArgumentException("Unsupported CAN/CAN-FD ISO-TP IDs or frame size.");
            if (MaxWaitFrames is < 0 or > 255)
                throw new ArgumentOutOfRangeException(nameof(MaxWaitFrames));
            if (FlowControlTimeoutMs is < 1 or > 30000 ||
                ConsecutiveFrameTimeoutMs is < 1 or > 30000)
                throw new ArgumentOutOfRangeException(
                    nameof(FlowControlTimeoutMs),
                    "N_Bs and N_Cr must each be between 1 and 30000 ms.");
            if (TxAddress.HasValue != RxAddress.HasValue)
                throw new ArgumentException("ISO-TP one-byte addressing requires both TX and RX addresses.");
            // Mixed addressing carries the same N_AE in both directions;
            // N_SA/N_TA are instead encoded in distinct 29-bit CAN IDs.
            if (MixedAddressing &&
                (!TxAddress.HasValue || !RxAddress.HasValue ||
                 TxAddress != RxAddress || TxId == RxId))
                throw new ArgumentException(
                    "Mixed ISO-TP requires one shared address extension and distinct CAN IDs.");
            ValidateAdvertisedStMin(StMin);
        }
    }

    private static int AddressWidth(Settings s) => s.TxAddress.HasValue ? 1 : 0;

    private static void SetAddress(byte[] frame, Settings s)
    {
        if (s.TxAddress is byte address) frame[0] = address;
    }

    internal interface ICanLink
    {
        Task SendAsync(CanFrame frame, CancellationToken token);
        Task<CanFrame> ReceiveAsync(TimeSpan timeout, CancellationToken token);
        /// <summary>Hardware/driver must respect minimum STmin for sub-ms timings.</summary>
        Task WaitMinimumSeparationAsync(TimeSpan minimum, CancellationToken token);
    }

    /// <summary>
    /// When the peer supplies a reserved STmin (0x80..0xF0 or 0xFA..0xFF),
    /// ISO 15765-2 requires a sender to use the longest defined interval
    /// (127 ms), not to silently send at zero separation or abort a valid
    /// transfer. Locally advertised STmin remains restricted to defined
    /// codes by ValidateAdvertisedStMin.
    /// </summary>
    /// <summary>
    /// Remaining delay relative to the previous completed CF enqueue.
    /// Physical CAN transmit-completion timestamps still require hardware
    /// qualification; an offline J2534 queue is not bus timing evidence.
    /// </summary>
    internal static TimeSpan RemainingCfSeparation(
        TimeSpan configured, TimeSpan sincePreviousCf)
    {
        if (configured < TimeSpan.Zero || sincePreviousCf < TimeSpan.Zero)
            throw new ArgumentOutOfRangeException(nameof(sincePreviousCf));
        return configured > sincePreviousCf ?
            configured - sincePreviousCf : TimeSpan.Zero;
    }

    internal static TimeSpan DecodeStMin(byte value) => value switch
    {
        <= 0x7F => TimeSpan.FromMilliseconds(value),
        >= 0xF1 and <= 0xF9 => TimeSpan.FromTicks((value - 0xF0) * 1000L),
        _ => TimeSpan.FromMilliseconds(127)
    };

    internal static bool IsReservedStMin(byte value) =>
        value > 0x7F && (value < 0xF1 || value > 0xF9);

    internal static TimeSpan EffectiveTransferStMin(
        TimeSpan current, bool reservedStMinSeen) =>
        reservedStMinSeen ? TimeSpan.FromMilliseconds(127) : current;

    private static void ValidateAdvertisedStMin(byte value)
    {
        if (IsReservedStMin(value))
            throw new InvalidDataException(
                "Local ISO-TP FlowControl cannot advertise a reserved STmin.");
    }

    internal static byte[] BuildFlowControl(int length, byte status,
        byte blockSize, byte stMin, byte padding = 0xCC,
        byte? address = null)
    {
        if (length is < 8 or > 64 ||
            (length > 8 && StandardVehicleProtocols.CanFdLengthToDlc(length) < 0) ||
            status > 2)
            throw new ArgumentOutOfRangeException(nameof(length));
        ValidateAdvertisedStMin(stMin);
        var bytes = Enumerable.Repeat(padding, length).ToArray();
        var offset = address.HasValue ? 1 : 0;
        if (address is byte source) bytes[0] = source;
        bytes[offset] = (byte)(0x30 | status);
        bytes[offset + 1] = blockSize;
        bytes[offset + 2] = stMin;
        return bytes;
    }

    internal static async Task SendAsync(ICanLink link, Settings settings,
        byte[] payload, TimeSpan timeout, CancellationToken token)
    {
        ArgumentNullException.ThrowIfNull(link);
        ArgumentNullException.ThrowIfNull(payload);
        settings.Validate();
        if (payload.Length is < 1 or > MaximumPayloadBytes ||
            timeout <= TimeSpan.Zero)
            throw new ArgumentOutOfRangeException(nameof(payload));

        var clock = Stopwatch.StartNew();
        var addressWidth = AddressWidth(settings);
        var singleCapacity = settings.TxFrameLength == 8 ?
            7 - addressWidth : settings.TxFrameLength - 2 - addressWidth;
        if (payload.Length <= singleCapacity)
        {
            var sf = Pad(settings);
            SetAddress(sf, settings);
            var header = settings.TxFrameLength > 8 ? 2 : 1;
            var pci = addressWidth;
            if (header == 2)
            {
                sf[pci] = 0x00; // Escaped SF_DL for CAN FD with CAN_DL > 8
                sf[pci + 1] = checked((byte)payload.Length);
            }
            else sf[pci] = checked((byte)payload.Length);
            payload.CopyTo(sf, pci + header);
            await link.SendAsync(new(settings.TxId, settings.CanFd, sf), token);
            _ = Remaining(timeout, clock);
            token.ThrowIfCancellationRequested();
            return;
        }

        var prefixSize = payload.Length > 4095 ? 6 : 2;
        var ff = Pad(settings);
        SetAddress(ff, settings);
        if (prefixSize == 2)
        {
            ff[addressWidth] = (byte)(0x10 | (payload.Length >> 8));
            ff[addressWidth + 1] = (byte)payload.Length;
        }
        else
        {
            ff[addressWidth] = 0x10;
            ff[addressWidth + 1] = 0;
            BinaryPrimitives.WriteUInt32BigEndian(ff.AsSpan(addressWidth + 2), (uint)payload.Length);
        }
        var offset = Math.Min(payload.Length, ff.Length - prefixSize - addressWidth);
        payload.AsSpan(0, offset).CopyTo(ff.AsSpan(prefixSize + addressWidth));
        await link.SendAsync(new(settings.TxId, settings.CanFd, ff), token);
        token.ThrowIfCancellationRequested();

        var nextSequence = 1;
        var sentInBlock = 0;
        // STmin is measured between consecutive CF transmissions, never
        // from FC to the very first CF. Preserve the previous CF timestamp
        // across block boundaries; time consumed awaiting the next FC
        // already counts towards the next CF's separation.
        long? previousCfCompletedAt = null;
        var flow = await AwaitCtsAsync(link, settings,
            Remaining(timeout, clock), token);
        // ISO 15765-2: once an FC carries a reserved STmin, the longest
        // defined interval applies for the rest of this segmented transfer.
        // Later CTS frames may change block size but cannot clear that
        // conservative 127 ms fallback.
        var reservedStMinSeen = flow.ReservedSeparation;
        while (offset < payload.Length)
        {
            if (flow.BlockSize != 0 && sentInBlock >= flow.BlockSize)
            {
                flow = await AwaitCtsAsync(link, settings,
                    Remaining(timeout, clock), token);
                reservedStMinSeen |= flow.ReservedSeparation;
                sentInBlock = 0;
            }
            _ = Remaining(timeout, clock);
            var sincePreviousCf = previousCfCompletedAt is long previous ?
                Stopwatch.GetElapsedTime(previous) : flow.Separation;
            var effectiveSeparation = EffectiveTransferStMin(
                flow.Separation, reservedStMinSeen);
            var separationToWait = RemainingCfSeparation(
                effectiveSeparation, sincePreviousCf);
            await link.WaitMinimumSeparationAsync(separationToWait, token);
            token.ThrowIfCancellationRequested();
            _ = Remaining(timeout, clock);
            var cf = Pad(settings);
            SetAddress(cf, settings);
            cf[addressWidth] = (byte)(0x20 | (nextSequence & 0x0F));
            var take = Math.Min(payload.Length - offset, cf.Length - 1 - addressWidth);
            payload.AsSpan(offset, take).CopyTo(cf.AsSpan(1 + addressWidth));
            await link.SendAsync(new(settings.TxId, settings.CanFd, cf), token);
            token.ThrowIfCancellationRequested();
            previousCfCompletedAt = Stopwatch.GetTimestamp();
            offset += take;
            nextSequence = (nextSequence + 1) & 0x0F;
            sentInBlock++;
        }
        _ = Remaining(timeout, clock);
        token.ThrowIfCancellationRequested();
    }

    internal static async Task<byte[]> ReceiveAsync(
        ICanLink link, Settings settings, TimeSpan timeout, CancellationToken token)
    {
        ArgumentNullException.ThrowIfNull(link);
        settings.Validate();
        if (timeout <= TimeSpan.Zero) throw new ArgumentOutOfRangeException(nameof(timeout));

        var clock = Stopwatch.StartNew();
        var first = await ReceiveCorrelatedAsync(link, settings,
            Remaining(timeout, clock), token);
        var data = first.Data;
        var addressWidth = AddressWidth(settings);
        if (data.Length <= addressWidth)
            throw new InvalidDataException("Missing ISO-TP protocol control information.");
        var kind = data[addressWidth] & 0xF0;
        if (kind == 0)
        {
            var extended = (data[addressWidth] & 0x0F) == 0;
            if (extended && (!settings.CanFd || data.Length <= 8 ||
                data.Length < addressWidth + 3))
                throw new InvalidDataException("Escaped ISO-TP SF requires CAN FD length > 8.");
            if (!extended && data.Length > 8)
                throw new InvalidDataException("CAN FD SF must use escaped size on CAN_DL > 8.");
            var header = extended ? 2 : 1;
            var singleLength = extended ? data[addressWidth + 1] :
                data[addressWidth] & 0x0F;
            if (singleLength == 0 || singleLength > data.Length - header - addressWidth)
                throw new InvalidDataException("ISO-TP single-frame size mismatch.");
            _ = Remaining(timeout, clock);
            token.ThrowIfCancellationRequested();
            return data.AsSpan(header + addressWidth, singleLength).ToArray();
        }

        if (kind != 0x10 || data.Length < 8 ||
            data.Length < addressWidth + 3)
            throw new InvalidDataException(
                "ISO-TP FirstFrame needs at least eight CAN data bytes.");

        var size12 = (data[addressWidth] & 0x0F) << 8 |
            data[addressWidth + 1];
        var extendedSize = size12 == 0;
        var headerBytes = (extendedSize ? 6 : 2) + addressWidth;
        if (data.Length <= headerBytes)
            throw new InvalidDataException("Invalid ISO-TP first frame header.");
        var lengthLong = extendedSize ?
            BinaryPrimitives.ReadUInt32BigEndian(data.AsSpan(addressWidth + 2, 4)) : (uint)size12;
        // RX_DL is defined by the peer's FirstFrame CAN_DL, not the
        // length we use for our own outgoing CAN/FC frames.
        var rxDl = data.Length;
        // ISO 15765-2: a well-formed FF declaring more data than the
        // configured receive buffer can hold is rejected with FC/OVFLW.
        // Do so *before* any large allocation, and do not confuse malformed
        // lengths (which are framing errors) with receiver exhaustion.
        if (lengthLong > MaximumPayloadBytes)
        {
            _ = Remaining(timeout, clock);
            await link.SendAsync(new(settings.TxId, settings.CanFd,
                BuildFlowControl(settings.TxFrameLength, 2, settings.BlockSize,
                    settings.StMin, settings.Padding, settings.TxAddress)), token);
            _ = Remaining(timeout, clock);
            throw new InvalidDataException(
                "ISO-TP peer declared a FirstFrame larger than the configured receive buffer (FC/OVFLW sent).");
        }
        if (lengthLong <= (rxDl == 8 ?
                (uint)(7 - addressWidth) :
                (uint)(rxDl - 2 - addressWidth)) ||
            (extendedSize && lengthLong <= 4095))
            throw new InvalidDataException("ISO-TP first frame has invalid declared size.");

        var length = (int)lengthLong;
        var output = new byte[length];
        var collected = Math.Min(length, data.Length - headerBytes);
        data.AsSpan(headerBytes, collected).CopyTo(output);
        await link.SendAsync(new(settings.TxId, settings.CanFd,
            BuildFlowControl(settings.TxFrameLength, 0, settings.BlockSize,
                settings.StMin, settings.Padding, settings.TxAddress)), token);
        var nextSequence = 1;
        var framesInBlock = 0;
        while (collected < length)
        {
            var next = await ReceiveCorrelatedAsync(link, settings,
                RemainingStage(timeout, clock,
                    settings.ConsecutiveFrameTimeoutMs), token);
            var cf = next.Data;
            if (cf.Length < addressWidth + 2 || cf.Length > rxDl ||
                (cf[addressWidth] & 0xF0) != 0x20 ||
                (cf[addressWidth] & 0x0F) != (nextSequence & 0x0F))
                throw new InvalidDataException("ISO-TP CF sequence/control mismatch.");
            var take = Math.Min(length - collected, cf.Length - 1 - addressWidth);
            // Nonterminal frames must carry a full CAN payload. An early
            // short frame would introduce a gap, not a valid next offset.
            if (take < length - collected && cf.Length != rxDl)
                throw new InvalidDataException("Truncated nonterminal ISO-TP CF.");
            cf.AsSpan(1 + addressWidth, take).CopyTo(output.AsSpan(collected));
            collected += take;
            nextSequence = (nextSequence + 1) & 0x0F;
            framesInBlock++;
            if (collected < length && settings.BlockSize != 0 &&
                framesInBlock >= settings.BlockSize)
            {
                await link.SendAsync(new(settings.TxId, settings.CanFd,
                    BuildFlowControl(settings.TxFrameLength, 0, settings.BlockSize,
                        settings.StMin, settings.Padding, settings.TxAddress)), token);
                framesInBlock = 0;
            }
        }
        _ = Remaining(timeout, clock);
        token.ThrowIfCancellationRequested();
        return output;
    }

    private sealed record Flow(
        byte BlockSize, TimeSpan Separation, bool ReservedSeparation);

    private static async Task<Flow> AwaitCtsAsync(
        ICanLink link, Settings config, TimeSpan timeout, CancellationToken token)
    {
        var clock = Stopwatch.StartNew();
        for (var wait = 0; wait <= config.MaxWaitFrames; wait++)
        {
            var frame = await ReceiveCorrelatedAsync(link, config,
                RemainingStage(timeout, clock,
                    config.FlowControlTimeoutMs), token);
            var pci = AddressWidth(config);
            if (frame.Data.Length < pci + 3 ||
                (frame.Data[pci] & 0xF0) != 0x30)
                throw new InvalidDataException("ISO-TP flow control expected after first frame.");
            var status = frame.Data[pci] & 0x0F;
            if (status == 2) throw new IOException("ISO-TP receiver overflow (FC/OVFLW).");
            if (status == 1) continue;
            if (status != 0) throw new InvalidDataException("Invalid ISO-TP FC status.");
            var receivedStMin = frame.Data[pci + 2];
            return new Flow(frame.Data[pci + 1], DecodeStMin(receivedStMin),
                IsReservedStMin(receivedStMin));
        }
        throw new TimeoutException("Too many ISO-TP FC/WAIT responses.");
    }

    private static async Task<CanFrame> ReceiveCorrelatedAsync(
        ICanLink link, Settings config, TimeSpan timeout, CancellationToken token)
    {
        var clock = Stopwatch.StartNew();
        while (true)
        {
            token.ThrowIfCancellationRequested();
            var frame = await link.ReceiveAsync(Remaining(timeout, clock), token);
            token.ThrowIfCancellationRequested();
            _ = Remaining(timeout, clock);
            if (frame.Id != config.RxId) continue; // Ignore unrelated bus traffic
            if (frame.IsFd != config.CanFd ||
                frame.Data is null || frame.Data.Length is < 1 or > 64 ||
                (!config.CanFd && frame.Data.Length > 8) ||
                (config.CanFd && StandardVehicleProtocols.CanFdLengthToDlc(frame.Data.Length) < 0))
                throw new InvalidDataException("ISO-TP peer frame DLC/format mismatch.");
            // Extended addressing: frames with a different target address are
            // traffic for another logical participant on the same CAN ID.
            if (config.RxAddress is byte target &&
                (frame.Data.Length == 0 || frame.Data[0] != target)) continue;
            return frame;
        }
    }

    /// <summary>
    /// Bound each required peer FC (N_Bs) or subsequent CF (N_Cr) inside
    /// the still-active overall PDU deadline. Unrelated CAN frames do not
    /// restart this per-frame window.
    /// </summary>
    private static TimeSpan RemainingStage(TimeSpan budget,
        Stopwatch clock, int stageTimeoutMs)
    {
        var overall = Remaining(budget, clock);
        var stage = TimeSpan.FromMilliseconds(stageTimeoutMs);
        return overall < stage ? overall : stage;
    }

    /// <summary>
    /// The caller's timeout covers the complete transport exchange,
    /// including unrelated RX frames and FC/WAIT, rather than restarting
    /// at every CAN frame. Link implementations must also respect each
    /// remaining timeout passed to ReceiveAsync.
    /// </summary>
    private static TimeSpan Remaining(TimeSpan budget, Stopwatch clock)
    {
        var left = budget - clock.Elapsed;
        if (left <= TimeSpan.Zero)
            throw new TimeoutException("ISO-TP transaction deadline exceeded.");
        return left;
    }

    private static byte[] Pad(Settings s) =>
        Enumerable.Repeat(s.Padding, s.TxFrameLength).ToArray();
}
