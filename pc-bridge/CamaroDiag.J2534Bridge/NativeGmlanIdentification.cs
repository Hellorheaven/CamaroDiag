using System.Text;
using System.Text.RegularExpressions;

namespace CamaroDiag.J2534Bridge;

/// <summary>
/// Read-only GM GMLAN ECU identification for the native E39/E39A workflow.
/// Unlike standard UDS ReadDataByIdentifier (0x22), legacy GM CAN uses
/// service 0x1A with one-byte identifiers, returning 0x5A.
/// </summary>
internal static class NativeGmlanIdentification
{
    internal const byte VinDid = 0x90;
    internal const byte OsModuleDid = 0xC1;
    internal const byte OsFallbackDid = 0xC9;

    /** Unsupported legacy identity, distinct from an inconsistent or denied ECU response. */
    internal sealed class IdentityUnavailableException(string reason) : InvalidDataException(reason);

    internal sealed record Identity(
        string Vin,
        uint OperatingSystemId,
        byte OsDid,
        bool PhysicalRead,
        string Status)
    {
        // The OSID alone does not prove E39 vs E39A or exact firmware family.
        internal bool FlashHardwareQualified => false;
    }

    internal static byte[] BuildRequest(byte did)
    {
        if (did is not (VinDid or OsModuleDid or OsFallbackDid))
            throw new UnauthorizedAccessException("GMLAN identity request must use a named read-only DID.");
        return [0x1A, did];
    }

    /// <returns>null for clearly unsupported DIDs (NRC 11/12/31).</returns>
    internal static byte[]? DecodeResponse(byte did, byte[] reply)
    {
        ArgumentNullException.ThrowIfNull(reply);
        if (reply.Length == 3 && reply[0] == 0x7F && reply[1] == 0x1A)
        {
            if (reply[2] is 0x11 or 0x12 or 0x31) return null;
            throw new IOException($"GMLAN identity read denied: NRC 0x{reply[2]:X2}.");
        }
        if (reply.Length < 3 || reply[0] != 0x5A || reply[1] != did)
            throw new InvalidDataException("Unexpected GMLAN identity response format.");
        return reply[2..];
    }

    internal static string DecodeVin(byte[] data)
    {
        ArgumentNullException.ThrowIfNull(data);
        var vin = Encoding.ASCII.GetString(data);
        if (data.Length != 17 ||
            !Regex.IsMatch(vin, "^[A-HJ-NPR-Z0-9]{17}$", RegexOptions.CultureInvariant))
            throw new InvalidDataException("GM identity returned malformed 17-character VIN.");
        return vin;
    }

    internal static uint DecodeOsId(byte[] data)
    {
        ArgumentNullException.ThrowIfNull(data);
        if (data.Length != 4)
            throw new InvalidDataException("GMLAN OS identifier must contain four bytes.");
        var osid = ((uint)data[0] << 24) | ((uint)data[1] << 16) |
                   ((uint)data[2] << 8) | data[3];
        // Zero/FFFFFFFF can mean unpopulated software module slots.
        return osid is 0 or uint.MaxValue ? 0 : osid;
    }

    internal static Identity Compose(byte[] vinReply, byte[] c1Reply, byte[] c9Reply,
        bool physicalRead)
    {
        var vin = DecodeVin(DecodeResponse(VinDid, vinReply) ??
            throw new IdentityUnavailableException("GMLAN ECU does not support the legacy VIN identifier."));
        uint osid = 0;
        byte did = 0;
        foreach (var (candidateDid, response) in new[]
        {
            (OsModuleDid, c1Reply), (OsFallbackDid, c9Reply)
        })
        {
            var bytes = DecodeResponse(candidateDid, response);
            if (bytes is null) continue;
            var parsed = DecodeOsId(bytes);
            if (parsed == 0) continue;
            osid = parsed;
            did = candidateDid;
            break;
        }
        if (osid == 0)
            throw new IdentityUnavailableException("GMLAN ECU does not expose a usable legacy OSID.");
        return new Identity(vin, osid, did, physicalRead,
            "Read-only VIN/OSID identity; E39/E39A firmware and recovery are not yet qualified.");
    }
}
