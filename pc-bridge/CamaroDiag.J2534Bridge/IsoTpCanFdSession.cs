using System.Buffers.Binary;

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
        byte? TxAddress = null, byte? RxAddress = null)
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
            if (TxAddress.HasValue != RxAddress.HasValue)
                throw new ArgumentException("ISO-TP extended addressing requires both TX and RX addresses.");
            _ = DecodeStMin(StMin);
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

    internal static TimeSpan DecodeStMin(byte value) => value switch
    {
        <= 0x7F => TimeSpan.FromMilliseconds(value),
        >= 0xF1 and <= 0xF9 => TimeSpan.FromTicks((value - 0xF0) * 1000L),
        _ => throw new InvalidDataException("Reserved ISO-TP STmin value.")
    };

    internal static byte[] BuildFlowControl(int length, byte status,
        byte blockSize, byte stMin, byte padding = 0xCC,
        byte? address = null)
    {
        if (length is < 8 or > 64 ||
            (length > 8 && StandardVehicleProtocols.CanFdLengthToDlc(length) < 0) ||
            status > 2)
            throw new ArgumentOutOfRangeException(nameof(length));
        _ = DecodeStMin(stMin);
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

        var nextSequence = 1;
        var sentInBlock = 0;
        var flow = await AwaitCtsAsync(link, settings, timeout, token);
        while (offset < payload.Length)
        {
            if (flow.BlockSize != 0 && sentInBlock >= flow.BlockSize)
            {
                flow = await AwaitCtsAsync(link, settings, timeout, token);
                sentInBlock = 0;
            }
            await link.WaitMinimumSeparationAsync(flow.Separation, token);
            var cf = Pad(settings);
            SetAddress(cf, settings);
            cf[addressWidth] = (byte)(0x20 | (nextSequence & 0x0F));
            var take = Math.Min(payload.Length - offset, cf.Length - 1 - addressWidth);
            payload.AsSpan(offset, take).CopyTo(cf.AsSpan(1 + addressWidth));
            await link.SendAsync(new(settings.TxId, settings.CanFd, cf), token);
            offset += take;
            nextSequence = (nextSequence + 1) & 0x0F;
            sentInBlock++;
        }
    }

    internal static async Task<byte[]> ReceiveAsync(
        ICanLink link, Settings settings, TimeSpan timeout, CancellationToken token)
    {
        ArgumentNullException.ThrowIfNull(link);
        settings.Validate();
        if (timeout <= TimeSpan.Zero) throw new ArgumentOutOfRangeException(nameof(timeout));

        var first = await ReceiveCorrelatedAsync(link, settings, timeout, token);
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
            return data.AsSpan(header + addressWidth, singleLength).ToArray();
        }

        if (kind != 0x10 || data.Length < addressWidth + 3)
            throw new InvalidDataException("Expected single or first ISO-TP frame.");

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
        if (lengthLong <= (rxDl == 8 ?
                (uint)(7 - addressWidth) :
                (uint)(rxDl - 2 - addressWidth)) ||
            lengthLong > MaximumPayloadBytes ||
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
            var next = await ReceiveCorrelatedAsync(link, settings, timeout, token);
            var cf = next.Data;
            if (cf.Length < addressWidth + 2 ||
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
        return output;
    }

    private sealed record Flow(byte BlockSize, TimeSpan Separation);

    private static async Task<Flow> AwaitCtsAsync(
        ICanLink link, Settings config, TimeSpan timeout, CancellationToken token)
    {
        for (var wait = 0; wait <= 4; wait++)
        {
            var frame = await ReceiveCorrelatedAsync(link, config, timeout, token);
            var pci = AddressWidth(config);
            if (frame.Data.Length < pci + 3 ||
                (frame.Data[pci] & 0xF0) != 0x30)
                throw new InvalidDataException("ISO-TP flow control expected after first frame.");
            var status = frame.Data[pci] & 0x0F;
            if (status == 2) throw new IOException("ISO-TP receiver overflow (FC/OVFLW).");
            if (status == 1) continue;
            if (status != 0) throw new InvalidDataException("Invalid ISO-TP FC status.");
            return new Flow(frame.Data[pci + 1], DecodeStMin(frame.Data[pci + 2]));
        }
        throw new TimeoutException("Too many ISO-TP FC/WAIT responses.");
    }

    private static async Task<CanFrame> ReceiveCorrelatedAsync(
        ICanLink link, Settings config, TimeSpan timeout, CancellationToken token)
    {
        for (var attempts = 0; attempts < 64; attempts++)
        {
            var frame = await link.ReceiveAsync(timeout, token);
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
        throw new TimeoutException("No correlated ISO-TP peer frame.");
    }

    private static byte[] Pad(Settings s) =>
        Enumerable.Repeat(s.Padding, s.TxFrameLength).ToArray();
}
