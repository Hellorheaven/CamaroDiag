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
        var reserve = new TcpListener(IPAddress.Loopback, 0);
        reserve.Start();
        try
        {
            var passivePort = ((IPEndPoint)reserve.LocalEndpoint).Port;
            var observed = DoIpVehicleDiscovery.ListenAsync(
                TimeSpan.FromMilliseconds(160), CancellationToken.None,
                localPortOverride: passivePort, sourcePortOverride: port);
            var destination = new IPEndPoint(IPAddress.Loopback, passivePort);
            await rogue.SendAsync(Identity(rogueVin), destination);
            await Task.Delay(15);
            await gateway.SendAsync(Identity(requested), destination);
            var found = await observed.WaitAsync(TimeSpan.FromSeconds(2));
            Check(found.Count == 1 && found[0].Vin == requested,
                "Passive DoIP discovery filters wrong UDP source ports.");
        }
        finally { reserve.Stop(); }

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


        return checks;
    }
}
