using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace CamaroDiag.J2534Bridge;

/// <summary>Offline validation of actual flash bytes captured by the ECU journal.
/// Valid backup files are NOT evidence that a dead ECU is physically recoverable.</summary>
internal static class NativeE39SavedBlockAssessment
{
    internal sealed record Block(uint Address, int Length, string OriginalSha256);
    internal sealed record Result(bool Verified, int ExpectedSectors,
        IReadOnlyList<Block> Blocks, string Problem,
        NativeE39FileWriteJournal.CompletionState JournalState)
    {
        internal bool PhysicalRestoreQualified => false;
    }

    internal sealed record TargetMetadata(bool Verified, string? Family,
        string? Vin, uint? OsId, string Problem);

    /// <summary>
    /// Verify the separately persisted target manifest before using its
    /// VIN/OSID as recovery context. A coherent manifest is NOT a current
    /// physical identity measurement or a cryptographic provenance signature.
    /// Older journals without this sidecar remain inspectable but unbound.
    /// </summary>
    internal static TargetMetadata InspectTargetMetadata(string journalPath)
    {
        try
        {
            ArgumentException.ThrowIfNullOrWhiteSpace(journalPath);
            var path = journalPath + ".meta.json";
            using var source = File.OpenRead(path);
            if (source.Length is < 1 or > 16384)
                throw new InvalidDataException("Invalid ECU target metadata length.");
            var bytes = new byte[checked((int)source.Length)];
            source.ReadExactly(bytes);
            if (source.ReadByte() != -1)
                throw new InvalidDataException("ECU metadata changed during inspection.");
            using var document = JsonDocument.Parse(bytes);
            var root = document.RootElement;
            if (root.ValueKind != JsonValueKind.Object)
                throw new InvalidDataException("ECU target metadata is not a JSON object.");

            var names = new HashSet<string>(StringComparer.Ordinal)
            {
                "Schema", "Utc", "Family", "ExpectedVin", "ExpectedOsId",
                "ProfileSha256", "DriverSha256", "RegistryView",
                "OriginalSha256", "TargetSha256", "KernelSha256"
            };
            foreach (var property in root.EnumerateObject())
                if (!names.Remove(property.Name))
                    throw new InvalidDataException("Unexpected or duplicated ECU target metadata.");
            if (names.Count != 0 || root.GetProperty("Schema").GetInt32() != 1)
                throw new InvalidDataException("Incomplete or unsupported ECU target metadata.");
            string Value(string field) => root.GetProperty(field).GetString()
                ?? throw new InvalidDataException("Missing ECU target property: " + field);
            string Digest(string field)
            {
                var digest = Value(field);
                if (digest.Length != 64 || !digest.All(Uri.IsHexDigit))
                    throw new InvalidDataException("Invalid ECU target hash: " + field);
                return digest;
            }
            var family = Value("Family");
            var vin = Value("ExpectedVin");
            var osid = root.GetProperty("ExpectedOsId").GetUInt32();
            var registry = Value("RegistryView");
            var timestamp = Value("Utc");
            if (family is not ("E39" or "E39A") ||
                !System.Text.RegularExpressions.Regex.IsMatch(vin,
                    @"\A[A-HJ-NPR-Z0-9]{17}\z") ||
                osid == 0 || registry is not ("Registry32" or "Registry64") ||
                !DateTimeOffset.TryParse(timestamp, out _))
                throw new InvalidDataException("Unusable ECU VIN, family, OSID or profile metadata.");
            _ = Digest("ProfileSha256");
            _ = Digest("DriverSha256");
            _ = Digest("OriginalSha256");
            _ = Digest("TargetSha256");
            _ = Digest("KernelSha256");
            return new TargetMetadata(true, family, vin, osid, "");
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException or
            InvalidDataException or JsonException or KeyNotFoundException or
            ArgumentException or FormatException or OverflowException)
        {
            return new TargetMetadata(false, null, null, null, error.Message);
        }
    }

    internal static Result Inspect(string journalPath)
    {
        var state = NativeE39FileWriteJournal.InspectDetailed(journalPath).State;
        var expected = new Dictionary<uint, string>();
        var verified = new List<Block>();
        try
        {
            if (new FileInfo(journalPath).Length > 16 * 1024 * 1024)
                throw new InvalidDataException("Journal exceeds inspection limit.");
            foreach (var line in File.ReadLines(journalPath))
            {
                if (string.IsNullOrWhiteSpace(line)) continue;
                using var entry = JsonDocument.Parse(line);
                var root = entry.RootElement;
                if (!root.TryGetProperty("Phase", out var phase) ||
                    phase.GetString() != "Prepared") continue;
                var address = root.GetProperty("Address").GetUInt32();
                var hash = root.GetProperty("Detail").GetString();
                if (hash is null || hash.Length != 64 ||
                    !hash.All(Uri.IsHexDigit) ||
                    !expected.TryAdd(address, hash.ToUpperInvariant()))
                    throw new InvalidDataException("Invalid or duplicated original ECU sector.");
            }
            if (expected.Count == 0)
                throw new InvalidDataException("No prepared sectors in the journal.");
            var dir = journalPath + ".blocks";
            if (!Directory.Exists(dir))
                throw new InvalidDataException("Physical ECU sector backup directory missing.");
            var files = Directory.EnumerateFiles(dir).ToArray();
            if (files.Length != expected.Count)
                throw new InvalidDataException("Physical backup count differs from the journal.");
            var observed = new HashSet<uint>();
            foreach (var file in files)
            {
                if (new FileInfo(file).Length > 1024 * 1024 + 1024)
                    throw new InvalidDataException("Physical backup file is too large.");
                var data = File.ReadAllBytes(file);
                var newline = Array.IndexOf(data, (byte)'\n');
                if (newline is < 1 or > 1024)
                    throw new InvalidDataException("Invalid physical backup metadata.");
                var metadata = new UTF8Encoding(false, true).GetString(data, 0, newline);
                using var envelope = JsonDocument.Parse(metadata);
                var header = envelope.RootElement;
                var address = header.GetProperty("Address").GetUInt32();
                var length = header.GetProperty("Length").GetInt32();
                var hash = header.GetProperty("OriginalSha256").GetString();
                if (header.GetProperty("Schema").GetInt32() != 1 ||
                    header.GetProperty("Region").GetString() != "E39_MAIN_FLASH" ||
                    length is < 1 or > 1024 * 1024 ||
                    length != data.Length - newline - 1 ||
                    !expected.TryGetValue(address, out var journalHash) ||
                    !string.Equals(hash, journalHash, StringComparison.OrdinalIgnoreCase) ||
                    !string.Equals(Path.GetFileName(file),
                        $"{address:X8}-{length}.ecu-block", StringComparison.Ordinal) ||
                    !observed.Add(address))
                    throw new InvalidDataException("Backup address, region, sector size or metadata mismatch.");
                var onDiskHash = Convert.ToHexString(
                    SHA256.HashData(data.AsSpan(newline + 1)));
                if (onDiskHash != journalHash)
                    throw new InvalidDataException("Physical saved ECU bytes do not match their hash.");
                verified.Add(new Block(address, length, onDiskHash));
            }
            return new Result(true, expected.Count,
                verified.OrderBy(b => b.Address).ToArray(), "", state);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or
            InvalidDataException or JsonException or KeyNotFoundException or
            ArgumentException or FormatException)
        {
            return new Result(false, expected.Count, [], ex.Message, state);
        }
    }

    internal static byte[] ReadPhysicalOriginal(string journalPath, Block descriptor)
    {
        var evidence = Inspect(journalPath);
        if (!evidence.Verified || !evidence.Blocks.Contains(descriptor))
            throw new InvalidDataException("Incomplete or changed physical-sector backups.");
        var name = Path.Combine(journalPath + ".blocks",
            $"{descriptor.Address:X8}-{descriptor.Length}.ecu-block");
        var file = File.ReadAllBytes(name);
        var cut = Array.IndexOf(file, (byte)'\n');
        var original = file.AsSpan(cut + 1).ToArray();
        if (original.Length != descriptor.Length ||
            Convert.ToHexString(SHA256.HashData(original)) != descriptor.OriginalSha256)
            throw new InvalidDataException("Physical-sector backup changed during restore preview.");
        return original;
    }
}
