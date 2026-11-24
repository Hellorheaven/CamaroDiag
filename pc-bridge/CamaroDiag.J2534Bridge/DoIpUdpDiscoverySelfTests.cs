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
        return checks;
    }
}
