using System.Buffers.Binary;

namespace CamaroDiag.J2534Bridge;

/// <summary>
/// SAE J1979-2 OBDonUDS convenience codecs built over standard ISO 14229.
/// Only the explicitly described DID/DTC forms are decoded here; other UDS
/// services remain available via UdsStandardServices and the same transport.
/// No ECU, emissions readiness or write capability is inferred from a PDU.
/// </summary>
internal static class ObdOnUdsServices
{
    internal const ushort ProtocolIdentificationDid = 0xF810;

    internal sealed record ProtocolIdentification(byte Value)
    {
        internal bool IsObdOnUds => Value == 1;
    }

    /// <summary>
    /// SAE J1979-2 / ISO 15765-4 detection query. This is a physical
    /// single-ECU query helper; full functional discovery and OBD Classic
    /// fallback are handled separately.
    /// </summary>
    internal static byte[] ReadProtocolIdentification() =>
        UdsStandardServices.ReadDid(ProtocolIdentificationDid);

    internal static ProtocolIdentification DecodeProtocolIdentification(
        ReadOnlySpan<byte> response)
    {
        if (response.Length != 4 || response[0] != 0x62 ||
            response[1] != 0xF8 || response[2] != 0x10)
            throw new InvalidDataException(
                "OBDonUDS protocol identification requires exactly 62 F8 10 xx.");
        return new ProtocolIdentification(response[3]);
    }

    internal sealed record DiagnosticTroubleCode(uint Code, byte Status);
    internal sealed record DtcStatusReport(byte AvailabilityMask,
        IReadOnlyList<DiagnosticTroubleCode> Codes);

    /// <summary>Known OBDonUDS real-time data DID ranges, not an OEM whitelist.</summary>
    internal static bool IsEmissionsDataDid(ushort did) =>
        did is >= 0xF400 and <= 0xF5FF or >= 0xF700 and <= 0xF7FF;

    internal enum DataGroup
    {
        NotLegislated,
        CurrentPowertrain,
        LegacyMonitor,
        VehicleInformation
    }

    /// <summary>
    /// Broad OBD-on-UDS legislated DID grouping. The F600-F6FF group is
    /// legacy monitoring and not necessarily implemented by newer ECUs.
    /// Data scaling and DID availability are defined by SAE J1979DA and
    /// the vehicle, not inferred from this numeric range.
    /// </summary>
    internal static DataGroup ClassifyDid(ushort did) => did switch
    {
        >= 0xF400 and <= 0xF5FF => DataGroup.CurrentPowertrain,
        >= 0xF600 and <= 0xF6FF => DataGroup.LegacyMonitor,
        >= 0xF700 and <= 0xF7FF => DataGroup.CurrentPowertrain,
        >= 0xF800 and <= 0xF8FF => DataGroup.VehicleInformation,
        _ => DataGroup.NotLegislated
    };

    internal static byte[] ReadObdDid(ushort did)
    {
        if (ClassifyDid(did) == DataGroup.NotLegislated)
            throw new ArgumentOutOfRangeException(nameof(did),
                "This DID is not in the supported OBDonUDS data groups; generic ISO 14229 ReadDID remains available.");
        return UdsStandardServices.ReadDid(did);
    }

    internal static byte[] ReadEmissionsData(ushort did)
    {
        if (!IsEmissionsDataDid(did))
            throw new ArgumentOutOfRangeException(nameof(did),
                "The requested DID is outside standardized emissions data groups; use the generic UDS DID service for other values.");
        return UdsStandardServices.ReadDid(did);
    }

    /// <summary>
    /// Parse one DID reply. Other values, including proprietary DIDs, use
    /// the generic UDS service and are not blocked by this helper.
    /// </summary>
    internal static byte[] DecodeReadDid(ushort did, ReadOnlySpan<byte> reply)
    {
        if (reply.Length < 4 || reply[0] != 0x62 ||
            BinaryPrimitives.ReadUInt16BigEndian(reply.Slice(1, 2)) != did)
            throw new InvalidDataException("OBDonUDS ReadDataByIdentifier response does not match the requested DID.");
        return reply.Slice(3).ToArray();
    }

    internal const byte EmissionsFunctionalGroup = 0x33;

    internal sealed record WwhDtc(uint Code, byte Status, byte? Severity);

    internal sealed record WwhDtcReport(byte FunctionalGroup,
        byte StatusAvailabilityMask, byte? SeverityAvailabilityMask,
        byte DtcFormatIdentifier, IReadOnlyList<WwhDtc> Codes);

    /// <summary>
    /// UDS 0x19/0x42 reportWWHOBDDTCByMaskRecord for legislated
    /// emissions functional group 0x33. Status and severity masks
    /// are explicitly supplied; never assume a vehicle's readiness state.
    /// </summary>
    internal static byte[] ReadWwhEmissionsDtcs(
        byte statusMask, byte severityMask = 0xFF) =>
        UdsStandardServices.ReadDtc(0x42,
            [EmissionsFunctionalGroup, severityMask, statusMask]);

    /// <summary>
    /// UDS 0x19/0x55 reportWWHOBDDTCWithPermanentStatus, emissions group.
    /// </summary>
    internal static byte[] ReadWwhPermanentEmissionsDtcs() =>
        UdsStandardServices.ReadDtc(0x55, [EmissionsFunctionalGroup]);

    /// <summary>
    /// ISO 14229 0x19/0x56 reportDTCInformationByDTCReadinessGroupIdentifier.
    /// The readiness ID must be provided by the caller; it is not inferred
    /// from ECU hardware, emissions capabilities or response content.
    /// </summary>
    internal static byte[] ReadWwhReadinessGroup(byte readinessGroup) =>
        UdsStandardServices.ReadDtc(0x56,
            [EmissionsFunctionalGroup, readinessGroup]);

    /// <summary>
    /// ISO 14229 0x19/0x04 DTC snapshot. Snapshot DID payload lengths
    /// require the specific ECU data definitions and are kept raw.
    /// </summary>
    internal static byte[] ReadDtcSnapshot(uint dtc, byte recordNumber)
    {
        if (dtc > 0xFFFFFF || recordNumber == 0)
            throw new ArgumentOutOfRangeException(
                nameof(dtc), "DTC must fit 24 bits and record must be 1..255.");
        return UdsStandardServices.ReadDtc(0x04,
            [(byte)(dtc >> 16), (byte)(dtc >> 8),
             (byte)dtc, recordNumber]);
    }

    /// <summary>
    /// UDS 0x19/0x06 reportDTCExtDataRecordByDTCNumber. Records 0x90-0x9F
    /// are legislated OBD; 0xFE requests all regulated OBD ext records.
    /// Additional OEM/ISO records are accessible by explicit number.
    /// </summary>
    internal static byte[] ReadDtcExtendedData(uint dtc, byte recordNumber)
    {
        if (dtc > 0xFFFFFF || recordNumber == 0)
            throw new ArgumentOutOfRangeException(
                nameof(dtc), "DTC must fit 24 bits and record must be 1..255.");
        return UdsStandardServices.ReadDtc(0x06,
            [(byte)(dtc >> 16), (byte)(dtc >> 8),
             (byte)dtc, recordNumber]);
    }

    internal sealed record ExtendedDataReply(uint Dtc, byte Status,
        byte[] RawExtendedDataRecords);

    internal static ExtendedDataReply DecodeDtcExtendedData(
        uint expectedDtc, ReadOnlySpan<byte> reply)
    {
        if (expectedDtc > 0xFFFFFF || reply.Length < 6 ||
            reply[0] != 0x59 || reply[1] != 0x06)
            throw new InvalidDataException(
                "Invalid UDS DTC extended-data response header.");
        var dtc = (uint)reply[2] << 16 |
            (uint)reply[3] << 8 | reply[4];
        if (dtc != expectedDtc)
            throw new InvalidDataException(
                "DTC extended-data response belongs to a different fault.");
        return new ExtendedDataReply(dtc, reply[5], reply[6..].ToArray());
    }

    internal sealed record SnapshotReply(uint Dtc, byte Status,
        byte[] RawSnapshotRecords);

    internal static SnapshotReply DecodeDtcSnapshot(
        uint expectedDtc, ReadOnlySpan<byte> reply)
    {
        if (expectedDtc > 0xFFFFFF || reply.Length < 6 ||
            reply[0] != 0x59 || reply[1] != 0x04)
            throw new InvalidDataException(
                "Invalid UDS DTC snapshot response header.");
        var dtc = (uint)reply[2] << 16 |
            (uint)reply[3] << 8 | reply[4];
        if (dtc != expectedDtc)
            throw new InvalidDataException(
                "DTC snapshot response belongs to another fault code.");
        return new SnapshotReply(dtc, reply[5],
            reply[6..].ToArray());
    }



    internal sealed record WwhReadinessReport(byte ReadinessGroup,
        WwhDtcReport DtcReport);

    internal static WwhReadinessReport DecodeWwhReadinessGroup(
        ReadOnlySpan<byte> reply)
    {
        if (reply.Length < 6 || reply[0] != 0x59 ||
            reply[1] != 0x56 || reply[2] != EmissionsFunctionalGroup ||
            (reply.Length - 6) % 4 != 0)
            throw new InvalidDataException(
                "Malformed WWH-OBD readiness DTC 0x19 0x56 response.");

        var codes = new List<WwhDtc>((reply.Length - 6) / 4);
        for (var offset = 6; offset < reply.Length; offset += 4)
        {
            var dtc = (uint)reply[offset] << 16 |
                (uint)reply[offset + 1] << 8 | reply[offset + 2];
            codes.Add(new WwhDtc(dtc, reply[offset + 3], null));
        }
        return new WwhReadinessReport(reply[5],
            new WwhDtcReport(reply[2], reply[3], null,
                reply[4], codes));
    }

    internal static WwhDtcReport DecodeWwhEmissionsDtcs(
        ReadOnlySpan<byte> reply)
    {
        if (reply.Length < 6 || reply[0] != 0x59 ||
            reply[1] != 0x42 || reply[2] != EmissionsFunctionalGroup ||
            (reply.Length - 6) % 5 != 0)
            throw new InvalidDataException(
                "Malformed WWH-OBD 0x19 0x42 emissions DTC reply.");

        var codes = new List<WwhDtc>((reply.Length - 6) / 5);
        for (var offset = 6; offset < reply.Length; offset += 5)
        {
            var dtc = (uint)reply[offset + 1] << 16 |
                (uint)reply[offset + 2] << 8 | reply[offset + 3];
            codes.Add(new WwhDtc(dtc, reply[offset + 4], reply[offset]));
        }
        return new WwhDtcReport(reply[2], reply[3], reply[4],
            reply[5], codes);
    }

    internal static WwhDtcReport DecodeWwhPermanentEmissionsDtcs(
        ReadOnlySpan<byte> reply)
    {
        if (reply.Length < 5 || reply[0] != 0x59 ||
            reply[1] != 0x55 || reply[2] != EmissionsFunctionalGroup ||
            (reply.Length - 5) % 4 != 0)
            throw new InvalidDataException(
                "Malformed WWH-OBD 0x19 0x55 permanent DTC reply.");

        var codes = new List<WwhDtc>((reply.Length - 5) / 4);
        for (var offset = 5; offset < reply.Length; offset += 4)
        {
            var dtc = (uint)reply[offset] << 16 |
                (uint)reply[offset + 1] << 8 | reply[offset + 2];
            codes.Add(new WwhDtc(dtc, reply[offset + 3], null));
        }
        return new WwhDtcReport(reply[2], reply[3], null,
            reply[4], codes);
    }

    internal sealed record DtcCountReport(byte StatusAvailabilityMask,
        byte DtcFormatIdentifier, ushort Count);

    /// <summary>
    /// ISO 14229-1 0x19/0x01 reportNumberOfDTCByStatusMask:
    /// request 19 01 [mask], response 59 01 [availability] [format]
    /// [count MSB] [count LSB]. Count is not an ECU-readiness verdict.
    /// </summary>
    internal static byte[] ReadDtcCountByStatusMask(byte statusMask) =>
        UdsStandardServices.ReadDtc(0x01, [statusMask]);

    internal static DtcCountReport DecodeDtcCountByStatusMask(
        ReadOnlySpan<byte> reply)
    {
        if (reply.Length != 6 || reply[0] != 0x59 ||
            reply[1] != 0x01)
            throw new InvalidDataException(
                "UDS DTC count must be 59 01 availability format countHi countLo.");
        var count = BinaryPrimitives.ReadUInt16BigEndian(reply.Slice(4, 2));
        return new(reply[2], reply[3], count);
    }

    internal sealed record SeverityRecord(uint Dtc, byte Severity, byte FunctionalUnit, byte Status);
    internal sealed record SeverityReport(byte Subfunction, byte AvailabilityMask,
        IReadOnlyList<SeverityRecord> Records, ushort? Count = null, byte? FormatIdentifier = null);

    internal static byte[] ReadSeverity(byte subfunction, byte severityMask, byte statusMask, uint dtc = 0)
    {
        if (subfunction is < 7 or > 9 || dtc > 0xFFFFFF) throw new ArgumentOutOfRangeException(nameof(subfunction));
        return subfunction == 9 ? [0x19, 9, (byte)(dtc >> 16), (byte)(dtc >> 8), (byte)dtc] :
            [0x19, subfunction, severityMask, statusMask];
    }

    internal static SeverityReport DecodeSeverity(ReadOnlySpan<byte> request, ReadOnlySpan<byte> reply)
    {
        if (request.Length < 2 || request[0] != 0x19 || request[1] is < 7 or > 9 ||
            request.Length != (request[1] == 9 ? 5 : 4) || reply.Length < 3 ||
            reply[0] != 0x59 || reply[1] != request[1])
            throw new InvalidDataException("Malformed severity response.");
        var sub = request[1];
        var availability = reply[2];
        if (sub == 7)
        {
            if (reply.Length != 6) throw new InvalidDataException("Invalid severity count response.");
            var count = BinaryPrimitives.ReadUInt16BigEndian(reply[4..]);
            if (count != 0 && (request[2] == 0 || (request[3] & availability) == 0))
                throw new InvalidDataException("Count contradicts requested masks.");
            return new(sub, availability, Array.Empty<SeverityRecord>(), count, reply[3]);
        }
        if ((reply.Length - 3) % 6 != 0 || (sub == 9 && reply.Length is not (3 or 9)))
            throw new InvalidDataException("Malformed severity records.");
        var rows = new List<SeverityRecord>();
        var seen = new HashSet<uint>();
        for (var offset = 3; offset < reply.Length; offset += 6)
        {
            var dtc = ((uint)reply[offset+2] << 16) | ((uint)reply[offset+3] << 8) | reply[offset+4];
            var row = new SeverityRecord(dtc, reply[offset], reply[offset+1], reply[offset+5]);
            if (!seen.Add(dtc) || (row.Status & ~availability) != 0)
                throw new InvalidDataException("Duplicate DTC or unavailable status bit.");
            if (sub == 9 && !reply.Slice(offset+2, 3).SequenceEqual(request.Slice(2, 3)))
                throw new InvalidDataException("Severity record belongs to another DTC.");
            if (sub == 8 && ((row.Severity & request[2]) == 0 || (row.Status & request[3] & availability) == 0))
                throw new InvalidDataException("Severity record contradicts requested masks.");
            rows.Add(row);
        }
        return new(sub, availability, rows);
    }

    internal sealed record FaultCounter(uint Dtc, sbyte Counter);
    internal static byte[] ReadFaultCounters() => UdsStandardServices.ReadDtc(0x14, []);
    internal static IReadOnlyList<FaultCounter> DecodeFaultCounters(ReadOnlySpan<byte> reply)
    {
        if (reply.Length < 2 || reply[0] != 0x59 || reply[1] != 0x14 || (reply.Length - 2) % 4 != 0)
            throw new InvalidDataException("Malformed fault counter report.");
        var rows = new List<FaultCounter>();
        var seen = new HashSet<uint>();
        for (var offset = 2; offset < reply.Length; offset += 4)
        {
            var dtc = ((uint)reply[offset] << 16) | ((uint)reply[offset+1] << 8) | reply[offset+2];
            if (!seen.Add(dtc)) throw new InvalidDataException("Duplicate fault counter DTC.");
            rows.Add(new(dtc, unchecked((sbyte)reply[offset+3])));
        }
        return rows;
    }

    internal static byte[] ReadHistoricalDtc(byte subfunction)
    {
        if (subfunction is < 0x0B or > 0x0E) throw new ArgumentOutOfRangeException(nameof(subfunction));
        return UdsStandardServices.ReadDtc(subfunction, []);
    }

    internal static DtcStatusReport DecodeHistoricalDtc(byte subfunction, ReadOnlySpan<byte> reply)
    {
        if (subfunction is < 0x0B or > 0x0E || reply.Length is not (3 or 7) ||
            reply[0] != 0x59 || reply[1] != subfunction)
            throw new InvalidDataException("Malformed historical DTC report.");
        if (reply.Length == 3) return new(reply[2], Array.Empty<DiagnosticTroubleCode>());
        if ((reply[6] & ~reply[2]) != 0)
            throw new InvalidDataException("Historical DTC uses unavailable status bits.");
        var code = ((uint)reply[3] << 16) | ((uint)reply[4] << 8) | reply[5];
        return new(reply[2], new[] { new DiagnosticTroubleCode(code, reply[6]) });
    }

    internal static byte[] ReadSupportedDtcs() => UdsStandardServices.ReadDtc(0x0A, []);
    internal static byte[] ReadPermanentDtcs() => UdsStandardServices.ReadDtc(0x15, []);
    internal static byte[] ReadSnapshotIndex() => UdsStandardServices.ReadDtc(0x03, []);

    internal static DtcStatusReport DecodeSupportedDtcs(ReadOnlySpan<byte> reply) => DecodeDtcInventory(0x0A, reply);
    internal static DtcStatusReport DecodePermanentDtcs(ReadOnlySpan<byte> reply) => DecodeDtcInventory(0x15, reply);

    private static DtcStatusReport DecodeDtcInventory(byte subfunction, ReadOnlySpan<byte> reply)
    {
        if (reply.Length < 3 || reply[0] != 0x59 || reply[1] != subfunction || (reply.Length - 3) % 4 != 0)
            throw new InvalidDataException("Malformed DTC inventory.");
        var codes = new List<DiagnosticTroubleCode>();
        var seen = new HashSet<uint>();
        for (var offset = 3; offset < reply.Length; offset += 4)
        {
            var code = ((uint)reply[offset] << 16) | ((uint)reply[offset + 1] << 8) | reply[offset + 2];
            var status = reply[offset + 3];
            if (!seen.Add(code) || (status & ~reply[2]) != 0)
                throw new InvalidDataException("Duplicate DTC or unavailable status bit.");
            codes.Add(new(code, status));
        }
        return new(reply[2], codes);
    }

    internal sealed record SnapshotReference(uint Dtc, byte Record);
    internal static IReadOnlyList<SnapshotReference> DecodeSnapshotIndex(ReadOnlySpan<byte> reply)
    {
        if (reply.Length < 2 || reply[0] != 0x59 || reply[1] != 0x03 || (reply.Length - 2) % 4 != 0)
            throw new InvalidDataException("Malformed snapshot index.");
        var rows = new List<SnapshotReference>();
        var seen = new HashSet<SnapshotReference>();
        for (var offset = 2; offset < reply.Length; offset += 4)
        {
            var row = new SnapshotReference(((uint)reply[offset] << 16) |
                ((uint)reply[offset + 1] << 8) | reply[offset + 2], reply[offset + 3]);
            if (!seen.Add(row)) throw new InvalidDataException("Duplicate snapshot reference.");
            rows.Add(row);
        }
        return rows;
    }

    internal sealed record ExtendedRecordEntry(uint Dtc, byte Status, byte[] Data);
    internal sealed record ExtendedRecordReport(byte Record, byte? Availability, IReadOnlyList<ExtendedRecordEntry> Entries);

    internal static byte[] ReadExtendedRecord(byte record, int? size)
    {
        if (record is 0 or > 0xEF || size is < 1 or > 4090)
            throw new ArgumentException("Expected specific record 01..EF and documented size 1..4090.");
        return UdsStandardServices.ReadDtc(size is null ? (byte)0x1A : (byte)0x16, [record]);
    }

    internal static ExtendedRecordReport DecodeExtendedRecord(byte record, int? size, ReadOnlySpan<byte> reply)
    {
        var request = ReadExtendedRecord(record, size);
        _ = UdsStandardServices.RequireResponse(request, reply);
        int start = size is null ? 4 : 3, width = 4 + (size ?? 0);
        if ((reply.Length - start) % width != 0)
            throw new InvalidDataException("Extended data does not match documented length.");
        byte? available = size is null ? reply[2] : null;
        var seen = new HashSet<uint>();
        var rows = new List<ExtendedRecordEntry>();
        for (var offset = start; offset < reply.Length; offset += width)
        {
            uint dtc = ((uint)reply[offset] << 16) | ((uint)reply[offset + 1] << 8) | reply[offset + 2];
            var status = reply[offset + 3];
            if (!seen.Add(dtc) || (available.HasValue && (status & ~available.Value) != 0))
                throw new InvalidDataException("Duplicate DTC or unavailable status bit.");
            rows.Add(new(dtc, status, reply.Slice(offset + 4, width - 4).ToArray()));
        }
        return new(record, available, rows);
    }

    internal sealed record UserMemoryReport(byte Memory, DtcStatusReport? List, uint? Dtc, byte? Status, byte[]? RawRecords);

    internal static byte[] ReadUserMemory(byte sub, byte memory, byte mask, uint dtc = 0, byte record = 1)
    {
        if (sub == 0x17) return UdsStandardServices.ReadDtc(sub, [mask, memory]);
        if (sub is not (0x18 or 0x19) || dtc > 0xFFFFFF || record == 0 ||
            (sub == 0x19 && record is >= 0xF0 and <= 0xFD))
            throw new ArgumentException("Invalid user-memory DTC selector.");
        return UdsStandardServices.ReadDtc(sub, [(byte)(dtc >> 16), (byte)(dtc >> 8),
            (byte)dtc, record, memory]);
    }

    internal static UserMemoryReport DecodeUserMemory(ReadOnlySpan<byte> request, ReadOnlySpan<byte> reply)
    {
        if (request.Length < 2 || request[0] != 0x19 || request[1] is < 0x17 or > 0x19)
            throw new ArgumentException("Invalid user-memory request.");
        _ = UdsStandardServices.RequireResponse(request, reply);
        var memory = request[^1];
        if (request[1] == 0x17)
        {
            byte[] normalized = [0x59, 0x02, .. reply[3..].ToArray()];
            return new(memory, DecodeDtcByStatusMask(request[2], normalized), null, null, null);
        }
        uint dtc = ((uint)reply[3] << 16) | ((uint)reply[4] << 8) | reply[5];
        return new(memory, null, dtc, reply[6], reply[7..].ToArray());
    }

    internal static byte[] ReadDtcByStatusMask(byte statusMask) =>
        UdsStandardServices.ReadDtc(0x02, [statusMask]);

    /// <summary>
    /// Correlate a 0x19/0x02 reply with the exact status mask sent by this
    /// client. ISO 14229 matches any selected status bit supported by the
    /// ECU; codes with no matching bit are not evidence for the requested
    /// query. This does not infer DTC semantics or approve ECU operations.
    /// </summary>
    internal static DtcStatusReport DecodeDtcByStatusMask(
        byte requestedStatusMask, ReadOnlySpan<byte> reply)
    {
        var report = DecodeDtcByStatusMask(reply);
        var effectiveMask = (byte)(requestedStatusMask & report.AvailabilityMask);
        var seen = new HashSet<uint>();
        foreach (var code in report.Codes)
        {
            if ((code.Status & effectiveMask) == 0)
                throw new InvalidDataException(
                    "UDS DTC response contains a code outside the requested available status mask.");
            if ((code.Status & ~report.AvailabilityMask) != 0)
                throw new InvalidDataException(
                    "UDS DTC response uses a status bit the ECU reports unavailable.");
            if (!seen.Add(code.Code))
                throw new InvalidDataException(
                    "UDS DTC response repeats the same DTC in one result.");
        }
        return report;
    }


    /// <summary>
    /// ISO 14229 ReadDTCInformation reportDTCByStatusMask positive response:
    /// 0x59 0x02 availability-mask then repeated 3-byte DTC + status.
    /// Do not interpret manufacturer-specific DTC numbers as known faults.
    /// </summary>
    internal static DtcStatusReport DecodeDtcByStatusMask(ReadOnlySpan<byte> reply)
    {
        if (reply.Length < 3 || reply[0] != 0x59 || reply[1] != 0x02 ||
            (reply.Length - 3) % 4 != 0)
            throw new InvalidDataException("Malformed ReadDTCInformation status report.");
        var codes = new List<DiagnosticTroubleCode>();
        for (var offset = 3; offset < reply.Length; offset += 4)
        {
            var code = ((uint)reply[offset] << 16) |
                ((uint)reply[offset + 1] << 8) | reply[offset + 2];
            codes.Add(new DiagnosticTroubleCode(code, reply[offset + 3]));
        }
        return new DtcStatusReport(reply[2], codes);
    }
}
