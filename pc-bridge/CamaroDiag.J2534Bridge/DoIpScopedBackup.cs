using System.Globalization;
using System.IO.Compression;
using System.Net;
using System.Security.Cryptography;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace CamaroDiag.J2534Bridge;

/// <summary>Explicit logical-address scope for DoIP, independent of CAN IDs.</summary>
internal sealed record DoIpBackupScope(string Vin, ushort Gateway, ushort Tester, ushort Ecu,
    ushort IdentityDid, string IdentityHex, uint Start, int Length, int BlockSize, string Region)
{
    internal static DoIpBackupScope Load(string path)
    {
        if (new FileInfo(path).Length is <= 0 or > 16384)
            throw new InvalidDataException("Invalid DoIP backup profile size.");
        using var doc = JsonDocument.Parse(File.ReadAllText(path));
        var root = doc.RootElement;
        var keys = new HashSet<string>(StringComparer.Ordinal) { "vin", "gateway", "tester", "ecu",
            "identityDid", "identityHex", "startAddress", "lengthBytes", "blockSize", "region" };
        foreach (var property in root.EnumerateObject())
            if (!keys.Remove(property.Name)) throw new InvalidDataException("Unknown or duplicate profile field.");
        if (keys.Count != 0) throw new InvalidDataException("Incomplete DoIP backup profile.");
        string Get(string key) => root.GetProperty(key).GetString() ?? throw new InvalidDataException(key);
        var scope = new DoIpBackupScope(Get("vin"), DoIpVinReader.ParseLogicalAddress(Get("gateway")),
            DoIpVinReader.ParseLogicalAddress(Get("tester")), DoIpVinReader.ParseLogicalAddress(Get("ecu")),
            DoIpVinReader.ParseLogicalAddress(Get("identityDid")), Get("identityHex"),
            uint.Parse(Get("startAddress"), NumberStyles.AllowHexSpecifier, CultureInfo.InvariantCulture),
            root.GetProperty("lengthBytes").GetInt32(), root.GetProperty("blockSize").GetInt32(), Get("region"));
        scope.Validate();
        return scope;
    }

    internal void Validate()
    {
        if (!Regex.IsMatch(Vin, @"\A[A-HJ-NPR-Z0-9]{17}\z") || Tester == Ecu || Tester == Gateway ||
            IdentityDid == 0xF190 || IdentityHex.Length is < 2 or > 512 || IdentityHex.Length % 2 != 0 ||
            !IdentityHex.All(Uri.IsHexDigit) || IdentityHex.All(c => c == '0') ||
            IdentityHex.All(c => c is 'F' or 'f') ||
            Length is < 1 or > NativeScopedMemoryReader.MaximumUiCaptureBytes ||
            BlockSize is < 1 or > 256 || (ulong)Start + (uint)Length > 0x100000000UL ||
            !J2534MemoryReadScope.IsKnownRegion(Region))
            throw new InvalidDataException("Invalid or incomplete DoIP ECU memory scope.");
    }
}

internal static class DoIpScopedBackup
{
    internal sealed record Capture(DoIpBackupScope Scope, byte[] First, byte[] Second, string Sha256);

    internal static async Task<Capture> ReadAsync(DoIpDiagnosticTransport link, DoIpBackupScope scope,
        TimeSpan timeout, CancellationToken token, Action<int, int>? progress = null)
    {
        scope.Validate();
        async Task VerifyIdentity()
        {
            var vin = await DoIpVinReader.ReadAsync(link, timeout, token);
            var identity = await DoIpVinReader.ReadDidAsync(link, scope.IdentityDid, timeout, token);
            if (!string.Equals(vin, scope.Vin, StringComparison.Ordinal) ||
                !string.Equals(Convert.ToHexString(identity), scope.IdentityHex, StringComparison.OrdinalIgnoreCase))
                throw new UnauthorizedAccessException("Live DoIP ECU identity does not match the approved profile.");
        }
        async Task<byte[]> ReadPass(int pass)
        {
            await VerifyIdentity();
            var data = new byte[scope.Length];
            var offset = 0;
            var blocks = 0;
            while (offset < data.Length)
            {
                token.ThrowIfCancellationRequested();
                if (blocks > 0 && blocks % 32 == 0) await VerifyIdentity();
                var count = Math.Min(scope.BlockSize, data.Length - offset);
                var address = checked(scope.Start + (uint)offset);
                var request = UdsStandardServices.ReadMemory(address, (uint)count, 4,
                    count > 255 ? (byte)2 : (byte)1);
                var reply = await link.ExchangeUdsAsync(request, timeout, token);
                token.ThrowIfCancellationRequested();
                NativeScopedMemoryReader.ParseReadResponse(reply, count).CopyTo(data, offset);
                offset += count;
                blocks++;
                if (NativeScopedMemoryReader.ShouldReportProgress(blocks, offset, data.Length))
                    progress?.Invoke(pass, offset);
            }
            return data;
        }
        // No security unlock, session change, programming or automatic retry.
        var first = await ReadPass(1);
        var second = await ReadPass(2);
        await VerifyIdentity();
        token.ThrowIfCancellationRequested();
        if (!CryptographicOperations.FixedTimeEquals(first, second))
            throw new InvalidDataException("Independent DoIP memory reads differ; no backup can be published.");
        return new Capture(scope, first, second, Convert.ToHexString(SHA256.HashData(first)));
    }

    internal static async Task<Capture> ReadObservedAsync(DoIpVehicleDiscovery.Identity gateway,
        IPAddress address, DoIpBackupScope scope, bool tls, TimeSpan timeout, CancellationToken token,
        Action<int, int>? progress = null, string? certificate = null)
    {
        scope.Validate();
        if (!gateway.Source.Equals(address) || gateway.LogicalAddress != scope.Gateway ||
            (gateway.Vin is not null && gateway.Vin != scope.Vin))
            throw new UnauthorizedAccessException("Discovered DoIP gateway differs from the backup profile.");
        await using var link = await DoIpVinReader.ConnectObservedAsync(gateway, address, scope.Tester,
            scope.Ecu, tls, timeout, token, certificate);
        return await ReadAsync(link, scope, timeout, token, progress);
    }

    internal static void SaveNew(string destination, Capture capture, CancellationToken token)
    {
        capture.Scope.Validate();
        var first = capture.First.ToArray();
        var second = capture.Second.ToArray();
        if (ReferenceEquals(capture.First, capture.Second) || first.Length != capture.Scope.Length ||
            second.Length != first.Length || !CryptographicOperations.FixedTimeEquals(first, second) ||
            Convert.ToHexString(SHA256.HashData(first)) != capture.Sha256)
            throw new InvalidDataException("DoIP capture integrity check failed.");
        var full = Path.GetFullPath(destination);
        if (!full.EndsWith(".zip", StringComparison.OrdinalIgnoreCase))
            throw new ArgumentException("A ZIP destination is required.");
        var staged = full + "." + Guid.NewGuid().ToString("N") + ".partial";
        var manifest = JsonSerializer.SerializeToUtf8Bytes(new
        {
            format = "CamaroDiag-DoIP-memory-v1", transport = "DoIP",
            coverage = "SCOPED_REGION_ONLY", origin = "REPORTED_HARDWARE_READ",
            capturedAtUtc = DateTimeOffset.UtcNow, scope = capture.Scope, sha256 = capture.Sha256
        });
        try
        {
            token.ThrowIfCancellationRequested();
            using (var file = new FileStream(staged, FileMode.CreateNew, FileAccess.Write, FileShare.None))
            {
                using (var zip = new ZipArchive(file, ZipArchiveMode.Create, leaveOpen: true))
                {
                    void Add(string name, byte[] bytes)
                    {
                        token.ThrowIfCancellationRequested();
                        using var stream = zip.CreateEntry(name).Open();
                        stream.Write(bytes);
                    }
                    Add("manifest.json", manifest);
                    Add("original.bin", first);
                    Add("verification.bin", second);
                }
                file.Flush(flushToDisk: true);
            }
            using (var file = File.OpenRead(staged))
            using (var zip = new ZipArchive(file, ZipArchiveMode.Read))
            {
                if (zip.Entries.Count != 3) throw new InvalidDataException("Invalid staged backup.");
                void Verify(string name, byte[] expected)
                {
                    token.ThrowIfCancellationRequested();
                    var entry = zip.GetEntry(name) ?? throw new InvalidDataException(name);
                    if (entry.Length != expected.Length) throw new InvalidDataException(name);
                    var bytes = new byte[expected.Length];
                    using var stream = entry.Open();
                    stream.ReadExactly(bytes);
                    if (stream.ReadByte() != -1 || !CryptographicOperations.FixedTimeEquals(bytes, expected))
                        throw new InvalidDataException("Staged DoIP backup differs from the capture.");
                }
                Verify("manifest.json", manifest);
                Verify("original.bin", first);
                Verify("verification.bin", second);
            }
            token.ThrowIfCancellationRequested();
            File.Move(staged, full, overwrite: false);
        }
        finally { if (File.Exists(staged)) File.Delete(staged); }
    }
}
