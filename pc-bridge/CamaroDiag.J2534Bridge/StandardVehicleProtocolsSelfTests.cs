namespace CamaroDiag.J2534Bridge;

internal static class StandardVehicleProtocolsSelfTests
{
    internal static int Run()
    {
        var count = 0;
        void Check(bool condition, string description)
        {
            count++;
            if (!condition) throw new InvalidOperationException("Protocol test failed: " + description);
        }
        void Reject<T>(Action operation, string description) where T : Exception
        {
            var rejected = false;
            try { operation(); }
            catch (T) { rejected = true; }
            Check(rejected, description);
        }

        Check(StandardVehicleProtocols.Catalog.Count == 13 &&
            StandardVehicleProtocols.Catalog.All(p => !p.PhysicalWriteQualified),
            "No transport silently certifies physical flash/rollback.");
        Check(StandardVehicleProtocols.Find("K-Line KWP2000").J2534ProtocolId == 4 &&
            !StandardVehicleProtocols.Find("K-Line KWP2000").TransportPresent,
            "J2534 K-Line ID is recognized, initialization not invented.");
        Check(StandardVehicleProtocols.Find("J1850 VPW").J2534ProtocolId == 1 &&
            StandardVehicleProtocols.Find("J1850 PWM").J2534ProtocolId == 2,
            "SAE J1850 J2534 IDs.");
        Check(StandardVehicleProtocols.Find("CAN FD + ISO-TP").J2534ProtocolId is null,
            "CAN FD vendor capability must be negotiated.");
        for (int dlc = 0; dlc <= 15; dlc++)
            Check(StandardVehicleProtocols.CanFdLengthToDlc(
                StandardVehicleProtocols.CanFdDlcToLength(dlc)) == dlc,
                "CAN FD DLC round-trip.");
        Reject<ArgumentOutOfRangeException>(
            () => StandardVehicleProtocols.CanFdLengthToDlc(9), "Noncanonical FD length.");
        Reject<ArgumentOutOfRangeException>(
            () => StandardVehicleProtocols.CanFdDlcToLength(16), "Invalid FD DLC.");
        var j1939 = StandardVehicleProtocols.ParseJ1939Id(0x18DA10F1);
        Check(j1939.Priority == 6 && j1939.Pgn == 0xDA00 &&
            j1939.Destination == 0x10 && j1939.Source == 0xF1,
            "J1939 PDU1 target and PGN.");
        var j1939Broadcast = StandardVehicleProtocols.ParseJ1939Id(0x18F00401);
        Check(j1939Broadcast.Pgn == 0xF004 && j1939Broadcast.Destination is null,
            "J1939 PDU2 PGN includes group extension.");
        Reject<ArgumentOutOfRangeException>(
            () => StandardVehicleProtocols.ParseJ1939Id(0x80000000),
            "J1939 29-bit limit.");

        foreach (int size in new[] { 1, 63, 64, 255 })
        {
            var kwp = StandardVehicleProtocols.BuildKwpPhysicalFrame(
                0x33, 0xF1, Enumerable.Repeat((byte)0x22, size).ToArray());
            Check(kwp.Length == (size <= 63 ? 3 : 4) + size + 1 &&
                kwp[^1] == (byte)(kwp[..^1].Sum(x => (int)x) & 0xFF),
                "KWP2000 long/short physical frame and checksum.");
            Check(StandardVehicleProtocols.ParseKwpPhysicalFrame(
                kwp, 0x33, 0xF1).SequenceEqual(
                    Enumerable.Repeat((byte)0x22, size)),
                "KWP physical frame round-trip 1/63/64/255 bytes.");
            var wrongChecksum = kwp.ToArray();
            wrongChecksum[^1] ^= 0x80;
            Reject<InvalidDataException>(() =>
                StandardVehicleProtocols.ParseKwpPhysicalFrame(
                    wrongChecksum, 0x33, 0xF1),
                "KWP checksum mismatch must reject frame.");
            Reject<InvalidDataException>(() =>
                StandardVehicleProtocols.ParseKwpPhysicalFrame(
                    kwp, 0x34, 0xF1),
                "KWP wrong diagnostic target rejected.");
        }
        var kwpLength = StandardVehicleProtocols.BuildKwpPhysicalFrame(
            0x33, 0xF1, [0x22, 0xF1, 0x90]);
        Reject<InvalidDataException>(() =>
            StandardVehicleProtocols.ParseKwpPhysicalFrame(
                kwpLength[..^1], 0x33, 0xF1),
            "Truncated KWP physical frame rejected.");

        Reject<ArgumentException>(() =>
            StandardVehicleProtocols.BuildKwpPhysicalFrame(0x33, 0x33, [0x22]),
            "KWP refuses equal source and target.");

        var identificationRequest = DoIpCodec.Decode(
            DoIpVehicleDiscovery.BuildRequest());
        Check(identificationRequest.Type == DoIpCodec.VehicleIdentificationRequest &&
            identificationRequest.Payload.Length == 0,
            "DoIP UDP identification request has 0x0001 and empty payload.");
        var vinRequest = DoIpCodec.Decode(
            DoIpVehicleDiscovery.BuildVinRequest("12345678901234567"));
        Check(vinRequest.Type == DoIpCodec.VehicleIdentificationRequestVin &&
            System.Text.Encoding.ASCII.GetString(vinRequest.Payload) ==
                "12345678901234567",
            "DoIP type 0x0003 VIN-specific UDP request.");
        var eidRequest = DoIpCodec.Decode(
            DoIpVehicleDiscovery.BuildEidRequest([1, 2, 3, 4, 5, 6]));
        Check(eidRequest.Type == DoIpCodec.VehicleIdentificationRequestEid &&
            eidRequest.Payload.SequenceEqual(new byte[] {1, 2, 3, 4, 5, 6}),
            "DoIP type 0x0002 EID-specific UDP request.");
        Reject<ArgumentException>(() =>
            DoIpVehicleDiscovery.BuildVinRequest("short"),
            "Invalid VIN length is rejected before transmitting.");
        Reject<ArgumentException>(() =>
            DoIpVehicleDiscovery.BuildEidRequest([1, 2, 3]),
            "Invalid EID length is rejected before transmitting.");
        var identityBytes = new byte[33];
        System.Text.Encoding.ASCII.GetBytes("12345678901234567").CopyTo(identityBytes, 0);
        identityBytes[17] = 0x12;
        identityBytes[18] = 0x34;
        for (int i = 0; i < 6; i++)
        {
            identityBytes[19 + i] = (byte)(i + 1);
            identityBytes[25 + i] = (byte)(i + 11);
        }
        identityBytes[31] = 0x10;
        identityBytes[32] = 0x00;
        var discoveryAddress = System.Net.IPAddress.Parse("192.0.2.10");
        var vehicleIdentity = DoIpVehicleDiscovery.ParseAnnouncement(
            DoIpCodec.Encode(DoIpCodec.VehicleIdentificationResponse,
                identityBytes), discoveryAddress);
        Check(vehicleIdentity.Vin == "12345678901234567" &&
            vehicleIdentity.LogicalAddress == 0x1234 &&
            vehicleIdentity.Eid.SequenceEqual(new byte[] {1, 2, 3, 4, 5, 6}) &&
            vehicleIdentity.Gid.SequenceEqual(new byte[] {11, 12, 13, 14, 15, 16}) &&
            vehicleIdentity.FurtherAction == 0x10 &&
            vehicleIdentity.VinGidStatus == 0,
            "DoIP UDP response parses VIN, logical address, EID, GID and status.");
        Check(DoIpVehicleDiscovery.MatchesRequestedIdentity(
            vehicleIdentity, "12345678901234567", [1, 2, 3, 4, 5, 6]),
            "DoIP VIN/EID-targeted identification accepts exact matching response.");
        Check(!DoIpVehicleDiscovery.MatchesRequestedIdentity(
            vehicleIdentity, "12345678901234568", null),
            "DoIP VIN mismatch cannot satisfy a targeted discovery.");
        Check(!DoIpVehicleDiscovery.MatchesRequestedIdentity(
            vehicleIdentity, null, [1, 2, 3, 4, 5, 7]),
            "DoIP EID mismatch cannot satisfy a targeted discovery.");

        var withoutStatus = DoIpVehicleDiscovery.ParseAnnouncement(
            DoIpCodec.Encode(DoIpCodec.VehicleIdentificationResponse,
                identityBytes[..32]), discoveryAddress);
        Check(withoutStatus.VinGidStatus is null,
            "Older DoIP announcement supports optional VIN/GID status byte.");
        var unknownVin = identityBytes.ToArray();
        Array.Clear(unknownVin, 0, 17);
        var announcedWithoutVin = DoIpVehicleDiscovery.ParseAnnouncement(
            DoIpCodec.Encode(DoIpCodec.VehicleIdentificationResponse,
                unknownVin), discoveryAddress);
        Check(announcedWithoutVin.Vin is null &&
            announcedWithoutVin.LogicalAddress == 0x1234,
            "DoIP unavailable VIN does not fabricate a vehicle identifier.");
        Array.Fill(unknownVin, (byte)0xFF, 0, 17);
        Check(DoIpVehicleDiscovery.ParseAnnouncement(
            DoIpCodec.Encode(DoIpCodec.VehicleIdentificationResponse,
                unknownVin), discoveryAddress).Vin is null,
            "DoIP all-FF VIN invalidity pattern remains unavailable.");

        Reject<InvalidDataException>(() => DoIpVehicleDiscovery.ParseAnnouncement(
            DoIpCodec.Encode(DoIpCodec.VehicleIdentificationResponse,
                identityBytes[..31]), discoveryAddress),
            "Truncated DoIP UDP vehicle identification is rejected.");
        Reject<InvalidDataException>(() => DoIpVehicleDiscovery.ParseAnnouncement(
            DoIpCodec.Encode(DoIpCodec.DiagnosticMessage, identityBytes),
            discoveryAddress), "Diagnostic DoIP packet cannot masquerade as discovery.");

        var statusRequest = DoIpCodec.Decode(DoIpCodec.Encode(
            DoIpCodec.EntityStatusRequest, []));
        Check(statusRequest.Type == 0x4001 &&
            statusRequest.Payload.Length == 0,
            "DoIP UDP entity status request has no payload.");
        var entityResponse = DoIpCodec.Decode(DoIpCodec.Encode(
            DoIpCodec.EntityStatusResponse, [0x00, 0x04, 0x02, 0, 0, 0x10, 0x00]));
        var entity = DoIpCodec.ParseEntityStatus(entityResponse);
        Check(entity.NodeType == 0 && entity.MaxSockets == 4 &&
            entity.CurrentSockets == 2 && entity.MaxDataSize == 4096,
            "DoIP entity status exposes gateway type and socket/data limits.");
        Check(DoIpCodec.ParseEntityStatus(DoIpCodec.Decode(DoIpCodec.Encode(
            DoIpCodec.EntityStatusResponse, [1, 2, 1]))).MaxDataSize is null,
            "DoIP entity status supports optional max-data-size field.");
        Reject<InvalidDataException>(() => DoIpCodec.ParseEntityStatus(
            DoIpCodec.Decode(DoIpCodec.Encode(
                DoIpCodec.EntityStatusResponse, [0, 4, 2, 1]))),
            "DoIP malformed entity status size refused.");
        Check(DoIpCodec.ParseDiagnosticPowerMode(DoIpCodec.Decode(
            DoIpCodec.Encode(DoIpCodec.PowerModeResponse, [1]))) == 1,
            "DoIP diagnostic power-mode ready state decoded.");
        Reject<InvalidDataException>(() => DoIpCodec.ParseDiagnosticPowerMode(
            DoIpCodec.Decode(DoIpCodec.Encode(
                DoIpCodec.PowerModeResponse, [3]))),
            "DoIP unknown diagnostic power-mode code is not reported ready.");

        var aliveRequest = DoIpCodec.Decode(DoIpCodec.Encode(
            DoIpCodec.AliveCheckRequest, [], version: 0x03));
        var aliveReply = DoIpCodec.Decode(
            DoIpCodec.ReplyToAliveCheck(aliveRequest, 0x0E80));
        Check(aliveReply.Type == DoIpCodec.AliveCheckResponse &&
            aliveReply.Version == 0x03 &&
            aliveReply.Payload.SequenceEqual(new byte[] {0x0E, 0x80}),
            "DoIP alive-check response carries tester address and peer version.");
        Reject<InvalidDataException>(() => DoIpCodec.ReplyToAliveCheck(
            DoIpCodec.Decode(DoIpCodec.Encode(
                DoIpCodec.AliveCheckRequest, [0x01])), 0x0E80),
            "DoIP alive-check request with payload rejected.");
        Reject<InvalidDataException>(() => DoIpCodec.ReplyToAliveCheck(
            DoIpCodec.Decode(DoIpCodec.RoutingActivation(0x0E80)), 0x0E80),
            "Routing activation cannot masquerade as DoIP alive-check.");

        var route = DoIpCodec.Decode(DoIpCodec.RoutingActivation(0x0E80));
        Check(route.Type == DoIpCodec.RoutingActivationRequest &&
              route.Payload.Length == 7, "ISO 13400 route request.");
        var routeResponse = DoIpCodec.Decode(DoIpCodec.Encode(
            DoIpCodec.RoutingActivationResponse, [0x0E, 0x80, 0x12, 0x34, 0x10, 0, 0, 0, 0]));
        DoIpCodec.RequireRoutingActive(routeResponse, 0x0E80);
        Reject<InvalidDataException>(() =>
            DoIpCodec.RequireRoutingActive(routeResponse, 0x0E81),
            "DoIP routing response to another tester.");
        Reject<InvalidDataException>(() => DoIpCodec.RequireRoutingActive(
            DoIpCodec.Decode(DoIpCodec.Encode(
                DoIpCodec.RoutingActivationResponse, [0x0E, 0x80, 0x12, 0x34, 0x11, 0, 0, 0, 0])),
            0x0E80), "DoIP refused routing activation.");
        var diag = DoIpCodec.Decode(DoIpCodec.Diagnostic(
            0x0E80, 0x1234, [0x22, 0xF1, 0x90]));
        Check(diag.Type == 0x8001 && diag.Payload.Length == 7 &&
            diag.Payload[4] == 0x22, "DoIP diagnostic target and UDS payload.");
        var reply = DoIpCodec.Decode(DoIpCodec.Encode(0x8001,
            [0x12, 0x34, 0x0E, 0x80, 0x62, 0xF1, 0x90, 0x41]));
        Check(DoIpCodec.RequireDiagnosticResponse(reply, 0x0E80, 0x1234)
            .SequenceEqual(new byte[] {0x62, 0xF1, 0x90, 0x41}),
            "DoIP ECU source / tester target correlation.");
        Reject<InvalidDataException>(() =>
            DoIpCodec.RequireDiagnosticResponse(reply, 0x0E81, 0x1234),
            "DoIP wrong tester address.");
        var positiveAck = DoIpCodec.Decode(DoIpCodec.Encode(
            DoIpCodec.DiagnosticPositiveAck,
            [0x12, 0x34, 0x0E, 0x80, 0x00]));
        Check(DoIpCodec.IsCorrelatedDiagnosticAck(
            positiveAck, 0x0E80, 0x1234),
            "DoIP positive ACK code zero is accepted for the active ECU.");
        var corruptAck = DoIpCodec.Decode(DoIpCodec.Encode(
            DoIpCodec.DiagnosticPositiveAck,
            [0x12, 0x34, 0x0E, 0x80, 0x7F]));
        Reject<InvalidDataException>(() => DoIpCodec.IsCorrelatedDiagnosticAck(
            corruptAck, 0x0E80, 0x1234),
            "Unexpected non-zero DoIP positive ACK code must not pass.");
        Reject<InvalidDataException>(() => DoIpCodec.RequireRoutingActive(
            DoIpCodec.Decode(DoIpCodec.Encode(
                DoIpCodec.RoutingActivationResponse,
                [0x0E, 0x80, 0x12, 0x34, 0x10, 0, 0, 0, 0, 1])),
            0x0E80), "DoIP routing activation forbids invalid payload length.");
        var nack = DoIpCodec.Decode(DoIpCodec.Encode(0x8003,
            [0x12, 0x34, 0x0E, 0x80, 0x02]));
        Reject<IOException>(() => DoIpCodec.IsCorrelatedDiagnosticAck(
            nack, 0x0E80, 0x1234), "DoIP diagnostic gateway negative acknowledgment.");
        var badInverse = DoIpCodec.Diagnostic(0x0E80, 0x1234, [0x22, 0xF1, 0x90]);
        badInverse[1] ^= 0x02;
        Reject<InvalidDataException>(() => DoIpCodec.Decode(badInverse),
            "DoIP bad protocol/inverse version.");
        var truncated = DoIpCodec.Diagnostic(0x0E80, 0x1234, [0x22, 0xF1, 0x90]);
        Reject<InvalidDataException>(() => DoIpCodec.Decode(truncated[..^1]),
            "DoIP frame truncated.");
        var oversizedHeader = DoIpCodec.Diagnostic(0x0E80, 0x1234, [0x22, 0xF1, 0x90]);
        oversizedHeader[4] = 0xFF;
        Reject<InvalidDataException>(() => DoIpCodec.Decode(oversizedHeader),
            "DoIP oversized declared length.");

        // The same DoIP transport carries read, write, erase, and transfer
        // services. No invented SID blacklist at the network layer.
        foreach (byte[] request in new byte[][] {
            [0x22, 0xF1, 0x90], [0x2E, 0xF1, 0x90, 0x42],
            [0x31, 0x01, 0xFF, 0x00], [0x34, 0, 0x44],
            [0x36, 1, 0xAA], [0x37], [0x11, 1]
        })
        {
            Check(DoIpCodec.Decode(DoIpCodec.Diagnostic(
                0x0E80, 0x1234, request)).Payload.AsSpan(4).SequenceEqual(request),
                "DoIP protocol preserves arbitrary UDS read AND write SID.");
        }
        Check(J2534StandardChannel.IsKnownProtocol(J2534StandardChannel.Iso9141) &&
              J2534StandardChannel.IsKnownProtocol(J2534StandardChannel.Iso14230) &&
              J2534StandardChannel.IsKnownProtocol(J2534StandardChannel.J1850Vpw) &&
              J2534StandardChannel.IsKnownProtocol(J2534StandardChannel.J1850Pwm) &&
              J2534StandardChannel.IsKnownProtocol(J2534StandardChannel.FdIso15765) &&
              !J2534StandardChannel.IsKnownProtocol(0xFFFF),
            "SAE J2534 standard and FD extended protocols recognized.");
        var parsed = StandardProtocolCli.ParseDoIp([
            StandardProtocolCli.DoIpCommand, "--host", "127.0.0.1",
            "--tester", "0E80", "--ecu", "1234",
            "--payload", "2EF19001"]);
        Check(parsed.Request.SequenceEqual(new byte[] { 0x2E, 0xF1, 0x90, 1 }),
            "DoIP bidirectional CLI accepts WDBI service.");
        var protocolDoIp = StandardProtocolCli.ParseDoIp([
            StandardProtocolCli.DoIpCommand, "--host", "127.0.0.1",
            "--tester", "0E80", "--ecu", "1234",
            "--obd-identify"]);
        Check(protocolDoIp.Request.SequenceEqual(
            new byte[] {0x22, 0xF8, 0x10}),
            "DoIP OBDonUDS discovery selects F810 without arbitrary payload.");
        Reject<ArgumentException>(() => StandardProtocolCli.ParseDoIp([
            StandardProtocolCli.DoIpCommand, "--host", "127.0.0.1",
            "--tester", "0E80", "--ecu", "1234",
            "--obd-identify", "--payload", "22F810"]),
            "Protocol identification cannot be combined with raw DoIP payload.");

        var obdDoIp = StandardProtocolCli.ParseDoIp([
            StandardProtocolCli.DoIpCommand, "--host", "127.0.0.1",
            "--tester", "0E80", "--ecu", "1234",
            "--obd-did", "0xF40C"]);
        Check(obdDoIp.Request.SequenceEqual(new byte[] {0x22, 0xF4, 0x0C}),
            "DoIP OBDonUDS DID selector constructs the standardized UDS request.");
        var obdDtcDoIp = StandardProtocolCli.ParseDoIp([
            StandardProtocolCli.DoIpCommand, "--host", "127.0.0.1",
            "--tester", "0E80", "--ecu", "1234",
            "--obd-dtc-mask", "0xFF"]);
        Check(obdDtcDoIp.Request.SequenceEqual(new byte[] {0x19, 0x02, 0xFF}),
            "DoIP OBDonUDS DTC mask selector shares the UDS request service.");
        Reject<ArgumentException>(() => StandardProtocolCli.ParseDoIp([
            StandardProtocolCli.DoIpCommand, "--host", "127.0.0.1",
            "--tester", "0E80", "--ecu", "1234",
            "--payload", "22F40C", "--obd-did", "F40C"]),
            "DoIP mutually exclusive raw and named UDS requests enforced.");

        var secureDoIp = StandardProtocolCli.ParseDoIp([
            StandardProtocolCli.DoIpCommand, "--host", "gateway.example.org",
            "--tester", "0E80", "--ecu", "1234", "--tls", "true",
            "--payload", "22F190"]);
        Check(secureDoIp.UseTls && secureDoIp.Host == "gateway.example.org" &&
            secureDoIp.Request.SequenceEqual(new byte[] {0x22, 0xF1, 0x90}),
            "Secure DoIP CLI selects certificate-validated TLS transport.");
        Check(!parsed.UseTls, "Legacy DoIP TCP mode remains explicit and unchanged.");
        Reject<FormatException>(() => StandardProtocolCli.ParseDoIp([
            StandardProtocolCli.DoIpCommand, "--host", "gateway.example.org",
            "--tester", "0E80", "--ecu", "1234", "--tls", "maybe",
            "--payload", "22F190"]),
            "Invalid DoIP TLS option fails parsing before gateway access.");

        var j2534Options = StandardProtocolCli.ParseJ2534([
            StandardProtocolCli.PassThruCommand, "--adapter-index", "0",
            "--protocol-id", "0x04", "--baud", "10400",
            "--data", "83F11022019077", "--kline-init", "five:33"]);
        Check(j2534Options.ProtocolId == J2534StandardChannel.Iso14230 &&
            j2534Options.KlineInit == "five:33",
            "J2534 K-Line ISO14230 bidirectional channel can initialize via vendor driver.");
        var kwpWireCli = StandardProtocolCli.ParseJ2534([
            StandardProtocolCli.PassThruCommand, "--adapter-index", "0",
            "--protocol-id", "0x04", "--baud", "10400",
            "--data", "83F11022019077",
            "--kwp-rx-target", "0xF1", "--kwp-rx-source", "0x33"]);
        Check(kwpWireCli.KwpRxTarget == 0xF1 &&
            kwpWireCli.KwpRxSource == 0x33,
            "KWP opt-in physical response framing selects expected addresses.");
        var passiveKline = StandardProtocolCli.ParseJ2534([
            StandardProtocolCli.PassThruCommand, "--adapter-index", "0",
            "--protocol-id", "0x04", "--baud", "10400",
            "--mode", "receive", "--read-frames", "3"]);
        Check(passiveKline.Mode == "receive" && passiveKline.Data.Length == 0 &&
            passiveKline.ReadFrames == 3,
            "J2534 K-Line receive mode never builds an outgoing diagnostic payload.");
        Reject<ArgumentException>(() => StandardProtocolCli.ParseJ2534([
            StandardProtocolCli.PassThruCommand, "--adapter-index", "0",
            "--protocol-id", "0x04", "--baud", "10400",
            "--mode", "receive", "--read-frames", "1",
            "--kline-init", "five:33"]),
            "Passive J2534 receive mode cannot initiate K-Line signalling.");

        var passiveVpw = StandardProtocolCli.ParseJ2534([
            StandardProtocolCli.PassThruCommand, "--adapter-index", "0",
            "--protocol-id", "0x01", "--baud", "10400",
            "--mode", "receive"]);
        Check(passiveVpw.Mode == "receive" && passiveVpw.Data.Length == 0,
            "J2534 J1850 receive-only mode uses vendor RX channel.");
        Reject<ArgumentException>(() => StandardProtocolCli.ParseJ2534([
            StandardProtocolCli.PassThruCommand, "--adapter-index", "0",
            "--protocol-id", "0x04", "--baud", "10400",
            "--mode", "receive", "--data", "22F190"]),
            "J2534 passive receive refuses hidden TX payload.");
        Reject<ArgumentException>(() => StandardProtocolCli.ParseJ2534([
            StandardProtocolCli.PassThruCommand, "--adapter-index", "0",
            "--protocol-id", "0x04", "--baud", "10400",
            "--data", "83F11022019077", "--kwp-rx-target", "0xF1"]),
            "KWP physical verification needs both RX source and target.");
        Reject<ArgumentException>(() => StandardProtocolCli.ParseJ2534([
            StandardProtocolCli.PassThruCommand, "--adapter-index", "0",
            "--protocol-id", "0xFFFF", "--baud", "10400", "--data", "22F190"]),
            "Unknown SAE J2534 ProtocolId fails before DLL load.");
        Reject<ArgumentException>(() => StandardProtocolCli.ParseDoIp([
            StandardProtocolCli.DoIpCommand, "--host", "127.0.0.1",
            "--tester", "0E80", "--ecu", "0E80", "--payload", "22F190"]),
            "DoIP equal logical tester/target rejected.");

        return count;
    }
}
