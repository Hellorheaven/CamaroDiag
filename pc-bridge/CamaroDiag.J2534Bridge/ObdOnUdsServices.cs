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

    internal static byte[] ReadDtcByStatusMask(byte statusMask) =>
        UdsStandardServices.ReadDtc(0x02, [statusMask]);

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
