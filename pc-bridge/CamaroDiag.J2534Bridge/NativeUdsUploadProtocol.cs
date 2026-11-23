namespace CamaroDiag.J2534Bridge;

/// <summary>
/// Generic ISO 14229 RequestUpload (0x35), TransferData (0x36) and
/// RequestTransferExit (0x37) read-only transport. An ECU-specific caller
/// supplies physical address/length and performs its own session/security,
/// VIN/OSID, sector mapping, source verification and durable backup checks.
/// This transport is not proof that an ECU supports complete image reads.
/// </summary>
internal static class NativeUdsUploadProtocol
{
    internal const int MaximumReadBytes = 16 * 1024 * 1024;
    internal const int BridgeMaximumTransferData = 4089;

    internal sealed record Result(ulong Address, byte[] Image, int Blocks,
        byte LastSequenceCounter);

    internal sealed record DoubleReadResult(ulong Address, byte[] Image,
        string Sha256, int FirstTransferBlocks, int SecondTransferBlocks);

    /// <summary>
    /// Perform two independent UDS RequestUpload transactions and compare
    /// the exact returned bytes. The caller must still check identity, supply
    /// a qualified ECU region, and durably persist/verify the physical image.
    /// This does not attest that an ECU image is complete or restorable.
    /// </summary>
    internal static async Task<DoubleReadResult> UploadTwiceAsync(
        NativeUdsDownloadProtocol.IExchange link,
        ulong address, int length, byte addressBytes,
        byte lengthBytes, CancellationToken token)
    {
        var first = await UploadAsync(link, address, length,
            addressBytes, lengthBytes, token);
        token.ThrowIfCancellationRequested();
        var second = await UploadAsync(link, address, length,
            addressBytes, lengthBytes, token);
        if (first.Address != second.Address ||
            !first.Image.AsSpan().SequenceEqual(second.Image))
            throw new InvalidDataException(
                "Two physical UDS upload acquisitions differ; no validated backup image.");
        return new(address, first.Image,
            Convert.ToHexString(
                System.Security.Cryptography.SHA256.HashData(first.Image)),
            first.Blocks, second.Blocks);
    }

    internal static Task<Result> UploadAsync(
        NativeUdsDownloadProtocol.IExchange link,
        uint address, int length, CancellationToken token) =>
        UploadAsync(link, (ulong)address, length, 4, 4, token);

    internal static async Task<Result> UploadAsync(
        NativeUdsDownloadProtocol.IExchange link,
        ulong address, int length, byte addressBytes,
        byte lengthBytes, CancellationToken token)
    {
        ArgumentNullException.ThrowIfNull(link);
        if (length is < 1 or > MaximumReadBytes ||
            (ulong)length > ulong.MaxValue - address)
            throw new ArgumentOutOfRangeException(nameof(length),
                "UDS upload must be a bounded physical memory range.");
        // Check the caller's address field width and size field width
        // before sending anything. The payload remains a read request.
        var request = UdsStandardServices.RequestUpload(
            address, (ulong)length, addressBytes, lengthBytes);
        token.ThrowIfCancellationRequested();

        // This only requests a read from an already properly authenticated
        // ECU; no fabricated address map, seed/key, flash erase or write.
        var response = await link.SendAsync(request, token);
        NativeUdsDownloadProtocol.CheckPositiveReply(response, 0x35, 0x75);
        var negotiated = UdsStandardServices.DecodeTransferParameters(
            0x35, response);
        var maxPayload = (int)Math.Min(
            negotiated.MaxDataBytes, (ulong)BridgeMaximumTransferData);
        if (maxPayload < 1)
            throw new InvalidDataException(
                "ECU offered no usable UDS upload payload capacity.");

        var image = new byte[length];
        var received = 0;
        var counter = (byte)1;
        var blocks = 0;
        while (received < length)
        {
            token.ThrowIfCancellationRequested();
            response = await link.SendAsync(
                UdsStandardServices.TransferData(counter, []), token);
            NativeUdsDownloadProtocol.CheckPositiveReply(response, 0x36, 0x76);
            if (response.Length < 3 || response[1] != counter)
                throw new InvalidDataException(
                    "UDS upload response has wrong sequence counter or no bytes.");
            var count = response.Length - 2;
            if (count > maxPayload || count > length - received)
                throw new InvalidDataException(
                    "ECU upload block exceeds negotiated capacity or target region.");
            Buffer.BlockCopy(response, 2, image, received, count);
            received += count;
            blocks++;
            unchecked { counter++; }
        }
        token.ThrowIfCancellationRequested();
        response = await link.SendAsync(UdsStandardServices.TransferExit([]), token);
        NativeUdsDownloadProtocol.CheckPositiveReply(response, 0x37, 0x77);
        // TransferExit may contain ECU-defined result records. The generic
        // transport cannot interpret them as a verified full ECU image.
        return new Result(address, image, blocks,
            unchecked((byte)(counter - 1)));
    }
}
