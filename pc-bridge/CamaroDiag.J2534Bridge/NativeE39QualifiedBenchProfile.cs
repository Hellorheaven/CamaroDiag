using System.Security.Cryptography;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace CamaroDiag.J2534Bridge;

/// <summary>
/// Operator-supplied local E39/E39A bench configuration. This is neither
/// an OEM key nor a CamaroDiag license or independently signed permit.

/// </summary>
internal static class NativeE39QualifiedBenchProfile
{
    internal sealed record Accepted(string Family, string ProfileSha256,
        J2534MemoryReadScope Scope, NativeE39PhysicalAdmission.QualifiedChannel Channel,
        NativeE39BenchWorkflow.Policy Policy);

    private static readonly string[] Fields =
    [
        "schema", "family", "expectedVin", "expectedOsId",
        "registryView", "requestCanId", "responseCanId", "protocolNumber",
        "stockReadBytes", "stockReadSession", "minimumSupplyMillivolts",
        "maximumSupplyMillivolts", "kernelSha256", "maximumIsoTpPayloadBytes",
        "maximumReadBytes", "maximumWriteBytes"
    ];

    internal static Accepted Load(string profilePath) => Parse(ReadBounded(profilePath, 16384));

    internal static byte[] ReadBounded(string path, int maximum)
    {
        using var file = File.OpenRead(path);
        if (file.Length < 1 || file.Length > maximum)
            throw new InvalidDataException("Unexpected qualification artifact length.");
        var bytes = new byte[checked((int)file.Length)];
        file.ReadExactly(bytes);
        if (file.ReadByte() != -1) throw new InvalidDataException("Artifact changed during reading.");
        return bytes;
    }

    internal static Accepted Parse(byte[] payload)
    {
        ArgumentNullException.ThrowIfNull(payload);
        if (payload.Length is < 1 or > 16384)
            throw new InvalidDataException("Invalid bench profile length.");
        payload = payload.ToArray();
        using var doc = JsonDocument.Parse(payload);
        var root = doc.RootElement;
        var expected = new HashSet<string>(Fields, StringComparer.Ordinal);
        if (root.ValueKind != JsonValueKind.Object)
            throw new InvalidDataException("Bench profile must be a JSON object.");
        var hasDriverFingerprint = false;
        foreach (var property in root.EnumerateObject())
        {
            if (property.Name == "driverSha256")
            {
                if (hasDriverFingerprint)
                    throw new InvalidDataException("Duplicate optional driver fingerprint.");
                hasDriverFingerprint = true;
                continue;
            }
            if (!expected.Remove(property.Name))
                throw new InvalidDataException("Unknown or duplicate bench profile field: " + property.Name);
        }
        if (expected.Count != 0 || root.GetProperty("schema").GetInt32() != 1)
            throw new InvalidDataException("Incomplete or unsupported qualification schema.");
        string Value(string name) => root.GetProperty(name).GetString()
            ?? throw new InvalidDataException("Missing " + name);
        string Digest(string name)
        {
            var value = Value(name);
            if (!Regex.IsMatch(value, @"\A[0-9A-Fa-f]{64}\z"))
                throw new InvalidDataException("Invalid digest: " + name);
            return value.ToUpperInvariant();
        }
        int Number(string name) => root.GetProperty(name).GetInt32();
        uint Unsigned(string name) => root.GetProperty(name).GetUInt32();
        var family = Value("family");
        var registry = Value("registryView");
        var protocol = Number("protocolNumber");
        var os = Unsigned("expectedOsId");
        var minimum = Unsigned("minimumSupplyMillivolts");
        var maximum = Unsigned("maximumSupplyMillivolts");
        var payloadLimit = Number("maximumIsoTpPayloadBytes");
        var readLimit = Number("maximumReadBytes");
        var writeLimit = Number("maximumWriteBytes");
        if (family is not ("E39" or "E39A") || registry is not ("Registry32" or "Registry64") ||
            protocol is not (33 or 34) || os == 0 || minimum == 0 || maximum <= minimum ||
            payloadLimit is < 17 or > NativeE39RamLoader.MaxJ2534PayloadBytes ||
            readLimit < 1 || readLimit >= payloadLimit ||
            writeLimit < NativeMpc5566FlashMap.ProgramUnitBytes || writeLimit > payloadLimit - 9 ||
            writeLimit % NativeMpc5566FlashMap.ProgramUnitBytes != 0)
            throw new InvalidDataException("Invalid qualified ECU, voltage or transport limits.");
        var scope = new J2534MemoryReadScope(Value("expectedVin"), Number("requestCanId"),
            Number("responseCanId"), protocol, 0, NativeE39ImageInspector.ImageBytes,
            Number("stockReadBytes"), Number("stockReadSession"));
        var optionalDriverHash = hasDriverFingerprint ? Digest("driverSha256") : "";
        var channel = new NativeE39PhysicalAdmission.QualifiedChannel(optionalDriverHash,
            registry, scope.RequestCanId, scope.ResponseCanId, protocol, scope.MaxReadBytes, scope.Session);
        var session = new NativeE39ProgrammingSession.Qualification(scope.ExpectedVin, os,
            true, true, true, minimum, maximum);
        // Profile content is configuration, never proof of a physically
        // qualified RAM kernel, complete backup or recovery procedure.
        return new Accepted(family, Convert.ToHexString(SHA256.HashData(payload)), scope, channel,
            new NativeE39BenchWorkflow.Policy(session, Digest("kernelSha256"), payloadLimit,
                readLimit, writeLimit));
    }
}
