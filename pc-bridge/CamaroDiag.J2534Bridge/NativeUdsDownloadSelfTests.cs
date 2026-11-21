namespace CamaroDiag.J2534Bridge;

internal static class NativeUdsDownloadSelfTests
{
    private sealed class ScriptedLink : NativeUdsDownloadProtocol.IExchange
    {
        internal readonly List<byte[]> Requests = [];
        internal Func<byte[], byte[]> Respond { get; init; } = _ => [];
        public Task<byte[]> SendAsync(byte[] request, CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            Requests.Add(request.ToArray());
            return Task.FromResult(Respond(request));
        }
    }

    internal static int Run()
    {
        var checks = 0;
        void Assert(bool success, string name)
        {
            checks++;
            if (!success) throw new InvalidOperationException("Native ECU transfer self-test failed: " + name);
        }
        static T Throws<T>(Action action) where T : Exception
        {
            try { action(); }
            catch (T error) { return error; }
            throw new InvalidOperationException("Expected " + typeof(T).Name);
        }

        Assert(Convert.ToHexString(NativeUdsDownloadProtocol.BuildRequestDownload(
            0x80001000, 0x10)) == "3400448000100000000010",
            "ISO 14229 RequestDownload 4-byte address and size");
        Assert(NativeUdsDownloadProtocol.ParseMaximumBlockLength(
            [0x74, 0x20, 0x00, 0x06]) == 4,
            "ECU maxNumberOfBlockLength includes SID/counter");

        Throws<InvalidDataException>(() =>
            NativeUdsDownloadProtocol.ParseMaximumBlockLength([0x74, 0x50, 0, 0, 0, 6, 0]));
        checks++;
        Throws<IOException>(() =>
            NativeUdsDownloadProtocol.ParseMaximumBlockLength([0x7F, 0x34, 0x33]));
        checks++;

        var blocked = new NativeUdsDownloadProtocol.TransferPlan(0x80001000, [1, 2, 3], 3, false);
        var unused = new ScriptedLink();
        Throws<UnauthorizedAccessException>(() =>
            NativeUdsDownloadProtocol.TransferAsync(unused, blocked,
                CancellationToken.None).GetAwaiter().GetResult());
        Assert(unused.Requests.Count == 0, "Unvalidated hardware blocked before all I/O");

        var link = new ScriptedLink
        {
            Respond = req => req[0] switch
            {
                0x34 => [0x74, 0x20, 0x00, 0x06],
                0x36 => [0x76, req[1]],
                0x37 => [0x77],
                _ => [0x7F, req[0], 0x11]
            }
        };
        var result = NativeUdsDownloadProtocol.TransferAsync(link,
            new(0x80001000, Enumerable.Range(0, 10).Select(x => (byte)x).ToArray(), 4, true),
            CancellationToken.None).GetAwaiter().GetResult();
        Assert(result.TransferBlocks == 3 && result.PayloadBytes == 10 &&
               result.LastBlockSequenceCounter == 3, "Three native blocks transferred");
        Assert(link.Requests.Count == 5, "Download + 3 transfer blocks + exit");
        Assert(Convert.ToHexString(link.Requests[1]) == "360100010203" &&
               Convert.ToHexString(link.Requests[2]) == "360204050607" &&
               Convert.ToHexString(link.Requests[3]) == "36030809",
               "Native TransferData counter and payload segmentation");
        Assert(link.Requests[^1].SequenceEqual(new byte[] { 0x37 }),
            "Native transfer exit sent only after acknowledged blocks");

        var wrapped = new ScriptedLink { Respond = link.Respond };
        var wrappedResult = NativeUdsDownloadProtocol.TransferAsync(wrapped,
            new(0, new byte[256], 1, true), CancellationToken.None)
            .GetAwaiter().GetResult();
        Assert(wrappedResult.TransferBlocks == 256 &&
               wrappedResult.LastBlockSequenceCounter == 0 &&
               wrapped.Requests[256][1] == 0,
               "Block counter correctly wraps from FF to 00");

        var failed = new ScriptedLink
        {
            Respond = req => req[0] switch
            {
                0x34 => [0x74, 0x10, 0x04],
                0x36 => [0x7F, 0x36, 0x72],
                0x37 => [0x77],
                _ => []
            }
        };
        Throws<IOException>(() =>
            NativeUdsDownloadProtocol.TransferAsync(failed,
                new(0, new byte[8], 2, true), CancellationToken.None)
                .GetAwaiter().GetResult());
        Assert(failed.Requests.Count == 2, "NRC stops without transfer exit or blind retry");

        var wrongCounter = new ScriptedLink
        {
            Respond = req => req[0] switch
            {
                0x34 => [0x74, 0x10, 0x04],
                0x36 => [0x76, (byte)(req[1] + 1)],
                _ => [0x77]
            }
        };
        Throws<InvalidDataException>(() =>
            NativeUdsDownloadProtocol.TransferAsync(wrongCounter,
                new(0, new byte[8], 2, true), CancellationToken.None)
                .GetAwaiter().GetResult());
        Assert(wrongCounter.Requests.Count == 2, "Invalid ACK stops before later writes");

        var cancelled = new ScriptedLink { Respond = link.Respond };
        using (var source = new CancellationTokenSource())
        {
            source.Cancel();
            Throws<OperationCanceledException>(() =>
                NativeUdsDownloadProtocol.TransferAsync(cancelled,
                    new(0, [0xCA], 1, true), source.Token)
                    .GetAwaiter().GetResult());
        }
        Assert(cancelled.Requests.Count == 0, "Cancellation before any ECU request");

        Throws<ArgumentOutOfRangeException>(() =>
            NativeUdsDownloadProtocol.BuildRequestDownload(0xFFFFFFFF, 2));
        checks++;
        Console.WriteLine($"PASS: {checks} native UDS transfer protocol self-tests.");
        return checks;
    }
}
