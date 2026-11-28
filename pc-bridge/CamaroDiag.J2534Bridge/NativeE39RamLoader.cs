namespace CamaroDiag.J2534Bridge;

/// <summary>
/// Original GM E39/E39A bootloader wire implementation.
///
/// Technical protocol facts were cross-checked against independent public
/// descriptions and PCM Hammer's E39/E39A behavior; no source code or binary
/// kernel from that GPL project is included or linked.
///
/// E39 uses GMLAN 0x34 and address-framed 0x36 messages for a RAM-resident
/// loader; it does NOT use generic ISO-14229 0x36 block sequence counters.
/// </summary>
internal static class NativeE39RamLoader
{
    internal const uint RamLoadAddress = 0x40007000;
    internal const uint RamEntryAddress = 0x40007004;
    internal const int MaxJ2534PayloadBytes = 4091;
    internal const int MaximumKernelBytes = 128 * 1024;

    internal sealed record Frame(byte[] Payload, bool Executes);
    internal sealed record Plan(byte[] RequestDownload, IReadOnlyList<Frame> Frames,
        int FramedLength, string KernelSha256);

    /// <summary>
    /// Plans RAM staging from *caller-supplied* code. Never selects, downloads
    /// or incorporates a third-party binary. It never talks to the ECU.
    /// </summary>
    internal static Plan Build(byte[] ramCode, int maxIsoTpPayloadBytes)
    {
        ArgumentNullException.ThrowIfNull(ramCode);
        if (ramCode.Length is < 1 or > MaximumKernelBytes)
            throw new ArgumentOutOfRangeException(nameof(ramCode));
        if (maxIsoTpPayloadBytes is < 11 or > MaxJ2534PayloadBytes)
            throw new ArgumentOutOfRangeException(nameof(maxIsoTpPayloadBytes));

        // Both the copy header and the executing header must fit the adapter.
        // The executing block occupies 0x36, mode, load address and run address.
        var codePerBlock = maxIsoTpPayloadBytes - 10;
        var frames = new List<Frame>();
        for (var end = ramCode.Length; end > 0;)
        {
            var start = ((end - 1) / codePerBlock) * codePerBlock;
            var length = end - start;
            var executes = start == 0;
            var prefixBytes = executes ? 10 : 6;
            var payload = new byte[prefixBytes + length];
            payload[0] = 0x36;
            payload[1] = executes ? (byte)0x80 : (byte)0x00;
            WriteU32(payload, 2, executes ? RamLoadAddress
                : checked(RamLoadAddress + (uint)start + 4u));
            if (executes) WriteU32(payload, 6, RamEntryAddress);
            Buffer.BlockCopy(ramCode, start, payload, prefixBytes, length);
            frames.Add(new Frame(payload, executes));
            end = start;
        }

        if (frames.Count == 0 || !frames[^1].Executes ||
            frames.Take(frames.Count - 1).Any(x => x.Executes) ||
            frames.Any(x => x.Payload.Length > maxIsoTpPayloadBytes))
            throw new InvalidDataException("Invalid GM RAM staging plan.");

        // GMLAN's download length is the sum of framed TransferData messages,
        // rather than the raw code length or a standard UDS ALFI address/size.
        var total = frames.Sum(x => x.Payload.Length);
        if (total is < 1 or > 0xFFFFFF)
            throw new ArgumentOutOfRangeException(nameof(ramCode));
        byte[] begin =
        [
            0x34, 0x00, (byte)(total >> 16), (byte)(total >> 8), (byte)total
        ];

        return new Plan(begin, frames, total,
            Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(ramCode)));
    }

    // Snapshot and validate the entire plan before sending any diagnostic frame.
    // A modified plan must not redirect a copy/entry address or silently change
    // the reviewed kernel bytes after the SHA-256 was calculated.
    private static Plan ValidateAndSnapshot(Plan plan)
    {
        if (plan.Frames is null || plan.RequestDownload is null ||
            plan.Frames.Count is < 1 or > MaximumKernelBytes ||
            plan.RequestDownload.Length != 5)
            throw new ArgumentException("Invalid E39 staging plan.");
        var frames = new List<Frame>(plan.Frames.Count);
        int codeLength = 0;
        int wireLength = 0;
        foreach (var frame in plan.Frames)
        {
            if (frame is null || frame.Payload is null ||
                frame.Payload.Length > MaxJ2534PayloadBytes ||
                frame.Payload.Length <= (frame.Executes ? 10 : 6))
                throw new InvalidDataException("Invalid E39 RAM transfer frame.");
            byte[] payload = frame.Payload.ToArray();
            codeLength = checked(codeLength + payload.Length - (frame.Executes ? 10 : 6));
            wireLength = checked(wireLength + payload.Length);
            if (codeLength > MaximumKernelBytes)
                throw new InvalidDataException("RAM transfer exceeds the allowed size.");
            frames.Add(new Frame(payload, frame.Executes));
        }
        byte[] begin = plan.RequestDownload.ToArray();
        if (wireLength != plan.FramedLength ||
            !begin.SequenceEqual(new byte[] { 0x34, 0, (byte)(wireLength >> 16),
                (byte)(wireLength >> 8), (byte)wireLength }) ||
            frames.Take(frames.Count - 1).Any(f => f.Executes) || !frames[^1].Executes)
            throw new InvalidDataException("Invalid E39 RAM transfer sequence.");

        byte[] reconstructed = new byte[codeLength];
        int offset = 0;
        foreach (var frame in frames.AsEnumerable().Reverse())
        {
            int header = frame.Executes ? 10 : 6;
            uint address = frame.Executes ? RamLoadAddress : checked(RamEntryAddress + (uint)offset);
            var payload = frame.Payload;
            if (payload[0] != 0x36 || payload[1] != (frame.Executes ? 0x80 : 0x00) ||
                System.Buffers.Binary.BinaryPrimitives.ReadUInt32BigEndian(payload.AsSpan(2, 4)) != address ||
                (frame.Executes && System.Buffers.Binary.BinaryPrimitives.ReadUInt32BigEndian(
                    payload.AsSpan(6, 4)) != RamEntryAddress))
                throw new InvalidDataException("Unexpected E39 RAM target or entry address.");
            Buffer.BlockCopy(payload, header, reconstructed, offset, payload.Length - header);
            offset += payload.Length - header;
        }
        string hash = Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(reconstructed));
        if (!string.Equals(hash, plan.KernelSha256, StringComparison.Ordinal))
            throw new InvalidDataException("E39 kernel hash mismatch before staging.");
        return new Plan(begin, frames.AsReadOnly(), wireLength, hash);
    }

    private static void WriteU32(byte[] into, int offset, uint value)
    {
        into[offset] = (byte)(value >> 24);
        into[offset + 1] = (byte)(value >> 16);
        into[offset + 2] = (byte)(value >> 8);
        into[offset + 3] = (byte)value;
    }

    internal static void RequireAcknowledgment(byte[] reply, bool executionFrame)
    {
        ArgumentNullException.ThrowIfNull(reply);
        if (reply.Length == 3 && reply[0] == 0x7F)
            throw new IOException(
                $"GMLAN loader refused service 0x{reply[1]:X2}: NRC 0x{reply[2]:X2}.");

        var positive = executionFrame ? (byte)0x99 : (byte)0x76;
        if (reply.Length != 1 || reply[0] != positive)
            throw new InvalidDataException(
                $"Unexpected E39 loader response; required 0x{positive:X2}.");
    }

    internal interface ILink
    {
        Task<byte[]> ExchangeAsync(byte[] request, CancellationToken cancellationToken);
        Task<uint?> ReadSupplyMillivoltsAsync(CancellationToken cancellationToken);
    }

    /// <summary>
    /// Sends only a RAM staging plan, never a flash erase or a calibration.
    /// Called solely by a future family-specific, bench-validated programmer:
    /// the authenticated programming session must already be established.
    /// </summary>
    internal static async Task StageAsync(
        ILink link, Plan plan, uint minimumSupplyMillivolts, uint maximumSupplyMillivolts,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(link);
        ArgumentNullException.ThrowIfNull(plan);
        cancellationToken.ThrowIfCancellationRequested();
        plan = ValidateAndSnapshot(plan);
        if (minimumSupplyMillivolts == 0 || maximumSupplyMillivolts <= minimumSupplyMillivolts)
            throw new ArgumentException("No qualified RAM staging supply window.");
        await RequireSupplyAsync();
        var response = await link.ExchangeAsync(plan.RequestDownload, cancellationToken);
        // Do not stream any RAM block after a late cancelled download ACK.
        cancellationToken.ThrowIfCancellationRequested();
        // Stock GM bootloader acknowledges download with 0x74. Its response
        // is not the generic ISO-14229 maxBlockLength structure.
        if (response.Length == 3 && response[0] == 0x7F)
            throw new IOException($"E39 download denied with NRC 0x{response[2]:X2}.");
        if (response.Length == 0 || response[0] != 0x74)
            throw new InvalidDataException("E39 RequestDownload was not acknowledged.");
        foreach (var frame in plan.Frames)
        {
            await RequireSupplyAsync();
            response = await link.ExchangeAsync(frame.Payload, cancellationToken);
            cancellationToken.ThrowIfCancellationRequested();
            RequireAcknowledgment(response, frame.Executes);
        }
        await RequireSupplyAsync();

        async Task RequireSupplyAsync()
        {
            cancellationToken.ThrowIfCancellationRequested();
            var value = await link.ReadSupplyMillivoltsAsync(cancellationToken);
            cancellationToken.ThrowIfCancellationRequested();
            if (!value.HasValue || value.Value < minimumSupplyMillivolts ||
                value.Value > maximumSupplyMillivolts)
                throw new IOException("Supply outside qualified E39 RAM staging window.");
        }
        // Launch acknowledgement is NOT a proof of a valid recovery path or
        // of successful flash erase/program. The kernel must be probed by a
        // separate ECU-family-specific implementation.
    }
}
