using System.Buffers.Binary;
using System.Diagnostics;

namespace CamaroDiag.J2534Bridge;

/// <summary>
/// Explicit adapter between raw J2534 CAN/FD_CAN and ISO-TP CAN-FD.
/// J2534 ISO15765/FD_ISO15765 must NOT be passed here: those vendor channels
/// already implement ISO-TP. 4-byte arbitration ID precedes each raw CAN PDU.
/// Nominal/data bitrates and FD flags still require vendor qualification.
/// </summary>
internal sealed class IsoTpJ2534RawCanLink : IsoTpCanFdSession.ICanLink
{
    private const uint Can29BitId = 0x100;
    private const uint FdCanBrs = 0x0008_0000;
    private const uint FdCanFormat = 0x0010_0000;
    /// <summary>
    /// Narrow J2534 raw-frame seam. The actual vendor DLL remains
    /// J2534StandardChannel; this contract makes TX flags, arbitration
    /// prefix, receive statuses and errors independently testable offline.
    /// </summary>
    internal interface IRawChannel
    {
        uint ProtocolId { get; }
        void Transmit(byte[] data, uint flags);
        J2534StandardChannel.Frame? Receive(int timeoutMs);
    }

    private sealed class VendorChannel(J2534StandardChannel device) : IRawChannel
    {
        private readonly J2534StandardChannel device =
            device ?? throw new ArgumentNullException(nameof(device));
        public uint ProtocolId => device.ProtocolId;
        public void Transmit(byte[] data, uint flags) => device.Transmit(data, flags);
        public J2534StandardChannel.Frame? Receive(int timeoutMs) =>
            device.Receive(timeoutMs);
    }

    private readonly IRawChannel channel;
    private readonly bool fd;
    private readonly bool extendedId;
    private readonly bool brs;

    internal IsoTpJ2534RawCanLink(
        J2534StandardChannel channel, bool canFd, bool extendedId, bool bitRateSwitch)
        : this(new VendorChannel(channel), canFd, extendedId, bitRateSwitch) { }

    internal IsoTpJ2534RawCanLink(
        IRawChannel channel, bool canFd, bool extendedId, bool bitRateSwitch)
    {
        ArgumentNullException.ThrowIfNull(channel);
        if (channel.ProtocolId != (canFd ?
                J2534StandardChannel.FdCan : J2534StandardChannel.Can) ||
            (!canFd && bitRateSwitch))
            throw new ArgumentException(
                "Raw CAN link must use vendor CAN/FD_CAN, never ISO15765/FD_ISO15765.");
        this.channel = channel;
        fd = canFd;
        this.extendedId = extendedId;
        brs = bitRateSwitch;
    }

    internal static byte[] Pack(uint canId, byte[] payload, bool extended)
    {
        if (payload is null || canId > (extended ? 0x1FFF_FFFFu : 0x7FFu) ||
            payload.Length is < 1 or > 64)
            throw new ArgumentOutOfRangeException(nameof(payload));
        var wire = new byte[4 + payload.Length];
        BinaryPrimitives.WriteUInt32BigEndian(wire, canId);
        payload.CopyTo(wire, 4);
        return wire;
    }

    internal static IsoTpCanFdSession.CanFrame Unpack(
        byte[] bytes, uint rxStatus, bool requiredFd, bool extended)
    {
        if (bytes is null || bytes.Length is < 5 or > 68)
            throw new InvalidDataException("J2534 raw CAN reply missing CAN ID or data.");
        var canId = BinaryPrimitives.ReadUInt32BigEndian(bytes);
        if (canId > (extended ? 0x1FFF_FFFFu : 0x7FFu))
            throw new InvalidDataException("Unexpected 11/29-bit CAN identifier.");
        if ((rxStatus & Can29BitId) != (extended ? Can29BitId : 0u))
            throw new InvalidDataException(
                "CAN_29BIT_ID receive status does not match the selected arbitration format.");
        if ((rxStatus & FdCanFormat) != (requiredFd ? FdCanFormat : 0u))
            throw new InvalidDataException("FD_CAN_FORMAT receive status disagrees with channel.");
        var data = bytes.AsSpan(4).ToArray();
        if (requiredFd)
        {
            _ = StandardVehicleProtocols.CanFdLengthToDlc(data.Length);
        }
        else if (data.Length > 8)
            throw new InvalidDataException("Classical CAN frame exceeds 8 bytes.");
        return new(canId, requiredFd, data);
    }

    public Task SendAsync(IsoTpCanFdSession.CanFrame frame, CancellationToken token)
    {
        token.ThrowIfCancellationRequested();
        if (frame.IsFd != fd) throw new InvalidDataException("Wrong CAN frame format.");
        if (fd) _ = StandardVehicleProtocols.CanFdLengthToDlc(frame.Data.Length);
        else if (frame.Data.Length > 8)
            throw new InvalidDataException("Classical CAN payload length exceeds 8 bytes.");
        var flags = (extendedId ? Can29BitId : 0u) |
                    (fd ? FdCanFormat : 0u) |
                    (brs ? FdCanBrs : 0u);
        channel.Transmit(Pack(frame.Id, frame.Data, extendedId), flags);
        return Task.CompletedTask;
    }

    public Task<IsoTpCanFdSession.CanFrame> ReceiveAsync(TimeSpan timeout,
        CancellationToken token)
    {
        token.ThrowIfCancellationRequested();
        if (timeout <= TimeSpan.Zero || timeout > TimeSpan.FromSeconds(30))
            throw new ArgumentOutOfRangeException(nameof(timeout));
        // Vendor queues may contain status reports or unrelated bus traffic.
        // Correlation with a particular ECU is enforced by ISO-TP settings.
        var frame = channel.Receive((int)Math.Clamp(timeout.TotalMilliseconds, 1, 30000));
        if (frame is null) throw new TimeoutException("No J2534 raw CAN frame.");
        return Task.FromResult(Unpack(frame.Data, frame.RxStatus, fd, extendedId));
    }

    public async Task WaitMinimumSeparationAsync(TimeSpan minimum, CancellationToken token)
    {
        token.ThrowIfCancellationRequested();
        if (minimum < TimeSpan.Zero ||
            minimum > TimeSpan.FromMilliseconds(127))
            throw new ArgumentOutOfRangeException(nameof(minimum));
        if (minimum == TimeSpan.Zero) return;
        // Preserve a *minimum* interval; no claim of precise upper-bound
        // microsecond scheduling on general-purpose Windows/user-mode J2534.
        var deadline = Stopwatch.GetTimestamp() +
            (long)Math.Ceiling(minimum.TotalSeconds * Stopwatch.Frequency);
        if (minimum >= TimeSpan.FromMilliseconds(2))
            await Task.Delay(minimum - TimeSpan.FromMilliseconds(1), token);
        while (Stopwatch.GetTimestamp() < deadline)
        {
            token.ThrowIfCancellationRequested();
            Thread.SpinWait(16);
        }
    }
}
