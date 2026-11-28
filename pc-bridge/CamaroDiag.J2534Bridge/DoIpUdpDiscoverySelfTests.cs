using System.Net;
using System.Net.Sockets;
using System.Text;

namespace CamaroDiag.J2534Bridge;

/// <summary>
/// UDP gateway endpoint correlation with local simulated packets.
/// A discovered identity is NOT authenticated or a real ECU capability.
/// </summary>
internal static class DoIpUdpDiscoverySelfTests
{
    private static byte[] Identity(string vin)
    {
        var payload = new byte[33];
        Encoding.ASCII.GetBytes(vin).CopyTo(payload, 0);
        payload[17] = 0x12;
        payload[18] = 0x34;
        for (var i = 0; i < 6; i++)
        {
            payload[19 + i] = (byte)(i + 1);
            payload[25 + i] = (byte)(i + 11);
        }
        return DoIpCodec.Encode(DoIpCodec.VehicleIdentificationResponse, payload);
    }

    internal static int Run() => RunAsync().GetAwaiter().GetResult();

    private static async Task<int> RunAsync()
    {
        const string requested = "1G1FA1RXXR0123456";
        const string rogueVin = "1G1FA1RXXR0123457";
        var checks = 0;
        void Check(bool ok, string name)
        {
            checks++;
            if (!ok)
                throw new InvalidOperationException("DoIP UDP loopback: " + name);
        }
        Check(!DoIpVehicleDiscovery.IsExplicitUnicast(IPAddress.Parse("fe80::2")),
            "Link-local IPv6 requires a scope.");
        Check(DoIpVehicleDiscovery.IsExplicitUnicast(IPAddress.Parse("fe80::2%3")),
            "Explicit link-local IPv6 scope accepted.");
        Check(!DoIpVehicleDiscovery.IsExplicitUnicast(IPAddress.Parse("224.0.0.1")),
            "Multicast IPv4 is not a diagnostic target.");
        Check(DoIpVehicleDiscovery.TlsServerName("fe80::2%3") == "fe80::2",
            "TLS validates IP identity rather than routing scope.");
        Check(DoIpVehicleDiscovery.TlsServerName("gateway.example.org") == "gateway.example.org",
            "TLS retains explicit DNS identity.");
        using var gateway = new UdpClient(new IPEndPoint(IPAddress.Loopback, 0));
        using var rogue = new UdpClient(new IPEndPoint(IPAddress.Loopback, 0));
        var port = ((IPEndPoint)gateway.Client.LocalEndPoint!).Port;
        var sourcePort = ((IPEndPoint)rogue.Client.LocalEndPoint!).Port;
        Check(port != sourcePort, "DoIP test needs two different UDP source ports.");

        var discovered = DoIpVehicleDiscovery.DiscoverAsync(
            "127.0.0.1", TimeSpan.FromSeconds(2), CancellationToken.None,
            portOverride: port);
        var request = await gateway.ReceiveAsync().WaitAsync(TimeSpan.FromSeconds(2));
        var discoveryRequest = DoIpCodec.Decode(request.Buffer);
        Check(discoveryRequest.Type == DoIpCodec.VehicleIdentificationRequest &&
            discoveryRequest.Version == 0xFF,
            "Unicast DoIP discovery sends 0xFF default version before ECU negotiation.");
        await rogue.SendAsync(Identity(rogueVin),
            request.RemoteEndPoint);
        await Task.Delay(20); // Ensure spoof reaches the client first.
        await gateway.SendAsync(Identity(requested),
            request.RemoteEndPoint);
        var identity = await discovered.WaitAsync(TimeSpan.FromSeconds(2));
        Check(identity.Vin == requested &&
            identity.Source.Equals(IPAddress.Loopback),
            "DoIP unicast ignores same-IP identity announcements from wrong port.");

        Check(DoIpVehicleDiscovery.SameGatewayIdentity(identity, identity with
            { Eid = identity.Eid.ToArray(), Gid = identity.Gid.ToArray() }),
            "Gateway identity uses byte content, not array references.");
        foreach (var changed in new[] {
            identity with { Vin = rogueVin },
            identity with { LogicalAddress = 0x1235 },
            identity with { ProtocolVersion = 3 },
            identity with { Eid = new byte[6] },
            identity with { Gid = new byte[6] },
            identity with { Source = IPAddress.Parse("127.0.0.2") } })
            Check(!DoIpVehicleDiscovery.SameGatewayIdentity(identity, changed),
                "Substituted gateway identity is rejected.");

        // Caller mutation after the request reaches the gateway must not
        // change which EID the discovery accepts.
        byte[] requestedEid = [1, 2, 3, 4, 5, 6];
        var eidDiscovery = DoIpVehicleDiscovery.DiscoverAsync(
            "127.0.0.1", TimeSpan.FromSeconds(2), CancellationToken.None,
            eid: requestedEid, portOverride: port);
        var eidRequest = await gateway.ReceiveAsync().WaitAsync(TimeSpan.FromSeconds(2));
        var eidMessage = DoIpCodec.Decode(eidRequest.Buffer);
        Check(eidMessage.Type == DoIpCodec.VehicleIdentificationRequestEid &&
            eidMessage.Payload.SequenceEqual(requestedEid),
            "DoIP sends the requested EID.");
        requestedEid[0] = 99;
        await gateway.SendAsync(Identity(requested), eidRequest.RemoteEndPoint);
        var eidIdentity = await eidDiscovery.WaitAsync(TimeSpan.FromSeconds(2));
        Check(eidIdentity.Eid.SequenceEqual(new byte[] {1, 2, 3, 4, 5, 6}),
            "DoIP correlates against the transmitted EID despite caller mutation.");

        var status = DoIpVehicleDiscovery.QueryStatusAsync(
            "127.0.0.1", false, TimeSpan.FromSeconds(2),
            CancellationToken.None, portOverride: port);
        var statusReq = await gateway.ReceiveAsync().WaitAsync(TimeSpan.FromSeconds(2));
        Check(DoIpCodec.Decode(statusReq.Buffer).Type ==
            DoIpCodec.EntityStatusRequest,
            "EntityStatus UDP sends standard 0x4001 request.");
        await rogue.SendAsync(DoIpCodec.Encode(
            DoIpCodec.EntityStatusResponse, [0, 1, 1]),
            statusReq.RemoteEndPoint);
        await Task.Delay(20);
        await gateway.SendAsync(DoIpCodec.Encode(
            DoIpCodec.EntityStatusResponse, [0, 4, 2]),
            statusReq.RemoteEndPoint);
        var parsed = DoIpCodec.ParseEntityStatus(
            await status.WaitAsync(TimeSpan.FromSeconds(2)));
        Check(parsed.MaxSockets == 4 && parsed.CurrentSockets == 2,
            "DoIP EntityStatus uses selected source UDP port, not spoofed status.");

        var power = DoIpVehicleDiscovery.QueryStatusAsync(
            "127.0.0.1", true, TimeSpan.FromSeconds(2),
            CancellationToken.None, portOverride: port);
        var powerReq = await gateway.ReceiveAsync().WaitAsync(TimeSpan.FromSeconds(2));
        Check(DoIpCodec.Decode(powerReq.Buffer).Type ==
            DoIpCodec.PowerModeRequest,
            "PowerMode UDP sends standard 0x4003 request.");
        await gateway.SendAsync(DoIpCodec.Encode(DoIpCodec.PowerModeResponse, [1]),
            powerReq.RemoteEndPoint);
        Check(DoIpCodec.ParseDiagnosticPowerMode(
            await power.WaitAsync(TimeSpan.FromSeconds(2))) == 1,
            "DoIP power-mode ready status is parsed from selected gateway.");

        var statusV3 = DoIpVehicleDiscovery.QueryStatusAsync(
            "127.0.0.1", false, TimeSpan.FromSeconds(2),
            CancellationToken.None, portOverride: port,
            protocolVersion: 0x03);
        var requestV3 = await gateway.ReceiveAsync()
            .WaitAsync(TimeSpan.FromSeconds(2));
        Check(DoIpCodec.Decode(requestV3.Buffer).Version == 0x03,
            "DoIP UDP status uses requested version 0x03.");
        await gateway.SendAsync(DoIpCodec.Encode(
            DoIpCodec.EntityStatusResponse, [0, 2, 1], 0x03),
            requestV3.RemoteEndPoint);
        Check((await statusV3.WaitAsync(TimeSpan.FromSeconds(2))).Version == 0x03,
            "DoIP UDP gateway status accepts version-matched 0x03 response.");

        var mismatchedVersion = DoIpVehicleDiscovery.QueryStatusAsync(
            "127.0.0.1", true, TimeSpan.FromSeconds(2),
            CancellationToken.None, portOverride: port,
            protocolVersion: 0x03);
        var mismatchRequest = await gateway.ReceiveAsync()
            .WaitAsync(TimeSpan.FromSeconds(2));
        await gateway.SendAsync(DoIpCodec.Encode(
            DoIpCodec.PowerModeResponse, [1], 0x02),
            mismatchRequest.RemoteEndPoint);
        var refusedVersion = false;
        try { await mismatchedVersion; }
        catch (InvalidDataException) { refusedVersion = true; }
        Check(refusedVersion,
            "DoIP UDP status rejects a response on the wrong negotiated version.");

        var badStatusVersion = false;
        try
        {
            await DoIpVehicleDiscovery.QueryStatusAsync(
                "127.0.0.1", false, TimeSpan.FromMilliseconds(20),
                CancellationToken.None, portOverride: port,
                protocolVersion: 0xFF);
        }
        catch (ArgumentOutOfRangeException) { badStatusVersion = true; }
        Check(badStatusVersion,
            "Discovery-only 0xFF is forbidden for DoIP UDP status requests.");


        // A standard UDP Generic NACK from the selected endpoint must
        // reveal the gateway's rejection code instead of masking it as an
        // unresponsive vehicle or returning a false-positive discovery.
        var rejectedStatus = DoIpVehicleDiscovery.QueryStatusAsync(
            "127.0.0.1", false, TimeSpan.FromSeconds(2),
            CancellationToken.None, portOverride: port);
        var rejectedStatusRequest = await gateway.ReceiveAsync()
            .WaitAsync(TimeSpan.FromSeconds(2));
        await gateway.SendAsync(DoIpCodec.Encode(
            DoIpCodec.GenericNegativeAck, [0x01]),
            rejectedStatusRequest.RemoteEndPoint);
        var receivedNack = false;
        try { await rejectedStatus; }
        catch (IOException) { receivedNack = true; }
        Check(receivedNack,
            "UDP status generic NACK is reported as gateway refusal.");

        var rejectedDiscovery = DoIpVehicleDiscovery.DiscoverAsync(
            "127.0.0.1", TimeSpan.FromSeconds(2),
            CancellationToken.None, portOverride: port);
        var rejectedDiscoveryRequest = await gateway.ReceiveAsync()
            .WaitAsync(TimeSpan.FromSeconds(2));
        await gateway.SendAsync(DoIpCodec.Encode(
            DoIpCodec.GenericNegativeAck, [0x04]),
            rejectedDiscoveryRequest.RemoteEndPoint);
        var refusedIdentity = false;
        try { await rejectedDiscovery; }
        catch (IOException) { refusedIdentity = true; }
        Check(refusedIdentity,
            "Unicast DoIP identification generic NACK is not a timeout.");

        // No response from the selected endpoint: even a syntactically valid
        // announcement from the same IP but wrong source port must time out.
        var onlySpoof = DoIpVehicleDiscovery.DiscoverAsync(
            "127.0.0.1", TimeSpan.FromMilliseconds(100), CancellationToken.None,
            portOverride: port);
        var spoofReq = await gateway.ReceiveAsync().WaitAsync(TimeSpan.FromSeconds(2));
        await rogue.SendAsync(Identity(requested), spoofReq.RemoteEndPoint);
        var rejectedSpoof = false;
        try { await onlySpoof; }
        catch (OperationCanceledException) { rejectedSpoof = true; }
        Check(rejectedSpoof, "Sole wrong-source-port announcement cannot pass discovery.");

        // A correct endpoint is still not authorized to satisfy an already
        // expired request: the client must not credit a late UDP response.
        // Windows delivers an ICMP Port Unreachable as SocketException
        // on the sending UDP socket after the client times out. Each
        // delayed-response test must use its OWN gateway socket.
        using var lateIdentityGateway = new UdpClient(
            new IPEndPoint(IPAddress.Loopback, 0));
        var lateIdentityPort =
            ((IPEndPoint)lateIdentityGateway.Client.LocalEndPoint!).Port;
        var lateIdentity = DoIpVehicleDiscovery.DiscoverAsync(
            "127.0.0.1", TimeSpan.FromMilliseconds(30),
            CancellationToken.None, portOverride: lateIdentityPort);
        var lateIdentityRequest = await lateIdentityGateway.ReceiveAsync()
            .WaitAsync(TimeSpan.FromSeconds(2));
        await Task.Delay(90);
        await lateIdentityGateway.SendAsync(Identity(requested),
            lateIdentityRequest.RemoteEndPoint);
        var identityExpired = false;
        try { await lateIdentity; }
        catch (OperationCanceledException) { identityExpired = true; }
        Check(identityExpired,
            "Late correct-port DoIP identity must not pass deadline.");

        using var lateStatusGateway = new UdpClient(
            new IPEndPoint(IPAddress.Loopback, 0));
        var lateStatusPort =
            ((IPEndPoint)lateStatusGateway.Client.LocalEndPoint!).Port;
        var lateStatus = DoIpVehicleDiscovery.QueryStatusAsync(
            "127.0.0.1", false, TimeSpan.FromMilliseconds(30),
            CancellationToken.None, portOverride: lateStatusPort);
        var lateStatusRequest = await lateStatusGateway.ReceiveAsync()
            .WaitAsync(TimeSpan.FromSeconds(2));
        await Task.Delay(90);
        await lateStatusGateway.SendAsync(DoIpCodec.Encode(
            DoIpCodec.EntityStatusResponse, [0, 4, 2]),
            lateStatusRequest.RemoteEndPoint);
        var statusExpired = false;
        try { await lateStatus; }
        catch (OperationCanceledException) { statusExpired = true; }
        Check(statusExpired,
            "Late correct-port DoIP status must not pass deadline.");


        var invalidPort = false;
        try
        {
            await DoIpVehicleDiscovery.DiscoverAsync(
                "127.0.0.1", TimeSpan.FromMilliseconds(100),
                CancellationToken.None, portOverride: 0);
        }
        catch (ArgumentException) { invalidPort = true; }
        Check(invalidPort, "Invalid UDP destination port is refused before any send.");

        // A passive announcement is likewise valid only if it originates
        // from the configured DoIP discovery port, not arbitrary UDP noise.
        // Allocate the actual UDP endpoint atomically, rather than reserving
        // an unrelated TCP port that may be excluded or occupied for UDP.
        using (var passiveReceiver = new UdpClient(
            new IPEndPoint(IPAddress.Loopback, 0)))
        {
            var destination = (IPEndPoint)passiveReceiver.Client.LocalEndPoint!;
            var observed = DoIpVehicleDiscovery.ListenOnBoundSocketAsync(
                passiveReceiver, TimeSpan.FromMilliseconds(500),
                CancellationToken.None, port);
            await rogue.SendAsync(Identity(rogueVin), destination);
            await Task.Delay(15);
            await gateway.SendAsync(Identity(requested), destination);
            var found = await observed.WaitAsync(TimeSpan.FromSeconds(2));
            Check(found.Count == 1 && found[0].Vin == requested,
                "Passive DoIP discovery filters wrong UDP source ports.");
        }

        // The bound-socket entry point must enforce the same constraints as
        // the public passive discovery path without taking socket ownership.
        using (var unbound = new UdpClient(AddressFamily.InterNetwork))
        {
            var rejected = false;
            try
            {
                await DoIpVehicleDiscovery.ListenOnBoundSocketAsync(
                    unbound, TimeSpan.FromMilliseconds(100),
                    CancellationToken.None, port);
            }
            catch (ArgumentException) { rejected = true; }
            Check(rejected, "Unbound passive UDP receiver is rejected.");
        }
        using (var bound = new UdpClient(new IPEndPoint(IPAddress.Loopback, 0)))
        {
            foreach (var invalidDuration in new[] { TimeSpan.Zero, TimeSpan.FromSeconds(31) })
            {
                var rejected = false;
                try
                {
                    await DoIpVehicleDiscovery.ListenOnBoundSocketAsync(
                        bound, invalidDuration, CancellationToken.None, port);
                }
                catch (ArgumentOutOfRangeException) { rejected = true; }
                Check(rejected, "Bound passive receiver rejects invalid duration.");
            }
            foreach (var invalidSourcePort in new[] { 0, 65536 })
            {
                var rejected = false;
                try
                {
                    await DoIpVehicleDiscovery.ListenOnBoundSocketAsync(
                        bound, TimeSpan.FromMilliseconds(100),
                        CancellationToken.None, invalidSourcePort);
                }
                catch (ArgumentOutOfRangeException) { rejected = true; }
                Check(rejected, "Bound passive receiver rejects invalid source port.");
            }
            using var canceled = new CancellationTokenSource();
            canceled.Cancel();
            var cancellationObserved = false;
            try
            {
                await DoIpVehicleDiscovery.ListenOnBoundSocketAsync(
                    bound, TimeSpan.FromMilliseconds(100), canceled.Token, port);
            }
            catch (OperationCanceledException) { cancellationObserved = true; }
            Check(cancellationObserved && bound.Client.IsBound,
                "Canceled passive discovery preserves caller-owned socket.");
        }

        var invalidPassivePort = false;
        try
        {
            await DoIpVehicleDiscovery.ListenAsync(
                TimeSpan.FromMilliseconds(100), CancellationToken.None,
                sourcePortOverride: 0);
        }
        catch (ArgumentOutOfRangeException) { invalidPassivePort = true; }
        Check(invalidPassivePort,
            "Invalid passive discovery source-port configuration is rejected.");

        // IPv6 UDP unicast has the same source-port and payload rules as
        // IPv4; skip the loopback probe only on machines without IPv6.
        if (Socket.OSSupportsIPv6)
        {
            using var ipv6Gateway = new UdpClient(
                new IPEndPoint(IPAddress.IPv6Loopback, 0));
            var ipv6Port =
                ((IPEndPoint)ipv6Gateway.Client.LocalEndPoint!).Port;
            var ipv6Discovery = DoIpVehicleDiscovery.DiscoverAsync(
                "::1", TimeSpan.FromSeconds(2), CancellationToken.None,
                portOverride: ipv6Port);
            var ipv6Request = await ipv6Gateway.ReceiveAsync()
                .WaitAsync(TimeSpan.FromSeconds(2));
            Check(DoIpCodec.Decode(ipv6Request.Buffer).Version == 0xFF,
                "IPv6 DoIP discovery uses standard default version 0xFF.");
            await ipv6Gateway.SendAsync(Identity(requested),
                ipv6Request.RemoteEndPoint);
            var ipv6Identity = await ipv6Discovery.WaitAsync(TimeSpan.FromSeconds(2));
            Check(ipv6Identity.Vin == requested &&
                ipv6Identity.Source.Equals(IPAddress.IPv6Loopback),
                "IPv6 unicast DoIP vehicle identification parses exact ECU source.");

            var ipv6Status = DoIpVehicleDiscovery.QueryStatusAsync(
                "::1", false, TimeSpan.FromSeconds(2),
                CancellationToken.None, portOverride: ipv6Port);
            var ipv6StatusRequest = await ipv6Gateway.ReceiveAsync()
                .WaitAsync(TimeSpan.FromSeconds(2));
            await ipv6Gateway.SendAsync(DoIpCodec.Encode(
                DoIpCodec.EntityStatusResponse, [0, 2, 1]),
                ipv6StatusRequest.RemoteEndPoint);
            var ipv6Entity = DoIpCodec.ParseEntityStatus(
                await ipv6Status.WaitAsync(TimeSpan.FromSeconds(2)));
            Check(ipv6Entity.MaxSockets == 2 &&
                ipv6Entity.CurrentSockets == 1,
                "IPv6 UDP DoIP status response is correlated and decoded.");
        }

        // Passive IPv6 announcements belong to the ff02::1 link-local
        // scope and must come from the selected DoIP UDP source port.
        // Test the filtering without requiring Windows CI runners to join
        // a real IPv6 multicast interface (hardware/Release test later).
        var invalidInterface = false;
        try
        {
            DoIpVehicleDiscovery.ValidateIpv6MulticastOptions(0,
                TimeSpan.FromSeconds(1), null, null);
        }
        catch (ArgumentOutOfRangeException) { invalidInterface = true; }
        Check(invalidInterface,
            "IPv6 multicast listener requires explicit nonzero interface.");

        var invalidMulticastPort = false;
        try
        {
            DoIpVehicleDiscovery.ValidateIpv6MulticastOptions(2,
                TimeSpan.FromMilliseconds(500), null, 0);
        }
        catch (ArgumentOutOfRangeException) { invalidMulticastPort = true; }
        Check(invalidMulticastPort,
            "IPv6 DoIP passive listener refuses source port zero.");

        var invalidMulticastDuration = false;
        try
        {
            DoIpVehicleDiscovery.ValidateIpv6MulticastOptions(2,
                TimeSpan.FromSeconds(31), null, null);
        }
        catch (ArgumentOutOfRangeException) { invalidMulticastDuration = true; }
        Check(invalidMulticastDuration,
            "IPv6 multicast listener remains bounded to thirty seconds.");

        DoIpVehicleDiscovery.ValidateIpv6MulticastOptions(2,
            TimeSpan.FromSeconds(1), 13400, 13400);
        Check(true, "Valid IPv6 multicast listen profile accepted offline.");

        var expectedIpv6Source = new IPEndPoint(
            IPAddress.Parse("fe80::1234"), 13400);
        Check(DoIpVehicleDiscovery.IsIpv6MulticastAnnouncementSource(
            expectedIpv6Source, 13400),
            "IPv6 link-local DoIP gateway and standard source port accepted.");
        Check(!DoIpVehicleDiscovery.IsIpv6MulticastAnnouncementSource(
            new IPEndPoint(IPAddress.IPv6Loopback, 13400), 13400),
            "IPv6 loopback does not masquerade as link-local announcement.");
        Check(!DoIpVehicleDiscovery.IsIpv6MulticastAnnouncementSource(
            new IPEndPoint(IPAddress.Parse("2001:db8::1"), 13400), 13400),
            "Unrelated global IPv6 sender cannot claim link-local announcement.");
        Check(!DoIpVehicleDiscovery.IsIpv6MulticastAnnouncementSource(
            new IPEndPoint(IPAddress.Parse("fe80::1234"), 15300), 13400),
            "Wrong UDP source port cannot claim IPv6 DoIP announcement.");

        var scopedV6 = new IPEndPoint(new IPAddress(
            IPAddress.Parse("fe80::1234").GetAddressBytes(), 2), 13400);
        Check(DoIpVehicleDiscovery.IsIpv6MulticastAnnouncementSource(
            scopedV6, 13400, 2),
            "DoIP IPv6 multicast accepts matching explicit link scope.");
        Check(!DoIpVehicleDiscovery.IsIpv6MulticastAnnouncementSource(
            scopedV6, 13400, 3),
            "DoIP IPv6 multicast rejects another explicit interface scope.");


        var activeDestination = DoIpVehicleDiscovery.Ipv6MulticastDestination(2);
        Check(activeDestination.AddressFamily == AddressFamily.InterNetworkV6 &&
            activeDestination.IsIPv6Multicast &&
            activeDestination.ToString().StartsWith("ff02::1%", StringComparison.OrdinalIgnoreCase) &&
            activeDestination.ScopeId == 2,
            "DoIP ff02::1 active multicast scopes the correct IPv6 interface.");
        var badActiveInterface = false;
        try { _ = DoIpVehicleDiscovery.Ipv6MulticastDestination(-1); }
        catch (ArgumentOutOfRangeException) { badActiveInterface = true; }
        Check(badActiveInterface,
            "Active DoIP IPv6 multicast refuses unscoped/default interface.");
        Check(DoIpCodec.Decode(DoIpVehicleDiscovery.BuildRequest()).Version == 0xFF,
            "Active DoIP multicast requests use version 0xFF before ECU negotiation.");
        Check(DoIpVehicleDiscovery.MatchesRequestedIdentity(
            DoIpVehicleDiscovery.ParseAnnouncement(
                Identity(requested), IPAddress.Parse("fe80::1234")),
            requested, null),
            "Active IPv6 multicast VIN filtering accepts matching response.");
        Check(!DoIpVehicleDiscovery.MatchesRequestedIdentity(
            DoIpVehicleDiscovery.ParseAnnouncement(
                Identity(requested), IPAddress.Parse("fe80::1234")),
            rogueVin, null),
            "Active IPv6 multicast VIN filtering excludes foreign gateway.");


        // VIN-targeted discovery must wait for competing announcements, not
        // simply select the first matching response. Same IP/logical address
        // with different EID is also ambiguous.
        foreach (var ambiguous in new[] { false, true })
        {
            using var vinGateway = new UdpClient(new IPEndPoint(IPAddress.Loopback, 0));
            var vinPort = ((IPEndPoint)vinGateway.Client.LocalEndPoint!).Port;
            var trace = new List<(string Direction, byte[] Data)>();
            var select = DoIpVehicleDiscovery.DiscoverUniqueVinAsync(requested,
                TimeSpan.FromMilliseconds(800), CancellationToken.None,
                (direction, _, data) => trace.Add((direction, data)),
                "127.0.0.1", vinPort);
            var req = await vinGateway.ReceiveAsync().WaitAsync(TimeSpan.FromSeconds(2));
            var expected = DoIpCodec.Decode(req.Buffer);
            Check(expected.Type == DoIpCodec.VehicleIdentificationRequestVin &&
                Encoding.ASCII.GetString(expected.Payload) == requested,
                "VIN-specific discovery transmits the selected VIN.");
            var matching = Identity(requested);
            var conflicting = matching.ToArray();
            conflicting[8 + 19] ^= 0x10; // different EID, same IP/logical address
            await vinGateway.SendAsync(Identity(rogueVin), req.RemoteEndPoint);
            await vinGateway.SendAsync(matching, req.RemoteEndPoint);
            await vinGateway.SendAsync(matching, req.RemoteEndPoint);
            if (ambiguous) await vinGateway.SendAsync(conflicting, req.RemoteEndPoint);
            if (ambiguous)
            {
                var refused = false;
                try { await select; }
                catch (InvalidOperationException) { refused = true; }
                Check(refused, "VIN discovery refuses competing gateway identities.");
            }
            else
            {
                var chosen = await select;
                Check(chosen.Vin == requested && chosen.Source.Equals(IPAddress.Loopback),
                    "VIN discovery ignores other VINs and deduplicates identical announcements.");
            }
            Check(trace.Count >= 4 && trace[0].Direction == "TX" &&
                trace[0].Data.SequenceEqual(req.Buffer) &&
                trace.Skip(1).All(x => x.Direction == "RX") &&
                trace.Any(x => x.Data.SequenceEqual(matching)),
                "UDP trace preserves direction and actual datagram bytes.");
        }

        return checks;
    }
}
