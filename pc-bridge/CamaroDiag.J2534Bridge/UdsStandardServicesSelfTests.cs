namespace CamaroDiag.J2534Bridge;

internal static class UdsStandardServicesSelfTests
{
    internal static int Run()
    {
        var checks = 0;
        void Check(bool result, string name)
        {
            checks++;
            if (!result) throw new InvalidOperationException("ISO 14229 self-test: " + name);
        }
        void Reject<T>(Action action, string name) where T : Exception
        {
            var rejected = false;
            try { action(); }
            catch (T) { rejected = true; }
            Check(rejected, name);
        }

        void Equal(byte[] bytes, string expected, string name) =>
            Check(Convert.ToHexString(bytes) == expected, name);
        Equal(UdsStandardServices.Session(0x02), "1002", "Programming session");
        Equal(UdsStandardServices.Reset(0x01), "1101", "ECU reset");
        Equal(UdsStandardServices.TesterPresent(true), "3E80",
            "Tester present suppress positive");
        Equal(UdsStandardServices.ReadDid(0xF190), "22F190", "Read DID");
        var requestedDids = UdsStandardServices.ReadDids(
            new ushort[] { 0xF190, 0xF187 });
        Equal(requestedDids, "22F190F187",
            "ISO 14229 multi-DID request uses ordered big-endian DIDs.");
        var didReply = UdsStandardServices.DecodeDids(
            requestedDids, [0x62, 0xF1, 0x90, 0x41, 0x42,
                            0xF1, 0x87, 0x12, 0x34, 0x56],
            new[] { 2, 3 });
        Check(didReply.Count == 2 && didReply[0].Did == 0xF190 &&
            didReply[0].Data.SequenceEqual(new byte[] {0x41, 0x42}) &&
            didReply[1].Did == 0xF187 &&
            didReply[1].Data.SequenceEqual(new byte[] {0x12, 0x34, 0x56}),
            "Multi-DID response uses explicit data lengths and preserves order.");
        Reject<ArgumentException>(() =>
            UdsStandardServices.ReadDids(new ushort[] {0xF190, 0xF190}),
            "Duplicate DID requests cannot be silently assigned to one response.");
        Reject<InvalidDataException>(() =>
            UdsStandardServices.DecodeDids(requestedDids,
                [0x62, 0xF1, 0x87, 0x12, 0x34, 0x56,
                 0xF1, 0x90, 0x41, 0x42], new[] {2, 3}),
            "Reordered DID replies are not attributed to the original request.");
        Reject<InvalidDataException>(() =>
            UdsStandardServices.DecodeDids(requestedDids,
                [0x62, 0xF1, 0x90, 0x41, 0x42,
                 0xF1, 0x87, 0x12, 0x34], new[] {2, 3}),
            "Truncated second DID data cannot pass the transport parser.");
        Reject<InvalidDataException>(() =>
            UdsStandardServices.DecodeDids(requestedDids,
                [0x62, 0xF1, 0x90, 0x41, 0x42,
                 0xF1, 0x87, 0x12, 0x34, 0x56, 0xFF],
                 new[] {2, 3}),
            "Unrequested trailing data cannot be accepted as part of DID response.");

        Equal(UdsStandardServices.ReadScalingDid(0xF190),
            "24F190", "ReadScalingDataByIdentifier standard wire payload.");
        Equal(UdsStandardServices.ControlIo(0x1234, [0x03, 0x42]),
            "2F12340342", "InputOutputControlByIdentifier exact DID and OEM bytes.");
        Equal(UdsStandardServices.CommunicationControl(0x01, 0x03),
            "280103", "CommunicationControl subfunction and communicationType.");
        Equal(UdsStandardServices.CommunicationControl(0x81, 0x03, [0x12]),
            "28810312", "CommunicationControl preserves suppress-positive and OEM bytes.");
        Equal(UdsStandardServices.ControlDtcSetting(0x01, [0xAA]),
            "8501AA", "ControlDTCSetting ON with optional OEM data.");
        Equal(UdsStandardServices.ControlDtcSetting(0x82),
            "8582", "ControlDTCSetting suppress positive responses preserved.");
        Equal(UdsStandardServices.ReadPeriodicData(0x02, [0x10, 0x20]),
            "2A021020", "ReadDataByPeriodicIdentifier preserves requested periodic IDs.");
        Reject<ArgumentOutOfRangeException>(() =>
            UdsStandardServices.ControlIo(0x1234, []),
            "IO control cannot omit its control option byte.");
        Reject<ArgumentOutOfRangeException>(() =>
            UdsStandardServices.ControlDtcSetting(0x00),
            "DTC setting requires a standard control subfunction.");
        Reject<ArgumentOutOfRangeException>(() =>
            UdsStandardServices.CommunicationControl(0x07, 0x03),
            "CommunicationControl unknown subfunction rejected.");
        Reject<ArgumentOutOfRangeException>(() =>
            UdsStandardServices.ReadPeriodicData(0x02, []),
            "Periodic read requires a periodic DID.");

        Equal(UdsStandardServices.WriteDid(0xF190, [0x43, 0x44]),
            "2EF1904344", "Write DID full wire bytes");
        Equal(UdsStandardServices.ReadMemory(0x80001000, 0x20),
            "23448000100000000020", "Read MemoryByAddress ALFI");
        Equal(UdsStandardServices.WriteMemory(0x80001000, [0xCA, 0xFE]),
            "3D448000100000000002CAFE", "Write MemoryByAddress ALFI");
        Equal(UdsStandardServices.RequestDownload(0x80001000, 0x20),
            "3400448000100000000020", "RequestDownload");
        Equal(UdsStandardServices.RequestUpload(0x80001000, 0x20),
            "3500448000100000000020", "RequestUpload");
        Equal(UdsStandardServices.RequestUpload(
                0x1234UL, 0x45UL, 2, 1),
            "350012123445", "UDS upload ALFI 0x12 uses 2-byte address and 1-byte length.");
        Equal(UdsStandardServices.RequestDownload(
                0x1122334455667788UL, 0x100UL, 8, 2),
            "34002811223344556677880100",
            "UDS 64-bit address and 16-bit size respect ALFI 0x28.");
        Reject<ArgumentOutOfRangeException>(() =>
            UdsStandardServices.RequestUpload(
                0x10000UL, 0x20UL, 2, 1),
            "UDS address overflow cannot be silently truncated by ALFI.");
        Reject<ArgumentOutOfRangeException>(() =>
            UdsStandardServices.RequestDownload(
                0x1234UL, 0x100UL, 2, 1),
            "UDS length exceeding declared one-byte field rejected.");
        Reject<ArgumentOutOfRangeException>(() =>
            UdsStandardServices.RequestDownload(
                0x1234UL, 0x20UL, 9, 1),
            "UDS ALFI address width greater than eight rejected.");
        var downloadLimit = UdsStandardServices.DecodeTransferParameters(
            0x34, [0x74, 0x20, 0x10, 0x02]);
        Check(downloadLimit.MaxNumberOfBlockLength == 4098 &&
            downloadLimit.LengthFieldBytes == 2 &&
            downloadLimit.MaxDataBytes == 4096,
            "UDS RequestDownload 0x74 interprets LFI big-endian block capacity.");
        var uploadLimit = UdsStandardServices.DecodeTransferParameters(
            0x35, [0x75, 0x10, 0x81]);
        Check(uploadLimit.MaxNumberOfBlockLength == 129 &&
            uploadLimit.MaxDataBytes == 127,
            "UDS RequestUpload 0x75 uses same negotiated block-width semantics.");
        Reject<InvalidDataException>(() =>
            UdsStandardServices.DecodeTransferParameters(
                0x34, [0x75, 0x10, 0x81]),
            "Upload reply cannot be attributed to a pending download.");
        Reject<InvalidDataException>(() =>
            UdsStandardServices.DecodeTransferParameters(
                0x34, [0x74, 0x21, 0x10, 0x02]),
            "Reserved low nibble in UDS lengthFormatIdentifier rejected.");
        Reject<InvalidDataException>(() =>
            UdsStandardServices.DecodeTransferParameters(
                0x34, [0x74, 0x20, 0x10]),
            "Truncated maxNumberOfBlockLength cannot initiate block transfer.");
        Reject<InvalidDataException>(() =>
            UdsStandardServices.DecodeTransferParameters(
                0x34, [0x74, 0x10, 0x02]),
            "TransferData maximum must exceed its SID/counter overhead.");
        Check(UdsStandardServices.RequireResponse(
            [0x34, 0x00, 0x44, 0x80, 0, 0, 0,
             0, 0, 0, 0x20], [0x74, 0x20, 0x10, 0x02])
                .SequenceEqual(new byte[] {0x74, 0x20, 0x10, 0x02}),
            "Shared UDS response correlation requires valid block-size negotiation.");
        Reject<InvalidDataException>(() =>
            UdsStandardServices.RequireResponse(
                [0x35, 0x00, 0x44, 0x80, 0, 0, 0, 0, 0, 0, 0x20],
                [0x75, 0x20, 0x10]),
            "RequestUpload response without full negotiated size cannot pass.");


        Equal(UdsStandardServices.TransferData(3, [0x10, 0x20]),
            "36031020", "TransferData block counter & payload");
        Equal(UdsStandardServices.TransferExit([0xAA, 0xBB]),
            "37AABB", "RequestTransferExit optional parameters");
        Equal(UdsStandardServices.Routine(1, 0xFF00, [0xAB]),
            "3101FF00AB", "StartRoutine and OEM routine ID");
        Equal(UdsStandardServices.SecurityAccess(1, []),
            "2701", "Request security seed");
        Equal(UdsStandardServices.SecurityAccess(2, [1, 2, 3]),
            "2702010203", "Send OEM key bytes without invented algorithm");
        Equal(UdsStandardServices.ClearDtc(0xFFFFFF),
            "14FFFFFF", "Clear DTC group");
        Equal(UdsStandardServices.ReadDtc(0x02, [0xFF]),
            "1902FF", "Read DTC by mask");
        Equal(ObdOnUdsServices.ReadProtocolIdentification(),
            "22F810", "SAE J1979-2 standard F810 protocol detection query.");
        var obdProtocol = ObdOnUdsServices.DecodeProtocolIdentification(
            [0x62, 0xF8, 0x10, 0x01]);
        Check(obdProtocol.Value == 1 && obdProtocol.IsObdOnUds,
            "F810 value 1 identifies a compliant OBDonUDS ECU.");
        Check(!ObdOnUdsServices.DecodeProtocolIdentification(
            [0x62, 0xF8, 0x10, 0x00]).IsObdOnUds,
            "Unrecognized protocol value never claims OBDonUDS support.");
        Reject<InvalidDataException>(() =>
            ObdOnUdsServices.DecodeProtocolIdentification(
                [0x62, 0xF8, 0x11, 0x01]),
            "Protocol identification from another DID is not accepted.");
        Reject<InvalidDataException>(() =>
            ObdOnUdsServices.DecodeProtocolIdentification(
                [0x62, 0xF8, 0x10]),
            "Truncated F810 protocol identification is rejected.");

        Check(ObdOnUdsServices.ClassifyDid(0xF40C) ==
                ObdOnUdsServices.DataGroup.CurrentPowertrain &&
            ObdOnUdsServices.ClassifyDid(0xF600) ==
                ObdOnUdsServices.DataGroup.LegacyMonitor &&
            ObdOnUdsServices.ClassifyDid(0xF810) ==
                ObdOnUdsServices.DataGroup.VehicleInformation &&
            ObdOnUdsServices.ClassifyDid(0xF190) ==
                ObdOnUdsServices.DataGroup.NotLegislated,
            "OBDonUDS differentiates current, legacy-monitor and vehicle DID groups.");
        Equal(ObdOnUdsServices.ReadObdDid(0xF600),
            "22F600", "OBDonUDS legacy monitor DID still uses ISO 14229 ReadDID.");
        Equal(ObdOnUdsServices.ReadObdDid(0xF810),
            "22F810", "OBDonUDS protocol info DID is available through named CLI.");
        Reject<ArgumentOutOfRangeException>(() =>
            ObdOnUdsServices.ReadObdDid(0xF190),
            "OBDonUDS named path does not mislabel generic OEM/ISO identifier.");
        Equal(ObdOnUdsServices.ReadEmissionsData(0xF40C),
            "22F40C", "OBDonUDS current emissions DID maps to ISO 14229 0x22.");
        Equal(ObdOnUdsServices.ReadEmissionsData(0xF701),
            "22F701", "OBDonUDS additional current-data DID range.");
        Check(ObdOnUdsServices.IsEmissionsDataDid(0xF5FF) &&
            !ObdOnUdsServices.IsEmissionsDataDid(0xF600),
            "OBDonUDS helper distinguishes standardized current-data ranges.");
        Reject<ArgumentOutOfRangeException>(() =>
            ObdOnUdsServices.ReadEmissionsData(0xF190),
            "OBDonUDS convenience DID scope does not mislabel OEM DID.");
        Check(ObdOnUdsServices.DecodeReadDid(0xF40C,
            [0x62, 0xF4, 0x0C, 0x12, 0x34])
                .SequenceEqual(new byte[] {0x12, 0x34}),
            "OBDonUDS DID response preserves uninterpreted emissions bytes.");
        Reject<InvalidDataException>(() =>
            ObdOnUdsServices.DecodeReadDid(0xF40C,
                [0x62, 0xF4, 0x0D, 0x12]),
            "OBDonUDS DID response cannot be confused with another DID.");

        Check(UdsStandardServices.RequireResponse(
                [0x19, 0x42, 0x33, 0xFF, 0x08],
                [0x59, 0x42, 0x33, 0xAF, 0x80, 0x04]).Length == 6,
            "Correlated WWH emissions DTC response accepts exact group 0x33.");
        Reject<InvalidDataException>(() =>
            UdsStandardServices.RequireResponse(
                [0x19, 0x42, 0x33, 0xFF, 0x08],
                [0x59, 0x42, 0xD0, 0xAF, 0x80, 0x04]),
            "UDS must not attribute a safety-system response to emissions DTCs.");
        Reject<InvalidDataException>(() =>
            UdsStandardServices.RequireResponse(
                [0x19, 0x55, 0x33],
                [0x59, 0x55, 0xD0, 0xFF, 0x04]),
            "Permanent WWH DTC group must match the requested group.");
        Check(UdsStandardServices.RequireResponse(
                [0x19, 0x56, 0x33, 0x06],
                [0x59, 0x56, 0x33, 0xFF, 0x04, 0x06]).Length == 6,
            "WWH DTC readiness-group ID is echoed at its specified offset.");
        Reject<InvalidDataException>(() =>
            UdsStandardServices.RequireResponse(
                [0x19, 0x56, 0x33, 0x06],
                [0x59, 0x56, 0x33, 0xFF, 0x04, 0x07]),
            "Reply for a different readiness group cannot pass UDS validation.");

        Equal(ObdOnUdsServices.ReadWwhEmissionsDtcs(0x08),
            "194233FF08", "OBDonUDS confirmed emission DTCs use WWH 0x42.");
        Equal(ObdOnUdsServices.ReadWwhEmissionsDtcs(0x04, 0x80),
            "1942338004", "WWH DTC requests retain explicit severity and pending masks.");
        Equal(ObdOnUdsServices.ReadDtcExtendedData(0x123456, 0xFE),
            "1906123456FE", "UDS 19/06 reads all regulated emissions extended records.");
        Equal(ObdOnUdsServices.ReadDtcExtendedData(0x123456, 0x90),
            "190612345690", "UDS 19/06 can select a legislated extended record.");
        Reject<ArgumentOutOfRangeException>(() =>
            ObdOnUdsServices.ReadDtcExtendedData(0x123456, 0),
            "Extended-data record zero is reserved.");
        var extendedData = ObdOnUdsServices.DecodeDtcExtendedData(0x123456,
            [0x59, 0x06, 0x12, 0x34, 0x56, 0x08,
             0x90, 0xAB, 0xCD]);
        Check(extendedData.Dtc == 0x123456 &&
            extendedData.Status == 0x08 &&
            extendedData.RawExtendedDataRecords.SequenceEqual(
                new byte[] {0x90, 0xAB, 0xCD}),
            "UDS extended DTC records preserve their actual raw bytes.");
        Reject<InvalidDataException>(() =>
            ObdOnUdsServices.DecodeDtcExtendedData(0x123456,
                [0x59, 0x06, 0x12, 0x34, 0x57, 0x08]),
            "Extended DTC reply cannot belong to a different fault.");
        Reject<InvalidDataException>(() =>
            UdsStandardServices.RequireResponse(
                [0x19, 0x06, 0x12, 0x34, 0x56, 0xFE],
                [0x59, 0x06, 0x12, 0x34, 0x57, 0x08]),
            "Shared UDS correlation rejects stale 0x19/0x06 DTC response.");

        Equal(ObdOnUdsServices.ReadDtcSnapshot(0x123456, 0x01),
            "190412345601", "UDS snapshot reads selected 24-bit DTC and record 1.");
        Equal(ObdOnUdsServices.ReadDtcSnapshot(0xABCDEF, 0xFF),
            "1904ABCDEFFF", "UDS DTC snapshot can request all records.");
        Reject<ArgumentOutOfRangeException>(() =>
            ObdOnUdsServices.ReadDtcSnapshot(0x1000000, 1),
            "UDS snapshot rejects out-of-range DTC number.");
        Reject<ArgumentOutOfRangeException>(() =>
            ObdOnUdsServices.ReadDtcSnapshot(0x123456, 0),
            "UDS snapshot rejects legislatively reserved record zero.");
        var snapshot = ObdOnUdsServices.DecodeDtcSnapshot(0x123456,
            [0x59, 0x04, 0x12, 0x34, 0x56, 0x08,
             0x01, 0x01, 0xF4, 0x0C, 0x14, 0x2A]);
        Check(snapshot.Dtc == 0x123456 && snapshot.Status == 0x08 &&
            snapshot.RawSnapshotRecords.SequenceEqual(
                new byte[] {0x01, 0x01, 0xF4, 0x0C, 0x14, 0x2A}),
            "Snapshot preserves variable-length DID records without guessing scaling.");
        Reject<InvalidDataException>(() =>
            ObdOnUdsServices.DecodeDtcSnapshot(0x123456,
                [0x59, 0x04, 0x12, 0x34, 0x57, 0x08]),
            "Snapshot cannot silently substitute another 24-bit DTC.");
        Reject<InvalidDataException>(() =>
            UdsStandardServices.RequireResponse(
                [0x19, 0x04, 0x12, 0x34, 0x56, 0x01],
                [0x59, 0x04, 0x12, 0x34, 0x56, 0x08, 0x02, 0x00]),
            "UDS snapshot record selector rejects a response from another record.");
        Check(UdsStandardServices.RequireResponse(
                [0x19, 0x04, 0x12, 0x34, 0x56, 0xFF],
                [0x59, 0x04, 0x12, 0x34, 0x56, 0x08, 0x02, 0x00]).Length == 8,
            "UDS snapshot FF all-record selector accepts arbitrary returned records.");
        Reject<InvalidDataException>(() =>
            UdsStandardServices.RequireResponse(
                [0x19, 0x06, 0x12, 0x34, 0x56, 0x90],
                [0x59, 0x06, 0x12, 0x34, 0x56, 0x08, 0x91, 0x00]),
            "UDS extended-data specific record cannot accept another record ID.");
        Check(UdsStandardServices.RequireResponse(
                [0x19, 0x06, 0x12, 0x34, 0x56, 0xFE],
                [0x59, 0x06, 0x12, 0x34, 0x56, 0x08, 0x90, 0x00]).Length == 8,
            "UDS FE all-regulated selector accepts an emitted regulated record.");

        Check(UdsStandardServices.RequireResponse(
                [0x19, 0x04, 0x12, 0x34, 0x56, 0x01],
                [0x59, 0x04, 0x12, 0x34, 0x56, 0x08]).Length == 6,
            "ISO 14229 snapshot response passes matching DTC correlation.");
        Reject<InvalidDataException>(() =>
            UdsStandardServices.RequireResponse(
                [0x19, 0x04, 0x12, 0x34, 0x56, 0x01],
                [0x59, 0x04, 0x12, 0x34, 0x57, 0x08]),
            "Stale snapshot response for another DTC is rejected.");

        Equal(ObdOnUdsServices.ReadWwhReadinessGroup(0x06),
            "19563306", "OBDonUDS readiness-group query uses UDS 0x19 0x56.");
        var readiness = ObdOnUdsServices.DecodeWwhReadinessGroup(
            [0x59, 0x56, 0x33, 0xFF, 0x04, 0x06,
             0x12, 0x34, 0x56, 0x08]);
        Check(readiness.ReadinessGroup == 0x06 &&
            readiness.DtcReport.FunctionalGroup == 0x33 &&
            readiness.DtcReport.DtcFormatIdentifier == 4 &&
            readiness.DtcReport.Codes.Count == 1 &&
            readiness.DtcReport.Codes[0].Code == 0x123456 &&
            readiness.DtcReport.Codes[0].Status == 0x08,
            "WWH readiness DTC reply decodes 24-bit code and status.");
        Check(ObdOnUdsServices.DecodeWwhReadinessGroup(
            [0x59, 0x56, 0x33, 0xFF, 0x04, 0x06])
                .DtcReport.Codes.Count == 0,
            "WWH readiness report with no DTC does not fabricate a fault.");
        Reject<InvalidDataException>(() =>
            ObdOnUdsServices.DecodeWwhReadinessGroup(
                [0x59, 0x56, 0x33, 0xFF, 0x04, 0x06, 0x12]),
            "Partial WWH readiness DTC record rejected.");
        Equal(ObdOnUdsServices.ReadWwhPermanentEmissionsDtcs(),
            "195533", "Permanent WWH emission DTC request uses 0x55 and group 0x33.");
        var wwh = ObdOnUdsServices.DecodeWwhEmissionsDtcs(
            [0x59, 0x42, 0x33, 0xAF, 0xA0, 0x04,
             0x40, 0x12, 0x34, 0x56, 0x08,
             0x80, 0xAB, 0xCD, 0xEF, 0x04]);
        Check(wwh.FunctionalGroup == 0x33 &&
            wwh.StatusAvailabilityMask == 0xAF &&
            wwh.SeverityAvailabilityMask == 0xA0 &&
            wwh.DtcFormatIdentifier == 4 &&
            wwh.Codes.Count == 2 &&
            wwh.Codes[0].Code == 0x123456 &&
            wwh.Codes[0].Severity == 0x40 &&
            wwh.Codes[0].Status == 0x08 &&
            wwh.Codes[1].Code == 0xABCDEF &&
            wwh.Codes[1].Severity == 0x80,
            "WWH 0x42 positive reply retains severity, status, format and 24-bit DTC.");
        Check(ObdOnUdsServices.DecodeWwhEmissionsDtcs(
            [0x59, 0x42, 0x33, 0xFF, 0xFF, 0x04]).Codes.Count == 0,
            "Empty WWH 0x42 record cannot create fictitious DTCs.");
        var permanent = ObdOnUdsServices.DecodeWwhPermanentEmissionsDtcs(
            [0x59, 0x55, 0x33, 0xFF, 0x04,
             0x12, 0x34, 0x56, 0x08]);
        Check(permanent.Codes.Count == 1 &&
            permanent.Codes[0].Code == 0x123456 &&
            permanent.Codes[0].Status == 0x08 &&
            permanent.Codes[0].Severity is null,
            "WWH 0x55 permanent DTC format omits severity but preserves status.");
        Reject<InvalidDataException>(() =>
            ObdOnUdsServices.DecodeWwhEmissionsDtcs(
                [0x59, 0x42, 0x33, 0xAF, 0xA0, 0x04,
                 0x40, 0x12, 0x34]),
            "WWH 0x42 refuses a truncated five-byte DTC record.");
        Reject<InvalidDataException>(() =>
            ObdOnUdsServices.DecodeWwhPermanentEmissionsDtcs(
                [0x59, 0x55, 0xD0, 0xFF, 0x04]),
            "Emissions mode cannot misinterpret a safety-system group as emissions.");
        Reject<InvalidDataException>(() =>
            ObdOnUdsServices.DecodeWwhPermanentEmissionsDtcs(
                [0x59, 0x55, 0x33, 0xFF, 0x04, 0x12, 0x34]),
            "WWH 0x55 refuses partial permanent DTC records.");

        Equal(ObdOnUdsServices.ReadDtcByStatusMask(0xFF),
            "1902FF", "OBDonUDS DTC status-mask diagnostic request.");
        var obdCodes = ObdOnUdsServices.DecodeDtcByStatusMask(
            [0x59, 0x02, 0xAF, 0x12, 0x34, 0x56, 0x08,
             0xAB, 0xCD, 0xEF, 0x04]);
        Check(obdCodes.AvailabilityMask == 0xAF &&
            obdCodes.Codes.Count == 2 &&
            obdCodes.Codes[0].Code == 0x123456 &&
            obdCodes.Codes[0].Status == 0x08 &&
            obdCodes.Codes[1].Code == 0xABCDEF,
            "UDS DTC status report reads 24-bit DTCs and status separately.");
        Check(ObdOnUdsServices.DecodeDtcByStatusMask(
            [0x59, 0x02, 0xAF]).Codes.Count == 0,
            "Empty OBDonUDS DTC report is not a fabricated fault.");
        Reject<InvalidDataException>(() =>
            ObdOnUdsServices.DecodeDtcByStatusMask(
                [0x59, 0x02, 0xAF, 0x12, 0x34, 0x56]),
            "Partial UDS DTC status record rejected.");

        Check(UdsStandardServices.RequireResponse([0x2E, 0xF1, 0x90, 0x01],
            [0x6E, 0xF1, 0x90]).SequenceEqual(new byte[] {0x6E, 0xF1, 0x90}),
            "UDS write DID positive acknowledgement correlated");
        Check(UdsStandardServices.RequireResponse([0x31, 1, 0xFF, 0x00],
            [0x71, 1, 0xFF, 0x00]).Length == 4,
            "UDS routine response correlated");
        Check(UdsStandardServices.RequireResponse([0x36, 3, 0xFF],
            [0x76, 3]).Length == 2,
            "UDS transfer counter response correlated");
        Check(UdsStandardServices.RequireResponse([0x10, 0x03],
            [0x50, 0x03, 0x00, 0x32, 0x01, 0xF4]).Length == 6,
            "DoIP and CAN share programming-session positive response validation");
        Check(UdsStandardServices.RequireResponse([0x27, 0x01],
            [0x67, 0x01, 0xAB, 0xCD]).Length == 4,
            "Security seed response correlated without generating OEM key");
        Check(UdsStandardServices.RequireResponse([0x19, 0x02, 0xFF],
            [0x59, 0x02, 0xFF]).Length == 3,
            "ReadDTCInformation subfunction echoed in DoIP and CAN");
        Check(UdsStandardServices.RequireResponse([0x3E, 0x00],
            [0x7E, 0x00]).Length == 2,
            "TesterPresent response subfunction echoes requested zero.");
        Check(UdsStandardServices.RequireResponse([0x2F, 0xF1, 0x90, 0x03],
            [0x6F, 0xF1, 0x90, 0x03]).Length == 4,
            "InputOutputControlByIdentifier positive response matches DID.");
        Reject<InvalidDataException>(() =>
            UdsStandardServices.RequireResponse([0x2F, 0xF1, 0x90, 0x03],
                [0x6F, 0xF1, 0x91, 0x03]),
            "IO control cannot accept another DID response.");
        Reject<InvalidDataException>(() =>
            UdsStandardServices.RequireResponse([0x3E, 0x00],
                [0x7E, 0x01]),
            "TesterPresent cannot accept stale subfunction response.");
        Reject<InvalidDataException>(() =>
            UdsStandardServices.RequireResponse([0x85, 0x01],
                [0xC5, 0x02]),
            "ControlDTCSetting response must echo subfunction.");

        Reject<InvalidDataException>(() =>
            UdsStandardServices.RequireResponse([0x27, 0x01],
                [0x67, 0x02, 0xAA]),
            "Mismatched security level cannot satisfy active UDS request");
        Reject<InvalidDataException>(() =>
            UdsStandardServices.RequireResponse([0x10, 0x03],
                [0x50, 0x02]),
            "Mismatched diagnostic session rejected before continuing");
        Reject<InvalidDataException>(() =>
            UdsStandardServices.RequireResponse([0x19, 0x02, 0xFF],
                [0x59, 0x06, 0xFF]),
            "DTC report for another subfunction is rejected");

        Reject<InvalidDataException>(() =>
            UdsStandardServices.RequireResponse([0x2E, 0xF1, 0x90, 0x01],
                [0x6E, 0xF1, 0x91]),
            "Wrong DID is rejected");
        Reject<InvalidDataException>(() =>
            UdsStandardServices.RequireResponse([0x31, 1, 0xFF, 0x00],
                [0x71, 1, 0xFF, 0x01]),
            "Wrong routine identifier rejected");
        Reject<InvalidDataException>(() =>
            UdsStandardServices.RequireResponse([0x36, 3, 0xFF],
                [0x76, 4]),
            "Wrong block counter rejected");
        Reject<IOException>(() =>
            UdsStandardServices.RequireResponse([0x2E, 0xF1, 0x90, 0x01],
                [0x7F, 0x2E, 0x33]),
            "True OEM security access denial honored");
        Reject<InvalidDataException>(() =>
            UdsStandardServices.RequireResponse([0x2E, 0xF1, 0x90],
                [0x7F, 0x22, 0x33]),
            "Misdirected negative response rejected");
        Reject<ArgumentOutOfRangeException>(() =>
            UdsStandardServices.ReadMemory(0xFFFF_FFFF, 2),
            "Address overflow rejected");
        Reject<ArgumentOutOfRangeException>(() =>
            UdsStandardServices.WriteMemory(0x100, new byte[0]),
            "Zero write bytes refused");
        Reject<ArgumentOutOfRangeException>(() =>
            UdsStandardServices.WriteDid(0xF190, new byte[0]),
            "Zero DID write bytes refused");
        Reject<ArgumentOutOfRangeException>(() =>
            UdsStandardServices.Routine(0, 0xFF00, []),
            "Invalid UDS routine control type rejected");
        Reject<ArgumentOutOfRangeException>(() =>
            UdsStandardServices.SecurityAccess(0, []),
            "Invalid security subfunction rejected");
        static byte[] AwaitUds(params byte[][] frames)
        {
            var pending = new Queue<byte[]>(frames);
            return UdsResponseSequence.AwaitFinalAsync(
                [0x22, 0xF1, 0x90],
                (remaining, token) =>
                {
                    token.ThrowIfCancellationRequested();
                    if (remaining <= TimeSpan.Zero ||
                        pending.Count == 0)
                        throw new TimeoutException("No more fake UDS messages.");
                    return Task.FromResult(pending.Dequeue());
                },
                TimeSpan.FromSeconds(1), CancellationToken.None)
                .GetAwaiter().GetResult();
        }
        Check(AwaitUds([0x7F, 0x22, 0x78],
                [0x7F, 0x22, 0x78],
                [0x62, 0xF1, 0x90, 0x41])
                .SequenceEqual(new byte[] {0x62, 0xF1, 0x90, 0x41}),
            "UDS ResponsePending messages never masquerade as final DID data.");
        Reject<TimeoutException>(() =>
            AwaitUds([0x7F, 0x22, 0x78]),
            "UDS receive fails if no final answer follows ResponsePending.");
        Reject<IOException>(() =>
            AwaitUds([0x7F, 0x22, 0x33]),
            "UDS genuine SecurityAccessDenied does not trigger another wait.");
        Reject<InvalidDataException>(() =>
            AwaitUds([0x7F, 0x31, 0x78]),
            "UDS pending for another service is rejected.");
        Reject<InvalidDataException>(() =>
            AwaitUds([0x7F, 0x22]),
            "UDS malformed incomplete NRC is rejected.");
        Reject<InvalidDataException>(() =>
            AwaitUds([0x62, 0xF1, 0x91]),
            "UDS final response must echo the requested DID.");
        return checks;
    }
}
