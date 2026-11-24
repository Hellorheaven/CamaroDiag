using System.Buffers.Binary;
using System.Net;
using System.Net.Sockets;
using System.Text;

namespace CamaroDiag.J2534Bridge;

/// <summary>
/// ISO 13400-2 UDP vehicle identification using explicitly selected
/// unicast gateways or operator-provided IPv4 broadcast destinations. No diagnostic service or vehicle write is transmitted.
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

    internal static bool MatchesRequestedIdentity(Identity found,
        string? expectedVin, byte[]? expectedEid)
    {
        ArgumentNullException.ThrowIfNull(found);
        if (expectedVin is not null &&
            !string.Equals(found.Vin, expectedVin, StringComparison.Ordinal))
            return false;
        if (expectedEid is not null && !found.Eid.AsSpan().SequenceEqual(expectedEid))
            return false;
        return true;
    }

    /// <summary>
    /// Explicit UDP broadcast vehicle-identification request. Collects responses
    /// only for the bounded discovery window; never probes another network or
    /// sends any UDS service. Identities are announced, not authenticated.
    /// </summary>
    internal static async Task<IReadOnlyList<Identity>> DiscoverBroadcastAsync(
        string broadcastIp, TimeSpan timeout, CancellationToken cancellationToken,
        string? vin = null, byte[]? eid = null, int? portOverride = null)
    {
        if (!IPAddress.TryParse(broadcastIp, out var address) ||
            address.AddressFamily != AddressFamily.InterNetwork ||
            address.Equals(IPAddress.Any) ||
            timeout <= TimeSpan.Zero || timeout > TimeSpan.FromSeconds(30) ||
            (portOverride.HasValue && portOverride.Value is < 1 or > 65535))
            throw new ArgumentException(
                "Explicit IPv4 broadcast destination, UDP port and bounded timeout required.");

        if (vin is not null && eid is not null)
            throw new ArgumentException("Choose VIN or EID identification, not both.");
        var request = vin is not null ? BuildVinRequest(vin) :
            eid is not null ? BuildEidRequest(eid) : BuildRequest();
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        deadline.CancelAfter(timeout);
        using var socket = new UdpClient(AddressFamily.InterNetwork)
        {
            EnableBroadcast = true
        };
        var port = portOverride ?? 13400;
        await socket.SendAsync(request.AsMemory(),
            new IPEndPoint(address, port), deadline.Token);
        var identified = new Dictionary<string, Identity>(StringComparer.Ordinal);
        while (identified.Count < 32)
        {
            UdpReceiveResult received;
            try
            {
                received = await socket.ReceiveAsync(deadline.Token);
            }
            catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
            {
                break; // Discovery window elapsed, return only observed identities.
            }
            try
            {
                // A response from an arbitrary UDP source port is not an
                // announcement from the requested DoIP endpoint.
                if (received.RemoteEndPoint.Port != port) continue;
                var found = ParseAnnouncement(received.Buffer,
                    received.RemoteEndPoint.Address);
                if (!MatchesRequestedIdentity(found, vin, eid))
                    continue;
                var key = found.Source + ":" + found.LogicalAddress.ToString("X4");
                identified[key] = found;
            }
            catch (InvalidDataException)
            {
                // Unrelated or malformed UDP announcements are not discoveries.
            }
        }
        return identified.Values.ToArray();
    }

    /// <summary>
    /// Query one explicitly selected DoIP gateway for status or diagnostic
    /// power mode. Uses standard UDP control messages only, never UDS.
    /// </summary>
    internal static async Task<DoIpCodec.Message> QueryStatusAsync(
        string host, bool powerMode, TimeSpan timeout,
        CancellationToken cancellationToken, int? portOverride = null)
    {
        if (!IPAddress.TryParse(host, out var address) ||
            address.AddressFamily != AddressFamily.InterNetwork ||
            address.Equals(IPAddress.Any) ||
            address.Equals(IPAddress.Broadcast) ||
            timeout <= TimeSpan.Zero || timeout > TimeSpan.FromSeconds(30) ||
            (portOverride.HasValue && portOverride.Value is < 1 or > 65535))
            throw new ArgumentException("Provide a valid unicast DoIP IPv4 gateway, port and timeout.");
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        deadline.CancelAfter(timeout);
        using var socket = new UdpClient(AddressFamily.InterNetwork);
        var request = DoIpCodec.Encode(powerMode ?
            DoIpCodec.PowerModeRequest : DoIpCodec.EntityStatusRequest, []);
        var port = portOverride ?? 13400;
        await socket.SendAsync(request.AsMemory(),
            new IPEndPoint(address, port), deadline.Token);
        var expectedType = powerMode ?
            DoIpCodec.PowerModeResponse : DoIpCodec.EntityStatusResponse;
        while (true)
        {
            var response = await socket.ReceiveAsync(deadline.Token);
            if (!response.RemoteEndPoint.Address.Equals(address) ||
                response.RemoteEndPoint.Port != port)
                continue;
            var decoded = DoIpCodec.Decode(response.Buffer);
            if (decoded.Type != expectedType)
                continue;
            if (powerMode) _ = DoIpCodec.ParseDiagnosticPowerMode(decoded);
            else _ = DoIpCodec.ParseEntityStatus(decoded);
            return decoded;
        }
    }

    /// <summary>
    /// Listen for spontaneous DoIP UDP identification announcements, without
    /// sending even a vehicle-identification request. Requires permission to
    /// bind local UDP port 13400; this is not ECU authentication.
    /// </summary>
    internal static async Task<IReadOnlyList<Identity>> ListenAsync(
        TimeSpan duration, CancellationToken cancellationToken)
    {
        if (duration <= TimeSpan.Zero || duration > TimeSpan.FromSeconds(30))
            throw new ArgumentOutOfRangeException(nameof(duration));
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        deadline.CancelAfter(duration);
        using var receiver = new UdpClient(new IPEndPoint(IPAddress.Any, 13400));
        var identified = new Dictionary<string, Identity>(StringComparer.Ordinal);
        while (identified.Count < 32)
        {
            UdpReceiveResult received;
            try
            {
                received = await receiver.ReceiveAsync(deadline.Token);
            }
            catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
            {
                break;
            }
            try
            {
                var found = ParseAnnouncement(received.Buffer,
                    received.RemoteEndPoint.Address);
                var key = found.Source + ":" + found.LogicalAddress.ToString("X4");
                identified[key] = found;
            }
            catch (InvalidDataException)
            {
                // Ignore non-identification and malformed network traffic.
            }
        }
        return identified.Values.ToArray();
    }

    internal static async Task<Identity> DiscoverAsync(string host,
        TimeSpan timeout, CancellationToken cancellationToken,
        string? vin = null, byte[]? eid = null, int? portOverride = null)
    {
        if (!IPAddress.TryParse(host, out var address) ||
            address.AddressFamily != AddressFamily.InterNetwork ||
            address.Equals(IPAddress.Any) ||
            address.Equals(IPAddress.Broadcast) ||
            timeout <= TimeSpan.Zero || timeout > TimeSpan.FromSeconds(30) ||
            (portOverride.HasValue && portOverride.Value is < 1 or > 65535))
            throw new ArgumentException(
                "Provide an explicit unicast IPv4 gateway, port and bounded timeout.");
        if (vin is not null && eid is not null)
            throw new ArgumentException("Use either VIN or EID identification, not both.");
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        deadline.CancelAfter(timeout);
        using var socket = new UdpClient(AddressFamily.InterNetwork);
        var destination = new IPEndPoint(address, portOverride ?? 13400);
        var request = vin is not null ? BuildVinRequest(vin) :
            eid is not null ? BuildEidRequest(eid) : BuildRequest();
        await socket.SendAsync(request.AsMemory(), destination, deadline.Token);
        while (true)
        {
            var received = await socket.ReceiveAsync(deadline.Token);
            if (!received.RemoteEndPoint.Address.Equals(address) ||
                received.RemoteEndPoint.Port != destination.Port)
                continue;
            // Reject invalid announcements from the selected gateway rather
            // than silently interpreting arbitrary UDP traffic as an ECU.
            var found = ParseAnnouncement(received.Buffer,
                received.RemoteEndPoint.Address);
            if (!MatchesRequestedIdentity(found, vin, eid))
                continue;
            return found;
        }
    }
}
