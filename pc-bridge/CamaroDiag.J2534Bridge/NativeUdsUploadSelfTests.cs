namespace CamaroDiag.J2534Bridge;

/// <summary>Offline ISO 14229 upload regressions. No real ECU read is implied.</summary>
internal static class NativeUdsUploadSelfTests
{
    private sealed class FakeExchange : NativeUdsDownloadProtocol.IExchange
    {
        internal readonly Queue<(byte[] Request, byte[] Reply)> Frames = new();
        internal int Sent;
        internal void Enqueue(byte[] request, byte[] reply) =>
            Frames.Enqueue((request, reply));
        public Task<byte[]> SendAsync(byte[] request, CancellationToken token)
        {
            token.ThrowIfCancellationRequested();
            if (Frames.Count == 0)
                throw new InvalidOperationException(
                    "Unexpected ECU upload request after expected script.");
            var (expected, response) = Frames.Dequeue();
            if (!request.SequenceEqual(expected))
                throw new InvalidOperationException(
                    $"Wrong upload request: {Convert.ToHexString(request)}");
            Sent++;
            return Task.FromResult(response);
        }
    }

    internal static int Run()
    {
        var checks = 0;
        void Check(bool condition, string label)
        {
            checks++;
            if (!condition)
                throw new InvalidOperationException(
                    "UDS upload self-test failed: " + label);
        }
        void Reject<T>(Action action, string label) where T : Exception
        {
            var caught = false;
            try { action(); }
            catch (T) { caught = true; }
            Check(caught, label);
        }
        static NativeUdsUploadProtocol.Result Upload(
            FakeExchange fake, int length = 7, uint address = 0x80001000) =>
            NativeUdsUploadProtocol.UploadAsync(
                fake, address, length, CancellationToken.None)
                .GetAwaiter().GetResult();
        static byte[] UploadRequest(uint address, int length) =>
            UdsStandardServices.RequestUpload(address, (uint)length);

        var good = new FakeExchange();
        good.Enqueue(UploadRequest(0x80001000, 7), [0x75, 0x10, 0x05]);
        good.Enqueue([0x36, 0x01], [0x76, 0x01, 1, 2, 3]);
        good.Enqueue([0x36, 0x02], [0x76, 0x02, 4, 5, 6]);
        good.Enqueue([0x36, 0x03], [0x76, 0x03, 7]);
        good.Enqueue([0x37], [0x77]);
        var uploaded = Upload(good);
        Check(uploaded.Address == 0x80001000 &&
            uploaded.Image.SequenceEqual(
                new byte[] {1, 2, 3, 4, 5, 6, 7}) &&
            uploaded.Blocks == 3 && uploaded.LastSequenceCounter == 3 &&
            good.Sent == 5 && good.Frames.Count == 0,
            "Read-only ISO 14229 upload transfers exact physical request length.");

        var wideAddress = new FakeExchange();
        const ulong address64 = 0x1122334455667788UL;
        wideAddress.Enqueue(UdsStandardServices.RequestUpload(
            address64, 3, 8, 1), [0x75, 0x10, 0x05]);
        wideAddress.Enqueue([0x36, 0x01], [0x76, 0x01, 0xAA, 0xBB, 0xCC]);
        wideAddress.Enqueue([0x37], [0x77]);
        var wide = NativeUdsUploadProtocol.UploadAsync(wideAddress,
            address64, 3, 8, 1, CancellationToken.None)
            .GetAwaiter().GetResult();
        Check(wide.Address == address64 &&
            wide.Image.SequenceEqual(new byte[] {0xAA, 0xBB, 0xCC}) &&
            wide.Blocks == 1,
            "ISO 14229 upload 64-bit address and one-byte length use exact ALFI.");
        var wrongWidth = new FakeExchange();
        Reject<ArgumentOutOfRangeException>(() =>
            NativeUdsUploadProtocol.UploadAsync(
                wrongWidth, address64, 3, 4, 1, CancellationToken.None)
                .GetAwaiter().GetResult(),
            "Physical upload cannot truncate 64-bit address into four ALFI bytes.");
        Check(wrongWidth.Sent == 0,
            "Invalid 64-bit upload mapping rejected before opening a transport.");

        var wrongCounter = new FakeExchange();
        wrongCounter.Enqueue(UploadRequest(0x80001000, 7),
            [0x75, 0x10, 0x05]);
        wrongCounter.Enqueue([0x36, 0x01], [0x76, 0x02, 1, 2, 3]);
        Reject<InvalidDataException>(() => Upload(wrongCounter),
            "Stale TransferData sequence counter cannot contaminate a backup.");
        Check(wrongCounter.Sent == 2,
            "Malformed first UDS upload block stops before TransferExit.");

        var oversizedBlock = new FakeExchange();
        oversizedBlock.Enqueue(UploadRequest(0x80001000, 7),
            [0x75, 0x10, 0x04]);
        oversizedBlock.Enqueue([0x36, 0x01], [0x76, 0x01, 1, 2, 3]);
        Reject<InvalidDataException>(() => Upload(oversizedBlock),
            "UDS upload rejects response larger than ECU negotiated payload.");

        var emptyBlock = new FakeExchange();
        emptyBlock.Enqueue(UploadRequest(0x80001000, 7),
            [0x75, 0x10, 0x05]);
        emptyBlock.Enqueue([0x36, 0x01], [0x76, 0x01]);
        Reject<InvalidDataException>(() => Upload(emptyBlock),
            "Zero-byte transfer cannot loop forever.");

        var negative = new FakeExchange();
        negative.Enqueue(UploadRequest(0x80001000, 7),
            [0x7F, 0x35, 0x33]);
        Reject<IOException>(() => Upload(negative),
            "OEM security denial must propagate without invented seed/key.");

        var overflow = new FakeExchange();
        Reject<ArgumentOutOfRangeException>(() => Upload(
            overflow, 8, 0xFFFFFFFC),
            "32-bit ECU memory wrap is rejected before transmitting.");
        Check(overflow.Sent == 0,
            "Invalid ECU memory region is never requested.");

        var wrapping = new FakeExchange();
        wrapping.Enqueue(UploadRequest(0x1000, 257), [0x75, 0x10, 0x03]);
        for (var i = 0; i < 257; i++)
            wrapping.Enqueue([0x36, (byte)(i + 1)],
                [0x76, (byte)(i + 1), (byte)i]);
        wrapping.Enqueue([0x37], [0x77, 0xAA]);
        var wrapped = Upload(wrapping, 257, 0x1000);
        Check(wrapped.Image.Length == 257 &&
            wrapped.Blocks == 257 && wrapped.LastSequenceCounter == 1 &&
            wrapped.Image[255] == 255 && wrapped.Image[256] == 0,
            "UDS upload accepts standard block counter wrap 0xFF -> 0x00.");

        return checks;
    }
}
