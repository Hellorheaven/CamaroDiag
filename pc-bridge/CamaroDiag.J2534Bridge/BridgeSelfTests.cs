using System.Net;
using System.Text;
using System.Text.Json;

namespace CamaroDiag.J2534Bridge;

/// <summary>Hardware-free checks of network isolation and bridge request parsing.</summary>
internal static class BridgeSelfTests
{
    public static int Run()
    {
        ConnectorTlsReferenceSelfTests.Run();
        if (ConnectorDiscoverySelfTests.Run() != 0) return 1;
        ConnectorCheckpointSelfTests.Run();
        UserExportStorageSelfTests.Run();
        var checks = 0;
        void Assert(bool condition, string name)
        {
            checks++;
            if (!condition) throw new InvalidOperationException("Bridge self-test failed: " + name);
        }

        Assert(J2534Device.IsWellFormedReadOnlyDiagnosticPayload(
            [0x22, 0xF1, 0x90]), "J2534 UDS single DID shape");
        Assert(!J2534Device.IsWellFormedReadOnlyDiagnosticPayload(
            [0x22, 0xF1, 0x90, 0xF1, 0x91]), "J2534 multi-DID denied until correlation is supported");
        Assert(!J2534Device.IsWellFormedReadOnlyDiagnosticPayload(
            [0x22]), "J2534 UDS missing DID rejected");
        Assert(!J2534Device.IsWellFormedReadOnlyDiagnosticPayload(
            [0x22, 0xF1]), "J2534 UDS truncated DID rejected");
        Assert(!J2534Device.IsWellFormedReadOnlyDiagnosticPayload(
            [0x22, 0xF1, 0x90, 0xF1]), "J2534 UDS incomplete second DID rejected");
        Assert(J2534Device.IsWellFormedReadOnlyDiagnosticPayload(
            [0x19, 0x01, 0xFF]), "J2534 UDS report number by status mask");
        Assert(J2534Device.IsWellFormedReadOnlyDiagnosticPayload(
            [0x19, 0x02, 0xFF]), "J2534 UDS report DTC by status mask");
        Assert(J2534Device.IsWellFormedReadOnlyDiagnosticPayload(
            [0x19, 0x0A]), "J2534 UDS report supported DTC");
        Assert(!J2534Device.IsWellFormedReadOnlyDiagnosticPayload(
            [0x19]), "J2534 UDS missing DTC subfunction rejected");
        Assert(!J2534Device.IsWellFormedReadOnlyDiagnosticPayload(
            [0x19, 0x01]), "J2534 UDS missing status mask for DTC count");
        Assert(!J2534Device.IsWellFormedReadOnlyDiagnosticPayload(
            [0x19, 0x02]), "J2534 UDS missing status mask for DTC list");
        Assert(!J2534Device.IsWellFormedReadOnlyDiagnosticPayload(
            [0x19, 0x0A, 0xFF]), "J2534 UDS unwanted mask on supported DTC list");
        Assert(!J2534Device.IsWellFormedReadOnlyDiagnosticPayload(
            [0x19, 0xFE, 0xFF]), "J2534 UDS unsupported DTC subfunction");
        Assert(!J2534Device.IsWellFormedReadOnlyDiagnosticPayload(
            [0x14, 0xFF, 0xFF, 0xFF]), "J2534 UDS clear DTC not a read-only query");

        // A memory-scoped J2534 channel must purge stale RX before every
        // request, including its first physical F190 VIN identification.
        // Unscoped console/diagnostic channels retain their existing policy.
        Assert(J2534Device.NeedsFreshReceiveQueue(false, true),
            "Scoped ECU memory requires RX queue isolation before every request");
        Assert(J2534Device.NeedsFreshReceiveQueue(true, false),
            "Qualified native bench always requires isolated receive traffic");
        Assert(!J2534Device.NeedsFreshReceiveQueue(false, false),
            "Standard read-only J2534 console does not require the memory RX policy");

        // The first OK never authorizes a write; a second independent OUI
        // is mandatory for local programming. No, empty input and EOF cancel.
        bool Consent(params string?[] answers)
        {
            var pending = new Queue<string?>(answers);
            var messages = new List<string>();
            return EcuWriteOperatorConsent.AskBeforeProgramming(
                () => pending.Count > 0 ? pending.Dequeue() : null,
                messages.Add, "ECU test");
        }
        Assert(!Consent(), "Missing warning acknowledgement cannot write");
        Assert(!Consent("OUI", "OUI"), "Direct YES without initial OK cannot write");
        Assert(!Consent("OK"), "OK without separate YES cannot write");
        Assert(!Consent("OK", "NON"), "Explicit NO always cancels the ECU write");
        Assert(!Consent("OK", null), "Closing the final confirmation cancels");
        Assert(Consent("OK", "OUI"), "Warning OK followed by explicit YES approves one write");

        Assert(NativeEcuWriteAvailability.PhysicalEcuProgrammingEnabled,
            "Dedicated local E39 bench hardware test route is enabled");
        NativeEcuWriteAvailability.RequirePhysicalEcuProgrammingEnabled();
        var deniedBenchCli = NativeE39QualifiedBenchCli.RunAsync(new[] {
            "--program-e39-bench", "0", "not-present.json",
            "original.bin", "target.bin", "kernel.bin", "new-journal.jsonl"
        }).GetAwaiter().GetResult();
        Assert(deniedBenchCli == 8,
            "Missing local bench profile is rejected before opening J2534 drivers");

        const string memoryVin = "1G1FA1RXXR0123456";
        Assert(J2534Device.IsUnsupportedUdsVinResponse([0x7F, 0x22, 0x11]) &&
               J2534Device.IsUnsupportedUdsVinResponse([0x7F, 0x22, 0x12]) &&
               J2534Device.IsUnsupportedUdsVinResponse([0x7F, 0x22, 0x31]) &&
               !J2534Device.IsUnsupportedUdsVinResponse([0x7F, 0x22, 0x33]),
            "Only unsupported UDS VIN replies permit a legacy GMLAN fallback");
        var physicalGmlanVin = new byte[] { 0x5A, 0x90 }
            .Concat(Encoding.ASCII.GetBytes(memoryVin)).ToArray();
        Assert(J2534Device.MatchesScopedGmlanVin(physicalGmlanVin, memoryVin) &&
               !J2534Device.MatchesScopedGmlanVin(physicalGmlanVin, "1G1FA1RXXR0123457") &&
               !J2534Device.MatchesScopedGmlanVin(physicalGmlanVin[..^1], memoryVin),
            "GMLAN 1A/90 must prove the exact full VIN before memory read");
        var memoryVinReply = new byte[] { 0x62, 0xF1, 0x90 }
            .Concat(Encoding.ASCII.GetBytes(memoryVin)).ToArray();
        Assert(J2534Device.MatchesScopedMemoryVin(memoryVinReply, memoryVin),
            "J2534 scoped memory read requires exact physical F190 VIN");
        Assert(!J2534Device.MatchesScopedMemoryVin(
            memoryVinReply.Concat(new byte[] { 0x00 }).ToArray(), memoryVin),
            "J2534 rejects physical VIN responses with trailing bytes");
        Assert(!J2534Device.MatchesScopedMemoryVin(
            memoryVinReply[..^1], memoryVin),
            "J2534 rejects truncated physical VIN replies");
        Assert(!J2534Device.MatchesScopedMemoryVin(
            memoryVinReply, "1G1FA1RXXR0123457"),
            "J2534 rejects a different ECU VIN before any scoped memory reads");
        Assert(!J2534Device.MatchesScopedMemoryVin(
            new byte[] { 0x62, 0xF1, 0x91 }
                .Concat(Encoding.ASCII.GetBytes(memoryVin)).ToArray(), memoryVin),
            "J2534 rejects a VIN from a different DID");

        Assert(BridgeConsoleDirection.Normalize("TX") == "TX", "CAN console TX direction");
        Assert(BridgeConsoleDirection.Normalize("RX") == "RX", "CAN console RX direction");
        Assert(BridgeConsoleDirection.Normalize("SYSTEM") == "SYSTEM", "CAN console SYSTEM direction");
        Assert(BridgeConsoleDirection.Normalize("INFO") == "SYSTEM", "Legacy INFO classified SYSTEM");
        Assert(BridgeConsoleDirection.Normalize(null) == "SYSTEM", "Unknown CAN console direction fails closed");
        // Pending TX and driver queue acceptance are not evidence of bus
        // delivery; only a correlated ECU reply may be marked as such.
        foreach (var state in new[] { BridgeConsoleTxState.Requested,
            BridgeConsoleTxState.DriverAccepted, BridgeConsoleTxState.Unconfirmed })
        {
            Assert(!BridgeConsoleTxEvidence.HasVerifiedEcuReply(state),
                "Unverified Windows console TX must not claim ECU confirmation");
        }
        Assert(BridgeConsoleTxEvidence.HasVerifiedEcuReply(BridgeConsoleTxState.EcuReplied),
            "Correlated J2534 ECU reply confirms a response");
        Assert(BridgeConsoleTxEvidence.Label(BridgeConsoleTxState.Requested).Contains("non confirmée"),
            "Windows TX request is visibly pending");
        Assert(BridgeConsoleTxEvidence.Label(BridgeConsoleTxState.DriverAccepted).Contains("non vérifiée"),
            "Windows no-response transmission does not claim bus delivery");
        Assert(BridgeConsoleTxEvidence.Label(BridgeConsoleTxState.Unconfirmed).Contains("non confirmée"),
            "Windows J2534 timeout marks TX as unconfirmed");

        Assert(BridgeServer.IsPrivateNetworkAddress(IPAddress.Parse("192.168.1.20")), "RFC1918 LAN");
        Assert(BridgeServer.IsPrivateNetworkAddress(IPAddress.Parse("10.1.2.3")), "10/8 LAN");
        Assert(BridgeServer.IsPrivateNetworkAddress(IPAddress.Loopback), "Loopback");
        Assert(BridgeServer.IsPrivateNetworkAddress(IPAddress.Parse("172.16.0.1")), "172.16 LAN");
        Assert(!BridgeServer.IsPrivateNetworkAddress(IPAddress.Parse("172.15.255.255")), "172 public edge");
        Assert(!BridgeServer.IsPrivateNetworkAddress(IPAddress.Parse("8.8.8.8")), "Public IPv4 denied");
        Assert(!BridgeServer.IsPrivateNetworkAddress(IPAddress.Parse("100.64.0.10")), "Carrier-grade NAT denied");
        Assert(!BridgeServer.IsPrivateNetworkAddress(IPAddress.Parse("2001:4860:4860::8888")), "Public IPv6 denied");

        const string samplePairingToken = "authenticated-bridge-token";
        foreach (var valid in new[] {
            "{\"token\":\"authenticated-bridge-token\",\"op\":\"open\"}",
            "{\"op\":\"close\",\"token\":\"authenticated-bridge-token\"}"
        })
        {
            using var json = JsonDocument.Parse(valid);
            Assert(BridgeServer.HasValidRequestToken(json.RootElement, samplePairingToken),
                "Correct bearer token is required on every J2534 operation");
        }
        foreach (var denied in new[] {
            "{}", "null", "[]", "true",
            "{\"token\":null}", "{\"token\":42}",
            "{\"token\":[]}", "{\"token\":{}}",
            "{\"token\":\"\"}",
            "{\"token\":\"authenticated-bridge-TOKEN\"}",
            "{\"token\":\"authenticated-bridge-token-extra\"}",
            "{\"token\":\"bad\",\"token\":\"authenticated-bridge-token\",\"op\":\"open\"}",
            "{\"token\":\"authenticated-bridge-token\",\"op\":\"close\",\"op\":\"open\"}",
            "{\"token\":\"authenticated-bridge-token\"}",
            "{\"token\":\"authenticated-bridge-token\",\"op\":42}"
        })
        {
            using var json = JsonDocument.Parse(denied);
            Assert(!BridgeServer.HasValidRequestToken(json.RootElement, samplePairingToken),
                "Malformed or missing bearer token must end the bridge session");
        }
        using (var json = JsonDocument.Parse("{\"token\":\"authenticated-bridge-token\"}"))
            Assert(!BridgeServer.HasValidRequestToken(json.RootElement, ""),
                "A bridge with an empty expected token never authorizes operations");

        // A correct bearer token must never make an ambiguously decoded
        // operation safe. The full command schema is checked before J2534.
        foreach (var validRequest in new[] {
            "{\"token\":\"x\",\"op\":\"open\",\"requestCanId\":\"7E0\",\"responseCanId\":\"7E8\",\"can29Bit\":false,\"protocolNumber\":33}",
            "{\"token\":\"x\",\"op\":\"identify\"}",
            "{\"token\":\"x\",\"op\":\"identifyGmlan\"}",
            "{\"token\":\"x\",\"op\":\"exchange\",\"payloadHex\":\"22F190\",\"timeoutMs\":3000}",
            "{\"token\":\"x\",\"op\":\"sendOnly\",\"payloadHex\":\"3E80\"}",
            "{\"token\":\"x\",\"op\":\"exchangeRaw\",\"payloadHex\":\"2EF19000\",\"timeoutMs\":3000,\"warningAcknowledged\":true,\"writeConfirmed\":true}",
            "{\"token\":\"x\",\"op\":\"sendRaw\",\"payloadHex\":\"2701\",\"warningAcknowledged\":true,\"writeConfirmed\":true}",
            "{\"token\":\"x\",\"op\":\"clearStandardObdDtcs\",\"confirmed\":true,\"warningAcknowledged\":true,\"writeConfirmed\":true}",
            "{\"token\":\"x\",\"op\":\"close\"}"
        }) {
            using var json = JsonDocument.Parse(validRequest);
            Assert(BridgeServer.HasCanonicalRequestShape(json.RootElement),
                "Known J2534 operation has exactly its expected fields");
        }
        foreach (var rawConfirmation in new[] {
            "{\"warningAcknowledged\":true,\"writeConfirmed\":true}",
            "{\"warningAcknowledged\":true,\"writeConfirmed\":false}",
            "{\"warningAcknowledged\":false,\"writeConfirmed\":true}",
            "{\"warningAcknowledged\":true}",
            "{\"writeConfirmed\":true}",
            "{}"
        })
        {
            using var json = JsonDocument.Parse(rawConfirmation);
            var expected = rawConfirmation == "{\"warningAcknowledged\":true,\"writeConfirmed\":true}";
            Assert(BridgeServer.HasExplicitEcuWriteConsent(json.RootElement) == expected,
                "Only both explicit UI confirmations permit a raw ECU write");
        }
        foreach (var forged in new[] {
            "{\"token\":\"x\",\"op\":\"exchange\",\"payloadHex\":\"22F190\",\"payloadHex\":\"27AA\"}",
            "{\"token\":\"x\",\"op\":\"exchangeRaw\",\"payloadHex\":\"2EF190\",\"payloadHex\":\"27AA\"}",
            "{\"token\":\"x\",\"op\":\"sendRaw\",\"payloadHex\":\"3601\",\"unexpectedFlag\":true}",
            "{\"token\":\"x\",\"op\":\"exchangeRaw\",\"payloadHex\":\"2EF19000\"}",
            "{\"token\":\"x\",\"op\":\"sendRaw\",\"payloadHex\":\"2701\",\"warningAcknowledged\":false,\"writeConfirmed\":true}",
            "{\"token\":\"x\",\"op\":\"sendRaw\",\"payloadHex\":\"2701\",\"warningAcknowledged\":true,\"writeConfirmed\":false}",
            "{\"token\":\"x\",\"op\":\"clearStandardObdDtcs\",\"confirmed\":true}",
            "{\"token\":\"x\",\"op\":\"clearStandardObdDtcs\",\"confirmed\":true,\"warningAcknowledged\":true,\"writeConfirmed\":false}",
            "{\"token\":\"x\",\"op\":\"open\",\"requestCanId\":\"7E0\",\"requestCanId\":\"7DF\",\"responseCanId\":\"7E8\"}",
            "{\"token\":\"x\",\"op\":\"clearStandardObdDtcs\",\"confirmed\":true,\"confirmed\":false}",
            "{\"token\":\"x\",\"op\":\"exchange\",\"payloadHex\":\"22F190\",\"timeoutMs\":1000,\"timeoutMs\":9999}",
            "{\"token\":\"x\",\"op\":\"exchange\",\"payloadHex\":\"22F190\",\"confirmed\":true}",
            "{\"token\":\"x\",\"op\":\"close\",\"payloadHex\":\"27AA\"}",
            "{\"token\":\"x\",\"op\":\"open\",\"requestCanId\":\"7E0\"}",
            "{\"token\":\"x\",\"op\":\"open\",\"requestCanId\":\"7E0\",\"responseCanId\":\"7E8\",\"can29Bit\":1}",
            "{\"token\":\"x\",\"op\":\"exchange\",\"payloadHex\":\"22F190\",\"timeoutMs\":1.5}",
            "{\"token\":\"x\",\"op\":\"unknown\"}",
            "[]"
        }) {
            using var json = JsonDocument.Parse(forged);
            Assert(!BridgeServer.HasCanonicalRequestShape(json.RootElement),
                "Duplicate, unknown and mistyped CAN requests are denied before native I/O");
        }

        Assert(BridgeServer.RequiresSessionTermination(new UnauthorizedAccessException()),
            "Denied ECU service revokes the current PC bridge session");
        Assert(BridgeServer.RequiresSessionTermination(new IOException()),
            "Uncertain native J2534 transport ends its owner session");
        Assert(BridgeServer.RequiresSessionTermination(new InvalidDataException()),
            "Malformed physical reply invalidates the session");
        Assert(!BridgeServer.RequiresSessionTermination(new TimeoutException()),
            "Ordinary ECU silence is not independently a disconnected adapter");
        Assert(!BridgeServer.RequiresSessionTermination(new ArgumentException()),
            "Bad local field syntax is rejected without reusing a privileged request");

        // The peer is told when its authorized TLS session is revoked.
        using (var fatal = JsonDocument.Parse(BridgeServer.SerializeErrorResponse(
            "Unqualified ECU operation.", true)))
            Assert(fatal.RootElement.GetProperty("sessionTerminated").GetBoolean() &&
                !fatal.RootElement.GetProperty("ok").GetBoolean(),
                "Fatal security errors explicitly revoke the client's bridge session");
        using (var ordinary = JsonDocument.Parse(BridgeServer.SerializeErrorResponse(
            "A recoverable ECU timeout.", false)))
            Assert(!ordinary.RootElement.GetProperty("sessionTerminated").GetBoolean(),
                "Recoverable errors do not falsely report a terminated session");

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
        foreach (var invalidFrame in new[] {
            "{\"op\":\"cl\rose\"}\n",
            "{\"op\":\"open\"}\r\r\n",
            "{\"op\":\"open\"}\0\n",
            "{\"op\":\"open\"}\t\n",
            "{\"op\":\"identité\"}\n"
        })
        {
            var refused = false;
            try { ReadWire(invalidFrame); }
            catch (InvalidDataException) { refused = true; }
            Assert(refused, "A malformed bridge line must not be silently canonicalized");
        }
        Assert(ReadWire("") is null, "Clean EOF on empty client");
        // A malicious LAN peer can complete TLS before knowing the bearer
        // token; malformed UTF-8 must never be silently replaced or parsed.
        using (var malformed = new MemoryStream(new byte[] {
            (byte)'{', (byte)'"', 0xC3, 0x28, (byte)'"', (byte)'}', (byte)'\n'
        }))
        using (var strictReader = new StreamReader(
            malformed, new UTF8Encoding(false, true), detectEncodingFromByteOrderMarks: false))
        {
            var rejectedUtf8 = false;
            try {
                BridgeServer.ReadBoundedLineAsync(strictReader, CancellationToken.None)
                    .GetAwaiter().GetResult();
            }
            catch (DecoderFallbackException) { rejectedUtf8 = true; }
            Assert(rejectedUtf8, "Bridge must reject invalid UTF-8 before parsing credentials");
        }
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
        foreach (string malformedCanId in new[] { "70xE0", "0x0x7E0", "7E0-",
            "0x", "0000000007E0", "7 E0", "0x-7E0" })
        {
            using var input = JsonDocument.Parse(
                JsonSerializer.Serialize(new { requestCanId = malformedCanId }));
            var rejected = false;
            try { BridgeServer.ParseHexId(input.RootElement, "requestCanId"); }
            catch (ArgumentException) { rejected = true; }
            Assert(rejected, "CAN target ID is never repaired by stripping embedded markers");
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
        Assert(map.Region == "OTHER", "Old scoped read maps cannot claim a region type");
        Assert(J2534MemoryReadScope.IsKnownRegion("CALIBRATION") &&
               J2534MemoryReadScope.IsKnownRegion("PROGRAM_FLASH") &&
               J2534MemoryReadScope.IsKnownRegion("DATA_FLASH") &&
               J2534MemoryReadScope.IsKnownRegion("EXTERNAL_EEPROM") &&
               !J2534MemoryReadScope.IsKnownRegion("COMPLETE_ECU"),
               "Declared ECU memory regions never imply complete coverage");
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
            Assert(loaded.Region == "OTHER", "Legacy read profile has unknown region type");
            File.WriteAllText(scopedFile, scopedJson[..^1] + ",\"region\":\"CALIBRATION\"}");
            var typed = J2534MemoryReadScope.Load(scopedFile);
            Assert(typed.Region == "CALIBRATION", "Operator-declared calibration range is retained");
            File.WriteAllText(scopedFile, scopedJson[..^1] + ",\"region\":\"COMPLETE_ECU\"}");
            var invalidRegion = false;
            try { J2534MemoryReadScope.Load(scopedFile); }
            catch (ArgumentException) { invalidRegion = true; }
            Assert(invalidRegion, "Invented full-ECU region type cannot be selected");
            foreach (var badRegion in new[] { "null", "123", "true", "{}" })
            {
                File.WriteAllText(scopedFile,
                    scopedJson[..^1] + ",\"region\":" + badRegion + "}");
                var rejectedType = false;
                try { J2534MemoryReadScope.Load(scopedFile); }
                catch (ArgumentException) { rejectedType = true; }
                Assert(rejectedType, "Non-string region is rejected as a profile error");
            }
            Assert(loaded.AllowsReadMemory(new byte[] {
                0x23, 0x14, 0x80, 0x00, 0x10, 0x00, 0x20 }), "Parsed read profile usable");
            File.WriteAllText(scopedFile, scopedJson[..^1] + ",\"allowWrite\":true}");
            var rejected = false;
            try { J2534MemoryReadScope.Load(scopedFile); }
            catch (ArgumentException) { rejected = true; }
            Assert(rejected, "Unknown/dangerous profile permissions rejected");
            File.WriteAllText(scopedFile, new string(' ', 16 * 1024 + 1));
            rejected = false;
            try { J2534MemoryReadScope.Load(scopedFile); }
            catch (ArgumentException) { rejected = true; }
            Assert(rejected, "Oversized memory read profile is rejected before parsing");
            File.WriteAllText(scopedFile, "");
            rejected = false;
            try { J2534MemoryReadScope.Load(scopedFile); }
            catch (ArgumentException) { rejected = true; }
            Assert(rejected, "Empty memory read profile is rejected before parsing");
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
        checks += NativeUdsUploadSelfTests.Run();
        checks += StandardVehicleProtocolsSelfTests.Run();
        checks += ObdJ1979TransactionSelfTests.Run();
        checks += ObdJ1979PidSupportSelfTests.Run();
        checks += ObdJ1979VinSelfTests.Run();
        checks += ObdJ1979DtcSelfTests.Run();
        checks += ObdJ1979InterpretationSelfTests.Run();
        checks += ObdJ1979PidDiscoverySelfTests.Run();
        checks += ObdJ1979PidValuesSelfTests.Run();
        checks += ObdJ1979FunctionalCanDiscoverySelfTests.Run();
        checks += ObdJ1979VehicleInfoSelfTests.Run();
        checks += ObdJ1979MonitorStatusSelfTests.Run();
        checks += ObdJ1979Mode06SelfTests.Run();
        checks += ObdCanProtocolDetectionSelfTests.Run();
        checks += ObdOnUdsVehicleIdentificationSelfTests.Run();
        checks += ObdOnUdsDtcCountSelfTests.Run();
        checks += IsoTpCanFdSelfTests.Run();
        checks += IsoTpDeadlineSelfTests.Run();
        checks += J1939ClassicTpSelfTests.Run();
        checks += J1939AddressClaimSessionSelfTests.Run();
        checks += J1939FdMultiPgSelfTests.Run();
        checks += J1939FdTransportFramesSelfTests.Run();
        checks += J1939FdBamSelfTests.Run();
        checks += J1939FdCmdtSelfTests.Run();
        checks += UdsStandardServicesSelfTests.Run();
        checks += ObdOnUdsCanDiscoverySelfTests.Run();
        checks += EcuPostFlashAssessmentSelfTests.Run();
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
        checks += NativeE39QualifiedBenchSelfTests.Run();
        checks += NativeE39CalibrationRepairSelfTests.Run();
        checks += NativeGmlanBenchProbeSelfTests.Run();
        checks += NativeScopedMemoryReaderSelfTests.Run();
        checks += NativeScopedBackupArchiveSelfTests.Run();
        checks += BridgePairingDetailsSelfTests.Run();
        Console.WriteLine($"PASS: {checks} hardware-free J2534 bridge self-tests.");
        return 0;
    }
}
