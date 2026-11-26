using System.Buffers.Binary;
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
            DoIpVinReader.ParseLogicalAddress(Get("identityDid")), Get("identityHex").ToUpperInvariant(),
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
    internal const int MaximumBlocksPerPass = 8192;
    internal static readonly TimeSpan MaximumCaptureDuration = TimeSpan.FromMinutes(10);

    internal sealed record Capture(DoIpBackupScope Scope, byte[] First, byte[] Second, string Sha256);

    internal static void ValidateCaptureBudget(DoIpBackupScope scope)
    {
        scope.Validate();
        if (((long)scope.Length + scope.BlockSize - 1) / scope.BlockSize > MaximumBlocksPerPass)
            throw new InvalidDataException("DoIP memory scope exceeds the maximum number of blocks per pass.");
    }

    internal static async Task<Capture> ReadAsync(DoIpDiagnosticTransport link, DoIpBackupScope scope,
        TimeSpan timeout, CancellationToken token, Action<int, int>? progress = null)
    {
        ValidateCaptureBudget(scope);
        if (!link.MatchesLogicalTarget(scope.Gateway, scope.Tester, scope.Ecu))
            throw new UnauthorizedAccessException("Active DoIP channel differs from the backup scope.");
        using var budget = CancellationTokenSource.CreateLinkedTokenSource(token);
        budget.CancelAfter(MaximumCaptureDuration);
        var captureToken = budget.Token;
        var clock = System.Diagnostics.Stopwatch.StartNew();

        void CheckBudget()
        {
            token.ThrowIfCancellationRequested();
            if (clock.Elapsed >= MaximumCaptureDuration || budget.IsCancellationRequested)
                throw new TimeoutException("DoIP memory capture exceeded its overall deadline.");
        }

        async Task VerifyIdentity()
        {
            CheckBudget();
            var vin = await DoIpVinReader.ReadAsync(link, timeout, captureToken);
            CheckBudget();
            if (!string.Equals(vin, scope.Vin, StringComparison.Ordinal))
                throw new UnauthorizedAccessException("Live DoIP VIN does not match the approved profile.");
            var identity = await DoIpVinReader.ReadDidAsync(link, scope.IdentityDid, timeout, captureToken);
            CheckBudget();
            if (!string.Equals(Convert.ToHexString(identity), scope.IdentityHex, StringComparison.OrdinalIgnoreCase))
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
                CheckBudget();
                var count = Math.Min(scope.BlockSize, data.Length - offset);
                var address = checked(scope.Start + (uint)offset);
                var request = UdsStandardServices.ReadMemory(address, (uint)count, 4,
                    count > 255 ? (byte)2 : (byte)1);
                var reply = await link.ExchangeUdsAsync(request, timeout, captureToken);
                CheckBudget();
                NativeScopedMemoryReader.ParseReadResponse(reply, count).CopyTo(data, offset);
                offset += count;
                blocks++;
                if (blocks % 32 == 0 || offset == data.Length)
                {
                    // Do not publish progress for a batch until the same ECU
                    // has confirmed its identity after those memory reads.
                    await VerifyIdentity();
                    progress?.Invoke(pass, offset);
                    CheckBudget();
                }
            }
            return data;
        }

        // No security unlock, session change, programming or automatic retry.
        try
        {
            var first = await ReadPass(1);
            var second = await ReadPass(2);
            await VerifyIdentity();
            CheckBudget();
            if (!CryptographicOperations.FixedTimeEquals(first, second))
                throw new InvalidDataException("Independent DoIP memory reads differ; no backup can be published.");
            return new Capture(scope, first, second, Convert.ToHexString(SHA256.HashData(first)));
        }
        catch (OperationCanceledException) when (!token.IsCancellationRequested && budget.IsCancellationRequested)
        {
            throw new TimeoutException("DoIP memory capture exceeded its overall deadline.");
        }
    }

    internal static async Task<Capture> ReadObservedAsync(DoIpVehicleDiscovery.Identity gateway,
        IPAddress address, DoIpBackupScope scope, bool tls, TimeSpan timeout, CancellationToken token,
        Action<int, int>? progress = null, string? certificate = null,
        Action<DoIpDiagnosticTransport.PduTrace>? trace = null,
        DoIpDiagnosticTransport.SessionOptions? options = null)
    {
        ValidateCaptureBudget(scope);
        if (!gateway.Source.Equals(address) || gateway.LogicalAddress != scope.Gateway ||
            (gateway.Vin is not null && gateway.Vin != scope.Vin))
            throw new UnauthorizedAccessException("Discovered DoIP gateway differs from the backup profile.");
        await using var link = await DoIpVinReader.ConnectObservedAsync(gateway, address, scope.Tester,
            scope.Ecu, tls, timeout, token, certificate, trace, options);
        return await ReadAsync(link, scope, timeout, token, progress);
    }

    internal sealed record Verification(DoIpBackupScope Scope, string Sha256, DateTimeOffset CapturedAtUtc);

    // .NET 8 ZipArchive does not independently verify CRC on decompression.
    // Inspect the bounded classic ZIP directory and use its CRCs during reads.
    private static Dictionary<string, uint> VerifyDirectory(FileStream file)
    {
        var size = file.Length;
        if (size < 22 || size > 2L * NativeScopedMemoryReader.MaximumUiCaptureBytes + 1024 * 1024)
            throw new InvalidDataException("Invalid DoIP ZIP length.");
        var tail = new byte[(int)Math.Min(size, 65557)];
        file.Position = size - tail.Length;
        file.ReadExactly(tail);
        var end = -1;
        for (var i = tail.Length - 22; i >= 0; --i)
            if (BinaryPrimitives.ReadUInt32LittleEndian(tail.AsSpan(i)) == 0x06054B50 &&
                i + 22 + BinaryPrimitives.ReadUInt16LittleEndian(tail.AsSpan(i + 20)) == tail.Length)
            { end = i; break; }
        if (end < 0) throw new InvalidDataException("Incomplete DoIP ZIP footer.");
        ushort U16(int offset) => BinaryPrimitives.ReadUInt16LittleEndian(tail.AsSpan(end + offset));
        uint U32(int offset) => BinaryPrimitives.ReadUInt32LittleEndian(tail.AsSpan(end + offset));
        var start = U32(16);
        if (U16(4) != 0 || U16(6) != 0 || U16(8) != 3 || U16(10) != 3 ||
            (long)start + U32(12) != size - tail.Length + end)
            throw new InvalidDataException("Invalid DoIP ZIP directory.");
        var result = new Dictionary<string, uint>(StringComparer.Ordinal);
        var offsets = new HashSet<uint>();
        file.Position = start;
        for (var i = 0; i < 3; i++)
        {
            var header = new byte[46];
            file.ReadExactly(header);
            ushort H16(int offset) => BinaryPrimitives.ReadUInt16LittleEndian(header.AsSpan(offset));
            uint H32(int offset) => BinaryPrimitives.ReadUInt32LittleEndian(header.AsSpan(offset));
            var nameLength = H16(28);
            var local = H32(42);
            if (H32(0) != 0x02014B50 || (H16(8) & 1) != 0 || H16(10) is not (0 or 8) ||
                H16(34) != 0 || nameLength is < 1 or > 128 ||
                (long)local + 30 > start || !offsets.Add(local) ||
                file.Position + nameLength + H16(30) + H16(32) > size - tail.Length + end)
                throw new InvalidDataException("Invalid DoIP ZIP entry metadata.");
            var nameBytes = new byte[nameLength];
            file.ReadExactly(nameBytes);
            var name = System.Text.Encoding.ASCII.GetString(nameBytes);
            if (nameBytes.Any(value => value > 127) ||
                name is not ("manifest.json" or "original.bin" or "verification.bin") ||
                !result.TryAdd(name, H32(16)))
                throw new InvalidDataException("Unexpected DoIP ZIP entry.");
            file.Position += H16(30) + H16(32);
        }
        if (file.Position != size - tail.Length + end || !offsets.Contains(0))
            throw new InvalidDataException("Inconsistent DoIP ZIP directory.");
        file.Position = 0;
        return result;
    }

    private static uint UpdateCrc(uint crc, ReadOnlySpan<byte> bytes)
    {
        foreach (var value in bytes)
        {
            crc ^= value;
            for (var bit = 0; bit < 8; bit++)
                crc = (crc & 1) != 0 ? (crc >> 1) ^ 0xEDB88320U : crc >> 1;
        }
        return crc;
    }

    internal static Verification VerifyZip(string path, DoIpBackupScope expected, CancellationToken token)
    {
        expected.Validate();
        token.ThrowIfCancellationRequested();
        if (new FileInfo(path).Length > 2L * NativeScopedMemoryReader.MaximumUiCaptureBytes + 1024 * 1024)
            throw new InvalidDataException("Oversized DoIP backup.");
        using var file = File.OpenRead(path);
        var crcByName = VerifyDirectory(file);
        using var zip = new ZipArchive(file, ZipArchiveMode.Read);
        if (zip.Entries.Count != 3 || !zip.Entries.Select(e => e.FullName)
            .OrderBy(n => n, StringComparer.Ordinal).SequenceEqual(
                new[] { "manifest.json", "original.bin", "verification.bin" }))
            throw new InvalidDataException("Unexpected DoIP backup entries.");
        byte[] ReadEntry(string name, int maximum, int? exact = null)
        {
            token.ThrowIfCancellationRequested();
            var entry = zip.GetEntry(name) ?? throw new InvalidDataException(name);
            if (entry.Length < 1 || entry.Length > maximum || (exact is not null && entry.Length != exact.Value))
                throw new InvalidDataException("Unexpected DoIP backup entry length: " + name);
            var data = new byte[checked((int)entry.Length)];
            using var stream = entry.Open();
            stream.ReadExactly(data);
            if (stream.ReadByte() != -1 || ~UpdateCrc(uint.MaxValue, data) != crcByName[name])
                throw new InvalidDataException("DoIP ZIP entry checksum differs: " + name);
            return data;
        }
        using var manifest = JsonDocument.Parse(ReadEntry("manifest.json", 16384));
        var m = manifest.RootElement;
        var fields = new HashSet<string>(StringComparer.Ordinal)
            { "format", "transport", "coverage", "origin", "capturedAtUtc", "scope", "sha256" };
        foreach (var field in m.EnumerateObject())
            if (!fields.Remove(field.Name)) throw new InvalidDataException("Unknown or duplicate manifest field.");
        if (fields.Count != 0 || m.GetProperty("format").GetString() != "CamaroDiag-DoIP-memory-v1" ||
            m.GetProperty("transport").GetString() != "DoIP" ||
            m.GetProperty("coverage").GetString() != "SCOPED_REGION_ONLY" ||
            m.GetProperty("origin").GetString() != "REPORTED_HARDWARE_READ")
            throw new InvalidDataException("Invalid DoIP manifest.");
        var stored = m.GetProperty("scope");
        var canonical = JsonSerializer.SerializeToElement(expected);
        var scopeFields = canonical.EnumerateObject().Select(p => p.Name).ToHashSet(StringComparer.Ordinal);
        foreach (var field in stored.EnumerateObject())
        {
            if (!scopeFields.Remove(field.Name) || !canonical.TryGetProperty(field.Name, out var value) ||
                field.Value.ValueKind != value.ValueKind ||
                (value.ValueKind == JsonValueKind.String
                    ? field.Value.GetString() != value.GetString()
                    : field.Value.GetRawText() != value.GetRawText()))
                throw new InvalidDataException("DoIP backup scope differs from the selected profile.");
        }
        if (scopeFields.Count != 0) throw new InvalidDataException("Incomplete DoIP scope.");
        var first = zip.GetEntry("original.bin")!;
        var second = zip.GetEntry("verification.bin")!;
        if (first.Length != expected.Length || second.Length != expected.Length)
            throw new InvalidDataException("Unexpected DoIP region size.");
        using var left = first.Open();
        using var right = second.Open();
        using var digest = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
        var leftBytes = new byte[65536];
        var rightBytes = new byte[65536];
        var crc = uint.MaxValue;
        for (var remaining = expected.Length; remaining > 0;)
        {
            token.ThrowIfCancellationRequested();
            var count = Math.Min(remaining, leftBytes.Length);
            left.ReadExactly(leftBytes.AsSpan(0, count));
            right.ReadExactly(rightBytes.AsSpan(0, count));
            if (!CryptographicOperations.FixedTimeEquals(leftBytes.AsSpan(0, count), rightBytes.AsSpan(0, count)))
                throw new InvalidDataException("Independent DoIP memory reads differ.");
            crc = UpdateCrc(crc, leftBytes.AsSpan(0, count));
            digest.AppendData(leftBytes, 0, count);
            remaining -= count;
        }
        var hash = Convert.ToHexString(digest.GetHashAndReset());
        if (left.ReadByte() != -1 || right.ReadByte() != -1 ||
            ~crc != crcByName["original.bin"] || ~crc != crcByName["verification.bin"] ||
            m.GetProperty("sha256").GetString() != hash)
            throw new InvalidDataException("DoIP backup bytes, CRC or SHA-256 do not match.");
        var timestamp = m.GetProperty("capturedAtUtc");
        if (!Regex.IsMatch(timestamp.GetString() ?? "", @"\A[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\.[0-9]{1,9})?(?:Z|[+-][0-9]{2}:[0-9]{2})\z"))
            throw new InvalidDataException("Invalid DoIP capture timestamp.");
        var date = timestamp.GetDateTimeOffset();
        token.ThrowIfCancellationRequested();
        return new Verification(expected, hash, date);
    }

    internal static void SaveNew(string destination, Capture capture, CancellationToken token)
    {
        ValidateCaptureBudget(capture.Scope);
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
            _ = VerifyZip(staged, capture.Scope, token);
            token.ThrowIfCancellationRequested();
            File.Move(staged, full, overwrite: false);
        }
        finally { if (File.Exists(staged)) File.Delete(staged); }
    }
}
