using System.Text;
using System.Text.Json;
using System.Security.Cryptography;

namespace CamaroDiag.J2534Bridge;

/// <summary>
/// Append-only crash-resilient journal for a native ECU flash attempt.
/// One new file per operation; it NEVER overwrites an earlier recovery log.
/// Every phase is written and flushed to storage before an erase or write.
/// </summary>
internal sealed class NativeE39FileWriteJournal :
    NativeE39WriteSequence.IDurableJournal, IAsyncDisposable
{
    private const int MaximumJournalBytes = 8 * 1024 * 1024;
    private readonly FileStream stream;
    private readonly string backupDirectory;
    private bool backupDirectoryCreated;
    private readonly SemaphoreSlim gate = new(1, 1);
    private bool disposed;
    private bool appendFailed;

    internal enum CompletionState
    {
        NoEvents, Interrupted, RecoveryRequired, Completed
    }

    internal sealed record Inspection(
        CompletionState State, bool SequenceValid, int PreparedSectors = 0,
        int WrittenSectors = 0, int FinalReadbackSectors = 0,
        uint? PossiblyIncompleteAddress = null, string? LastPhase = null)
    {
        internal bool RequiresAssessment => State != CompletionState.Completed;
    }

    internal NativeE39FileWriteJournal(string path)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(path);
        var full = Path.GetFullPath(path);
        backupDirectory = full + ".blocks";
        // An existing path can represent a flash interrupted days ago.
        // Never truncate it, even on an explicit second attempt.
        stream = new FileStream(full, FileMode.CreateNew,
            FileAccess.Write, FileShare.Read, 4096, FileOptions.WriteThrough);
    }

    /// <summary>
    /// Persist original bytes ACQUIRED FROM THE ECU in their own create-new,
    /// fsync'd and readback-verified sector file BEFORE the erase journal event.
    /// Preserve this directory with the transaction's .meta.json and journal.
    /// </summary>
    public async Task PreserveOriginalBlockAsync(
        uint address, byte[] liveBytes, string expectedSha256, CancellationToken token)
    {
        ArgumentNullException.ThrowIfNull(liveBytes);
        if (liveBytes.Length == 0 ||
            !string.Equals(Convert.ToHexString(SHA256.HashData(liveBytes)),
                expectedSha256, StringComparison.OrdinalIgnoreCase))
            throw new InvalidDataException("Physical sector bytes differ from the verified original.");
        await gate.WaitAsync(token);
        try
        {
            ObjectDisposedException.ThrowIf(disposed, this);
            if (appendFailed)
                throw new IOException("Interrupted journal cannot accept physical backup data.");
            if (!backupDirectoryCreated)
            {
                if (Directory.Exists(backupDirectory) || File.Exists(backupDirectory))
                    throw new IOException("Refusing to reuse an existing ECU sector-backup directory.");
                Directory.CreateDirectory(backupDirectory);
                backupDirectoryCreated = true;
            }
            var name = Path.Combine(backupDirectory,
                address.ToString("X8") + "-" + liveBytes.Length + ".ecu-block");
            var frozen = liveBytes.ToArray();
            var header = Encoding.UTF8.GetBytes(JsonSerializer.Serialize(new {
                Schema = 1, Region = "E39_MAIN_FLASH",
                Address = address, Length = frozen.Length,
                OriginalSha256 = expectedSha256.ToUpperInvariant()
            }) + "\n");
            await using (var output = new FileStream(name, FileMode.CreateNew,
                FileAccess.Write, FileShare.Read, 4096, FileOptions.WriteThrough))
            {
                await output.WriteAsync(header, token);
                await output.WriteAsync(frozen, token);
                output.Flush(flushToDisk: true);
            }
            // Require a newly read, byte-identical persistent capture.
            var check = File.ReadAllBytes(name);
            if (check.Length != header.Length + frozen.Length ||
                !check.AsSpan(0, header.Length).SequenceEqual(header) ||
                !check.AsSpan(header.Length).SequenceEqual(frozen))
                throw new InvalidDataException("Persisted ECU sector backup is incomplete or altered.");
        }
        catch
        {
            appendFailed = true;
            throw;
        }
        finally { gate.Release(); }
    }

    public async Task AppendAsync(
        NativeE39WriteSequence.Event evidence, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(evidence);
        await gate.WaitAsync(cancellationToken);
        try
        {
            ObjectDisposedException.ThrowIf(disposed, this);
            if (appendFailed)
                throw new IOException("Previous journal append failed; retain the interrupted journal for assessment.");
            var entry = JsonSerializer.Serialize(new
            {
                Schema = 3,
                UtcTimestamp = DateTimeOffset.UtcNow,
                Phase = evidence.Phase.ToString(),
                Address = evidence.Address,
                Detail = evidence.Detail
            });
            var encoded = Encoding.UTF8.GetBytes(entry + "\n");
            if (encoded.Length > MaximumJournalBytes - stream.Length)
                throw new IOException("ECU journal size limit reached; refusing further programming.");
            cancellationToken.ThrowIfCancellationRequested();
            try
            {
                await stream.WriteAsync(encoded, cancellationToken);
                // Flush(true) propagates file buffers to the storage subsystem.
                // Filesystem/drive caches are still a hardware-dependent boundary.
                stream.Flush(flushToDisk: true);
            }
            catch
            {
                // An interrupted write may already have left a JSON fragment.
                // Do not append another phase to a physically ambiguous record.
                appendFailed = true;
                throw;
            }
        }
        finally
        {
            gate.Release();
        }
    }

    internal static CompletionState Inspect(string path) => InspectDetailed(path).State;

    internal static Inspection InspectDetailed(string path)
    {
        if (!File.Exists(path))
            throw new FileNotFoundException("No ECU programming journal exists.", path);
        // Read a bounded snapshot, not an unbounded or changing text file.
        // A concurrently appended record may be absent from this snapshot;
        // the result is strictly evidence from the captured journal prefix.
        byte[] snapshot;
        using (var source = new FileStream(path, FileMode.Open, FileAccess.Read,
            FileShare.ReadWrite))
        {
            var length = source.Length;
            if (length == 0) return new(CompletionState.NoEvents, true);
            if (length > MaximumJournalBytes) return new(CompletionState.Interrupted, false);
            snapshot = new byte[(int)length];
            try { source.ReadExactly(snapshot); }
            catch (EndOfStreamException) { return new(CompletionState.Interrupted, false); }
        }
        string text;
        try { text = new UTF8Encoding(false, true).GetString(snapshot); }
        catch (DecoderFallbackException) { return new(CompletionState.Interrupted, false); }
        var terminated = text.EndsWith('\n');
        var entries = terminated ? text[..^1].Split('\n') : text.Split('\n');
        // A durable explicit recovery marker wins over malformed older records.
        // Do not interpret invalid JSON as evidence of success or erase its warning.
        foreach (var entry in entries)
        {
            try
            {
                using var json = JsonDocument.Parse(entry);
                var node = json.RootElement;
                if (node.ValueKind == JsonValueKind.Object &&
                    node.TryGetProperty("Schema", out var schema) &&
                    schema.ValueKind == JsonValueKind.Number && schema.TryGetInt32(out var version) &&
                    version is 1 or 2 or 3 &&
                    node.TryGetProperty("Phase", out var phase) && phase.ValueKind == JsonValueKind.String &&
                    phase.GetString() == nameof(NativeE39WriteSequence.Phase.RecoveryRequired) &&
                    node.TryGetProperty("Address", out var address) && address.ValueKind == JsonValueKind.Number &&
                    address.TryGetUInt32(out var reportedAddress))
                    return new(CompletionState.RecoveryRequired, false,
                        PossiblyIncompleteAddress: reportedAddress, LastPhase: "RecoveryRequired");
            }
            catch (JsonException) { /* still evaluate subsequent complete records */ }
        }
        // A complete-looking JSON object without its record terminator can
        // represent an interrupted append. It is not evidence of completion.
        if (!terminated) return new(CompletionState.Interrupted, false);

        var sectors = new Dictionary<uint, NativeE39WriteSequence.Phase>();
        var preflightStarted = false;
        var destructiveStarted = false;
        uint? activeSector = null;
        var completed = false;
        NativeE39WriteSequence.Phase? lastPhase = null;
        foreach (var entry in entries)
        {
            try
            {
                using var json = JsonDocument.Parse(entry);
                var node = json.RootElement;
                if (node.ValueKind != JsonValueKind.Object ||
                    node.EnumerateObject().GroupBy(p => p.Name, StringComparer.Ordinal)
                        .Any(g => g.Count() != 1) ||
                    node.GetProperty("Schema").GetInt32() != 3 ||
                    !Enum.TryParse<NativeE39WriteSequence.Phase>(
                        node.GetProperty("Phase").GetString(), out var phase) ||
                    !Enum.IsDefined(phase) ||
                    node.GetProperty("Phase").GetString() != phase.ToString() ||
                    !node.GetProperty("Address").TryGetUInt32(out var address))
                    return new(CompletionState.Interrupted, false);

                // Recovery is terminal even if subsequent records claim success.
                if (phase == NativeE39WriteSequence.Phase.RecoveryRequired)
                    return new(CompletionState.RecoveryRequired, false,
                        PossiblyIncompleteAddress: address, LastPhase: phase.ToString());
                if (completed) return new(CompletionState.Interrupted, false);

                lastPhase = phase;
                switch (phase)
                {
                    case NativeE39WriteSequence.Phase.Prepared:
                        if (preflightStarted || !sectors.TryAdd(address, phase))
                            return new(CompletionState.Interrupted, false);
                        break;
                    case NativeE39WriteSequence.Phase.OriginalCrcVerified:
                        preflightStarted = true;
                        if (destructiveStarted || !Advance(address,
                            NativeE39WriteSequence.Phase.Prepared, phase))
                            return new(CompletionState.Interrupted, false);
                        break;
                    case NativeE39WriteSequence.Phase.OriginalBytesVerified:
                        if (destructiveStarted || !Advance(address,
                            NativeE39WriteSequence.Phase.OriginalCrcVerified, phase))
                            return new(CompletionState.Interrupted, false);
                        break;
                    case NativeE39WriteSequence.Phase.TestWriteAccepted:
                        if (destructiveStarted || !Advance(address,
                            NativeE39WriteSequence.Phase.OriginalBytesVerified, phase))
                            return new(CompletionState.Interrupted, false);
                        break;
                    case NativeE39WriteSequence.Phase.EraseStarted:
                        if (activeSector.HasValue || sectors.Count == 0 ||
                            sectors.Values.Any(p => p != NativeE39WriteSequence.Phase.TestWriteAccepted &&
                                p != NativeE39WriteSequence.Phase.WrittenBytesVerified) ||
                            !Advance(address, NativeE39WriteSequence.Phase.TestWriteAccepted, phase))
                            return new(CompletionState.Interrupted, false);
                        destructiveStarted = true;
                        activeSector = address;
                        break;
                    case NativeE39WriteSequence.Phase.EraseAcknowledged:
                        if (activeSector != address || !Advance(address,
                            NativeE39WriteSequence.Phase.EraseStarted, phase))
                            return new(CompletionState.Interrupted, false);
                        break;
                    case NativeE39WriteSequence.Phase.WriteStarted:
                        if (activeSector != address || !Advance(address,
                            NativeE39WriteSequence.Phase.EraseAcknowledged, phase))
                            return new(CompletionState.Interrupted, false);
                        break;
                    case NativeE39WriteSequence.Phase.WrittenCrcVerified:
                        if (activeSector != address || !Advance(address,
                            NativeE39WriteSequence.Phase.WriteStarted, phase))
                            return new(CompletionState.Interrupted, false);
                        break;
                    case NativeE39WriteSequence.Phase.WrittenBytesVerified:
                        if (activeSector != address || !Advance(address,
                            NativeE39WriteSequence.Phase.WrittenCrcVerified, phase))
                            return new(CompletionState.Interrupted, false);
                        activeSector = null;
                        break;
                    case NativeE39WriteSequence.Phase.FinalBytesVerified:
                        if (!destructiveStarted || activeSector.HasValue ||
                            sectors.Values.Any(p => p != NativeE39WriteSequence.Phase.WrittenBytesVerified &&
                                p != NativeE39WriteSequence.Phase.FinalBytesVerified) ||
                            !Advance(address, NativeE39WriteSequence.Phase.WrittenBytesVerified, phase))
                            return new(CompletionState.Interrupted, false);
                        break;
                    case NativeE39WriteSequence.Phase.Completed:
                        if (address != 0 || !destructiveStarted || activeSector.HasValue ||
                            sectors.Count == 0 || sectors.Values.Any(p =>
                                p != NativeE39WriteSequence.Phase.FinalBytesVerified))
                            return new(CompletionState.Interrupted, false);
                        completed = true;
                        break;
                    default:
                        return new(CompletionState.Interrupted, false);
                }
            }
            catch (Exception error) when (error is JsonException or
                KeyNotFoundException or InvalidOperationException or FormatException or OverflowException)
            {
                return new(CompletionState.Interrupted, false);
            }
        }

        return new(completed ? CompletionState.Completed : CompletionState.Interrupted,
            true, sectors.Count,
            sectors.Values.Count(p => p is NativeE39WriteSequence.Phase.WrittenBytesVerified or
                NativeE39WriteSequence.Phase.FinalBytesVerified),
            sectors.Values.Count(p => p == NativeE39WriteSequence.Phase.FinalBytesVerified),
            activeSector, lastPhase?.ToString());

        bool Advance(uint address, NativeE39WriteSequence.Phase expected,
            NativeE39WriteSequence.Phase next)
        {
            if (!sectors.TryGetValue(address, out var current) || current != expected)
                return false;
            sectors[address] = next;
            return true;
        }
    }

    public async ValueTask DisposeAsync()
    {
        await gate.WaitAsync();
        try
        {
            if (disposed) return;
            disposed = true;
            try { stream.Flush(flushToDisk: true); }
            finally { await stream.DisposeAsync(); }
        }
        finally
        {
            gate.Release();
        }
    }
}
