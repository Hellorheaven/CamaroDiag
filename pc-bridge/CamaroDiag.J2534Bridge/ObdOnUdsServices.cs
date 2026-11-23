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
