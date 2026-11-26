using System.Diagnostics;

namespace CamaroDiag.J2534Bridge;

/// <summary>ISO 15031-5:2006 Table 13, normal J2534 driver-managed
/// checksum/CRC/IFR representation. Functional read requests only.
/// A selected response source is not a physical TX address.
/// </summary>
internal sealed class ObdLegacyJ2534Transport : IDisposable
{
    internal enum Variant { Pwm = 1, Vpw = 2, Iso9141 = 3, KwpSlow = 4, KwpFast = 5 }
    internal sealed record Packet(byte Source, string RawHex, byte[] Payload, uint Timestamp,
        string? InFrameResponseHex = null);
    internal sealed record Exchange(string TxHex, IReadOnlyList<Packet> Replies);
    private readonly J2534StandardChannel channel;
    private readonly Variant variant;
    private readonly byte? source;
    private readonly Action requireActive;
    internal string? InitEvidence { get; private set; }

    private ObdLegacyJ2534Transport(J2534StandardChannel channel, Variant variant,
        byte? source, Action requireActive)
    {
        this.channel = channel;
        this.variant = variant;
        this.source = source;
        this.requireActive = requireActive;
    }

    internal static (uint Id, uint Baud) Profile(Variant variant) => variant switch
    {
        Variant.Pwm => (J2534StandardChannel.J1850Pwm, 41600),
        Variant.Vpw => (J2534StandardChannel.J1850Vpw, 10400),
        Variant.Iso9141 => (J2534StandardChannel.Iso9141, 10400),
        Variant.KwpSlow or Variant.KwpFast => (J2534StandardChannel.Iso14230, 10400),
        _ => throw new ArgumentException("Unknown legacy protocol.")
    };

    internal static bool AllowsRead(byte[] request)
    {
        if (request.Length == 0) return false;
        return request[0] switch
        {
            1 or 6 or 9 => request.Length == 2,
            2 => request.Length == 3 && request[2] == 0,
            3 or 7 or 10 => request.Length == 1,
            5 => request.Length == 3 && request[2] != 0 &&
                (request[2] & (request[2] - 1)) == 0,
            8 => request.Length == 7 && request[1] % 32 == 0 &&
                request.Skip(2).All(x => x == 0),
            _ => false
        };
    }

    internal static byte[] FrameRead(Variant variant, byte[] request)
    {
        _ = Profile(variant);
        if (!AllowsRead(request)) throw new ArgumentException("Not a legacy J1979 read.");
        if (variant is Variant.KwpSlow or Variant.KwpFast)
            return StandardVehicleProtocols.KwpForJ2534(
                StandardVehicleProtocols.BuildKwpFunctionalFrame(0x33, 0xF1, request), false);
        byte header = variant == Variant.Pwm ? (byte)0x61 : (byte)0x68;
        return [header, 0x6A, 0xF1, ..request];
    }

    internal static Packet? Decode(Variant variant, byte? source, J2534StandardChannel.Frame frame)
    {
        if (frame.ProtocolId != Profile(variant).Id ||
            J2534StandardChannel.IsAuxiliaryReceiveStatus(frame.RxStatus)) return null;
        var data = frame.Data;
        if (data.Length < 4 || data[2] is 0 or 0xF1 or 0xFF ||
            source.HasValue && data[2] != source.Value) return null;
        byte actualSource = data[2];
        byte[] payload;
        if (variant is Variant.KwpSlow or Variant.KwpFast)
        {
            if (data[1] != 0xF1 || (data[0] & 0xC0) != 0x80) return null;
            payload = StandardVehicleProtocols.ParseKwpJ2534(data, 0xF1, actualSource, false, false);
        }
        else
        {
            if (data[0] != (variant == Variant.Pwm ? 0x41 : 0x48) || data[1] != 0x6B)
                return null;
            payload = data[3..];
        }
        if (payload.Length is < 1 or > 7)
            throw new InvalidDataException("Legacy application record length invalid.");
        return new(actualSource, Convert.ToHexString(data), payload, frame.Timestamp,
            frame.InFrameResponse is null ? null : Convert.ToHexString(frame.InFrameResponse));
    }

    internal static ObdLegacyJ2534Transport Open(J2534Adapter adapter, Variant variant,
        byte? source, Action requireActive)
    {
        if (source is 0 or 0xF1 or 0xFF) throw new ArgumentException("Invalid ECU response source.");
        requireActive();
        var (id, baud) = Profile(variant);
        var channel = J2534StandardChannel.Connect(adapter, id, baud);
        var transport = new ObdLegacyJ2534Transport(channel, variant, source, requireActive);
        try
        {
            if (variant == Variant.Pwm) channel.ConfigurePwmTesterAddress();
            // Match target and source. KWP format byte contains message length.
            bool kwp = variant is Variant.KwpSlow or Variant.KwpFast;
            byte sourceMask = source.HasValue ? (byte)0xFF : (byte)0;
            byte sourcePattern = source ?? 0;
            channel.ConfigureFilter(kwp ? [0xC0, 0xFF, sourceMask] : [0xFF, 0xFF, sourceMask],
                kwp ? [0x80, 0xF1, sourcePattern] :
                    [variant == Variant.Pwm ? (byte)0x41 : (byte)0x48, 0x6B, sourcePattern]);
            requireActive();
            if (variant is Variant.Iso9141 or Variant.KwpSlow)
            {
                var keywords = channel.FiveBaudInitialize(0x33);
                if (keywords.Length != 2)
                    throw new InvalidDataException("Expected two driver-returned initialization keywords.");
                transport.InitEvidence = Convert.ToHexString(keywords);
            }
            else if (variant == Variant.KwpFast)
            {
                var start = StandardVehicleProtocols.KwpForJ2534(
                    StandardVehicleProtocols.BuildKwpFunctionalFrame(0x33, 0xF1, [0x81]), false);
                var reply = channel.FastInitialize(start);
                var packet = Decode(variant, source, new(id, 0, 0, reply));
                if (packet is null || packet.Payload.Length != 3 || packet.Payload[0] != 0xC1)
                    throw new InvalidDataException("Fast initialization response not correlated to selected ECU.");
                transport.InitEvidence = packet.RawHex;
            }
            requireActive();
            return transport;
        }
        catch
        {
            transport.Dispose();
            throw;
        }
    }

    internal Exchange Read(byte[] request, int timeoutMs)
    {
        if (timeoutMs is < 1 or > 4000) throw new ArgumentOutOfRangeException(nameof(timeoutMs));
        if (!source.HasValue && !request.AsSpan().SequenceEqual(new byte[] { 1, 0 }))
            throw new InvalidOperationException("Unpinned discovery can only read initial PID 00.");
        var tx = FrameRead(variant, request);
        requireActive();
        channel.ClearReceiveQueue();
        requireActive();
        var watch = Stopwatch.StartNew();
        channel.Transmit(tx, timeoutMs: timeoutMs);
        var replies = new List<Packet>();
        while (watch.ElapsedMilliseconds < timeoutMs)
        {
            requireActive();
            // Gather all records from the chosen source (including
            // multi-record VIN/DTC) within one absolute request window.
            var remaining = (int)Math.Max(1, timeoutMs - watch.ElapsedMilliseconds);
            var frame = channel.Receive(remaining, allowPwmIfr: variant == Variant.Pwm);
            if (frame is null) break;
            var packet = Decode(variant, source, frame);
            if (packet is null) continue;
            if (replies.Count >= 128) throw new InvalidDataException("Legacy response limit exceeded.");
            replies.Add(packet);
        }
        requireActive();
        return new(Convert.ToHexString(tx), replies.ToArray());
    }

    public void Dispose() => channel.Dispose();
}
