using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace CamaroDiag.J2534Bridge;

/// <summary>Offline validation of actual flash bytes captured by the ECU journal.
/// Valid backup files are NOT evidence that a dead ECU is physically recoverable.</summary>
internal static class NativeE39SavedBlockAssessment
{
    private const int MaximumJournalBytes = 8 * 1024 * 1024;
    private const int MaximumSectorFileBytes = 1024 * 1024 + 1024;
    private static readonly UTF8Encoding StrictUtf8 = new(false, true);

    internal sealed record Block(uint Address, int Length, string OriginalSha256);
    internal sealed record Result(bool Verified, int ExpectedSectors,
        IReadOnlyList<Block> Blocks, string Problem,
        NativeE39FileWriteJournal.CompletionState JournalState)
    {
        internal bool PhysicalRestoreQualified => false;
        internal string? SnapshotSha256 { get; init; }
        internal string? JournalSnapshotSha256 { get; init; }
        internal NativeE39FileWriteJournal.Inspection? JournalInspection { get; init; }
        internal IReadOnlyDictionary<string, string> EnvelopeSha256 { get; init; } =
            new System.Collections.ObjectModel.ReadOnlyDictionary<string, string>(
                new Dictionary<string, string>(StringComparer.Ordinal));
    }

    internal sealed record Bundle(Result Blocks, TargetMetadata Target);

    // Compose only observations that still refer to the same journal and
    // metadata bytes. Inconsistent observations must not display stale success.
    internal static Bundle InspectBundle(string journalPath)
    {
        var before = InspectTargetMetadata(journalPath);
        var blocks = Inspect(journalPath);
        var after = InspectTargetMetadata(journalPath);
        bool journalStable = false;
        try
        {
            journalStable = blocks.JournalSnapshotSha256 is not null &&
                blocks.JournalSnapshotSha256 == Convert.ToHexString(
                    SHA256.HashData(ReadBoundedFile(journalPath, MaximumJournalBytes)));
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException or
            InvalidDataException or ArgumentException) { }
        if (!journalStable || before != after)
        {
            var failed = new Result(false, blocks.ExpectedSectors, [],
                "Recovery evidence changed or could not be pinned during assessment.",
                NativeE39FileWriteJournal.CompletionState.Interrupted);
            return new Bundle(failed, new TargetMetadata(false, null, null, null, failed.Problem));
        }
        return new Bundle(blocks, after);
    }

    internal sealed record TargetMetadata(bool Verified, string? Family,
        string? Vin, uint? OsId, string Problem, string? OriginalImageSha256 = null,
        string? MetadataSnapshotSha256 = null);

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
            var bytes = ReadBoundedFile(path, 16384);
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
            var originalImageSha256 = Digest("OriginalSha256");
            _ = Digest("TargetSha256");
            _ = Digest("KernelSha256");
            return new TargetMetadata(true, family, vin, osid, "", originalImageSha256,
                Convert.ToHexString(SHA256.HashData(bytes)));
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException or
            InvalidDataException or JsonException or KeyNotFoundException or
            ArgumentException or FormatException or OverflowException or InvalidOperationException)
        {
            return new TargetMetadata(false, null, null, null, error.Message);
        }
    }

    /// <summary>
    /// Compare the original bytes saved to disk with the exact prepared and
    /// physically verified original SHA-256 events. An interrupted transaction
    /// may still have valid original blocks, but a missing receipt is not proof
    /// that those files were durably checked before the erase phase.
    /// </summary>
    internal static Result Inspect(string journalPath)
    {
        var state = NativeE39FileWriteJournal.CompletionState.Interrupted;
        var expected = new Dictionary<uint, string>();
        var physicalReceipts = new HashSet<uint>();
        var crcReceipts = new HashSet<uint>();
        var destructiveStarted = false;
        var verified = new List<Block>();
        byte[]? snapshot = null;
        NativeE39FileWriteJournal.Inspection? inspection = null;
        try
        {
            snapshot = ReadBoundedFile(journalPath, MaximumJournalBytes);
            inspection = NativeE39FileWriteJournal.InspectSnapshot(snapshot);
            state = inspection.State;
            if (!inspection.SequenceValid &&
                state != NativeE39FileWriteJournal.CompletionState.RecoveryRequired)
                throw new InvalidDataException("Invalid recovery journal sequence.");
            var text = StrictUtf8.GetString(snapshot);
            if (text.Length == 0 || text[^1] != '\n')
                throw new InvalidDataException("The journal has an incomplete final record.");
            // Inspect one immutable bounded snapshot; never mix a partially
            // appended record with sector files from a different journal read.
            foreach (var line in text[..^1].Split('\n'))
            {
                if (line.Length == 0)
                    throw new InvalidDataException("Unexpected empty ECU journal record.");
                using var entry = JsonDocument.Parse(line);
                var root = entry.RootElement;
                if (root.ValueKind != JsonValueKind.Object ||
                    HasDuplicateProperties(root) ||
                    root.GetProperty("Schema").GetInt32() != 3)
                    throw new InvalidDataException("Invalid ECU journal record.");
                var phase = root.GetProperty("Phase").GetString();
                if (phase == "EraseStarted")
                {
                    // The complete physical baseline must be durable before
                    // the first irreversible erase decision.
                    if (expected.Count == 0 || physicalReceipts.Count != expected.Count)
                        throw new InvalidDataException(
                            "ECU erase was recorded before all physical original receipts.");
                    destructiveStarted = true;
                }
                if (phase is not ("Prepared" or "OriginalCrcVerified" or "OriginalBytesVerified"))
                    continue;
                if (destructiveStarted)
                    throw new InvalidDataException(
                        "Physical original receipt appeared after ECU erase began.");
                var address = root.GetProperty("Address").GetUInt32();
                var hash = root.GetProperty("Detail").GetString();
                if (phase == "OriginalCrcVerified")
                {
                    // The CRC receipt is 8 uppercase hex digits, unlike
                    // original-sector SHA-256 receipts (64 hex digits).
                    if (hash is null || hash.Length != 8 || !hash.All(Uri.IsHexDigit) ||
                        !expected.ContainsKey(address) || !crcReceipts.Add(address))
                        throw new InvalidDataException(
                            "Missing, altered or duplicated original-sector CRC receipt.");
                    continue;
                }
                if (hash is null || hash.Length != 64 || !hash.All(Uri.IsHexDigit))
                    throw new InvalidDataException("Invalid original-sector SHA-256 receipt.");
                if (phase == "Prepared")
                {
                    if (!expected.TryAdd(address, hash.ToUpperInvariant()))
                        throw new InvalidDataException("Duplicated prepared original-sector address.");
                }
                else
                {
                    if (!crcReceipts.Contains(address) ||
                        !expected.TryGetValue(address, out var plannedHash) ||
                        !hash.Equals(plannedHash, StringComparison.OrdinalIgnoreCase) ||
                        !physicalReceipts.Add(address))
                        throw new InvalidDataException(
                            "Physical original-sector receipt is absent, altered or duplicated.");
                }
            }
            if (expected.Count == 0 || physicalReceipts.Count != expected.Count)
                throw new InvalidDataException(
                    "Not all original ECU sectors have durable physical-read evidence.");

            var dir = journalPath + ".blocks";
            if (!Directory.Exists(dir) ||
                (File.GetAttributes(dir) & FileAttributes.ReparsePoint) != 0)
                throw new InvalidDataException("Physical ECU sector backup directory missing or linked.");
            var files = Directory.EnumerateFileSystemEntries(dir).Take(expected.Count + 1).ToArray();
            if (files.Length != expected.Count)
                throw new InvalidDataException("Physical backup count differs from the journal.");
            var observed = new HashSet<uint>();
            var envelopeHashes = new Dictionary<string, byte[]>(StringComparer.Ordinal);
            foreach (var file in files)
            {
                var data = ReadBoundedFile(file, MaximumSectorFileBytes);
                envelopeHashes.Add(file, SHA256.HashData(data));
                var newline = Array.IndexOf(data, (byte)'\n');
                if (newline is < 1 or > 1024)
                    throw new InvalidDataException("Invalid physical backup metadata.");
                var metadata = StrictUtf8.GetString(data, 0, newline);
                using var envelope = JsonDocument.Parse(metadata);
                var header = envelope.RootElement;
                if (header.ValueKind != JsonValueKind.Object ||
                    HasDuplicateProperties(header) || header.EnumerateObject().Count() != 5)
                    throw new InvalidDataException("Physical backup metadata contains ambiguous fields.");
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
                    throw new InvalidDataException(
                        "Backup address, region, sector size or metadata mismatch.");
                var onDiskHash = Convert.ToHexString(
                    SHA256.HashData(data.AsSpan(newline + 1)));
                if (onDiskHash != journalHash)
                    throw new InvalidDataException(
                        "Physical saved ECU bytes do not match their recorded SHA-256.");
                verified.Add(new Block(address, length, onDiskHash));
            }
            ValidateImageRanges(verified);
            // A journal append or a replacement of a previously inspected
            // sector invalidates this composite report. Re-read every envelope
            // and check the full directory inventory before publishing.
            if ((File.GetAttributes(dir) & FileAttributes.ReparsePoint) != 0 ||
                !Directory.EnumerateFileSystemEntries(dir).ToHashSet(StringComparer.Ordinal)
                    .SetEquals(envelopeHashes.Keys))
                throw new InvalidDataException("Physical backup inventory changed during inspection.");
            foreach (var (path, hash) in envelopeHashes)
                if (!CryptographicOperations.FixedTimeEquals(hash,
                    SHA256.HashData(ReadBoundedFile(path, MaximumSectorFileBytes))))
                    throw new InvalidDataException("Physical backup changed during inspection.");
            if ((File.GetAttributes(dir) & FileAttributes.ReparsePoint) != 0 ||
                !Directory.EnumerateFileSystemEntries(dir).Take(expected.Count + 1)
                    .ToHashSet(StringComparer.Ordinal).SetEquals(envelopeHashes.Keys))
                throw new InvalidDataException("Physical backup inventory changed during final inspection.");
            if (!snapshot.AsSpan().SequenceEqual(ReadBoundedFile(journalPath, MaximumJournalBytes)))
            {
                state = NativeE39FileWriteJournal.CompletionState.Interrupted;
                throw new InvalidDataException("ECU journal changed during inspection.");
            }
            return new Result(true, expected.Count,
                verified.OrderBy(b => b.Address).ToArray(), "", state)
            {
                SnapshotSha256 = ComputeSnapshotDigest(snapshot, envelopeHashes),
                JournalSnapshotSha256 = Convert.ToHexString(SHA256.HashData(snapshot)),
                JournalInspection = inspection,
                EnvelopeSha256 = new System.Collections.ObjectModel.ReadOnlyDictionary<string, string>(
                    envelopeHashes.ToDictionary(x => Path.GetFileName(x.Key),
                        x => Convert.ToHexString(x.Value), StringComparer.Ordinal))
            };
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException or
            InvalidDataException or JsonException or KeyNotFoundException or
            ArgumentException or FormatException or OverflowException or InvalidOperationException)
        {
            // Preserve the interruption state only if the audited journal
            // bytes remain stable, even when a sector check failed first.
            string? journalDigest = null;
            try
            {
                if (snapshot is not null &&
                    snapshot.AsSpan().SequenceEqual(ReadBoundedFile(journalPath, MaximumJournalBytes)))
                    journalDigest = Convert.ToHexString(SHA256.HashData(snapshot));
                else
                    state = NativeE39FileWriteJournal.CompletionState.Interrupted;
            }
            catch (Exception rereadError) when (rereadError is IOException or
                UnauthorizedAccessException or InvalidDataException or ArgumentException)
            {
                state = NativeE39FileWriteJournal.CompletionState.Interrupted;
            }
            return new Result(false, expected.Count, [], error.Message, state)
            {
                JournalSnapshotSha256 = journalDigest,
                JournalInspection = journalDigest is not null ? inspection : null
            };
        }
    }

    // The journal contains E39_MAIN_FLASH offsets into a complete 3 MiB
    // image. Consistent individual hashes do not prove disjoint ranges.
    // This verifies bounds only, never OEM erase geometry or recoverability.
    internal static void ValidateImageRanges(IReadOnlyList<Block> blocks)
    {
        if (blocks.Count == 0)
            throw new InvalidDataException("No physical backup ranges.");
        ulong previousEnd = 0;
        foreach (var block in blocks.OrderBy(b => b.Address))
        {
            if (block.Length <= 0 || block.Address < previousEnd ||
                (ulong)block.Address + (uint)block.Length > NativeE39ImageInspector.ImageBytes)
                throw new InvalidDataException("Physical backup ranges overlap or exceed the E39 image.");
            previousEnd = (ulong)block.Address + (uint)block.Length;
        }
    }

    // Bind the exact journal and complete sector envelopes, not only
    // decoded addresses, data hashes or the completion-state enum.
    internal static string ComputeSnapshotDigest(byte[] journal,
        IReadOnlyDictionary<string, byte[]> envelopeHashes)
    {
        using var hash = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
        hash.AppendData(SHA256.HashData(journal));
        Span<byte> length = stackalloc byte[4];
        foreach (var pair in envelopeHashes.OrderBy(x => Path.GetFileName(x.Key), StringComparer.Ordinal))
        {
            var name = Encoding.UTF8.GetBytes(Path.GetFileName(pair.Key));
            if (pair.Value.Length != 32 || name.Length == 0)
                throw new InvalidDataException("Invalid sector envelope digest.");
            System.Buffers.Binary.BinaryPrimitives.WriteInt32LittleEndian(length, name.Length);
            hash.AppendData(length);
            hash.AppendData(name);
            hash.AppendData(pair.Value);
        }
        return Convert.ToHexString(hash.GetHashAndReset());
    }

    private static bool HasDuplicateProperties(JsonElement element)
    {
        var seen = new HashSet<string>(StringComparer.Ordinal);
        return element.EnumerateObject().Any(property => !seen.Add(property.Name));
    }

    private static byte[] ReadBoundedFile(string path, int maximumBytes)
    {
        if ((File.GetAttributes(path) & (FileAttributes.ReparsePoint | FileAttributes.Directory)) != 0)
            throw new InvalidDataException("Recovery evidence must be a regular, unlinked file.");
        using var source = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite);
        if (source.Length is < 1 || source.Length > maximumBytes)
            throw new InvalidDataException("Recovery evidence file exceeds supported bounds.");
        var data = new byte[checked((int)source.Length)];
        source.ReadExactly(data);
        if (source.ReadByte() != -1)
            throw new InvalidDataException("Recovery evidence changed while being read.");
        return data;
    }

    internal static byte[] ReadPhysicalOriginal(string journalPath, Block descriptor)
    {
        var evidence = Inspect(journalPath);
        return ReadPhysicalOriginal(journalPath, descriptor, evidence);
    }

    // The caller must revalidate the complete evidence snapshot before publishing
    // a multi-sector result. Each read still verifies the exact saved envelope.
    internal static byte[] ReadPhysicalOriginal(string journalPath, Block descriptor, Result evidence)
    {
        var fileName = $"{descriptor.Address:X8}-{descriptor.Length}.ecu-block";
        if (!evidence.Verified || evidence.SnapshotSha256 is null ||
            !evidence.Blocks.Contains(descriptor) ||
            !evidence.EnvelopeSha256.TryGetValue(fileName, out var expectedEnvelope))
            throw new InvalidDataException("Incomplete or changed physical-sector backups.");
        var directory = journalPath + ".blocks";
        if ((File.GetAttributes(directory) & FileAttributes.ReparsePoint) != 0)
            throw new InvalidDataException("Linked original sector directory.");
        var name = Path.Combine(directory, fileName);
        // The file may have been swapped or expanded since Inspect(). The
        // second acquisition must be independently bounded and rechecked.
        var file = ReadBoundedFile(name, MaximumSectorFileBytes);
        if (Convert.ToHexString(SHA256.HashData(file)) != expectedEnvelope)
            throw new InvalidDataException("Physical sector envelope changed after inspection.");
        var cut = Array.IndexOf(file, (byte)'\n');
        if (cut is < 1 or > 1024)
            throw new InvalidDataException("Saved-sector metadata is missing on second read.");
        using var document = JsonDocument.Parse(StrictUtf8.GetString(file, 0, cut));
        var header = document.RootElement;
        if (header.ValueKind != JsonValueKind.Object || HasDuplicateProperties(header) ||
            header.EnumerateObject().Count() != 5 ||
            header.GetProperty("Schema").GetInt32() != 1 ||
            header.GetProperty("Region").GetString() != "E39_MAIN_FLASH" ||
            header.GetProperty("Address").GetUInt32() != descriptor.Address ||
            header.GetProperty("Length").GetInt32() != descriptor.Length ||
            !string.Equals(header.GetProperty("OriginalSha256").GetString(),
                descriptor.OriginalSha256, StringComparison.OrdinalIgnoreCase))
            throw new InvalidDataException("Physical-sector metadata changed during preview.");
        var original = file.AsSpan(cut + 1).ToArray();
        if (original.Length != descriptor.Length ||
            Convert.ToHexString(SHA256.HashData(original)) != descriptor.OriginalSha256)
            throw new InvalidDataException("Physical-sector backup changed during restore preview.");
        return original;
    }
}
