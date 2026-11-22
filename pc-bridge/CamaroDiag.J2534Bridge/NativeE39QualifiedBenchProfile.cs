using System.Security.Cryptography;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace CamaroDiag.J2534Bridge;

/// <summary>
/// Exact, independently signed bench evidence. A user-supplied JSON boolean
/// or a self-generated signing key is not a hardware qualification.
/// </summary>
internal static class NativeE39QualifiedBenchProfile
{
    // Intentionally empty: no independent physical qualification authority
    // or approved hardware artifacts have been established for this release.
    private static readonly IReadOnlyDictionary<string, byte[]> TrustedKeys =
        new Dictionary<string, byte[]>(StringComparer.Ordinal);

    internal sealed record Accepted(string Family, string ProfileSha256,
        string BoardEvidenceSha256, string RecoveryEvidenceSha256,
        J2534MemoryReadScope Scope, NativeE39PhysicalAdmission.QualifiedChannel Channel,
        NativeE39BenchWorkflow.Policy Policy);

    private static readonly string[] Fields =
    [
        "schema", "keyId", "family", "expectedVin", "expectedOsId", "driverSha256",
        "registryView", "requestCanId", "responseCanId", "protocolNumber",
        "stockReadBytes", "stockReadSession", "minimumSupplyMillivolts",
        "maximumSupplyMillivolts", "kernelSha256", "maximumIsoTpPayloadBytes",
        "maximumReadBytes", "maximumWriteBytes", "boardEvidenceSha256",
        "recoveryEvidenceSha256"
    ];

    internal static Accepted Load(string profilePath, string signaturePath)
    {
        if (TrustedKeys.Count == 0)
            throw new UnauthorizedAccessException("No independently qualified ECU programming authority is installed.");
        return Verify(ReadBounded(profilePath, 16384), ReadBounded(signaturePath, 64), TrustedKeys);
    }

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

    // Trust injection is only used by the hardware-free self-tests. The local
    // production CLI calls Load, never this method with external keys.
    internal static Accepted Verify(byte[] payload, byte[] signature,
        IReadOnlyDictionary<string, byte[]> keys)
    {
        if (payload.Length is < 1 or > 16384 || signature.Length != 64)
            throw new InvalidDataException("Invalid qualification payload or signature length.");
        payload = payload.ToArray();
        signature = signature.ToArray();
        using var doc = JsonDocument.Parse(payload);
        var root = doc.RootElement;
        var expected = new HashSet<string>(Fields, StringComparer.Ordinal);
        if (root.ValueKind != JsonValueKind.Object)
            throw new InvalidDataException("Qualification must be a JSON object.");
        foreach (var property in root.EnumerateObject())
            if (!expected.Remove(property.Name))
                throw new InvalidDataException("Unknown or duplicate qualification field: " + property.Name);
        if (expected.Count != 0 || root.GetProperty("schema").GetInt32() != 1)
            throw new InvalidDataException("Incomplete or unsupported qualification schema.");
        string Value(string name) => root.GetProperty(name).GetString()
            ?? throw new InvalidDataException("Missing " + name);
        string Digest(string name)
        {
            var value = Value(name);
            if (!Regex.IsMatch(value, "^[0-9A-F]{64}$"))
                throw new InvalidDataException("Invalid digest: " + name);
            return value;
        }
        int Number(string name) => root.GetProperty(name).GetInt32();
        uint Unsigned(string name) => root.GetProperty(name).GetUInt32();
        if (!keys.TryGetValue(Value("keyId"), out var key))
            throw new UnauthorizedAccessException("Unknown qualification signing authority.");
        using var verifier = ECDsa.Create();
        verifier.ImportSubjectPublicKeyInfo(key, out var consumed);
        if (consumed != key.Length || verifier.KeySize != 256 ||
            verifier.ExportParameters(false).Curve.Oid.Value != "1.2.840.10045.3.1.7" ||
            !verifier.VerifyData(payload, signature, HashAlgorithmName.SHA256,
                DSASignatureFormat.IeeeP1363FixedFieldConcatenation))
            throw new UnauthorizedAccessException("Qualification signature is invalid.");
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
        var channel = new NativeE39PhysicalAdmission.QualifiedChannel(Digest("driverSha256"),
            registry, scope.RequestCanId, scope.ResponseCanId, protocol, scope.MaxReadBytes, scope.Session);
        var session = new NativeE39ProgrammingSession.Qualification(scope.ExpectedVin, os,
            true, true, true, minimum, maximum);
        return new Accepted(family, Convert.ToHexString(SHA256.HashData(payload)),
            Digest("boardEvidenceSha256"), Digest("recoveryEvidenceSha256"), scope, channel,
            new NativeE39BenchWorkflow.Policy(session, Digest("kernelSha256"), payloadLimit,
                readLimit, writeLimit));
    }
}
