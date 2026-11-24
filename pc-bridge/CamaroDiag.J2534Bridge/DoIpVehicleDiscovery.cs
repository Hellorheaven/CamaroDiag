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
        IPAddress Source, byte ProtocolVersion);

    internal delegate void DatagramTrace(string direction, IPEndPoint endpoint, byte[] data);

    internal static bool IsVehicleVin(string vin) =>
        vin.Length == 17 && vin.All(c => c is >= '0' and <= '9' ||
            c is >= 'A' and <= 'Z' && c is not ('I' or 'O' or 'Q'));

    internal static async Task<Identity> DiscoverUniqueVinAsync(string vin, TimeSpan timeout,
        CancellationToken cancellationToken, DatagramTrace? trace = null,
        string broadcastIp = "255.255.255.255", int? portOverride = null)
    {
        if (!IsVehicleVin(vin))
            throw new ArgumentException("Expected a valid vehicle VIN.", nameof(vin));
        var candidates = await DiscoverBroadcastAsync(broadcastIp, timeout, cancellationToken,
            vin: vin, portOverride: portOverride, trace: trace);
        if (candidates.Count != 1)
            throw new InvalidOperationException("DoIP gateway not found or ambiguous for selected VIN.");
        return candidates[0];
    }

    internal static byte[] BuildRequest() =>
        DoIpCodec.Encode(DoIpCodec.VehicleIdentificationRequest, [],
            DoIpCodec.VehicleIdentificationDefaultVersion);

    internal static byte[] BuildVinRequest(string vin)
    {
        if (vin is null || vin.Length != 17 ||
            vin.Any(c => c < 0x20 || c > 0x7E))
            throw new ArgumentException("DoIP VIN request requires 17 printable ASCII characters.");
        return DoIpCodec.Encode(DoIpCodec.VehicleIdentificationRequestVin,
            Encoding.ASCII.GetBytes(vin),
            DoIpCodec.VehicleIdentificationDefaultVersion);
    }

    internal static byte[] BuildEidRequest(byte[] eid)
    {
        if (eid is null || eid.Length != 6)
            throw new ArgumentException("DoIP EID request requires exactly 6 bytes.");
        return DoIpCodec.Encode(DoIpCodec.VehicleIdentificationRequestEid, eid,
            DoIpCodec.VehicleIdentificationDefaultVersion);
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
        if (!vinUnavailable && vinBytes.IndexOfAnyExceptInRange((byte)0x20, (byte)0x7E) >= 0)
            throw new InvalidDataException("DoIP VIN contains invalid bytes.");
        string? vin = vinUnavailable ? null : Encoding.ASCII.GetString(vinBytes);
        return new Identity(vin,
            BinaryPrimitives.ReadUInt16BigEndian(payload.AsSpan(17, 2)),
            payload.AsSpan(19, 6).ToArray(),
            payload.AsSpan(25, 6).ToArray(),
            payload[31], payload.Length == 33 ? payload[32] : null,
            source, decoded.Version);
    }

    internal static bool SameGatewayIdentity(Identity selected, Identity observed) =>
        selected.Source.Equals(observed.Source) &&
        string.Equals(selected.Vin, observed.Vin, StringComparison.Ordinal) &&
        selected.LogicalAddress == observed.LogicalAddress &&
        selected.ProtocolVersion == observed.ProtocolVersion &&
        selected.Eid.AsSpan().SequenceEqual(observed.Eid) &&
        selected.Gid.AsSpan().SequenceEqual(observed.Gid);

    internal static async Task RequireCurrentIdentityAsync(Identity selected,
        TimeSpan timeout, CancellationToken token, DatagramTrace? trace = null)
    {
        // A fresh unicast announcement correlates the target selected earlier;
        // UDP identity is not authenticated and never substitutes for TLS/ECU VIN.
        var current = await DiscoverAsync(selected.Source.ToString(), timeout, token,
            vin: selected.Vin, trace: trace);
        if (!SameGatewayIdentity(selected, current))
            throw new InvalidDataException("DoIP gateway identity changed; rediscover before routing.");
        token.ThrowIfCancellationRequested();
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
        string? vin = null, byte[]? eid = null, int? portOverride = null,
        DatagramTrace? trace = null)
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
        // Correlate replies with the same immutable EID used for the request.
        var fixedEid = eid?.ToArray();
        var request = vin is not null ? BuildVinRequest(vin) :
            fixedEid is not null ? BuildEidRequest(fixedEid) : BuildRequest();
        // Reject a UDP packet delivered after the receive deadline even if
        // the socket completes concurrently with cancellation.
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        deadline.CancelAfter(timeout);
        using var socket = new UdpClient(AddressFamily.InterNetwork)
        {
            EnableBroadcast = true
        };
        var port = portOverride ?? 13400;
        var destination = new IPEndPoint(address, port);
        await socket.SendAsync(request.AsMemory(), destination, deadline.Token);
        trace?.Invoke("TX", destination, request.ToArray());
        var identified = new Dictionary<string, Identity>(StringComparer.Ordinal);
        while (identified.Count < 32)
        {
            UdpReceiveResult received;
            try
            {
                received = await socket.ReceiveAsync(deadline.Token);
                deadline.Token.ThrowIfCancellationRequested();
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
                trace?.Invoke("RX", received.RemoteEndPoint, received.Buffer.ToArray());
                var found = ParseAnnouncement(received.Buffer,
                    received.RemoteEndPoint.Address);
                if (!MatchesRequestedIdentity(found, vin, fixedEid))
                    continue;
                var key = found.Source + ":" + found.LogicalAddress.ToString("X4") +
                    ":" + Convert.ToHexString(found.Eid) + ":" + Convert.ToHexString(found.Gid) +
                    ":" + found.ProtocolVersion.ToString("X2") + ":" + found.Vin;
                identified[key] = found;
            }
            catch (InvalidDataException)
            {
                // Unrelated or malformed UDP announcements are not discoveries.
            }
        }
        cancellationToken.ThrowIfCancellationRequested();
        return identified.Values.ToArray();
    }

    /// <summary>
    /// Query one explicitly selected DoIP gateway for status or diagnostic
    /// power mode. Uses standard UDP control messages only, never UDS.
    /// </summary>
    internal static async Task<DoIpCodec.Message> QueryStatusAsync(
        string host, bool powerMode, TimeSpan timeout,
        CancellationToken cancellationToken, int? portOverride = null,
        byte protocolVersion = DoIpCodec.Version, DatagramTrace? trace = null)
    {
        if (protocolVersion is not (0x02 or 0x03 or 0x04))
            throw new ArgumentOutOfRangeException(nameof(protocolVersion),
                "Gateway UDP control query requires an explicit supported DoIP version.");
        if (!IPAddress.TryParse(host, out var address) ||
            address.AddressFamily is not (AddressFamily.InterNetwork or
                AddressFamily.InterNetworkV6) ||
            address.Equals(IPAddress.Any) ||
            address.Equals(IPAddress.Broadcast) ||
            address.Equals(IPAddress.IPv6Any) ||
            address.IsIPv6Multicast ||
            timeout <= TimeSpan.Zero || timeout > TimeSpan.FromSeconds(30) ||
            (portOverride.HasValue && portOverride.Value is < 1 or > 65535))
            throw new ArgumentException("Provide a valid unicast DoIP IPv4/IPv6 gateway, port and timeout.");
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        deadline.CancelAfter(timeout);
        using var socket = new UdpClient(address.AddressFamily);
        var request = DoIpCodec.Encode(powerMode ?
            DoIpCodec.PowerModeRequest : DoIpCodec.EntityStatusRequest, [],
            protocolVersion);
        var port = portOverride ?? 13400;
        var destination = new IPEndPoint(address, port);
        await socket.SendAsync(request.AsMemory(), destination, deadline.Token);
        trace?.Invoke("TX", destination, request.ToArray());
        var expectedType = powerMode ?
            DoIpCodec.PowerModeResponse : DoIpCodec.EntityStatusResponse;
        while (true)
        {
            var response = await socket.ReceiveAsync(deadline.Token);
            deadline.Token.ThrowIfCancellationRequested();
            if (!response.RemoteEndPoint.Address.Equals(address) ||
                response.RemoteEndPoint.Port != port)
                continue;
            trace?.Invoke("RX", response.RemoteEndPoint, response.Buffer.ToArray());
            var decoded = DoIpCodec.Decode(response.Buffer);
            if (decoded.Version != protocolVersion)
                throw new InvalidDataException(
                    "DoIP UDP control response changed the requested protocol version.");
            // The gateway explicitly rejected our UDP request: expose the
            // standard DoIP error instead of eventually claiming silence.
            DoIpCodec.RequireNoGenericNack(decoded);
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
        TimeSpan duration, CancellationToken cancellationToken,
        int? localPortOverride = null, int? sourcePortOverride = null)
    {
        if (duration <= TimeSpan.Zero || duration > TimeSpan.FromSeconds(30) ||
            (localPortOverride.HasValue &&
                localPortOverride.Value is < 1 or > 65535) ||
            (sourcePortOverride.HasValue &&
                sourcePortOverride.Value is < 1 or > 65535))
            throw new ArgumentOutOfRangeException(nameof(duration),
                "Passive DoIP listening needs a bounded interval and standard UDP endpoint.");
        using var receiver = new UdpClient(
            new IPEndPoint(IPAddress.Any, localPortOverride ?? 13400));
        return await ListenOnBoundSocketAsync(receiver, duration,
            cancellationToken, sourcePortOverride ?? 13400);
    }

    // The caller owns the already-bound socket. Keeping it open avoids a
    // reserve/rebind race and permits OS-assigned UDP ports in offline tests.
    internal static async Task<IReadOnlyList<Identity>> ListenOnBoundSocketAsync(
        UdpClient receiver, TimeSpan duration, CancellationToken cancellationToken,
        int sourcePort)
    {
        ArgumentNullException.ThrowIfNull(receiver);
        if (duration <= TimeSpan.Zero || duration > TimeSpan.FromSeconds(30))
            throw new ArgumentOutOfRangeException(nameof(duration));
        if (sourcePort is < 1 or > 65535)
            throw new ArgumentOutOfRangeException(nameof(sourcePort));
        if (!receiver.Client.IsBound)
            throw new ArgumentException("Passive DoIP discovery requires an already bound UDP socket.", nameof(receiver));
        cancellationToken.ThrowIfCancellationRequested();
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        deadline.CancelAfter(duration);
        var identified = new Dictionary<string, Identity>(StringComparer.Ordinal);
        while (identified.Count < 32)
        {
            UdpReceiveResult received;
            try
            {
                received = await receiver.ReceiveAsync(deadline.Token);
                deadline.Token.ThrowIfCancellationRequested();
            }
            catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
            {
                break;
            }
            try
            {
                // Spontaneous DoIP announcements must originate at the
                // gateway's standardized UDP discovery port, not an
                // arbitrary sender on the same host or LAN.
                if (received.RemoteEndPoint.Port != sourcePort) continue;
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
        cancellationToken.ThrowIfCancellationRequested();
        return identified.Values.ToArray();
    }

    /// <summary>
    /// ISO 13400 passive IPv6 link-local vehicle announcements are delivered
    /// to the all-nodes multicast group ff02::1. An explicit local interface
    /// index is required: a link-local scope cannot be guessed safely.
    /// No UDP request, UDS service or ECU command is transmitted.
    /// </summary>
    internal static async Task<IReadOnlyList<Identity>> ListenIpv6MulticastAsync(
        int interfaceIndex, TimeSpan duration, CancellationToken cancellationToken,
        int? localPortOverride = null, int? sourcePortOverride = null)
    {
        ValidateIpv6MulticastOptions(interfaceIndex, duration,
            localPortOverride, sourcePortOverride);
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        deadline.CancelAfter(duration);
        var port = localPortOverride ?? 13400;
        using var receiver = new UdpClient(AddressFamily.InterNetworkV6);
        receiver.Client.Bind(new IPEndPoint(IPAddress.IPv6Any, port));
        receiver.JoinMulticastGroup(interfaceIndex, IPAddress.Parse("ff02::1"));
        var identified = new Dictionary<string, Identity>(StringComparer.Ordinal);
        while (identified.Count < 32)
        {
            UdpReceiveResult received;
            try
            {
                received = await receiver.ReceiveAsync(deadline.Token);
                deadline.Token.ThrowIfCancellationRequested();
            }
            catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
            {
                break;
            }
            if (!IsIpv6MulticastAnnouncementSource(received.RemoteEndPoint,
                    sourcePortOverride ?? 13400, interfaceIndex))
                continue;
            try
            {
                var found = ParseAnnouncement(received.Buffer,
                    received.RemoteEndPoint.Address);
                var key = found.Source + ":" + found.LogicalAddress.ToString("X4");
                identified[key] = found;
            }
            catch (InvalidDataException)
            {
                // Other protocols and invalid UDP frames are not ECU identities.
            }
        }
        cancellationToken.ThrowIfCancellationRequested();
        return identified.Values.ToArray();
    }

    internal static void ValidateIpv6MulticastOptions(int interfaceIndex,
        TimeSpan duration, int? localPort, int? sourcePort)
    {
        if (interfaceIndex <= 0 || duration <= TimeSpan.Zero ||
            duration > TimeSpan.FromSeconds(30) ||
            (localPort.HasValue && localPort.Value is < 1 or > 65535) ||
            (sourcePort.HasValue && sourcePort.Value is < 1 or > 65535))
            throw new ArgumentOutOfRangeException(nameof(interfaceIndex),
                "IPv6 multicast DoIP listening requires an explicit interface index and bounded ports/time.");
    }

    internal static bool IsIpv6MulticastAnnouncementSource(
        IPEndPoint remote, int sourcePort, int? interfaceIndex = null) =>
        remote.Address.AddressFamily == AddressFamily.InterNetworkV6 &&
        remote.Address.IsIPv6LinkLocal && remote.Port == sourcePort &&
        // Some OS versions do not expose an ingress scope on a received
        // datagram. Reject an explicit *different* one; never invent it.
        (!interfaceIndex.HasValue || remote.Address.ScopeId == 0 ||
         remote.Address.ScopeId == interfaceIndex.Value);

    /// <summary>
    /// Active IPv6 link-local DoIP vehicle identification. Send exactly one
    /// standard 0x0001/0x0002/0x0003 UDP request to ff02::1 on the
    /// explicitly selected interface; no UDS/ECU mutation is emitted.
    /// Only identities returned by that interface's DoIP UDP port are shown.
    /// </summary>
    internal static async Task<IReadOnlyList<Identity>> DiscoverIpv6MulticastAsync(
        int interfaceIndex, TimeSpan timeout, CancellationToken cancellationToken,
        string? vin = null, byte[]? eid = null, int? portOverride = null)
    {
        ValidateIpv6MulticastOptions(interfaceIndex, timeout, null, portOverride);
        if (vin is not null && eid is not null)
            throw new ArgumentException("Choose VIN or EID identification, not both.");
        // Correlate replies with the same immutable EID used for the request.
        var fixedEid = eid?.ToArray();
        var request = vin is not null ? BuildVinRequest(vin) :
            fixedEid is not null ? BuildEidRequest(fixedEid) : BuildRequest();
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        deadline.CancelAfter(timeout);
        using var socket = new UdpClient(AddressFamily.InterNetworkV6);
        // The scoped multicast destination chooses the vehicle Ethernet
        // interface without guessing a default IPv6 route.
        var address = Ipv6MulticastDestination(interfaceIndex);
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
                deadline.Token.ThrowIfCancellationRequested();
            }
            catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
            {
                break;
            }
            if (!IsIpv6MulticastAnnouncementSource(received.RemoteEndPoint,
                    port, interfaceIndex))
                continue;
            try
            {
                var found = ParseAnnouncement(received.Buffer,
                    received.RemoteEndPoint.Address);
                if (!MatchesRequestedIdentity(found, vin, fixedEid))
                    continue;
                var key = found.Source + ":" + found.LogicalAddress.ToString("X4");
                identified[key] = found;
            }
            catch (InvalidDataException)
            {
                // Ignore unrelated IPv6 UDP payloads and malformed messages.
            }
        }
        cancellationToken.ThrowIfCancellationRequested();
        return identified.Values.ToArray();
    }

    internal static IPAddress Ipv6MulticastDestination(int interfaceIndex)
    {
        if (interfaceIndex <= 0)
            throw new ArgumentOutOfRangeException(nameof(interfaceIndex));
        return new IPAddress(IPAddress.Parse("ff02::1").GetAddressBytes(),
            interfaceIndex);
    }

    internal static async Task<Identity> DiscoverAsync(string host,
        TimeSpan timeout, CancellationToken cancellationToken,
        string? vin = null, byte[]? eid = null, int? portOverride = null, DatagramTrace? trace = null)
    {
        if (!IPAddress.TryParse(host, out var address) ||
            address.AddressFamily is not (AddressFamily.InterNetwork or
                AddressFamily.InterNetworkV6) ||
            address.Equals(IPAddress.Any) ||
            address.Equals(IPAddress.Broadcast) ||
            address.Equals(IPAddress.IPv6Any) ||
            address.IsIPv6Multicast ||
            timeout <= TimeSpan.Zero || timeout > TimeSpan.FromSeconds(30) ||
            (portOverride.HasValue && portOverride.Value is < 1 or > 65535))
            throw new ArgumentException(
                "Provide an explicit unicast IPv4/IPv6 gateway, port and bounded timeout.");
        if (vin is not null && eid is not null)
            throw new ArgumentException("Use either VIN or EID identification, not both.");
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        deadline.CancelAfter(timeout);
        using var socket = new UdpClient(address.AddressFamily);
        var destination = new IPEndPoint(address, portOverride ?? 13400);
        // Correlate replies with the same immutable EID used for the request.
        var fixedEid = eid?.ToArray();
        var request = vin is not null ? BuildVinRequest(vin) :
            fixedEid is not null ? BuildEidRequest(fixedEid) : BuildRequest();
        await socket.SendAsync(request.AsMemory(), destination, deadline.Token);
        trace?.Invoke("TX", destination, request.ToArray());
        while (true)
        {
            var received = await socket.ReceiveAsync(deadline.Token);
            deadline.Token.ThrowIfCancellationRequested();
            if (!received.RemoteEndPoint.Address.Equals(address) ||
                received.RemoteEndPoint.Port != destination.Port)
                continue;
            // An explicit generic NACK from the selected gateway is a
            // transport rejection, not an absent/malformed vehicle identity.
            trace?.Invoke("RX", received.RemoteEndPoint, received.Buffer.ToArray());
            var decoded = DoIpCodec.Decode(received.Buffer);
            DoIpCodec.RequireNoGenericNack(decoded);
            if (decoded.Type != DoIpCodec.VehicleIdentificationResponse)
                continue; // Other DoIP control packets are not announcements.
            var found = ParseAnnouncement(received.Buffer,
                received.RemoteEndPoint.Address);
            if (!MatchesRequestedIdentity(found, vin, fixedEid))
                continue;
            return found;
        }
    }
}
