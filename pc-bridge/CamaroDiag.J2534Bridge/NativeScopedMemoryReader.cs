using System.Security.Cryptography;
using System.Runtime.CompilerServices;

namespace CamaroDiag.J2534Bridge;

/// <summary>
/// Physical memory reading limited to the operator's explicit VIN-bound
/// J2534 scope. Never assumes a proprietary GM flash map. Reads twice,
/// rejects mismatches, and never exposes a half-complete backup.
/// </summary>
internal static class NativeScopedMemoryReader
{
    internal const int MaximumUiCaptureBytes = 16 * 1024 * 1024;

    // Cache only the ECU's supported, READ-ONLY VIN dialect on this specific
    // live transport. Long captures still re-read the exact VIN before every
    // pass and every 32 memory blocks, but do not wait for repeated silent
    // F190 timeouts once the physical GM 1A/90 route has succeeded.
    // Weak keys prevent retaining a disconnected adapter/session.
    private sealed class VinDialect { internal volatile bool PreferGmlan; }
    private static readonly ConditionalWeakTable<ITransport, VinDialect> VinDialects = new();

    // A 16 MiB capture with small read blocks can issue hundreds of thousands
    // of successful requests. Posting one WinForms Progress<T> callback per
    // request exhausts the UI message queue without changing any ECU data.
    internal static bool ShouldReportProgress(int blocks, int completed, int total) =>
        blocks > 0 && completed > 0 && completed <= total &&
            (blocks % 32 == 0 || completed == total);

    internal sealed class LiveVinMismatchException :
        UnauthorizedAccessException
    {
        internal LiveVinMismatchException() :
            base("Live ECU VIN no longer matches the approved memory scope.") { }
    }

    internal sealed class MemoryReadRefusedException(uint address, int size, byte nrc) :
        IOException($"ECU denied memory read at 0x{address:X8} ({size} bytes, NRC 0x{nrc:X2}). " +
            "No SecurityAccess, flash write or privileged retry was attempted.")
    {
        internal uint Address { get; } = address;
        internal int RequestedBytes { get; } = size;
        internal byte Nrc { get; } = nrc;
    }

    internal interface ITransport
    {
        Task<byte[]> ExchangeAsync(byte[] request, CancellationToken cancellationToken);
    }

    internal sealed record VerifiedCapture(
        byte[] Bytes, uint StartAddress, string ExpectedVin, string Sha256,
        int BlocksPerPass, byte[]? SecondBytes, J2534MemoryReadScope AcquiredScope);

    internal static byte[] BuildReadRequest(
        J2534MemoryReadScope scope, uint address, int bytes)
    {
        ArgumentNullException.ThrowIfNull(scope);
        if (bytes is < 1 or > 256)
            throw new ArgumentOutOfRangeException(nameof(bytes));
        var lenBytes = bytes > 255 ? 2 : 1;
        var request = new byte[2 + 4 + lenBytes];
        request[0] = 0x23;
        request[1] = lenBytes == 1 ? (byte)0x14 : (byte)0x24;
        request[2] = (byte)(address >> 24);
        request[3] = (byte)(address >> 16);
        request[4] = (byte)(address >> 8);
        request[5] = (byte)address;
        if (lenBytes == 2)
        {
            request[6] = (byte)(bytes >> 8);
            request[7] = (byte)bytes;
        }
        else
            request[6] = (byte)bytes;
        if (!scope.AllowsReadMemory(request))
            throw new UnauthorizedAccessException(
                "Memory read request violates the exact operator-approved VIN-bound scope.");
        return request;
    }

    internal static byte[] ParseReadResponse(byte[] reply, int requestedBytes)
    {
        ArgumentNullException.ThrowIfNull(reply);
        if (requestedBytes is < 1 or > 256)
            throw new ArgumentOutOfRangeException(nameof(requestedBytes));
        if (reply.Length == 3 && reply[0] == 0x7F && reply[1] == 0x23)
            throw new IOException($"The ECU refused memory read: NRC 0x{reply[2]:X2}.");
        if (reply.Length != requestedBytes + 1 || reply[0] != 0x63)
            throw new InvalidDataException("ECU memory response is truncated or malformed.");
        return reply[1..];
    }

    internal static async Task RequireLiveVinAsync(
        ITransport link, J2534MemoryReadScope scope, CancellationToken token)
    {
        token.ThrowIfCancellationRequested();
        var dialect = VinDialects.GetValue(link, static _ => new VinDialect());
        if (dialect.PreferGmlan)
        {
            var gmReply = await link.ExchangeAsync([0x1A, 0x90], token);
            token.ThrowIfCancellationRequested();
            if (!J2534Device.MatchesScopedGmlanVin(gmReply, scope.ExpectedVin))
                throw new LiveVinMismatchException();
            return;
        }
        bool gmlanFallback;
        try
        {
            var reply = await link.ExchangeAsync([0x22, 0xF1, 0x90], token);
            token.ThrowIfCancellationRequested();
            if (J2534Device.MatchesScopedMemoryVin(reply, scope.ExpectedVin))
                return;
            if (!J2534Device.IsUnsupportedUdsVinResponse(reply))
                throw new LiveVinMismatchException();
            gmlanFallback = true;
        }
        catch (TimeoutException)
        {
            // F190 silence on a GM GMLAN ECU is not proof of wrong hardware.
            // Attempt the read-only legacy VIN DID instead.
            gmlanFallback = true;
        }
        if (gmlanFallback)
        {
            var reply = await link.ExchangeAsync([0x1A, 0x90], token);
            token.ThrowIfCancellationRequested();
            if (J2534Device.MatchesScopedGmlanVin(reply, scope.ExpectedVin))
            {
                dialect.PreferGmlan = true;
                return;
            }
        }
        throw new LiveVinMismatchException();
    }

    /// <summary>
    /// Own the extended read session for a local capture. A successful capture
    /// must include confirmed return to default session. Cancellation still
    /// performs best-effort cleanup on the original, bounded transport.
    /// </summary>
    internal static async Task<VerifiedCapture> ReadWithSessionAsync(
        ITransport link, J2534MemoryReadScope scope,
        IProgress<(int Pass, int BytesRead, int TotalBytes)>? progress,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(link);
        ArgumentNullException.ThrowIfNull(scope);
        if (scope.Length == 0 || scope.Length > MaximumUiCaptureBytes)
            throw new ArgumentOutOfRangeException(nameof(scope));
        if (scope.Session == 1)
            return await ReadTwiceAsync(link, scope, progress, cancellationToken);
        if (scope.Session != 3)
            throw new UnauthorizedAccessException("Only default and extended read sessions are allowed.");

        await RequireLiveVinAsync(link, scope, cancellationToken);
        var requested = false;
        var captureCompleted = false;
        var identityUncertain = false;
        VerifiedCapture capture;
        try
        {
            cancellationToken.ThrowIfCancellationRequested();
            requested = true;
            var reply = await link.ExchangeAsync([0x10, 0x03], cancellationToken);
            RequireSessionReply(reply, 0x03);
            capture = await ReadTwiceAsync(link, scope, progress, cancellationToken);
            cancellationToken.ThrowIfCancellationRequested();
            captureCompleted = true;
        }
        catch (LiveVinMismatchException)
        {
            // A changed VIN is unsafe to address again. In contrast, a
            // scoped request denial on the SAME ECU must still attempt a
            // bounded return to the default session after verifying VIN.
            identityUncertain = true;
            throw;
        }
        finally
        {
            if (requested && !identityUncertain)
            {
                try
                {
                    // The physical link already bounds each exchange. Do not
                    // reconnect; re-confirm VIN before changing any session.
                    using var cleanup = new CancellationTokenSource(TimeSpan.FromSeconds(15));
                    await RequireLiveVinAsync(link, scope, cleanup.Token);
                    var reply = await link.ExchangeAsync([0x10, 0x01], cleanup.Token);
                    cleanup.Token.ThrowIfCancellationRequested();
                    RequireSessionReply(reply, 0x01);
                }
                catch (Exception) when (!captureCompleted)
                {
                    // Preserve the initial capture failure or cancellation.
                }
            }
        }
        cancellationToken.ThrowIfCancellationRequested();
        return capture;
    }

    private static void RequireSessionReply(byte[] reply, byte expected)
    {
        // Accept an echoed session response with either no timing fields or
        // the complete four-byte ISO 14229 timing pair. Reject trailing data.
        if (reply.Length is not (2 or 6) || reply[0] != 0x50 || reply[1] != expected)
            throw new InvalidDataException("ECU did not confirm the requested read session.");
    }

    internal static async Task<VerifiedCapture> ReadTwiceAsync(
        ITransport link,
        J2534MemoryReadScope scope,
        IProgress<(int Pass, int BytesRead, int TotalBytes)>? progress,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(link);
        ArgumentNullException.ThrowIfNull(scope);
        if (scope.Length > MaximumUiCaptureBytes)
            throw new ArgumentOutOfRangeException(nameof(scope),
                "A single Windows capture is limited to 16 MiB.");
        if (scope.Length == 0)
            throw new ArgumentException("Memory profile cannot be empty.");

        var size = checked((int)scope.Length);
        byte[]? first = null;
        byte[]? second = null;
        var blocksPerPass = 0;
        for (var pass = 1; pass <= 2; pass++)
        {
            // A connected interface can have been moved to another ECU.
            // Re-read the identity before each independent acquisition.
            await RequireLiveVinAsync(link, scope, cancellationToken);
            var output = new byte[size];
            var offset = 0;
            var blocks = 0;
            while (offset < size)
            {
                cancellationToken.ThrowIfCancellationRequested();
                if (blocks > 0 && blocks % 32 == 0)
                    await RequireLiveVinAsync(link, scope, cancellationToken);
                var length = Math.Min(scope.MaxReadBytes, size - offset);
                var address = checked(scope.StartAddress + (uint)offset);
                var request = BuildReadRequest(scope, address, length);
                var reply = await link.ExchangeAsync(request, cancellationToken);
                cancellationToken.ThrowIfCancellationRequested();
                if (reply.Length == 3 && reply[0] == 0x7F && reply[1] == 0x23)
                    throw new MemoryReadRefusedException(address, length, reply[2]);
                var data = ParseReadResponse(reply, length);
                data.CopyTo(output, offset);
                offset += length;
                blocks++;
                if (ShouldReportProgress(blocks, offset, size))
                    progress?.Report((pass, offset, size));
            }
            if (pass == 1)
            {
                first = output;
                blocksPerPass = blocks;
            }
            else
            {
                if (first is null || blocks != blocksPerPass ||
                    !CryptographicOperations.FixedTimeEquals(first, output))
                    throw new InvalidDataException(
                        "Two independent physical memory readings differ. No backup may be exported.");
                second = output;
            }
        }

        // A replacement during the final block must invalidate the entire
        // result even if the replacement happens to have identical bytes.
        await RequireLiveVinAsync(link, scope, cancellationToken);
        if (first is null || second is null)
            throw new InvalidDataException("No independent physical bytes received.");
        return new VerifiedCapture(
            first, scope.StartAddress, scope.ExpectedVin,
            Convert.ToHexString(SHA256.HashData(first)), blocksPerPass, second, scope);
    }
}
