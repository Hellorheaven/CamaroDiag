using System.Security.Cryptography;

namespace CamaroDiag.J2534Bridge;

/// <summary>
/// Physical memory reading limited to the operator's explicit VIN-bound
/// J2534 scope. Never assumes a proprietary GM flash map. Reads twice,
/// rejects mismatches, and never exposes a half-complete backup.
/// </summary>
internal static class NativeScopedMemoryReader
{
    internal const int MaximumUiCaptureBytes = 16 * 1024 * 1024;

    internal interface ITransport
    {
        Task<byte[]> ExchangeAsync(byte[] request, CancellationToken cancellationToken);
    }

    internal sealed record VerifiedCapture(
        byte[] Bytes, uint StartAddress, string ExpectedVin, string Sha256,
        int BlocksPerPass);

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
        if (reply.Length == 3 && reply[0] == 0x7F && reply[1] == 0x23)
            throw new IOException($"The ECU refused memory read: NRC 0x{reply[2]:X2}.");
        if (reply.Length != requestedBytes + 1 || reply[0] != 0x63)
            throw new InvalidDataException("ECU memory response is truncated or malformed.");
        return reply[1..];
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
        var blocksPerPass = 0;
        for (var pass = 1; pass <= 2; pass++)
        {
            var output = new byte[size];
            var offset = 0;
            var blocks = 0;
            while (offset < size)
            {
                cancellationToken.ThrowIfCancellationRequested();
                var length = Math.Min(scope.MaxReadBytes, size - offset);
                var address = checked(scope.StartAddress + (uint)offset);
                var request = BuildReadRequest(scope, address, length);
                var reply = await link.ExchangeAsync(request, cancellationToken);
                var data = ParseReadResponse(reply, length);
                data.CopyTo(output, offset);
                offset += length;
                blocks++;
                progress?.Report((pass, offset, size));
            }
            if (pass == 1)
            {
                first = output;
                blocksPerPass = blocks;
            }
            else if (first is null || !CryptographicOperations.FixedTimeEquals(first, output))
                throw new InvalidDataException(
                    "Two independent physical memory readings differ. No backup may be exported.");
        }

        if (first is null) throw new InvalidDataException("No physical bytes received.");
        return new VerifiedCapture(
            first, scope.StartAddress, scope.ExpectedVin,
            Convert.ToHexString(SHA256.HashData(first)), blocksPerPass);
    }
}
