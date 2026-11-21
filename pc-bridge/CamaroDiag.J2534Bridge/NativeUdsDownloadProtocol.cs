namespace CamaroDiag.J2534Bridge;

/// <summary>
/// Native ISO 14229 download/transfer/exit protocol engine for supported ECU
/// bootloaders. No PCM Hammer executable, libraries or proprietary kernels.
///
/// This is only the standard data transport portion of ECU programming.
/// A family-specific driver must separately supply an independently verified
/// flash layout, image checksum policy, security/session procedure, erase,
/// readback and recovery. Never infer those from J2534 transport support.
/// </summary>
internal static class NativeUdsDownloadProtocol
{
    internal sealed record TransferPlan(
        uint Address,
        byte[] Image,
        int MaximumApplicationBlockBytes,
        bool HardwareTargetValidated)
    {
        public void Validate()
        {
            if (!HardwareTargetValidated)
                throw new UnauthorizedAccessException(
                    "Target bootloader and recovery have not been physically validated.");
            if (Image.Length == 0 || Image.Length > 0x1000000)
                throw new ArgumentOutOfRangeException(nameof(Image),
                    "Download image must be between 1 and 16 MiB.");
            if ((ulong)Address + (uint)Image.Length > 0x1_0000_0000UL)
                throw new ArgumentOutOfRangeException(nameof(Address),
                    "Download address range wraps the 32-bit space.");
            if (MaximumApplicationBlockBytes is < 1 or > 4089)
                throw new ArgumentOutOfRangeException(nameof(MaximumApplicationBlockBytes),
                    "Payload must fit into a 4091-byte J2534 ISO-TP message.");
        }
    }

    /// <summary>
    /// A production family-specific driver owns the message exchange. The
    /// generic network bridge intentionally does not expose this engine.
    /// </summary>
    internal interface IExchange
    {
        Task<byte[]> SendAsync(byte[] request, CancellationToken cancellationToken);
    }

    internal sealed record Result(
        int PayloadBytes,
        int TransferBlocks,
        byte LastBlockSequenceCounter);

    internal static byte[] BuildRequestDownload(uint address, uint length)
    {
        if (length == 0 || (ulong)address + length > 0x1_0000_0000UL)
            throw new ArgumentOutOfRangeException(nameof(length));
        // ISO 14229: dataFormatIdentifier=0 (no compression/encryption),
        // addressAndLengthFormatIdentifier=0x44 (4-byte size and address).
        return
        [
            0x34, 0x00, 0x44,
            (byte)(address >> 24), (byte)(address >> 16),
            (byte)(address >> 8), (byte)address,
            (byte)(length >> 24), (byte)(length >> 16),
            (byte)(length >> 8), (byte)length
        ];
    }

    internal static int ParseMaximumBlockLength(byte[] reply)
    {
        CheckPositiveReply(reply, requestService: 0x34, positiveService: 0x74);
        if (reply.Length < 3 || (reply[1] & 0x0F) != 0)
            throw new InvalidDataException("Invalid UDS RequestDownload block length format.");
        var sizeWidth = reply[1] >> 4;
        if (sizeWidth is < 1 or > 4 || reply.Length != sizeWidth + 2)
            throw new InvalidDataException("Unexpected UDS maxNumberOfBlockLength encoding.");
        var max = 0UL;
        for (var i = 0; i < sizeWidth; i++)
            max = (max << 8) | reply[i + 2];
        if (max < 3 || max > int.MaxValue)
            throw new InvalidDataException("ECU reported an unsupported transfer block size.");
        // maxNumberOfBlockLength includes the 0x36 service and the block counter.
        return (int)max - 2;
    }

    internal static void CheckPositiveReply(
        byte[] reply, byte requestService, byte positiveService)
    {
        if (reply.Length == 3 && reply[0] == 0x7F &&
            reply[1] == requestService)
            throw new IOException(
                $"UDS service 0x{requestService:X2} rejected with NRC 0x{reply[2]:X2}.");
        if (reply.Length == 0 || reply[0] != positiveService)
            throw new InvalidDataException(
                $"Expected UDS positive response 0x{positiveService:X2}.");
    }

    internal static async Task<Result> TransferAsync(
        IExchange link, TransferPlan plan, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(link);
        ArgumentNullException.ThrowIfNull(plan);
        plan.Validate();
        cancellationToken.ThrowIfCancellationRequested();

        // A production caller MUST complete authenticated programming session,
        // target-specific erase and crash-safe journal BEFORE calling TransferAsync.
        var reply = await link.SendAsync(
            BuildRequestDownload(plan.Address, (uint)plan.Image.Length),
            cancellationToken);
        var maxData = Math.Min(
            ParseMaximumBlockLength(reply), plan.MaximumApplicationBlockBytes);
        if (maxData == 0) throw new InvalidDataException("Zero-size ECU transfer block.");

        var sequenceCounter = (byte)1;
        var blocks = 0;
        for (var offset = 0; offset < plan.Image.Length; offset += maxData)
        {
            cancellationToken.ThrowIfCancellationRequested();
            var count = Math.Min(maxData, plan.Image.Length - offset);
            var request = new byte[count + 2];
            request[0] = 0x36;
            request[1] = sequenceCounter;
            Buffer.BlockCopy(plan.Image, offset, request, 2, count);
            reply = await link.SendAsync(request, cancellationToken);
            CheckPositiveReply(reply, 0x36, 0x76);
            if (reply.Length != 2 || reply[1] != sequenceCounter)
                throw new InvalidDataException(
                    $"UDS TransferData returned an incorrect block counter at offset {offset}.");
            blocks++;
            unchecked { sequenceCounter++; }
        }
        cancellationToken.ThrowIfCancellationRequested();
        reply = await link.SendAsync([0x37], cancellationToken);
        CheckPositiveReply(reply, 0x37, 0x77);
        if (reply.Length != 1)
            throw new InvalidDataException(
                "Unexpected UDS RequestTransferExit response; family-specific checks required.");
        return new Result(plan.Image.Length, blocks, unchecked((byte)(sequenceCounter - 1)));
    }
}
