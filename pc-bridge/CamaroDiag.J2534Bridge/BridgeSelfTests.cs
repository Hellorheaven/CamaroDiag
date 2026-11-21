using System.Net;
using System.Text;
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

        // Request framing must reject oversized and truncated client data
        // before JSON parsing or J2534 device access.
        static string? ReadWire(string input)
        {
            using var stream = new MemoryStream(Encoding.UTF8.GetBytes(input));
            using var reader = new StreamReader(stream, Encoding.UTF8);
            return BridgeServer.ReadBoundedLineAsync(reader, CancellationToken.None)
                .GetAwaiter().GetResult();
        }
        Assert(ReadWire("{\"op\":\"open\"}\r\n") == "{\"op\":\"open\"}", "CRLF bridge request");
        Assert(ReadWire("") is null, "Clean EOF on empty client");
        Assert(ReadWire(new string('A', 64 * 1024) + "\n")?.Length == 64 * 1024,
            "Maximum request accepted");
        var tooLongRejected = false;
        try { ReadWire(new string('A', 64 * 1024 + 1) + "\n"); }
        catch (InvalidDataException) { tooLongRejected = true; }
        Assert(tooLongRejected, "Oversized request rejected before JSON parsing");
        var partialRequestRejected = false;
        try { ReadWire("{\"op\":\"open\"}"); }
        catch (IOException) { partialRequestRejected = true; }
        Assert(partialRequestRejected, "Unterminated request rejected");


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

        byte[] vinQuery = [0x22, 0xF1, 0x90];
        byte[] vinFrame = [0, 0, 0x07, 0xE8, 0x62, 0xF1, 0x90, 0x41];
        byte[]? Decode(uint rx = 0, uint protocol = 6, uint extra = 0,
            byte[]? frame = null, uint? size = null) =>
            J2534Device.DecodeReceivedPayload(protocol, rx, size ?? (uint)(frame ?? vinFrame).Length,
                extra, frame ?? vinFrame, 6, 0x7E8, vinQuery);
        Assert(Decode()!.SequenceEqual(vinFrame[4..]), "Normal physical J2534 receive payload");
        Assert(Decode(extra: 8) is not null, "Vendor no-extra-data index is accepted");
        foreach (var flag in new uint[] { 1, 2, 4, 8, 16, 0x80 })
            Assert(Decode(rx: flag) is null, "J2534 indication or unsupported address rejected");
        Assert(Decode(protocol: 5) is null, "Wrong protocol rejected");
        Assert(Decode(extra: 6) is null, "Unexpected trailing data rejected");
        Assert(Decode(size: 99) is null, "Truncated native message rejected");
        Assert(Decode(frame: [0, 0, 7, 0xE9, 0x62, 0xF1, 0x90]) is null, "Wrong response CAN ID rejected");
        Assert(Decode(frame: [0, 0, 7, 0xE8, 0x62, 0xF1, 0x91]) is null, "Stale DID reply rejected");
        Assert(Decode(frame: [0, 0, 7, 0xE8, 0x59, 0x02]) is null, "Stale service rejected");
        Assert(Decode(frame: [0, 0, 7, 0xE8, 0x7F, 0x19, 0x78]) is null, "Foreign pending response rejected");
        Assert(Decode(frame: [0, 0, 7, 0xE8, 0x7F, 0x22, 0x78]) is not null, "Matching pending response accepted");

        Assert(NativeE39BenchReply.Matches([0x36,0x80,0,0,0,0], [0x99]),
            "Native RAM execute acknowledgement is 99, not ordinary 76");
        Assert(!NativeE39BenchReply.Matches([0x36,0x80,0,0,0,0], [0x76]),
            "Copy acknowledgement cannot confirm RAM execution");
        Assert(NativeE39BenchReply.Matches([0x28], [0x60]), "Legacy GM communication ack");
        Assert(!NativeE39BenchReply.Matches([0x3D,0x05,2,0,0], [0x7D,2,0,0,0,0]),
            "CRC reply cannot confirm erase");
        Assert(!NativeE39BenchReply.Matches([0x27,1], [0x67,2]), "Stale security subfunction");
        Assert(NativeE39BenchReply.Matches([0x23,0x14,0,2,0,0,2], [0x63,1,2]),
            "Native readback exact response length");
        Assert(!NativeE39BenchReply.Matches([0x23,0x14,0,2,0,0,2], [0x63,1]),
            "Truncated native readback");
        byte[] executeRequest = [0x36,0x80,0,0,0,0];
        byte[] executeFrame = [0,0,7,0xE8,0x99];
        Assert(J2534Device.DecodeReceivedPayload(6,0,5,0,executeFrame,6,0x7E8,executeRequest) is null,
            "Regular bridge never interprets a native execute response");
        Assert(J2534Device.DecodeReceivedPayload(6,0,5,0,executeFrame,6,0x7E8,executeRequest,
            nativeBenchReply: true)!.SequenceEqual(new byte[]{0x99}),
            "Isolated native decoder accepts exact response from selected channel");

        Assert(StandardObdPhysicalPolicy.SupportsChannel(0x7E0,0x7E8,33), "Standard primary 11-bit OBD channel");
        Assert(StandardObdPhysicalPolicy.SupportsChannel(0x18DA10F1,0x18DAF110,34), "Standard primary 29-bit OBD channel");
        Assert(!StandardObdPhysicalPolicy.SupportsChannel(0x7DF,0x7E8,33), "Functional broadcasts not authorized");
        Assert(!StandardObdPhysicalPolicy.SupportsChannel(0x7E0,0x7E8,63), "SW-CAN not standard ISO 15765-4");
        foreach (byte[] req in new byte[][] { [1,0], [1,0x52], [3], [7], [0x0A] })
            Assert(StandardObdPhysicalPolicy.AllowsRead(req), "Standard OBD physical read supported");
        foreach (byte[] req in new byte[][] { [4], [0x27,1], [1], [1,0,1], [3,0], [0x36,0] })
            Assert(!StandardObdPhysicalPolicy.AllowsRead(req), "No malformed or mutating service admitted");
        Assert(J2534Device.DecodeReceivedPayload(6,0,7,0,[0,0,7,0xE8,0x41,0x0C,0],6,0x7E8,[1,0x0D]) is null,
            "Stale Mode 01 PID rejected");

        Assert(StandardObdPhysicalPolicy.CanClear(true,true,[0x41,0x0D,0],[0x41,0x0C,0,0]),
            "Locally enabled emissions reset requires zero speed and RPM");
        Assert(!StandardObdPhysicalPolicy.CanClear(false,true,[0x41,0x0D,0],[0x41,0x0C,0,0]),
            "Local operator permission is mandatory");
        Assert(!StandardObdPhysicalPolicy.CanClear(true,true,[0x41,0x0D,1],[0x41,0x0C,0,0]),
            "Moving vehicle cannot clear");
        Assert(!StandardObdPhysicalPolicy.CanClear(true,true,[0x41,0x0D,0],[0x41,0x0C,0,1]),
            "Running engine cannot clear");
        Assert(!StandardObdPhysicalPolicy.CanClear(true,true,[0x7F,1,0x12],[0x41,0x0C,0,0]),
            "Missing speed evidence cannot clear");
        Assert(StandardObdPhysicalPolicy.IsClearAcknowledged([0x44]) &&
            !StandardObdPhysicalPolicy.IsClearAcknowledged([0x44,0]),
            "Only exact positive clear acknowledgment accepted");

        Assert(StandardObdPhysicalPolicy.AllowsRead([2,2,0]), "Standard freeze-frame DTC request");
        Assert(StandardObdPhysicalPolicy.AllowsRead([6,0]), "Standard Mode 06 supported monitor query");
        Assert(!StandardObdPhysicalPolicy.AllowsRead([2,2,1]), "Only freeze frame 00 is scoped");
        Assert(!StandardObdPhysicalPolicy.AllowsRead([6,1]), "Unimplemented monitor read scope rejected");
        Assert(J2534Device.DecodeReceivedPayload(6,0,9,0,[0,0,7,0xE8,0x42,2,1,3,0],6,0x7E8,[2,2,0]) is null,
            "Wrong freeze-frame number rejected");
        Assert(J2534Device.DecodeReceivedPayload(6,0,7,0,[0,0,7,0xE8,0x46,1,0],6,0x7E8,[6,0]) is null,
            "Wrong monitor test identifier rejected");

        Assert(J2534Device.ClassifyExchangeDeadline(0x7E8,1500,0) is TimeoutException,
            "An optional silent identity service can be unavailable");
        Assert(J2534Device.ClassifyExchangeDeadline(0x7E8,1500,1) is IOException,
            "An unfinished ECU operation cannot be classified as silence");
        var cvns = J2534Device.DecodeIdentityRecords(6, Convert.FromHexString("49060201020304000000A0"));
        Assert(cvns.SequenceEqual(new[] { "01020304", "000000A0" }), "Mode 09 multiple CVNs");
        Assert(J2534Device.DecodeIdentityRecords(10, new byte[] { 0x7F, 9, 0x12 }).Count == 0,
            "Unavailable optional identity");
        foreach (byte nrc in new byte[] { 0x21, 0x22, 0x33, 0x78 })
        {
            bool failed = false;
            try { J2534Device.DecodeIdentityRecords(6, new byte[] { 0x7F, 9, nrc }); }
            catch (IOException) { failed = true; }
            Assert(failed, "Transient or denied identity read must not appear unsupported");
        }
        var vin = J2534Device.DecodeIdentityRecords(2, new byte[] { 0x49, 2, 1 }
            .Concat(System.Text.Encoding.ASCII.GetBytes("1G1FA1RXXR0123456")).ToArray());
        Assert(vin.Single() == "1G1FA1RXXR0123456", "Physical VIN");
        foreach (var malformed in new[] { "49060201020304", "49040101020304", "4906010102030400" })
        {
            var rejected = false;
            try { J2534Device.DecodeIdentityRecords(6, Convert.FromHexString(malformed)); }
            catch (InvalidDataException) { rejected = true; }
            Assert(rejected, "Wrong PID, record count or trailing bytes rejected");
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
            File.WriteAllText(scopedFile, scopedJson[..^1] + ",\"allowWrite\":true}");
            var rejected = false;
            try { J2534MemoryReadScope.Load(scopedFile); }
            catch (ArgumentException) { rejected = true; }
            Assert(rejected, "Unknown/dangerous profile permissions rejected");
        }
        finally { File.Delete(scopedFile); }

        // Required vendor PassThru ABI: test the same export gate used by the
        // real --probe-drivers command, without loading an unknown DLL in CI.
        Assert(J2534DriverProbe.MissingRequiredExports(_ => true).Count == 0,
            "Complete native J2534 export set accepted");
        var allMissing = J2534DriverProbe.MissingRequiredExports(_ => false);
        Assert(allMissing.Count == 11, "Every required J2534 API entry point enforced");
        Assert(allMissing.Contains("PassThruReadVersion") &&
               allMissing.Contains("PassThruWriteMsgs") &&
               allMissing.Contains("PassThruStartMsgFilter"),
            "Version, transport writes and filter APIs required");
        var missingVersion = J2534DriverProbe.MissingRequiredExports(
            name => name != "PassThruReadVersion");
        Assert(missingVersion.SequenceEqual(new[] { "PassThruReadVersion" }),
            "Missing version API detected precisely");
        var transportReady = new J2534DriverProbe.ProbeResult(
            "mock-driver", true, true, true, "1.0", "1.0", "04.04", "mock");
        Assert(transportReady.DriverOperational && !transportReady.EcuWriteValidated,
            "An operational J2534 driver must not be mistaken for certified ECU flash");
        var noDevice = transportReady with { DeviceOpened = false };
        Assert(!noDevice.DriverOperational && !noDevice.EcuWriteValidated,
            "A missing physical adapter cannot pass the driver probe");

        checks += BridgeTlsSelfTests.Run();
        checks += NativeUdsDownloadSelfTests.Run();
        checks += NativeEcuSectorSelfTests.Run();
        checks += NativeMpc5566FlashMapSelfTests.Run();
        checks += NativeE39RamLoaderSelfTests.Run();
        checks += NativeE39KernelLinkSelfTests.Run();
        checks += NativeE39ImageInspectorSelfTests.Run();
        checks += NativeE39FlashCommandsSelfTests.Run();
        checks += NativeE39SecuritySelfTests.Run();
        checks += NativeE39WriteSequenceSelfTests.Run();
        checks += NativeE39FileWriteJournalSelfTests.Run();
        checks += NativeE39ImagePreflightSelfTests.Run();
        checks += NativeE39BackupQualificationSelfTests.Run();
        checks += NativeGmlanIdentificationSelfTests.Run();
        checks += NativeE39ProgrammingSessionSelfTests.Run();
        checks += NativeE39BenchWorkflowSelfTests.Run();
        checks += NativeE39PhysicalAdmissionSelfTests.Run();
        checks += NativeE39CalibrationRepairSelfTests.Run();
        checks += NativeGmlanBenchProbeSelfTests.Run();
        checks += NativeScopedMemoryReaderSelfTests.Run();
        checks += BridgePairingDetailsSelfTests.Run();
        Console.WriteLine($"PASS: {checks} hardware-free J2534 bridge self-tests.");
        return 0;
    }
}
