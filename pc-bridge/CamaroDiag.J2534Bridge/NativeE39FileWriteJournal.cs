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
                Schema = 1,
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
        NativeE39WriteSequence.Phase? last = null;
        foreach (var entry in entries)
        {
            // A torn/corrupted last line must NOT be interpreted as success.
            try
            {
                using var json = JsonDocument.Parse(entry);
                var node = json.RootElement;
                if (node.GetProperty("Schema").GetInt32() != 1 ||
                    !Enum.TryParse<NativeE39WriteSequence.Phase>(
                        node.GetProperty("Phase").GetString(), out var phase) ||
                    !Enum.IsDefined(phase))
                    return CompletionState.Interrupted;
                last = phase;
            }
            catch (Exception error) when (error is JsonException or
                KeyNotFoundException or InvalidOperationException or FormatException)
            {
                return CompletionState.Interrupted;
            }
        }
        return last switch
        {
            NativeE39WriteSequence.Phase.Completed => CompletionState.Completed,
            NativeE39WriteSequence.Phase.RecoveryRequired => CompletionState.RecoveryRequired,
            _ => CompletionState.Interrupted
        };
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
