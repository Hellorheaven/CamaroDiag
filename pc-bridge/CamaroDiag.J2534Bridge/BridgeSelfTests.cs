using System.Net;
using System.Text.Json;

namespace CamaroDiag.J2534Bridge;

/// <summary>Hardware-free checks of network isolation and bridge request parsing.</summary>
internal static class BridgeSelfTests
{
    public static int Run()
    {
        var checks = 0;
        void Assert(bool condition, string name)
        {
            checks++;
            if (!condition) throw new InvalidOperationException("Bridge self-test failed: " + name);
        }

        Assert(BridgeServer.IsPrivateNetworkAddress(IPAddress.Parse("192.168.1.20")), "RFC1918 LAN");
        Assert(BridgeServer.IsPrivateNetworkAddress(IPAddress.Parse("10.1.2.3")), "10/8 LAN");
        Assert(BridgeServer.IsPrivateNetworkAddress(IPAddress.Loopback), "Loopback");
        Assert(BridgeServer.IsPrivateNetworkAddress(IPAddress.Parse("172.16.0.1")), "172.16 LAN");
        Assert(!BridgeServer.IsPrivateNetworkAddress(IPAddress.Parse("172.15.255.255")), "172 public edge");
        Assert(!BridgeServer.IsPrivateNetworkAddress(IPAddress.Parse("8.8.8.8")), "Public IPv4 denied");
        Assert(!BridgeServer.IsPrivateNetworkAddress(IPAddress.Parse("2001:4860:4860::8888")), "Public IPv6 denied");

        using (var valid = JsonDocument.Parse("{\"payloadHex\":\"22 F1 90\",\"requestCanId\":\"0x7e0\"}"))
        {
            var root = valid.RootElement;
            Assert(Convert.ToHexString(BridgeServer.ParseHexPayload(root)) == "22F190", "Hex payload");
            Assert(BridgeServer.ParseHexId(root, "requestCanId") == 0x7E0, "CAN identifier");
        }

        using (var invalid = JsonDocument.Parse("{\"payloadHex\":\"22F1Z0\"}"))
        {
            var rejected = false;
            try { BridgeServer.ParseHexPayload(invalid.RootElement); }
            catch (ArgumentException) { rejected = true; }
            Assert(rejected, "Malformed payload rejected");
        }

        using (var empty = JsonDocument.Parse("{}"))
        {
            var rejected = false;
            try { BridgeServer.ParseHexId(empty.RootElement, "requestCanId"); }
            catch (ArgumentException) { rejected = true; }
            Assert(rejected, "Missing CAN identifier rejected");
        }

        Console.WriteLine($"PASS: {checks} hardware-free J2534 bridge self-tests.");
        return 0;
    }
}
