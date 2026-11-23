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
            if (!condition) throw new InvalidOperationException("Bench profile test: " + name);
        }
        bool Rejected(Action operation)
        {
            try { operation(); return false; }
            catch (Exception e) when (e is UnauthorizedAccessException or InvalidDataException or
                ArgumentException or InvalidOperationException or JsonException or OverflowException)
            { return true; }
        }
        var fields = new Dictionary<string, object>
        {
            ["schema"] = 1, ["family"] = "E39",
            ["expectedVin"] = "1G1FA1RX0G0100001", ["expectedOsId"] = 12345U,
            ["registryView"] = "Registry64", ["requestCanId"] = 2016,
            ["responseCanId"] = 2024, ["protocolNumber"] = 33,
            ["stockReadBytes"] = 64, ["stockReadSession"] = 1,
            ["minimumSupplyMillivolts"] = 12000U, ["maximumSupplyMillivolts"] = 14500U,
            ["kernelSha256"] = new string('B', 64), ["maximumIsoTpPayloadBytes"] = 512,
            ["maximumReadBytes"] = 256, ["maximumWriteBytes"] = 128
        };
        var valid = JsonSerializer.SerializeToUtf8Bytes(fields);
        var accepted = NativeE39QualifiedBenchProfile.Parse(valid);
        Assert(accepted.Policy.Session.ExpectedOsId == 12345U &&
            accepted.Scope.Length == NativeE39ImageInspector.ImageBytes &&
            accepted.Scope.StartAddress == 0 && accepted.Channel.RequestCanId == 2016 &&
            accepted.Channel.DriverSha256 == "",
            "unsigned local profile binds ECU, CAN route and full image");
        var path = Path.GetTempFileName();
        try
        {
            File.WriteAllBytes(path, valid);
            Assert(NativeE39QualifiedBenchProfile.Load(path).ProfileSha256 == accepted.ProfileSha256,
                "ordinary local profile loads without signing key or license");
        }
        finally { File.Delete(path); }
        foreach (var (field, replacement) in new (string, object)[]
        {
            ("family", "E38"), ("expectedOsId", 0U), ("protocolNumber", 35),
            ("responseCanId", 2016), ("minimumSupplyMillivolts", 15000U),
            ("maximumWriteBytes", 511), ("kernelSha256", "not-a-digest"),
            ("expectedVin", "unknown"), ("stockReadSession", 2)
        })
        {
            var bad = new Dictionary<string, object>(fields) { [field] = replacement };
            Assert(Rejected(() => NativeE39QualifiedBenchProfile.Parse(JsonSerializer.SerializeToUtf8Bytes(bad))),
                "invalid physical profile field rejected: " + field);
        }
        var duplicate = Encoding.UTF8.GetBytes("{\"schema\":1," + Encoding.UTF8.GetString(valid)[1..]);
        Assert(Rejected(() => NativeE39QualifiedBenchProfile.Parse(duplicate)),
            "duplicate field rejected");
        var unknown = new Dictionary<string, object>(fields) { ["allowUnsigned"] = true };
        Assert(Rejected(() => NativeE39QualifiedBenchProfile.Parse(JsonSerializer.SerializeToUtf8Bytes(unknown))),
            "unrecognized field rejected");
        foreach (var obsolete in new[] { "keyId", "boardEvidenceSha256", "recoveryEvidenceSha256" })
        {
            var bad = new Dictionary<string, object>(fields) { [obsolete] = "unused" };
            Assert(Rejected(() => NativeE39QualifiedBenchProfile.Parse(JsonSerializer.SerializeToUtf8Bytes(bad))),
                "obsolete signing/evidence field removed: " + obsolete);
        }
        var pinned = new Dictionary<string, object>(fields) { ["driverSha256"] = new string('A',64) };
        Assert(NativeE39QualifiedBenchProfile.Parse(JsonSerializer.SerializeToUtf8Bytes(pinned))
            .Channel.DriverSha256 == new string('A',64), "optional vendor DLL pin supported");
        pinned["driverSha256"] = "invalid";
        Assert(Rejected(() => NativeE39QualifiedBenchProfile.Parse(JsonSerializer.SerializeToUtf8Bytes(pinned))),
            "bad optional driver fingerprint rejected");
        Console.WriteLine($"E39 local bench profile checks: {checks}");
        return checks;
    }
}
