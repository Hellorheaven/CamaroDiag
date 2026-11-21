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
        Assert(!BridgeServer.IsPrivateNetworkAddress(IPAddress.Parse("100.64.0.10")), "Carrier-grade NAT denied");
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

        // Physical memory reads require an explicitly scoped map and a matching
        // live VIN. These checks exercise ONLY the policy parser and range gate.
        var map = new J2534MemoryReadScope(
            "1G1FA1RXXR0123456", 0x7E0, 0x7E8, 33,
            0x80001000u, 0x100u, 64, 0x03);
        Assert(map.MatchesChannel(0x7E0, 0x7E8, 33), "Read map CAN channel");
        Assert(!map.MatchesChannel(0x7E1, 0x7E8, 33), "Wrong CAN ID rejected");
        Assert(map.AllowsReadMemory(new byte[] {
            0x23, 0x14, 0x80, 0x00, 0x10, 0x00, 0x20 }), "Scoped physical memory read");
        Assert(map.AllowsReadMemory(new byte[] {
            0x23, 0x24, 0x80, 0x00, 0x10, 0x20, 0x00, 0x40 }), "Two-byte size ALFI");
        Assert(!map.AllowsReadMemory(new byte[] {
            0x23, 0x14, 0x80, 0x00, 0x0F, 0xFF, 0x01 }), "No read before allowlist");
        Assert(!map.AllowsReadMemory(new byte[] {
            0x23, 0x24, 0x80, 0x00, 0x10, 0xF0, 0x00, 0x40 }), "No read past allowlist");
        Assert(!map.AllowsReadMemory(new byte[] {
            0x23, 0x14, 0x80, 0x00, 0x10, 0x00, 0x80 }), "Maximum block size enforced");
        Assert(!map.AllowsReadMemory(new byte[] {
            0x23, 0x41, 0x80, 0x00, 0x10, 0x00, 0x20 }), "Wrong ALFI rejected");
        Assert(!map.AllowsReadMemory(new byte[] {
            0x3D, 0x14, 0x80, 0x00, 0x10, 0x00, 0x20 }), "Writes never authorized");
        Assert(map.AllowsSessionControl(new byte[] { 0x10, 0x03 }), "Explicit extended read session");
        Assert(!map.AllowsSessionControl(new byte[] { 0x10, 0x02 }), "Programming session denied");

        var scopedJson = JsonSerializer.Serialize(new {
            expectedVin = "1G1FA1RXXR0123456",
            requestCanIdHex = "7E0",
            responseCanIdHex = "7E8",
            protocolNumber = 33,
            startAddressHex = "80001000",
            lengthBytes = 256,
            maxReadBytes = 64,
            session = 3
        });
        var scopedFile = Path.GetTempFileName();
        try
        {
            File.WriteAllText(scopedFile, scopedJson);
            var loaded = J2534MemoryReadScope.Load(scopedFile);
            Assert(loaded.StartAddress == 0x80001000u, "Unsigned 32-bit memory region parsed");
            Assert(loaded.AllowsReadMemory(new byte[] {
                0x23, 0x14, 0x80, 0x00, 0x10, 0x00, 0x20 }), "Parsed read profile usable");
            File.WriteAllText(scopedFile, scopedJson[..^1] + ",\\\"allowWrite\\\":true}");
            var rejected = false;
            try { J2534MemoryReadScope.Load(scopedFile); }
            catch (ArgumentException) { rejected = true; }
            Assert(rejected, "Unknown/dangerous profile permissions rejected");
        }
        finally { File.Delete(scopedFile); }

        Console.WriteLine($"PASS: {checks} hardware-free J2534 bridge self-tests.");
        return 0;
    }
}
