using System.Buffers.Binary;

namespace CamaroDiag.J2534Bridge;

/// <summary>Local wire adapter for an independently qualified, already running
/// native CD39 RAM kernel. This does not open a J2534 device or authorize flash.
/// The caller must provide an isolated, correlated physical payload exchange
/// and independent supply measurement.</summary>
internal sealed class NativeE39KernelLink : NativeE39WriteSequence.IKernelLink
{
    private readonly Func<byte[], CancellationToken, Task<byte[]>> exchange;
    private readonly Func<CancellationToken, Task<uint?>> supply;
    private readonly int maximumReadBytes;
    private bool ready;
    private bool failed;

    internal NativeE39KernelLink(
        Func<byte[], CancellationToken, Task<byte[]>> exchange,
        Func<CancellationToken, Task<uint?>> supply, int maximumReadBytes)
    {
        ArgumentNullException.ThrowIfNull(exchange);
        ArgumentNullException.ThrowIfNull(supply);
        if (maximumReadBytes is < 1 or > 4090)
            throw new ArgumentOutOfRangeException(nameof(maximumReadBytes));
        this.exchange = exchange;
        this.supply = supply;
        this.maximumReadBytes = maximumReadBytes;
    }

    internal async Task ProbeAsync(CancellationToken token)
    {
        if (failed || ready) throw new InvalidOperationException("Kernel link is not fresh.");
        try
        {
            token.ThrowIfCancellationRequested();
            var reply = await exchange([0x3D, 0x00], token);
            // Probe must never mark an already-cancelled kernel session ready.
            token.ThrowIfCancellationRequested();
            byte[] expected = [0x7D, 0x00, 0x43, 0x44, 0x33, 0x39, 1, 0, 28, 0];
            if (!reply.SequenceEqual(expected))
                throw new InvalidDataException("Unrecognized, incompatible or recovery-required CD39 kernel.");
            ready = true;
        }
        catch { failed = true; throw; }
    }

    public async Task<byte[]> ExchangeAsync(byte[] request, CancellationToken token)
    {
        ArgumentNullException.ThrowIfNull(request);
        RequireReady();
        if (request.Length < 2 || request.Length > 4091 ||
            !(request[0] == 0x3D && request[1] is 2 or 5 ||
              request[0] == 0x36 && request[1] is 0 or 0x44 ||
              request[0] == 0x23 && request[1] is 0x14 or 0x24))
            throw new UnauthorizedAccessException("Unsupported native kernel command.");
        try
        {
            token.ThrowIfCancellationRequested();
            var reply = await exchange(request.ToArray(), token);
            // Fail closed even when an ECU adapter ignores the token and
            // returns an apparent positive erase/write/read acknowledgement.
            token.ThrowIfCancellationRequested();
            return reply;
        }
        catch { failed = true; throw; }
    }

    public async Task<uint?> ReadSupplyMillivoltsAsync(CancellationToken token)
    {
        RequireReady();
        try
        {
            token.ThrowIfCancellationRequested();
            var millivolts = await supply(token);
            token.ThrowIfCancellationRequested();
            return millivolts;
        }
        catch { failed = true; throw; }
    }

    public async Task<byte[]> ReadFlashBytesAsync(uint address, int length, CancellationToken token)
    {
        RequireReady();
        if (length <= 0 || (ulong)address + (uint)length > 0x300000)
            throw new ArgumentOutOfRangeException(nameof(length));
        var bytes = new byte[length];
        try
        {
            for (int offset = 0; offset < length;)
            {
                int count = Math.Min(maximumReadBytes, length - offset);
                byte[] request = [0x23, 0x24, 0, 0, 0, 0, 0, 0];
                BinaryPrimitives.WriteUInt32BigEndian(request.AsSpan(2, 4), address + (uint)offset);
                BinaryPrimitives.WriteUInt16BigEndian(request.AsSpan(6, 2), (ushort)count);
                var reply = await ExchangeAsync(request, token);
                if (reply.Length != count + 1 || reply[0] != 0x63)
                    throw new InvalidDataException("Native kernel readback was incomplete or refused.");
                Buffer.BlockCopy(reply, 1, bytes, offset, count);
                offset += count;
            }
            token.ThrowIfCancellationRequested();
            return bytes;
        }
        catch { failed = true; throw; }
    }

    private void RequireReady()
    {
        if (!ready || failed)
            throw new InvalidOperationException("A fresh native kernel handshake is required.");
    }
}
