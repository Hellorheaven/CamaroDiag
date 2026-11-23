using System.Buffers.Binary;
using System.Net;
using System.Net.Sockets;
using System.Text;

namespace CamaroDiag.J2534Bridge;

/// <summary>
/// ISO 13400-2 UDP vehicle identification, limited to an explicitly selected
/// IPv4 gateway. No diagnostic service or vehicle write is transmitted.
/// An identity announcement is NOT authentication of the ECU or evidence of
/// support for routing activation, TLS, programming or recovery.
/// </summary>
internal static class DoIpVehicleDiscovery
{
    internal sealed record Identity(string? Vin, ushort LogicalAddress,
        byte[] Eid, byte[] Gid, byte FurtherAction, byte? VinGidStatus,
        IPAddress Source);

    internal static byte[] BuildRequest() =>
        DoIpCodec.Encode(DoIpCodec.VehicleIdentificationRequest, []);

    internal static byte[] BuildVinRequest(string vin)
    {
        if (vin is null || vin.Length != 17 ||
            vin.Any(c => c < 0x20 || c > 0x7E))
            throw new ArgumentException("DoIP VIN request requires 17 printable ASCII characters.");
        return DoIpCodec.Encode(DoIpCodec.VehicleIdentificationRequestVin,
            Encoding.ASCII.GetBytes(vin));
    }

    internal static byte[] BuildEidRequest(byte[] eid)
    {
        if (eid is null || eid.Length != 6)
            throw new ArgumentException("DoIP EID request requires exactly 6 bytes.");
        return DoIpCodec.Encode(DoIpCodec.VehicleIdentificationRequestEid, eid);
    }

    internal static Identity ParseAnnouncement(byte[] packet, IPAddress source)
    {
        ArgumentNullException.ThrowIfNull(source);
        var decoded = DoIpCodec.Decode(packet);
        // The VIN/GID status is optional in some editions/implementations.
        if (decoded.Type != DoIpCodec.VehicleIdentificationResponse ||
            decoded.Payload.Length is not (32 or 33))
            throw new InvalidDataException("Invalid DoIP vehicle announcement type or payload length.");
        var payload = decoded.Payload;
        // DoIP can announce before the VIN is available. Preserve that
        // distinction instead of inventing an identity from invalidity bytes.
        var vinBytes = payload.AsSpan(0, 17);
        var vinUnavailable = vinBytes.IndexOfAnyExcept((byte)0x00) < 0 ||
            vinBytes.IndexOfAnyExcept((byte)0xFF) < 0;
        string? vin = vinUnavailable ? null :
            Encoding.ASCII.GetString(payload, 0, 17);
        if (vin is not null && vin.Any(c => c < 0x20 || c > 0x7E))
            throw new InvalidDataException("DoIP VIN contains invalid bytes.");
        return new Identity(vin,
            BinaryPrimitives.ReadUInt16BigEndian(payload.AsSpan(17, 2)),
            payload.AsSpan(19, 6).ToArray(),
            payload.AsSpan(25, 6).ToArray(),
            payload[31], payload.Length == 33 ? payload[32] : null, source);
    }

    internal static async Task<Identity> DiscoverAsync(string host,
        TimeSpan timeout, CancellationToken cancellationToken,
        string? vin = null, byte[]? eid = null)
    {
        if (!IPAddress.TryParse(host, out var address) ||
            address.AddressFamily != AddressFamily.InterNetwork ||
            address.Equals(IPAddress.Any) ||
            address.Equals(IPAddress.Broadcast) ||
            timeout <= TimeSpan.Zero || timeout > TimeSpan.FromSeconds(30))
            throw new ArgumentException(
                "Provide an explicit unicast IPv4 gateway and a bounded timeout.");
        if (vin is not null && eid is not null)
            throw new ArgumentException("Use either VIN or EID identification, not both.");
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        deadline.CancelAfter(timeout);
        using var socket = new UdpClient(AddressFamily.InterNetwork);
        var destination = new IPEndPoint(address, 13400);
        var request = vin is not null ? BuildVinRequest(vin) :
            eid is not null ? BuildEidRequest(eid) : BuildRequest();
        await socket.SendAsync(request.AsMemory(), destination, deadline.Token);
        while (true)
        {
            var received = await socket.ReceiveAsync(deadline.Token);
            if (!received.RemoteEndPoint.Address.Equals(address))
                continue;
            // Reject invalid announcements from the selected gateway rather
            // than silently interpreting arbitrary UDP traffic as an ECU.
            return ParseAnnouncement(received.Buffer, received.RemoteEndPoint.Address);
        }
    }
}
