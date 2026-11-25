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

        Check(J1850Crc.Compute(
                System.Text.Encoding.ASCII.GetBytes("123456789")) == 0x4B,
            "SAE J1850 CRC-8 polynomial/reference check is 0x4B.");
        var j1850Raw = J1850Crc.AppendForRawFraming(
            new byte[] {0x68, 0x6A, 0xF1, 0x01, 0x0C});
        Check(j1850Raw.Length == 6 &&
            J1850Crc.VerifyRawFrame(j1850Raw),
            "Raw J1850 frame gains exactly one CRC and round-trips.");
        var corruptedJ1850 = j1850Raw.ToArray();
        corruptedJ1850[2] ^= 1;
        Check(!J1850Crc.VerifyRawFrame(corruptedJ1850) &&
            !J1850Crc.VerifyRawFrame(new byte[] {0x68}),
            "J1850 CRC corruption or missing CRC cannot be silently accepted.");
        Reject<ArgumentOutOfRangeException>(() =>
            J1850Crc.AppendForRawFraming(new byte[] {0x68}),
            "Raw J1850 CRC helper requires a complete header and data.");

        var j1850WireCheck = StandardProtocolCli.ParseJ2534([
            StandardProtocolCli.PassThruCommand, "--adapter-index", "0",
            "--protocol-id", "1", "--baud", "10400",
            "--mode", "receive", "--j1850-wire-crc", "true"]);
        Check(StandardProtocolCli.ShouldValidateJ1850WireCrc(true, 0) &&
            !StandardProtocolCli.ShouldValidateJ1850WireCrc(
                true, J2534StandardChannel.RxTxEcho) &&
            !StandardProtocolCli.ShouldValidateJ1850WireCrc(
                true, J2534StandardChannel.RxStartOfMessage) &&
            !StandardProtocolCli.ShouldValidateJ1850WireCrc(false, 0),
            "Only raw J1850 data frames with preserved CRC require CRC checking.");
        Check(j1850WireCheck.J1850WireCrc &&
            j1850WireCheck.ProtocolId == J2534StandardChannel.J1850Vpw &&
            j1850WireCheck.Data.Length == 0,
            "Explicit J1850 raw wire CRC checking is available in receive-only mode.");
        Reject<ArgumentException>(() => StandardProtocolCli.ParseJ2534([
            StandardProtocolCli.PassThruCommand, "--adapter-index", "0",
            "--protocol-id", "6", "--baud", "500000",
            "--mode", "receive", "--j1850-wire-crc", "true"]),
            "J1850 wire CRC cannot be interpreted on ISO-TP/CAN frames.");

        Check(StandardVehicleProtocols.Catalog.Count == 14 &&
            StandardVehicleProtocols.Catalog.All(p => !p.PhysicalWriteQualified) &&
            StandardVehicleProtocols.Find("J1939 CAN FD").TransportPresent,
            "Fourteen standard protocol entries are tracked; no transport silently certifies physical flash/rollback.");
        Check(StandardVehicleProtocols.Find("K-Line KWP2000").J2534ProtocolId == 4 &&
            !StandardVehicleProtocols.Find("K-Line KWP2000").TransportPresent,
            "J2534 K-Line ID is recognized, initialization not invented.");
        Check(J2534StandardChannel.SwIso15765 == 0x8007 &&
            J2534StandardChannel.SwCan == 0x8008 &&
            J2534StandardChannel.IsKnownProtocol(0x8007) &&
            J2534StandardChannel.IsKnownProtocol(0x8008),
            "SAE J2534-2 distinguishes SW_ISO15765 and SW_CAN channels.");
        Check(StandardVehicleProtocols.Find("SW ISO-TP").J2534ProtocolId == 0x8007 &&
            StandardVehicleProtocols.Find("GM single-wire CAN").J2534ProtocolId ==
                0x8008 &&
            !StandardVehicleProtocols.Find("GM single-wire CAN").PhysicalWriteQualified,
            "GM single-wire CAN must map to SW_CAN 0x8008 without physical claim.");

        Check(StandardVehicleProtocols.Find("J1850 VPW").J2534ProtocolId == 1 &&
            StandardVehicleProtocols.Find("J1850 PWM").J2534ProtocolId == 2,
            "SAE J1850 J2534 IDs.");
        Check(StandardVehicleProtocols.Find("CAN FD + ISO-TP").J2534ProtocolId is null,
            "CAN FD vendor capability must be negotiated.");
        Check(!J2534StandardChannel.IsAuxiliaryReceiveStatus(0) &&
            J2534StandardChannel.IsAuxiliaryReceiveStatus(
                J2534StandardChannel.RxTxEcho) &&
            J2534StandardChannel.IsAuxiliaryReceiveStatus(
                J2534StandardChannel.RxStartOfMessage) &&
            J2534StandardChannel.IsAuxiliaryReceiveStatus(
                J2534StandardChannel.RxTxIndication) &&
            J2534StandardChannel.IsAuxiliaryReceiveStatus(
                J2534StandardChannel.RxPaddingError),
            "J2534 RX classifier separates physical receive from vendor status reports.");
        Check(!J2534StandardChannel.IsAuxiliaryReceiveStatus(0x80000000),
            "J2534 unknown status bits alone do not falsely hide ECU RX data.");

        var j1939FdCapability = StandardVehicleProtocols.Find("J1939 CAN FD");
        Check(j1939FdCapability.TransportPresent &&
            !j1939FdCapability.PhysicalWriteQualified &&
            j1939FdCapability.Scope.Contains("FBFF 11-bit", StringComparison.Ordinal) &&
            j1939FdCapability.Scope.Contains("TOS2/TF0 only", StringComparison.Ordinal) &&
            j1939FdCapability.Scope.Contains("concurrent sessions unqualified",
                StringComparison.Ordinal),
            "J1939-22 software transport capability is described precisely without physical flash claim.");
        Check(StandardVehicleProtocols.Find("J1939 classical CAN").TransportPresent &&
            !StandardVehicleProtocols.Find("J1939 classical CAN").PhysicalWriteQualified,
            "J1939-21 bidirectional codec presence does not imply ECU write qualification.");
        Check(StandardVehicleProtocols.Find("OBDonUDS").TransportPresent &&
            StandardVehicleProtocols.Find("OBDonUDS").Scope
                .Contains("full service mapping pending", StringComparison.Ordinal),
            "OBDonUDS partial coverage is not represented as complete standard compliance.");
        Check(StandardVehicleProtocols.Find("CAN FD + ISO-TP").TransportPresent &&
            !StandardVehicleProtocols.Find("CAN FD + ISO-TP").PhysicalWriteQualified,
            "ISO-TP CAN FD software support is separate from actual hardware qualification.");

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
        foreach (int size in new[] { 1, 63, 64, 255 })
        {
            var value = Enumerable.Range(0, size)
                .Select(i => (byte)i).ToArray();
            var function = StandardVehicleProtocols.BuildKwpFunctionalFrame(
                0x33, 0xF1, value);
            Check((function[0] & 0xC0) == 0xC0 &&
                function.Length == size + (size <= 63 ? 4 : 5),
                "ISO 14230 functional KWP uses format 0xC0 and valid short/extended length.");
            Check(StandardVehicleProtocols.ParseKwpFunctionalFrame(
                    function, 0x33, 0xF1).SequenceEqual(value),
                "Functional KWP round trips payload with additive checksum.");
            Reject<InvalidDataException>(() =>
                StandardVehicleProtocols.ParseKwpPhysicalFrame(
                    function, 0x33, 0xF1),
                "Physical KWP parser must not silently accept functional addressing.");
            var corrupted = function.ToArray();
            corrupted[^1] ^= 0x01;
            Reject<InvalidDataException>(() =>
                StandardVehicleProtocols.ParseKwpFunctionalFrame(
                    corrupted, 0x33, 0xF1),
                "Functional KWP rejects corrupted checksum.");
            Reject<InvalidDataException>(() =>
                StandardVehicleProtocols.ParseKwpFunctionalFrame(
                    function, 0x33, 0xF2),
                "Functional KWP rejects mismatched source logical address.");
        }
        var kwpFunctional = StandardVehicleProtocols.BuildKwpFunctionalFrame(
            0x33, 0xF1, [0x81]);
        Check(kwpFunctional[0] == 0xC1 &&
            kwpFunctional[1] == 0x33 &&
            kwpFunctional[2] == 0xF1 &&
            kwpFunctional[3] == 0x81,
            "ISO 14230 functional StartCommunication uses standard format header.");
        Reject<ArgumentException>(() =>
            StandardVehicleProtocols.BuildKwpFunctionalFrame(
                0x33, 0x33, [0x81]),
            "Functional KWP requires distinct source and target.");
        Reject<InvalidDataException>(() =>
            StandardVehicleProtocols.ParseKwpFunctionalFrame(
                kwpFunctional[..^1], 0x33, 0xF1),
            "Functional KWP refuses a truncated checksum.");
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
            identificationRequest.Version == 0xFF &&
            identificationRequest.Payload.Length == 0,
            "DoIP UDP discovery uses 0xFF default version until ECU version is known.");
        var vinRequest = DoIpCodec.Decode(
            DoIpVehicleDiscovery.BuildVinRequest("12345678901234567"));
        Check(vinRequest.Type == DoIpCodec.VehicleIdentificationRequestVin &&
            vinRequest.Version == 0xFF &&
            System.Text.Encoding.ASCII.GetString(vinRequest.Payload) ==
                "12345678901234567",
            "DoIP type 0x0003 VIN-specific UDP request.");
        var eidRequest = DoIpCodec.Decode(
            DoIpVehicleDiscovery.BuildEidRequest([1, 2, 3, 4, 5, 6]));
        Check(eidRequest.Type == DoIpCodec.VehicleIdentificationRequestEid &&
            eidRequest.Version == 0xFF &&
            eidRequest.Payload.SequenceEqual(new byte[] {1, 2, 3, 4, 5, 6}),
            "DoIP type 0x0002 EID-specific UDP request.");
        Reject<ArgumentOutOfRangeException>(() =>
            DoIpCodec.Encode(DoIpCodec.DiagnosticMessage, [0x22, 0xF1, 0x90],
                version: 0xFF),
            "Default 0xFF DoIP version cannot be used for a diagnostic TCP PDU.");
        Reject<ArgumentOutOfRangeException>(() =>
            DoIpCodec.Encode(DoIpCodec.GenericNegativeAck, [0], version: 0xFF),
            "Default 0xFF DoIP version cannot be used for generic NACK.");
        var invalidDefaultVersion = DoIpCodec.Encode(DoIpCodec.DiagnosticMessage,
            [0x00, 0x00, 0x00, 0x00, 0x22]);
        invalidDefaultVersion[0] = 0xFF;
        invalidDefaultVersion[1] = 0x00;
        Reject<InvalidDataException>(() => DoIpCodec.Decode(invalidDefaultVersion),
            "Incoming diagnostic TCP PDU with discovery-only version is rejected.");
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
            vehicleIdentity.VinGidStatus == 0 &&
            vehicleIdentity.ProtocolVersion == 0x02,
            "DoIP UDP response parses VIN, logical address, EID, GID, version and status.");
        foreach (var gatewayVersion in new byte[] { 0x03, 0x04 })
        {
            var versionedIdentity = DoIpVehicleDiscovery.ParseAnnouncement(
                DoIpCodec.Encode(DoIpCodec.VehicleIdentificationResponse,
                    identityBytes, gatewayVersion), discoveryAddress);
            Check(versionedIdentity.ProtocolVersion == gatewayVersion &&
                versionedIdentity.Vin == vehicleIdentity.Vin &&
                versionedIdentity.LogicalAddress == vehicleIdentity.LogicalAddress,
                "DoIP gateway discovery preserves header protocol versions 0x" +
                gatewayVersion.ToString("X2") + " for explicit operator negotiation.");
        }
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

        var invalidVin = identityBytes.ToArray();
        invalidVin[8] = 0x80;
        Reject<InvalidDataException>(() => DoIpVehicleDiscovery.ParseAnnouncement(
            DoIpCodec.Encode(DoIpCodec.VehicleIdentificationResponse,
                invalidVin), discoveryAddress),
            "Mixed non-ASCII VIN bytes must not decode to printable replacement characters.");

        Reject<InvalidDataException>(() => DoIpVehicleDiscovery.ParseAnnouncement(
            DoIpCodec.Encode(DoIpCodec.VehicleIdentificationResponse,
                identityBytes[..31]), discoveryAddress),
            "Truncated DoIP UDP vehicle identification is rejected.");
        Reject<InvalidDataException>(() => DoIpVehicleDiscovery.ParseAnnouncement(
            DoIpCodec.Encode(DoIpCodec.DiagnosticMessage, identityBytes),
            discoveryAddress), "Diagnostic DoIP packet cannot masquerade as discovery.");

        var nackReason = DoIpCodec.Decode(DoIpCodec.Encode(
            DoIpCodec.GenericNegativeAck, [0x02]));
        Reject<IOException>(() =>
            DoIpCodec.RequireNoGenericNack(nackReason),
            "DoIP generic header NACK code 2 rejects oversized transport payload.");
        Reject<InvalidDataException>(() =>
            DoIpCodec.RequireNoGenericNack(DoIpCodec.Decode(
                DoIpCodec.Encode(DoIpCodec.GenericNegativeAck, [0x00, 0x02]))),
            "Malformed generic DoIP NACK must never be accepted.");
        DoIpCodec.RequireNoGenericNack(DoIpCodec.Decode(
            DoIpCodec.Encode(DoIpCodec.EntityStatusResponse, [0, 1, 1])));
        Check(true, "DoIP generic NACK classifier leaves ordinary messages untouched.");

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
        var oemRoutingResponse = DoIpCodec.Decode(DoIpCodec.Encode(
            DoIpCodec.RoutingActivationResponse,
            [0x0E, 0x80, 0x12, 0x34, 0x10, 0, 0, 0, 0, 1, 2, 3, 4],
            version: 0x04));
        DoIpCodec.RequireRoutingActive(oemRoutingResponse, 0x0E80);
        Check(oemRoutingResponse.Version == 0x04 &&
            oemRoutingResponse.Payload.Length == 13 &&
            oemRoutingResponse.Payload.AsSpan(9).SequenceEqual(
                new byte[] { 1, 2, 3, 4 }),
            "DoIP 13-byte OEM routing response is accepted without inventing credentials.");
        Reject<InvalidDataException>(() =>
            DoIpCodec.RequireRoutingActive(routeResponse, 0x0E81),
            "DoIP routing response to another tester.");
        var pendingRoute = DoIpCodec.Decode(DoIpCodec.Encode(
            DoIpCodec.RoutingActivationResponse,
            [0x0E, 0x80, 0x12, 0x34, 0x11, 0, 0, 0, 0]));
        Check(DoIpCodec.ReadRoutingActivationCode(pendingRoute, 0x0E80) ==
            0x11, "DoIP routing 0x11 is pending vehicle-side confirmation.");
        Reject<IOException>(() =>
            DoIpCodec.RequireRoutingActive(pendingRoute, 0x0E80),
            "DoIP confirmation-pending must not be declared routing active.");
        var deniedRoute = DoIpCodec.Decode(DoIpCodec.Encode(
            DoIpCodec.RoutingActivationResponse,
            [0x0E, 0x80, 0x12, 0x34, 0x04, 0, 0, 0, 0]));
        Reject<IOException>(() =>
            DoIpCodec.RequireRoutingActive(deniedRoute, 0x0E80),
            "DoIP routing authentication required is not bypassed.");
        Check(DoIpCodec.ReadRoutingActivationCode(routeResponse, 0x0E80) ==
            0x10, "DoIP routing only becomes active on code 0x10.");
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
        var echoedAck = DoIpCodec.Decode(DoIpCodec.Encode(
            DoIpCodec.DiagnosticPositiveAck,
            [0x12, 0x34, 0x0E, 0x80, 0x00, 0x22, 0xF1]));
        Check(DoIpCodec.IsCorrelatedDiagnosticAck(
                echoedAck, 0x0E80, 0x1234, [0x22, 0xF1, 0x90]),
            "DoIP ACK echoed prefix must belong to active UDS request.");
        Check(!DoIpCodec.IsCorrelatedDiagnosticAck(
                echoedAck, 0x0E80, 0x1234, [0x2E, 0xF1, 0x90]),
            "Stale DoIP ACK for another UDS service is not correlated.");
        var overlongAck = DoIpCodec.Decode(DoIpCodec.Encode(
            DoIpCodec.DiagnosticPositiveAck,
            [0x12, 0x34, 0x0E, 0x80, 0x00, 0x22, 0xF1, 0x90, 0x41]));
        Check(!DoIpCodec.IsCorrelatedDiagnosticAck(
                overlongAck, 0x0E80, 0x1234, [0x22, 0xF1, 0x90]),
            "DoIP ACK cannot contain more echoed data than the active request.");

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
        // The implementation deliberately caps a DoIP PDU at one MiB:
        // exercise that exact boundary, including its 32-bit wire header,
        // without pretending to support unbounded ISO 13400 transfers.
        var largestUds = new byte[DoIpCodec.MaxPayload - 4];
        largestUds[0] = 0x36; // Simulated UDS TransferData, never sent to a car.
        var maximumDoIp = DoIpCodec.Diagnostic(0x0E80, 0x1234, largestUds);
        Check(maximumDoIp.Length == DoIpCodec.MaxPayload + 8 &&
            DoIpCodec.ReadPayloadLength(maximumDoIp.AsSpan(0, 8)) ==
                DoIpCodec.MaxPayload &&
            DoIpCodec.RequireDiagnosticResponse(
                DoIpCodec.Decode(DoIpCodec.Encode(
                    DoIpCodec.DiagnosticMessage,
                    [0x12, 0x34, 0x0E, 0x80, .. largestUds])),
                0x0E80, 0x1234).Length == largestUds.Length,
            "DoIP maximum bounded diagnostic payload encodes/decodes correctly.");
        Reject<ArgumentException>(() =>
            DoIpCodec.Diagnostic(0x0E80, 0x1234,
                new byte[DoIpCodec.MaxPayload - 3]),
            "DoIP refuses payload beyond the explicit one-MiB implementation cap.");

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
        var verifiedMonitor = StandardProtocolCli.ParseDoIpMonitor([
            StandardProtocolCli.DoIpCommand, "--host", "192.0.2.10", "--tester", "0E80",
            "--ecu", "1234", "--expected-vin", "1G1FA1RXXR0123456", "--obd-monitor-did",
            "F40C", "--samples", "5", "--interval-ms", "250", "--timeout-ms", "3000",
            "--uds-p2-ms", "100", "--uds-p2-star-ms", "2000"]);
        Check(verifiedMonitor.Obd && verifiedMonitor.Target.UseTls &&
            verifiedMonitor.Did == 0xF40C && verifiedMonitor.Count == 5 &&
            verifiedMonitor.IntervalMs == 250 && verifiedMonitor.Target.DoIpP2Ms == 100 &&
            verifiedMonitor.Target.DoIpP2StarMs == 2000, "Verified OBD monitoring parses a bounded TLS target and timings.");
        var tracePath = Path.Combine(Path.GetTempPath(), $"camarodiag-doip-{Guid.NewGuid():N}.jsonl");
        var traceTarget = StandardProtocolCli.ParseDoIp(["--host", "127.0.0.1", "--tester", "0E80",
            "--ecu", "1234", "--payload", "22F190", "--trace-file", tracePath]);
        Check(traceTarget.TraceFile == Path.GetFullPath(tracePath) && !File.Exists(tracePath),
            "Trace path validation does not create a file before consent.");
        using (var json = System.Text.Json.JsonDocument.Parse(StandardProtocolCli.FormatDoIpTrace(traceTarget,
            new DoIpDiagnosticTransport.PduTrace("TX", DateTimeOffset.UnixEpoch, [2, 0xFD, 0x80, 1, 0, 0, 0, 7]))))
        {
            Check(json.RootElement.GetProperty("direction").GetString() == "TX" &&
                json.RootElement.GetProperty("pdu").GetString() == "02FD800100000007" &&
                json.RootElement.GetProperty("ecu").GetString() == "1234",
                "Full PDU trace retains direction, target and bytes.");
        }
        Reject<ArgumentException>(() => StandardProtocolCli.ParseDoIp([
            "--host", "127.0.0.1", "--tester", "0E80", "--ecu", "1234",
            "--payload", "22F190", "--trace-file", tracePath, "--rx-file", tracePath]),
            "Trace and response cannot use the same file.");
        using (var datagram = System.Text.Json.JsonDocument.Parse(StandardProtocolCli.FormatDoIpDatagramTrace(
            "RX", new System.Net.IPEndPoint(System.Net.IPAddress.IPv6Loopback, 13400),
            [2, 0xFD, 0, 4, 0, 0, 0, 0], DateTimeOffset.UnixEpoch)))
        {
            Check(datagram.RootElement.GetProperty("transport").GetString() == "UDP" &&
                datagram.RootElement.GetProperty("port").GetInt32() == 13400 &&
                datagram.RootElement.GetProperty("pdu").GetString() == "02FD000400000000",
                "UDP journal preserves endpoint and complete datagram.");
        }
        string[] backupArgs = ["--doip-backup", "--host", "127.0.0.1", "--profile", "profile.json",
            "--archive", Path.Combine(Path.GetTempPath(), "doip-capture.zip"), "--confirm-read", "true"];
        var backupOptions = DoIpBackupCli.Parse([..backupArgs, "--trace-file", tracePath,
            "--uds-p2-ms", "100", "--uds-p2-star-ms", "2000", "--routing-activation-type", "0x01",
            "--routing-oem-data", "01020304"]);
        Check(backupOptions.Tls && backupOptions.TraceFile == Path.GetFullPath(tracePath) &&
            backupOptions.Session is { P2Ms: 100, P2StarMs: 2000, RoutingType: 1, OemHex: "01020304" },
            "Scoped DoIP backup parses trace and explicit session configuration.");
        Reject<ArgumentException>(() => DoIpBackupCli.Parse([..backupArgs, "--uds-p2-ms", "2000",
            "--uds-p2-star-ms", "100"]), "Backup rejects reversed P2 timing.");
        Reject<ArgumentException>(() => DoIpBackupCli.Parse([..backupArgs, "--routing-oem-data", "0102"]),
            "Backup rejects partial OEM routing data.");
        Reject<ArgumentException>(() => DoIpBackupCli.Parse(["--doip-verify-backup", "--profile",
            "profile.json", "--archive", "capture.zip", "--trace-file", tracePath]),
            "Offline archive verification does not open a network trace.");
        var monitorArgs = new[] { StandardProtocolCli.DoIpCommand, "--host", "192.0.2.10",
            "--tester", "0E80", "--ecu", "1234", "--expected-vin", "1G1FA1RXXR0123456",
            "--obd-monitor-did", "F40C" };
        Check(StandardProtocolCli.ParseDoIpMonitor([..monitorArgs, "--trace-file", tracePath]).Target.TraceFile == tracePath,
            "Monitoring forwards the trace file option.");
        Reject<ArgumentException>(() => StandardProtocolCli.ParseDoIpMonitor(
            [.. monitorArgs, "--uds-p2-ms", "1000", "--uds-p2-star-ms", "500"]),
            "Monitoring rejects reversed P2/P2-star timing before opening a socket.");
        Reject<ArgumentException>(() => StandardProtocolCli.ParseDoIpMonitor(
            [.. monitorArgs, "--payload", "221234"]), "Monitoring rejects arbitrary command options.");
        Reject<ArgumentException>(() => StandardProtocolCli.ParseDoIpMonitor(
            [.. monitorArgs, "--monitor-did", "F190"]), "Monitoring rejects conflicting service routes.");
        Reject<ArgumentOutOfRangeException>(() => StandardProtocolCli.ParseDoIpMonitor(
            [.. monitorArgs, "--samples", "0"]), "Monitoring rejects invalid count before network I/O.");
        Reject<ArgumentException>(() => StandardProtocolCli.ParseDoIpMonitor(
            monitorArgs.Select(x => x == "F40C" ? "1234" : x).ToArray()),
            "Legislated monitoring rejects non-regulatory DID.");
        UdsMultiDidCli.RequireIdentity(new (ushort Did, byte[] Data)[] {
            (0xF802, System.Text.Encoding.ASCII.GetBytes("1G1FA1RXXR0123456")), (0xF810, new byte[] { 1 }) },
            "1G1FA1RXXR0123456", true);
        Reject<InvalidDataException>(() => UdsMultiDidCli.RequireIdentity(
            new (ushort Did, byte[] Data)[] { (0xF810, new byte[] { 2 }) },
            "1G1FA1RXXR0123456", true), "Contradictory sampled protocol rejected.");
        Reject<InvalidDataException>(() => UdsMultiDidCli.RequireIdentity(
            new (ushort Did, byte[] Data)[] { (0xF190, new byte[] { 1 }) },
            "1G1FA1RXXR0123456", false), "Contradictory sampled VIN rejected.");
        var multiMonitorArgs = new[] { "--host", "127.0.0.1", "--tester", "0E80", "--ecu", "1234",
            "--expected-vin", "1G1FA1RXXR0123456", "--obd-monitor-dids", "0xF40C:2,0xF40D:1" };
        var multiMonitor = StandardProtocolCli.ParseDoIpMonitor(multiMonitorArgs);
        Check(multiMonitor.Obd && multiMonitor.Target.UseTls && multiMonitor.Layout?.Count == 2 &&
            multiMonitor.Target.Request.SequenceEqual(new byte[] { 0x22, 0xF4, 0x0C, 0xF4, 0x0D }),
            "Multi-DID monitoring builds one verified UDS request.");
        Reject<ArgumentException>(() => StandardProtocolCli.ParseDoIpMonitor(
            [..multiMonitorArgs[..^1], "0xF40C:4095,0xF40D:1"]), "Oversized multi-DID monitor sample rejected.");
        Reject<ArgumentException>(() => StandardProtocolCli.ParseDoIpMonitor(
            [..multiMonitorArgs[..^1], "0x1234:2,0xF40D:1"]), "OBDonUDS multi-monitor rejects non-legislated DID.");
        Reject<ArgumentException>(() => StandardProtocolCli.ParseDoIpMonitor(
            [..multiMonitorArgs, "--monitor-did", "F190"]), "Multi-monitor and single-monitor are exclusive.");
        var parsed = StandardProtocolCli.ParseDoIp([
            StandardProtocolCli.DoIpCommand, "--host", "127.0.0.1",
            "--tester", "0E80", "--ecu", "1234",
            "--payload", "2EF19001"]);
        Check(parsed.Request.SequenceEqual(new byte[] { 0x2E, 0xF1, 0x90, 1 }),
            "DoIP bidirectional CLI accepts WDBI service.");

        var inventoryArgs = new[] { "--host", "127.0.0.1", "--tester", "0E80",
            "--expected-vin", "1G1FA1RXXR0123456", "--ecu-inventory", "1234,1235" };
        var ecuInventory = StandardProtocolCli.ParseDoIpInventory(inventoryArgs);
        Check(ecuInventory.Target.UseTls && ecuInventory.Ecus.SequenceEqual(new ushort[] {0x1234, 0x1235}) &&
            ecuInventory.Target.Request.SequenceEqual(new byte[] {0x22, 0xF1, 0x90}),
            "Explicit ECU inventory uses TLS and only F190.");
        Check(!ecuInventory.Obd, "Generic inventory uses UDS identity.");
        var obdInventoryArgs = inventoryArgs.Select(arg => arg == "--ecu-inventory" ? "--obd-ecu-inventory" : arg).ToArray();
        var obdInventory = StandardProtocolCli.ParseDoIpInventory(obdInventoryArgs);
        Check(obdInventory.Obd && obdInventory.Target.UseTls &&
            obdInventory.Target.Request.SequenceEqual(new byte[] {0x22, 0xF8, 0x02}),
            "Legislated ECU inventory selects F802 and TLS.");
        Reject<ArgumentException>(() => StandardProtocolCli.ParseDoIpInventory(
            [..inventoryArgs, "--obd-ecu-inventory", "1234"]),
            "Generic and legislated inventory selectors are exclusive.");
        foreach (var invalid in new[] {"1234,1234", "1234-1235", "0E80", "123", ""})
            Reject<ArgumentException>(() => StandardProtocolCli.ParseDoIpInventory([..inventoryArgs[..^1], invalid]),
                "Invalid or ambiguous ECU inventory rejected.");
        Reject<ArgumentException>(() => StandardProtocolCli.ParseDoIpInventory([..inventoryArgs, "--ecu", "1234"]),
            "Inventory cannot include a separate ECU selector.");
        Reject<ArgumentException>(() => StandardProtocolCli.ParseDoIpInventory([..inventoryArgs, "--payload", "22F190"]),
            "Inventory cannot include arbitrary payload.");
        var strictArgs = new[] { "--host", "127.0.0.1", "--tester", "0E80", "--ecu", "1234",
            "--payload", "22F190" };
        Reject<ArgumentException>(() => StandardProtocolCli.ParseDoIp([..strictArgs, "--host", "192.0.2.1"]),
            "Duplicate target is not silently ignored.");
        Reject<ArgumentException>(() => StandardProtocolCli.ParseDoIp([..strictArgs, "--tls", "true", "--TLS", "false"]),
            "Conflicting TLS options are rejected.");
        Reject<ArgumentException>(() => StandardProtocolCli.ParseDoIp([..strictArgs, "--unknown", "x"]),
            "Unknown diagnostic options are rejected.");
        Reject<ArgumentException>(() => StandardProtocolCli.ParseDoIp([..strictArgs, "--timeout-ms"]),
            "Missing value is rejected before network I/O.");
        Check(StandardProtocolCli.ParseDoIp(strictArgs.Select(x => x == "1234" ? "0000" : x).ToArray()).Ecu == 0,
            "Zero logical address parses without stripping hex digits.");
        Reject<ArgumentException>(() => StandardProtocolCli.ParseDoIp(
            strictArgs.Select(x => x == "1234" ? "00001234" : x).ToArray()),
            "Oversized logical address rejected.");
        var existingReply = Path.GetTempFileName();
        try
        {
            Reject<ArgumentException>(() => StandardProtocolCli.ParseDoIp([..strictArgs, "--rx-file", existingReply]),
                "An existing reply file is rejected before transmitting.");
        }
        finally { File.Delete(existingReply); }

        var sequence = StandardProtocolCli.ParseDoIpSequence([
            StandardProtocolCli.DoIpCommand, "--host", "127.0.0.1",
            "--tester", "0E80", "--ecu", "1234", "--expected-vin", "1G1FA1RXXR0123456",
            "--sequence", "22F190;1901FF"]);
        Check(sequence.Target.UseTls && sequence.Commands.Length == 2 &&
            sequence.Commands[1].SequenceEqual(new byte[] {0x19,1,0xFF}) &&
            !sequence.ExplicitVersion, "DoIP sequence defaults to TLS and preserves command order.");
        Reject<ArgumentException>(() => StandardProtocolCli.ParseDoIpSequence([
            StandardProtocolCli.DoIpCommand, "--host", "127.0.0.1",
            "--tester", "0E80", "--ecu", "1234", "--sequence", "22F190"]),
            "DoIP sequence requires explicit VIN.");
        Reject<ArgumentException>(() => StandardProtocolCli.ParseDoIpSequence([
            StandardProtocolCli.DoIpCommand, "--host", "127.0.0.1",
            "--tester", "0E80", "--ecu", "1234", "--expected-vin", "1G1FA1RXXR0123456",
            "--sequence", "22F190;1901FF", "--payload", "22F190"]),
            "DoIP sequence rejects competing payload option.");

        var customRouting = StandardProtocolCli.ParseDoIp([
            StandardProtocolCli.DoIpCommand, "--host", "127.0.0.1",
            "--tester", "0E80", "--ecu", "1234",
            "--payload", "22F190",
            "--routing-activation-type", "0xE0",
            "--routing-oem-data", "01020304"]);
        Check(customRouting.RoutingActivationType == 0xE0 &&
            customRouting.RoutingOemData is { Length: 4 } routingOem &&
            routingOem.SequenceEqual(new byte[] {1, 2, 3, 4}),
            "DoIP custom activation type and explicit 4-byte OEM field parsed.");
        var routeWithOem = DoIpCodec.Decode(
            DoIpCodec.RoutingActivation(0x0E80, activationType: 0xE0,
                version: 0x03, oemSpecificData: [1, 2, 3, 4]));
        Check(routeWithOem.Version == 0x03 &&
            routeWithOem.Payload.Length == 11 &&
            routeWithOem.Payload[2] == 0xE0 &&
            routeWithOem.Payload.AsSpan(3, 4).SequenceEqual(new byte[] {0, 0, 0, 0}) &&
            routeWithOem.Payload.AsSpan(7, 4).SequenceEqual(new byte[] {1, 2, 3, 4}),
            "DoIP 11-byte routing activation leaves ISO-reserved bytes zero.");
        Check(DoIpCodec.Decode(DoIpCodec.RoutingActivation(0x0E80))
            .Payload.Length == 7,
            "Legacy routing activation defaults to 7-byte request.");
        Reject<ArgumentException>(() => DoIpCodec.RoutingActivation(
            0x0E80, oemSpecificData: [1, 2, 3]),
            "OEM routing activation data is only accepted at exactly four bytes.");
        Reject<ArgumentException>(() => StandardProtocolCli.ParseDoIp([
            StandardProtocolCli.DoIpCommand, "--host", "127.0.0.1",
            "--tester", "0E80", "--ecu", "1234",
            "--payload", "22F190", "--routing-oem-data", "010203"
        ]), "DoIP CLI refuses truncated OEM routing activation data.");


        var doIpTiming = StandardProtocolCli.ParseDoIp([
            StandardProtocolCli.DoIpCommand, "--host", "127.0.0.1",
            "--tester", "0E80", "--ecu", "1234",
            "--payload", "22F190", "--uds-p2-ms", "20",
            "--uds-p2-star-ms", "150"
        ]);
        Check(doIpTiming.DoIpP2Ms == 20 &&
            doIpTiming.DoIpP2StarMs == 150,
            "DoIP P2/P2* CLI forwards both stage deadlines.");
        Check(parsed.DoIpP2Ms == 30000 &&
            parsed.DoIpP2StarMs == 30000,
            "DoIP default P2/P2* preserves previous timing.");
        Reject<ArgumentOutOfRangeException>(() =>
            StandardProtocolCli.ParseDoIp([
                StandardProtocolCli.DoIpCommand, "--host", "127.0.0.1",
                "--tester", "0E80", "--ecu", "1234",
                "--payload", "22F190", "--uds-p2-ms", "0"
            ]), "Zero DoIP P2 deadline is invalid.");

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

        var monitorDoIp = StandardProtocolCli.ParseDoIp([
            StandardProtocolCli.DoIpCommand, "--host", "127.0.0.1",
            "--tester", "0E80", "--ecu", "1234", "--obd-did", "0xF600"]);
        Check(monitorDoIp.Request.SequenceEqual(new byte[] {0x22, 0xF6, 0x00}),
            "DoIP OBDonUDS legacy monitor DID uses ReadDataByIdentifier.");
        var informationDoIp = StandardProtocolCli.ParseDoIp([
            StandardProtocolCli.DoIpCommand, "--host", "127.0.0.1",
            "--tester", "0E80", "--ecu", "1234", "--obd-did", "0xF810"]);
        Check(informationDoIp.Request.SequenceEqual(new byte[] {0x22, 0xF8, 0x10}),
            "DoIP OBDonUDS protocol information F810 can be selected.");

        var verifiedIdentity = StandardProtocolCli.ParseVerifiedDoIpRead([
            "--host", "127.0.0.1", "--tester", "0E80", "--ecu", "1234",
            "--expected-vin", "1G1FA1RX0J0100001", "--obd-identify"]);
        Check(verifiedIdentity.IdentityOnly && verifiedIdentity.Obd && verifiedIdentity.Target.UseTls,
            "Verified OBD identity is a distinct discovery operation with TLS default.");
        Reject<ArgumentException>(() => StandardProtocolCli.ParseVerifiedDoIpRead([
            "--host", "127.0.0.1", "--tester", "0E80", "--ecu", "1234",
            "--expected-vin", "1G1FA1RX0J0100001", "--obd-identify", "--obd-did", "F40C"]),
            "Identity and data read are exclusive.");

        var verifiedRead = StandardProtocolCli.ParseVerifiedDoIpRead([
            StandardProtocolCli.DoIpCommand, "--host", "127.0.0.1", "--tester", "0E80",
            "--ecu", "1234", "--expected-vin", "1G1FA1RX0J0100001", "--obd-wwh-permanent"]);
        Check(verifiedRead.Obd && verifiedRead.Target.UseTls && verifiedRead.Target.DoIpP2Ms == 5000,
            "Verified OBD read defaults to TLS and bounded stage deadlines.");
        foreach (var (selector, sid) in new[] { ("--uds-did", (byte)0x22),
            ("--uds-scaling-did", (byte)0x24) })
        {
            string[] typedArgs = ["--host", "127.0.0.1", "--tester", "0E80",
                "--ecu", "1234", "--expected-vin", "1G1FA1RX0J0100001", selector, "0xF40C"];
            var typed = StandardProtocolCli.ParseVerifiedDoIpRead(typedArgs);
            Check(!typed.Obd && typed.Target.UseTls && typed.Target.Request.SequenceEqual(
                new byte[] { sid, 0xF4, 0x0C }), "Generic typed UDS DID uses F190 and TLS by default.");
            Reject<ArgumentException>(() => StandardProtocolCli.ParseDoIp(
                ["--host", "127.0.0.1", "--tester", "0E80", "--ecu", "1234", selector, "F40C"]),
                "Typed UDS DID requires VIN before networking.");
            Reject<ArgumentException>(() => StandardProtocolCli.ParseVerifiedDoIpRead(
                [..typedArgs, "--obd-did", "F40C"]), "Typed selectors are exclusive.");
            Reject<ArgumentException>(() => StandardProtocolCli.ParseVerifiedDoIpRead(
                [..typedArgs[..^1], "F4"]), "DID must contain four hex digits.");
            Reject<ArgumentException>(() => StandardProtocolCli.ParseVerifiedDoIpRead(
                [..typedArgs, "--obd-verified", "true"]), "Generic UDS cannot be relabelled as OBDonUDS.");
            var responseSid = (byte)(sid + 0x40);
            Check(StandardProtocolCli.DecodeVerifiedSingleDid(typed.Target.Request,
                [responseSid, 0xF4, 0x0C, 0xAB]).SequenceEqual(new byte[] {0xAB}),
                "Typed UDS returns actual raw data.");
            Reject<InvalidDataException>(() => StandardProtocolCli.DecodeVerifiedSingleDid(
                typed.Target.Request, [responseSid, 0xF4, 0x0D, 0xAB]), "Wrong DID rejected.");
            Reject<InvalidDataException>(() => StandardProtocolCli.DecodeVerifiedSingleDid(
                typed.Target.Request, [responseSid, 0xF4, 0x0C]), "Empty DID rejected.");
        }

        foreach (var (selection, expected) in new (string[], byte[])[]
        {
            (["--uds-dtc-mask", "0x08"], [0x19, 0x02, 0x08]),
            (["--uds-dtc-count-mask", "0x08"], [0x19, 0x01, 0x08]),
            (["--uds-snapshot", "0x123456", "--uds-snapshot-record", "1"], [0x19, 4, 0x12, 0x34, 0x56, 1]),
            (["--uds-extended-dtc", "0x123456", "--uds-extended-record", "2"], [0x19, 6, 0x12, 0x34, 0x56, 2])
        })
        {
            string[] target = ["--host", "127.0.0.1", "--tester", "0E80", "--ecu", "1234"];
            var read = StandardProtocolCli.ParseVerifiedDoIpRead(
                [..target, "--expected-vin", "1G1FA1RX0J0100001", ..selection]);
            Check(!read.Obd && read.Target.UseTls && read.Target.Request.SequenceEqual(expected),
                "Generic typed UDS DTC uses F190 and preserves requested parameters.");
            Reject<ArgumentException>(() => StandardProtocolCli.ParseDoIp([..target, ..selection]),
                "Generic typed DTC requires expected VIN.");
            Reject<ArgumentException>(() => StandardProtocolCli.ParseVerifiedDoIpRead(
                [..target, "--expected-vin", "1G1FA1RX0J0100001", ..selection, "--obd-dtc-mask", "0xFF"]),
                "Generic and legislated DTC selectors cannot be combined.");
        }
        Reject<ArgumentException>(() => StandardProtocolCli.ParseVerifiedDoIpRead([
            "--host", "127.0.0.1", "--tester", "0E80", "--ecu", "1234",
            "--expected-vin", "1G1FA1RX0J0100001", "--uds-snapshot", "0x123456"]),
            "Generic snapshot requires explicit record.");

        foreach (var (selector, subfunction) in new[] {
            ("--uds-supported-dtcs", (byte)0x0A), ("--uds-permanent-dtcs", (byte)0x15), ("--uds-snapshot-index", (byte)0x03) })
        {
            string[] target = ["--host", "127.0.0.1", "--tester", "0E80", "--ecu", "1234"];
            var inventory = StandardProtocolCli.ParseVerifiedDoIpRead(
                [..target, "--expected-vin", "1G1FA1RX0J0100001", selector]);
            Check(!inventory.Obd && inventory.Target.Request.SequenceEqual(new byte[] { 0x19, subfunction }),
                "DTC inventory uses generic verified UDS.");
            Reject<ArgumentException>(() => StandardProtocolCli.ParseDoIp([..target, selector]),
                "DTC inventory requires VIN.");
        }
        Check(UdsStandardServices.RequireResponse([0x19, 3], [0x59, 3]).Length == 2,
            "Empty snapshot index is a valid correlated response.");
        Reject<InvalidDataException>(() => UdsStandardServices.RequireResponse(
            [0x19, 3], [0x59, 3, 1]), "Truncated snapshot index rejected at transport.");
        Reject<InvalidDataException>(() => UdsStandardServices.RequireResponse(
            [0x19, 0x0A], [0x59, 0x0A]), "Supported DTC response requires availability mask.");
        string[] memoryArgs = ["--host", "127.0.0.1", "--tester", "0E80", "--ecu", "1234",
            "--expected-vin", "1G1FA1RX0J0100001", "--uds-memory", "0x02"];
        var memoryRead = StandardProtocolCli.ParseVerifiedDoIpRead([..memoryArgs, "--uds-memory-mask", "0x08"]);
        Check(!memoryRead.Obd && memoryRead.Target.UseTls && memoryRead.Target.Request.SequenceEqual(
            new byte[] { 0x19, 0x17, 8, 2 }), "User memory list uses verified UDS.");
        var memoryList = ObdOnUdsServices.DecodeUserMemory(memoryRead.Target.Request, [0x59, 0x17, 2, 8, 1, 2, 3, 8]);
        Check(memoryList.Memory == 2 && memoryList.List!.Codes.Single().Code == 0x010203, "Explicit memory and DTC preserved.");
        Reject<InvalidDataException>(() => ObdOnUdsServices.DecodeUserMemory(memoryRead.Target.Request,
            [0x59, 0x17, 3, 8]), "Wrong memory rejected.");
        Reject<InvalidDataException>(() => ObdOnUdsServices.DecodeUserMemory(memoryRead.Target.Request,
            [0x59, 0x17, 2, 8, 1, 2, 3, 1]), "Unmatched status rejected.");
        foreach (var (option, sub) in new[] { ("--uds-memory-snapshot", (byte)0x18), ("--uds-memory-extended", (byte)0x19) })
        {
            var read = StandardProtocolCli.ParseVerifiedDoIpRead([..memoryArgs, option, "0x010203", "--uds-memory-record", "1"]);
            Check(!read.Obd && read.Target.Request.SequenceEqual(new byte[] { 0x19, sub, 1, 2, 3, 1, 2 }),
                "Explicit user memory detail.");
            var report = ObdOnUdsServices.DecodeUserMemory(read.Target.Request, [0x59, sub, 2, 1, 2, 3, 8, 1, 0xAA]);
            Check(report.Dtc == 0x010203 && report.RawRecords!.SequenceEqual(new byte[] { 1, 0xAA }), "Raw record preserved.");
            Reject<InvalidDataException>(() => ObdOnUdsServices.DecodeUserMemory(read.Target.Request,
                [0x59, sub, 2, 1, 2, 3, 8, 2]), "Wrong record rejected.");
        }
        Reject<ArgumentException>(() => StandardProtocolCli.ParseVerifiedDoIpRead([..memoryArgs, "--uds-memory-mask", "8", "--uds-memory-record", "1"]),
            "List cannot specify record.");
        Reject<ArgumentException>(() => StandardProtocolCli.ParseVerifiedDoIpRead([..memoryArgs, "--uds-memory-extended", "1", "--uds-memory-record", "0xF0"]),
            "Reserved record rejected.");
        var severityTarget = StandardProtocolCli.ParseVerifiedDoIpRead([
            "--host", "127.0.0.1", "--tester", "0E80", "--ecu", "1234",
            "--expected-vin", "1G1FA1RX0J0100001", "--uds-severity-list", "0x80", "--uds-severity-status", "0x08"]);
        Check(!severityTarget.Obd && severityTarget.Target.Request.SequenceEqual(new byte[] { 0x19, 8, 0x80, 8 }),
            "Severity CLI uses generic verified UDS and explicit masks.");
        var severityReport = ObdOnUdsServices.DecodeSeverity(severityTarget.Target.Request,
            [0x59, 8, 8, 0x80, 0x12, 0x12, 0x34, 0x56, 8]);
        Check(severityReport.Records.Single() == new ObdOnUdsServices.SeverityRecord(0x123456, 0x80, 0x12, 8),
            "Severity fields preserved.");
        Reject<InvalidDataException>(() => ObdOnUdsServices.DecodeSeverity(severityTarget.Target.Request,
            [0x59, 8, 8, 0x40, 0x12, 0x12, 0x34, 0x56, 8]), "Severity mask mismatch rejected.");
        var severityDetail = ObdOnUdsServices.ReadSeverity(9, 0, 0, 0x123456);
        Check(ObdOnUdsServices.DecodeSeverity(severityDetail, [0x59, 9, 8]).Records.Count == 0,
            "Missing severity record is a valid empty response.");
        Reject<InvalidDataException>(() => ObdOnUdsServices.DecodeSeverity(severityDetail,
            [0x59, 9, 8, 0x80, 0x12, 0x12, 0x34, 0x57, 8]), "Wrong DTC severity record rejected.");
        Check(ObdOnUdsServices.DecodeSeverity(ObdOnUdsServices.ReadSeverity(7, 0x80, 8),
            [0x59, 7, 8, 1, 0, 2]).Count == 2, "Severity count preserved.");
        var counters = StandardProtocolCli.ParseVerifiedDoIpRead([
            "--host", "127.0.0.1", "--tester", "0E80", "--ecu", "1234",
            "--expected-vin", "1G1FA1RX0J0100001", "--uds-fault-counters"]);
        Check(!counters.Obd && counters.Target.Request.SequenceEqual(new byte[] { 0x19, 0x14 }),
            "Fault counter CLI uses generic verified UDS.");
        var fdc = ObdOnUdsServices.DecodeFaultCounters([0x59, 0x14, 1, 2, 3, 0x7F, 1, 2, 4, 0x80, 1, 2, 5, 0xFF]);
        Check(fdc.Select(r => r.Counter).SequenceEqual(new sbyte[] { 127, -128, -1 }), "Signed counters preserved.");
        Check(ObdOnUdsServices.DecodeFaultCounters([0x59, 0x14]).Count == 0, "Empty counter report accepted.");
        Reject<InvalidDataException>(() => ObdOnUdsServices.DecodeFaultCounters(
            [0x59, 0x14, 1]), "Truncated counter rejected.");
        Reject<InvalidDataException>(() => ObdOnUdsServices.DecodeFaultCounters(
            [0x59, 0x14, 1, 2, 3, 1, 1, 2, 3, 2]), "Duplicate counter rejected.");
        foreach (byte sub in new byte[] { 0x0B, 0x0C, 0x0D, 0x0E })
        {
            var historical = StandardProtocolCli.ParseVerifiedDoIpRead([
                "--host", "127.0.0.1", "--tester", "0E80", "--ecu", "1234",
                "--expected-vin", "1G1FA1RX0J0100001", "--uds-historical-dtc", $"0x{sub:X2}"]);
            Check(!historical.Obd && historical.Target.Request.SequenceEqual(new byte[] { 0x19, sub }),
                "Historical DTC CLI is a verified generic read.");
            Check(ObdOnUdsServices.DecodeHistoricalDtc(sub, [0x59, sub, 8]).Codes.Count == 0,
                "No matching DTC is not a transport failure.");
            Check(ObdOnUdsServices.DecodeHistoricalDtc(sub, [0x59, sub, 8, 1, 2, 3, 0]).Codes[0].Code == 0x010203,
                "Historical record can have inactive current status.");
            Reject<InvalidDataException>(() => ObdOnUdsServices.DecodeHistoricalDtc(sub, [0x59, sub, 8, 1]),
                "Truncated historical record rejected.");
            Reject<InvalidDataException>(() => ObdOnUdsServices.DecodeHistoricalDtc(sub, [0x59, sub, 8, 1, 2, 3, 1]),
                "Unavailable current status bit rejected.");
        }
        var supported = ObdOnUdsServices.DecodeSupportedDtcs([0x59, 0x0A, 8, 1, 2, 3, 0]);
        Check(supported.Codes.Count == 1 && supported.Codes[0].Status == 0, "Inactive supported DTC retained.");
        Reject<InvalidDataException>(() => ObdOnUdsServices.DecodeSupportedDtcs(
            [0x59, 0x0A, 8, 1, 2, 3, 1]), "Unavailable status rejected.");
        Reject<InvalidDataException>(() => ObdOnUdsServices.DecodeSupportedDtcs(
            [0x59, 0x0A, 8, 1, 2, 3, 0, 1, 2, 3, 8]), "Duplicate DTC rejected.");
        var permanent = ObdOnUdsServices.DecodePermanentDtcs([0x59, 0x15, 8, 1, 2, 3, 0, 1, 2, 4, 8]);
        Check(permanent.Codes.Count == 2 && permanent.Codes[0].Status == 0, "Permanent DTC with zero current status retained.");
        Check(ObdOnUdsServices.DecodePermanentDtcs([0x59, 0x15, 8]).Codes.Count == 0, "Empty permanent DTC report retained.");
        Reject<InvalidDataException>(() => ObdOnUdsServices.DecodePermanentDtcs([0x59, 0x0A, 8]), "Wrong report rejected.");
        Reject<InvalidDataException>(() => ObdOnUdsServices.DecodePermanentDtcs([0x59, 0x15, 8, 1, 2, 3, 1]), "Unavailable status rejected.");
        Reject<InvalidDataException>(() => ObdOnUdsServices.DecodePermanentDtcs([0x59, 0x15, 8, 1, 2, 3, 0, 1, 2, 3, 8]), "Duplicate rejected.");
        Reject<InvalidDataException>(() => UdsStandardServices.RequireResponse([0x19, 0x15], [0x59, 0x15, 8, 1]), "Truncated permanent DTC report rejected.");
        var index = ObdOnUdsServices.DecodeSnapshotIndex([0x59, 3, 1, 2, 3, 1, 1, 2, 3, 2]);
        Check(index.Count == 2 && index[1].Record == 2, "Distinct snapshot records retained.");
        Reject<InvalidDataException>(() => ObdOnUdsServices.DecodeSnapshotIndex([0x59, 3, 1]), "Truncated snapshot index rejected.");
        Reject<InvalidDataException>(() => ObdOnUdsServices.DecodeSnapshotIndex(
            [0x59, 3, 1, 2, 3, 1, 1, 2, 3, 1]), "Duplicate snapshot reference rejected.");

        var verifiedMulti = StandardProtocolCli.ParseVerifiedDoIpRead([
            "--host", "127.0.0.1", "--tester", "0E80", "--ecu", "1234",
            "--expected-vin", "1G1FA1RX0J0100001", "--uds-dids", "0xF40C:2,0xF40D:1",
            "--obd-verified", "true"]);
        Check(verifiedMulti.Obd && verifiedMulti.Target.Request.SequenceEqual(
            new byte[] { 0x22, 0xF4, 0x0C, 0xF4, 0x0D }), "Verified multi-DID OBD route.");
        Reject<ArgumentException>(() => StandardProtocolCli.ParseVerifiedDoIpRead([
            "--host", "127.0.0.1", "--tester", "0E80", "--ecu", "1234",
            "--expected-vin", "1G1FA1RX0J0100001", "--payload", "22F190"]),
            "Raw exchanges use the separate confirmed sequence path.");
        var wwhDoIp = StandardProtocolCli.ParseDoIp([
            StandardProtocolCli.DoIpCommand, "--host", "127.0.0.1",
            "--tester", "0E80", "--ecu", "1234",
            "--obd-wwh-status", "0x08",
            "--obd-wwh-severity", "0x40"]);
        Check(wwhDoIp.Request.SequenceEqual(
            new byte[] {0x19, 0x42, 0x33, 0x40, 0x08}),
            "DoIP WWH emissions DTC status and severity selectors.");
        var permanentDoIp = StandardProtocolCli.ParseDoIp([
            StandardProtocolCli.DoIpCommand, "--host", "127.0.0.1",
            "--tester", "0E80", "--ecu", "1234",
            "--obd-wwh-permanent"]);
        Check(permanentDoIp.Request.SequenceEqual(
            new byte[] {0x19, 0x55, 0x33}),
            "DoIP WWH permanent DTC selector.");
        Reject<ArgumentException>(() => StandardProtocolCli.ParseDoIp([
            StandardProtocolCli.DoIpCommand, "--host", "127.0.0.1",
            "--tester", "0E80", "--ecu", "1234",
            "--obd-wwh-permanent", "--obd-wwh-status", "0x08"]),
            "Mutually exclusive DoIP WWH permanent and status-filtered requests.");

        var readinessDoIp = StandardProtocolCli.ParseDoIp([
            StandardProtocolCli.DoIpCommand, "--host", "127.0.0.1",
            "--tester", "0E80", "--ecu", "1234",
            "--obd-readiness-group", "0x06"]);
        Check(readinessDoIp.Request.SequenceEqual(
            new byte[] {0x19, 0x56, 0x33, 0x06}),
            "DoIP OBDonUDS readiness group uses standard 0x19/0x56.");
        Reject<ArgumentException>(() => StandardProtocolCli.ParseDoIp([
            StandardProtocolCli.DoIpCommand, "--host", "127.0.0.1",
            "--tester", "0E80", "--ecu", "1234",
            "--obd-readiness-group", "0x06", "--obd-wwh-permanent"]),
            "DoIP readiness and permanent DTC queries are exclusive.");

        var snapshotDoIp = StandardProtocolCli.ParseDoIp([
            StandardProtocolCli.DoIpCommand, "--host", "127.0.0.1",
            "--tester", "0E80", "--ecu", "1234",
            "--obd-snapshot", "0x123456",
            "--obd-snapshot-record", "0x02"]);
        Check(snapshotDoIp.Request.SequenceEqual(
            new byte[] {0x19, 0x04, 0x12, 0x34, 0x56, 0x02}),
            "DoIP DTC snapshot request preserves DTC and record number.");
        Reject<ArgumentException>(() => StandardProtocolCli.ParseDoIp([
            StandardProtocolCli.DoIpCommand, "--host", "127.0.0.1",
            "--tester", "0E80", "--ecu", "1234",
            "--obd-snapshot-record", "0x02", "--payload", "22F190"]),
            "DoIP snapshot record is forbidden without a DTC.");

        var extendedDoIp = StandardProtocolCli.ParseDoIp([
            StandardProtocolCli.DoIpCommand, "--host", "127.0.0.1",
            "--tester", "0E80", "--ecu", "1234",
            "--obd-extended-dtc", "0x123456"]);
        Check(extendedDoIp.Request.SequenceEqual(
            new byte[] {0x19, 0x06, 0x12, 0x34, 0x56, 0xFE}),
            "DoIP default extended DTC record FE selects regulated OBD records.");
        Reject<ArgumentException>(() => StandardProtocolCli.ParseDoIp([
            StandardProtocolCli.DoIpCommand, "--host", "127.0.0.1",
            "--tester", "0E80", "--ecu", "1234",
            "--obd-extended-record", "0x90", "--obd-did", "0xF810"]),
            "DoIP does not accept extended record selector without a DTC.");

        var multiDidDoIp = StandardProtocolCli.ParseDoIp([
            StandardProtocolCli.DoIpCommand, "--host", "127.0.0.1",
            "--tester", "0E80", "--ecu", "1234",
            "--uds-dids", "0xF190:17,0xF187:4"]);
        Check(multiDidDoIp.Request.SequenceEqual(
            new byte[] {0x22, 0xF1, 0x90, 0xF1, 0x87}),
            "DoIP multi-DID uses the same ISO14229 request as ISO-TP.");
        var selectors = UdsMultiDidCli.Parse("0xF190:2,0xF187:3");
        var fields = UdsMultiDidCli.Decode(selectors,
            [0x22, 0xF1, 0x90, 0xF1, 0x87],
            [0x62, 0xF1, 0x90, 0x41, 0x42,
             0xF1, 0x87, 0x11, 0x22, 0x33]);
        Check(fields.Count == 2 &&
            fields[0].Data.SequenceEqual(new byte[] {0x41, 0x42}),
            "DoIP multi-DID output uses configured data lengths.");
        Reject<ArgumentException>(() =>
            UdsMultiDidCli.Parse("0xF190:17,0xF190:4"),
            "Duplicate response DIDs fail parsing before network transmission.");
        Reject<ArgumentException>(() =>
            UdsMultiDidCli.Parse("F190:17,0xF187:4"),
            "UDS multi-DID selection rejects ambiguous decimal/hex identifiers.");
        Reject<ArgumentException>(() => StandardProtocolCli.ParseDoIp([
            StandardProtocolCli.DoIpCommand, "--host", "127.0.0.1",
            "--tester", "0E80", "--ecu", "1234",
            "--uds-dids", "0xF190:17,0xF187:4", "--payload", "22F190"]),
            "DoIP refuses competing UDS payload selectors.");

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

        var versionedDoIp = StandardProtocolCli.ParseDoIp([
            StandardProtocolCli.DoIpCommand, "--host", "gateway.example.org",
            "--tester", "0E80", "--ecu", "1234",
            "--doip-version", "3", "--tls", "true",
            "--rx-file", "ecu-response-new.bin", "--payload", "22F810"]);
        Check(versionedDoIp.Version == 3 && versionedDoIp.UseTls &&
            versionedDoIp.ReceiveFile == "ecu-response-new.bin",
            "Secure DoIP protocol version 0x03 and unique binary reply are selectable.");
        Reject<ArgumentException>(() => StandardProtocolCli.ParseDoIp([
            StandardProtocolCli.DoIpCommand, "--host", "gateway.example.org",
            "--tester", "0E80", "--ecu", "1234",
            "--doip-version", "5", "--payload", "22F810"]),
            "Unsupported DoIP header version rejected before TCP connection.");
        var version4Cli = StandardProtocolCli.ParseDoIp([
            StandardProtocolCli.DoIpCommand, "--host", "gateway.example.org",
            "--tester", "0E80", "--ecu", "1234", "--tls", "true",
            "--doip-version", "4", "--payload", "22F810"]);
        Check(version4Cli.Version == 4,
            "Current ISO 13400-2:2025 DoIP protocol header version 4 selected.");
        var version4Routing = DoIpCodec.RoutingActivation(
            0x0E80, version: 0x04);
        Check(version4Routing[0] == 0x04 &&
            version4Routing[1] == 0xFB &&
            DoIpCodec.Decode(version4Routing).Version == 0x04,
            "DoIP protocol 0x04 uses bitwise inverse header byte 0xFB.");
        Reject<InvalidDataException>(() => DoIpCodec.Decode(
            new byte[] {0x05, 0xFA, 0, 5, 0, 0, 0, 0}),
            "DoIP reserved protocol version 5 is not accepted.");

        var routingV3 = DoIpCodec.Decode(DoIpCodec.RoutingActivation(
            0x0E80, version: 0x03));
        Check(routingV3.Version == 0x03 &&
            routingV3.Type == DoIpCodec.RoutingActivationRequest &&
            routingV3.Payload.Length == 7,
            "DoIP routing activation preserves requested header version 3.");
        var diagnosticV3 = DoIpCodec.Decode(DoIpCodec.Diagnostic(
            0x0E80, 0x1234, [0x22, 0xF8, 0x10], version: 0x03));
        Check(diagnosticV3.Version == 0x03 &&
            diagnosticV3.Payload[4] == 0x22,
            "DoIP diagnostic message uses the selected TCP header version.");

        var tempDir = Path.Combine(Path.GetTempPath(),
            "camaro-doip-test-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(tempDir);
        try
        {
            var source = Path.Combine(tempDir, "uds-request.bin");
            var capture = Path.Combine(tempDir, "uds-reply.bin");
            var original = Enumerable.Range(0, 6000)
                .Select(i => (byte)(i & 0xFF)).ToArray();
            File.WriteAllBytes(source, original);
            var fromFile = StandardProtocolCli.ParseDoIp([
                StandardProtocolCli.DoIpCommand, "--host", "127.0.0.1",
                "--tester", "0E80", "--ecu", "1234",
                "--payload-file", source, "--rx-file", capture]);
            Check(fromFile.Request.SequenceEqual(original) &&
                fromFile.ReceiveFile == capture,
                "DoIP reads exact binary diagnostic request bytes from file.");
            StandardProtocolCli.SaveDoIpReplyAsync(capture,
                original).GetAwaiter().GetResult();
            Check(File.ReadAllBytes(capture).SequenceEqual(original),
                "DoIP binary response is saved and read back without modifications.");
            Reject<IOException>(() =>
                StandardProtocolCli.SaveDoIpReplyAsync(capture,
                    new byte[] {0x62}).GetAwaiter().GetResult(),
                "DoIP cannot overwrite an already saved raw diagnostic response.");
            Check(File.ReadAllBytes(capture).SequenceEqual(original),
                "An existing diagnostic file stays unmodified after refused overwrite.");
        }
        finally
        {
            Directory.Delete(tempDir, recursive: true);
        }

        var largeUds = Enumerable.Repeat((byte)0xAB, 6000).ToArray();
        var largeDoIp = DoIpCodec.Decode(DoIpCodec.Diagnostic(
            0x0E80, 0x1234, largeUds));
        Check(largeDoIp.Payload.Length == largeUds.Length + 4 &&
            largeDoIp.Payload.AsSpan(4).SequenceEqual(largeUds),
            "DoIP codec carries a six-kilobyte UDS payload without 4KB truncation.");
        Reject<ArgumentException>(() => DoIpCodec.Diagnostic(
            0x0E80, 0x1234, new byte[DoIpCodec.MaxPayload]),
            "DoIP retains an explicit in-memory payload cap.");

        const string testThumbprint = "0123456789ABCDEF0123456789ABCDEF01234567";
        var mutualTlsDoIp = StandardProtocolCli.ParseDoIp([
            StandardProtocolCli.DoIpCommand, "--host", "gateway.example.org",
            "--tester", "0E80", "--ecu", "1234", "--tls", "true",
            "--client-cert-thumbprint", testThumbprint, "--payload", "22F810"]);
        Check(mutualTlsDoIp.ClientCertificateThumbprint == testThumbprint &&
            mutualTlsDoIp.UseTls,
            "DoIP can select an installed client certificate without a password.");
        Reject<ArgumentException>(() => StandardProtocolCli.ParseDoIp([
            StandardProtocolCli.DoIpCommand, "--host", "gateway.example.org",
            "--tester", "0E80", "--ecu", "1234",
            "--client-cert-thumbprint", testThumbprint, "--payload", "22F810"]),
            "Mutual TLS client cert cannot be sent via unencrypted TCP.");
        Reject<ArgumentException>(() =>
            DoIpDiagnosticTransport.FindClientCertificate("1234"),
            "Malformed certificate thumbprint fails before OS store lookup.");

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
        var functionalCli = StandardProtocolCli.ParseJ2534([
            StandardProtocolCli.PassThruCommand,
            "--adapter-index", "0", "--protocol-id", "0x04",
            "--baud", "10400", "--kwp-service", "81",
            "--kwp-functional", "true",
            "--kwp-tx-target", "0x33", "--kwp-tx-source", "0xF1",
            "--kwp-rx-target", "0xF1", "--kwp-rx-source", "0x33"]);
        Check(functionalCli.Data.SequenceEqual(
                StandardVehicleProtocols.BuildKwpFunctionalFrame(
                    0x33, 0xF1, [0x81])[..^1]) &&
            functionalCli.Mode == "exchange",
            "KWP ISO14230 functional TX constructor generates full wire message.");
        var physicalCli = StandardProtocolCli.ParseJ2534([
            StandardProtocolCli.PassThruCommand,
            "--adapter-index", "0", "--protocol-id", "0x04",
            "--baud", "10400", "--kwp-service", "22F190",
            "--kwp-tx-target", "0x33", "--kwp-tx-source", "0xF1"]);
        Check(physicalCli.Data.SequenceEqual(
                StandardVehicleProtocols.BuildKwpPhysicalFrame(
                    0x33, 0xF1, [0x22, 0xF1, 0x90])[..^1]),
            "KWP ISO14230 physical TX constructor builds addressed frame.");
        var manualChecksum = StandardProtocolCli.ParseJ2534([
            StandardProtocolCli.PassThruCommand,
            "--adapter-index", "0", "--protocol-id", "0x04",
            "--baud", "10400", "--flags", "0x200",
            "--kwp-service", "22F190",
            "--kwp-tx-target", "0x33", "--kwp-tx-source", "0xF1"]);
        Check(manualChecksum.Data.SequenceEqual(
                StandardVehicleProtocols.BuildKwpPhysicalFrame(
                    0x33, 0xF1, [0x22, 0xF1, 0x90])) &&
            (manualChecksum.Flags &
                J2534StandardChannel.KlineChecksumDisabled) != 0,
            "Manual J2534 checksum mode supplies the complete KWP wire checksum.");
        var receiveWire = StandardVehicleProtocols.BuildKwpPhysicalFrame(
            0xF1, 0x33, [0x62, 0xF1, 0x90]);
        Check(StandardVehicleProtocols.ParseKwpJ2534(
                receiveWire[..^1], 0xF1, 0x33,
                functional: false, checksumDisabled: false)
                .SequenceEqual(new byte[] {0x62, 0xF1, 0x90}),
            "Auto-checksum J2534 strips the received KWP checksum before app RX.");
        Check(StandardVehicleProtocols.ParseKwpJ2534(
                receiveWire, 0xF1, 0x33,
                functional: false, checksumDisabled: true)
                .SequenceEqual(new byte[] {0x62, 0xF1, 0x90}),
            "Manual checksum mode verifies the exact received full wire checksum.");
        var badManualWire = receiveWire.ToArray();
        badManualWire[^1] ^= 1;
        Reject<InvalidDataException>(() =>
            StandardVehicleProtocols.ParseKwpJ2534(
                badManualWire, 0xF1, 0x33,
                functional: false, checksumDisabled: true),
            "Manual KWP checksum mismatch must fail physical response verification.");

        Reject<ArgumentException>(() => StandardProtocolCli.ParseJ2534([
            StandardProtocolCli.PassThruCommand,
            "--adapter-index", "0", "--protocol-id", "0x04",
            "--baud", "10400", "--mode", "receive",
            "--kwp-service", "22F190", "--kwp-tx-target", "0x33",
            "--kwp-tx-source", "0xF1"]),
            "KWP passive receive cannot inject a service request.");
        Reject<ArgumentException>(() => StandardProtocolCli.ParseJ2534([
            StandardProtocolCli.PassThruCommand,
            "--adapter-index", "0", "--protocol-id", "0x03",
            "--baud", "10400", "--kwp-service", "22F190",
            "--kwp-tx-target", "0x33", "--kwp-tx-source", "0xF1"]),
            "KWP service constructor requires ISO14230 vendor channel.");
        Reject<ArgumentException>(() => StandardProtocolCli.ParseJ2534([
            StandardProtocolCli.PassThruCommand,
            "--adapter-index", "0", "--protocol-id", "0x04",
            "--baud", "10400", "--data", "81",
            "--kwp-service", "22F190", "--kwp-tx-target", "0x33",
            "--kwp-tx-source", "0xF1"]),
            "Raw J2534 and built KWP wire frames cannot both be TX sources.");
        var functionalReceiveCli = StandardProtocolCli.ParseJ2534([
            StandardProtocolCli.PassThruCommand,
            "--adapter-index", "0", "--protocol-id", "0x04",
            "--baud", "10400", "--mode", "receive",
            "--kwp-rx-target", "0x33", "--kwp-rx-source", "0xF1",
            "--kwp-rx-functional", "true"]);
        Check(functionalReceiveCli.KwpFunctionalRx &&
            functionalReceiveCli.Mode == "receive" &&
            functionalReceiveCli.Data.Length == 0,
            "KWP functional parser opt-in does not start a K-Line transaction.");

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

        Reject<ArgumentException>(() => StandardProtocolCli.ParseJ2534([
            StandardProtocolCli.PassThruCommand, "--adapter-index", "0",
            "--protocol-id", "0x06", "--baud", "500000",
            "--mode", "receive", "--read-frames", "1",
            "--mask", "FFFFFFFF", "--pattern", "000007E8",
            "--flow-control", "000007E0"]),
            "Passive J2534 mode must reject vendor automatic ISO-TP FC transmission.");

        var swCanHighSpeed = StandardProtocolCli.ParseJ2534([
            StandardProtocolCli.PassThruCommand,
            "--adapter-index", "0", "--protocol-id", "0x8008",
            "--baud", "33333", "--sw-can-hs-baud", "83333",
            "--data", "0102"]);
        Check(swCanHighSpeed.ProtocolId == J2534StandardChannel.SwCan &&
            swCanHighSpeed.SwCanHighSpeedBaud == 83333,
            "J2534 SW_CAN high-speed data-rate delegated to vendor on real channel.");
        Reject<ArgumentException>(() => StandardProtocolCli.ParseJ2534([
            StandardProtocolCli.PassThruCommand,
            "--adapter-index", "0", "--protocol-id", "0x8007",
            "--baud", "33333", "--sw-can-hs-baud", "83333",
            "--data", "0102"]),
            "SW_CAN high-speed rate is not applicable to SW_ISO15765.");
        Reject<ArgumentException>(() => StandardProtocolCli.ParseJ2534([
            StandardProtocolCli.PassThruCommand,
            "--adapter-index", "0", "--protocol-id", "0x8008",
            "--baud", "33333", "--mode", "receive",
            "--sw-can-hs-baud", "83333"]),
            "Passive J2534 SW_CAN cannot trigger a physical speed-mode switch.");
        Reject<ArgumentException>(() => StandardProtocolCli.ParseJ2534([
            StandardProtocolCli.PassThruCommand,
            "--adapter-index", "0", "--protocol-id", "0x8008",
            "--baud", "33333", "--sw-can-hs-baud", "0", "--data", "0102"]),
            "SW_CAN high-speed setting refuses zero bitrate.");

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
