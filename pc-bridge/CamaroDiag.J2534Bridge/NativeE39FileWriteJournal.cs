using System.Text;
using System.Text.Json;

namespace CamaroDiag.J2534Bridge;

/// <summary>
/// Append-only crash-resilient journal for a native ECU flash attempt.
/// One new file per operation; it NEVER overwrites an earlier recovery log.
/// Every phase is written and flushed to storage before an erase or write.
/// </summary>
internal sealed class NativeE39FileWriteJournal :
    NativeE39WriteSequence.IDurableJournal, IAsyncDisposable
{
    private readonly FileStream stream;
    private readonly SemaphoreSlim gate = new(1, 1);
    private bool disposed;

    internal enum CompletionState
    {
        NoEvents, Interrupted, RecoveryRequired, Completed
    }

    internal NativeE39FileWriteJournal(string path)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(path);
        var full = Path.GetFullPath(path);
        // An existing path can represent a flash interrupted days ago.
        // Never truncate it, even on an explicit second attempt.
        stream = new FileStream(full, FileMode.CreateNew,
            FileAccess.Write, FileShare.Read, 4096, FileOptions.WriteThrough);
    }

    public async Task AppendAsync(
        NativeE39WriteSequence.Event evidence, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(evidence);
        await gate.WaitAsync(cancellationToken);
        try
        {
            ObjectDisposedException.ThrowIf(disposed, this);
            var entry = JsonSerializer.Serialize(new
            {
                Schema = 3,
                UtcTimestamp = DateTimeOffset.UtcNow,
                Phase = evidence.Phase.ToString(),
                Address = evidence.Address,
                Detail = evidence.Detail
            });
            var encoded = Encoding.UTF8.GetBytes(entry + "\n");
            await stream.WriteAsync(encoded, cancellationToken);
            // Flush(true) propagates file buffers to the storage subsystem.
            // Filesystem/drive caches are still a hardware-dependent boundary.
            stream.Flush(flushToDisk: true);
        }
        finally
        {
            gate.Release();
        }
    }

    internal static CompletionState Inspect(string path)
    {
        if (!File.Exists(path))
            throw new FileNotFoundException("No ECU programming journal exists.", path);
        var entries = File.ReadAllLines(path);
        if (entries.Length == 0)
            return CompletionState.NoEvents;
        var sectors = new Dictionary<uint, NativeE39WriteSequence.Phase>();
        var preflightStarted = false;
        var destructiveStarted = false;
        uint? activeSector = null;
        var completed = false;
        foreach (var entry in entries)
        {
            try
            {
                using var json = JsonDocument.Parse(entry);
                var node = json.RootElement;
                if (node.GetProperty("Schema").GetInt32() != 3 ||
                    !Enum.TryParse<NativeE39WriteSequence.Phase>(
                        node.GetProperty("Phase").GetString(), out var phase) ||
                    !Enum.IsDefined(phase) ||
                    !node.GetProperty("Address").TryGetUInt32(out var address))
                    return CompletionState.Interrupted;

                // Recovery is terminal even if subsequent records claim success.
                if (phase == NativeE39WriteSequence.Phase.RecoveryRequired)
                    return CompletionState.RecoveryRequired;
                if (completed) return CompletionState.Interrupted;

                switch (phase)
                {
                    case NativeE39WriteSequence.Phase.Prepared:
                        if (preflightStarted || !sectors.TryAdd(address, phase))
                            return CompletionState.Interrupted;
                        break;
                    case NativeE39WriteSequence.Phase.OriginalCrcVerified:
                        preflightStarted = true;
                        if (destructiveStarted || !Advance(address,
                            NativeE39WriteSequence.Phase.Prepared, phase))
                            return CompletionState.Interrupted;
                        break;
                    case NativeE39WriteSequence.Phase.OriginalBytesVerified:
                        if (destructiveStarted || !Advance(address,
                            NativeE39WriteSequence.Phase.OriginalCrcVerified, phase))
                            return CompletionState.Interrupted;
                        break;
                    case NativeE39WriteSequence.Phase.TestWriteAccepted:
                        if (destructiveStarted || !Advance(address,
                            NativeE39WriteSequence.Phase.OriginalBytesVerified, phase))
                            return CompletionState.Interrupted;
                        break;
                    case NativeE39WriteSequence.Phase.EraseStarted:
                        if (activeSector.HasValue || sectors.Count == 0 ||
                            sectors.Values.Any(p => p != NativeE39WriteSequence.Phase.TestWriteAccepted &&
                                p != NativeE39WriteSequence.Phase.WrittenBytesVerified) ||
                            !Advance(address, NativeE39WriteSequence.Phase.TestWriteAccepted, phase))
                            return CompletionState.Interrupted;
                        destructiveStarted = true;
                        activeSector = address;
                        break;
                    case NativeE39WriteSequence.Phase.EraseAcknowledged:
                        if (activeSector != address || !Advance(address,
                            NativeE39WriteSequence.Phase.EraseStarted, phase))
                            return CompletionState.Interrupted;
                        break;
                    case NativeE39WriteSequence.Phase.WriteStarted:
                        if (activeSector != address || !Advance(address,
                            NativeE39WriteSequence.Phase.EraseAcknowledged, phase))
                            return CompletionState.Interrupted;
                        break;
                    case NativeE39WriteSequence.Phase.WrittenCrcVerified:
                        if (activeSector != address || !Advance(address,
                            NativeE39WriteSequence.Phase.WriteStarted, phase))
                            return CompletionState.Interrupted;
                        break;
                    case NativeE39WriteSequence.Phase.WrittenBytesVerified:
                        if (activeSector != address || !Advance(address,
                            NativeE39WriteSequence.Phase.WrittenCrcVerified, phase))
                            return CompletionState.Interrupted;
                        activeSector = null;
                        break;
                    case NativeE39WriteSequence.Phase.FinalBytesVerified:
                        if (!destructiveStarted || activeSector.HasValue ||
                            sectors.Values.Any(p => p != NativeE39WriteSequence.Phase.WrittenBytesVerified &&
                                p != NativeE39WriteSequence.Phase.FinalBytesVerified) ||
                            !Advance(address, NativeE39WriteSequence.Phase.WrittenBytesVerified, phase))
                            return CompletionState.Interrupted;
                        break;
                    case NativeE39WriteSequence.Phase.Completed:
                        if (address != 0 || !destructiveStarted || activeSector.HasValue ||
                            sectors.Count == 0 || sectors.Values.Any(p =>
                                p != NativeE39WriteSequence.Phase.FinalBytesVerified))
                            return CompletionState.Interrupted;
                        completed = true;
                        break;
                    default:
                        return CompletionState.Interrupted;
                }
            }
            catch (Exception error) when (error is JsonException or
                KeyNotFoundException or InvalidOperationException or FormatException or OverflowException)
            {
                return CompletionState.Interrupted;
            }
        }

        return completed ? CompletionState.Completed : CompletionState.Interrupted;

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
            stream.Flush(flushToDisk: true);
            await stream.DisposeAsync();
            disposed = true;
        }
        finally
        {
            gate.Release();
        }
    }
}
