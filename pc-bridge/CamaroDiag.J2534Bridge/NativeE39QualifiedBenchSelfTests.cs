using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace CamaroDiag.J2534Bridge;

internal static class NativeE39QualifiedBenchSelfTests
{
    internal static int Run()
    {
        int checks = 0;
        void Assert(bool condition, string name)
        {
            checks++;
            if (!condition) throw new InvalidOperationException("Qualification test: " + name);
        }
        bool Rejected(Action operation)
        {
            try { operation(); return false; }
            catch (Exception e) when (e is UnauthorizedAccessException or InvalidDataException or
                ArgumentException or InvalidOperationException or JsonException or CryptographicException)
            { return true; }
        }
        using var key = ECDsa.Create(ECCurve.NamedCurves.nistP256);
        var trust = new Dictionary<string, byte[]> { ["test-only"] = key.ExportSubjectPublicKeyInfo() };
        var fields = new Dictionary<string, object>
        {
            ["schema"] = 1, ["keyId"] = "test-only", ["family"] = "E39",
            ["expectedVin"] = "1G1FA1RX0G0100001", ["expectedOsId"] = 12345U,
            ["driverSha256"] = new string('A', 64), ["registryView"] = "Registry64",
            ["requestCanId"] = 2016, ["responseCanId"] = 2024, ["protocolNumber"] = 33,
            ["stockReadBytes"] = 64, ["stockReadSession"] = 1,
            ["minimumSupplyMillivolts"] = 12000U, ["maximumSupplyMillivolts"] = 14500U,
            ["kernelSha256"] = new string('B', 64), ["maximumIsoTpPayloadBytes"] = 512,
            ["maximumReadBytes"] = 256, ["maximumWriteBytes"] = 128,
            ["boardEvidenceSha256"] = new string('C', 64),
            ["recoveryEvidenceSha256"] = new string('D', 64)
        };
        byte[] Sign(byte[] bytes) => key.SignData(bytes, HashAlgorithmName.SHA256,
            DSASignatureFormat.IeeeP1363FixedFieldConcatenation);
        var valid = JsonSerializer.SerializeToUtf8Bytes(fields);
        var signature = Sign(valid);
        var accepted = NativeE39QualifiedBenchProfile.Verify(valid, signature, trust);
        Assert(accepted.Policy.Session.ExpectedOsId == 12345U &&
            accepted.Scope.Length == NativeE39ImageInspector.ImageBytes &&
            accepted.Scope.StartAddress == 0 && accepted.Channel.RequestCanId == 2016,
            "signed profile binds exact vehicle, channel and full image");
        Assert(Rejected(() => NativeE39QualifiedBenchProfile.Verify(valid, signature,
            new Dictionary<string, byte[]>())), "untrusted key denied");
        Assert(Rejected(() => NativeE39QualifiedBenchProfile.Load("absent.json", "absent.sig")),
            "production has no trusted authority and refuses before file access");
        var altered = valid.ToArray();
        altered[^2] ^= 1;
        Assert(Rejected(() => NativeE39QualifiedBenchProfile.Verify(altered, signature, trust)),
            "modified payload denied");
        var badSignature = signature.ToArray();
        badSignature[0] ^= 1;
        Assert(Rejected(() => NativeE39QualifiedBenchProfile.Verify(valid, badSignature, trust)),
            "modified signature denied");
        foreach (var (field, replacement) in new (string, object)[]
        {
            ("family", "E38"), ("expectedOsId", 0U), ("protocolNumber", 35),
            ("responseCanId", 2016), ("minimumSupplyMillivolts", 15000U),
            ("maximumWriteBytes", 511), ("kernelSha256", "not-a-digest"),
            ("expectedVin", "unknown"), ("stockReadSession", 2)
        })
        {
            var bad = new Dictionary<string, object>(fields) { [field] = replacement };
            var bytes = JsonSerializer.SerializeToUtf8Bytes(bad);
            Assert(Rejected(() => NativeE39QualifiedBenchProfile.Verify(bytes, Sign(bytes), trust)),
                "reject signed invalid " + field);
        }
        var duplicate = Encoding.UTF8.GetBytes("{\"schema\":1," + Encoding.UTF8.GetString(valid)[1..]);
        Assert(Rejected(() => NativeE39QualifiedBenchProfile.Verify(duplicate, Sign(duplicate), trust)),
            "duplicate JSON field denied");
        var unknown = new Dictionary<string, object>(fields) { ["allowUnsigned"] = true };
        var unknownBytes = JsonSerializer.SerializeToUtf8Bytes(unknown);
        Assert(Rejected(() => NativeE39QualifiedBenchProfile.Verify(unknownBytes, Sign(unknownBytes), trust)),
            "unknown override field denied");
        Console.WriteLine($"Signed bench admission checks: {checks}");
        return checks;
    }
}
