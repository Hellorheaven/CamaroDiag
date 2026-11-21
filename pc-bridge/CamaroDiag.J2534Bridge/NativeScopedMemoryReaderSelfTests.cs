namespace CamaroDiag.J2534Bridge;

internal static class NativeScopedMemoryReaderSelfTests
{
    private sealed class FakeEcu : NativeScopedMemoryReader.ITransport
    {
        private readonly uint start;
        private readonly byte[] memory;
        internal bool CorruptSecondPass;
        internal bool Deny;
        internal bool Truncate;
        internal int Calls;

        internal FakeEcu(uint start, byte[] memory)
        {
            this.start = start;
            this.memory = memory;
        }

        public Task<byte[]> ExchangeAsync(byte[] request, CancellationToken token)
        {
            token.ThrowIfCancellationRequested();
            Calls++;
            if (Deny) return Task.FromResult(new byte[] { 0x7F, 0x23, 0x31 });
            var address = ((uint)request[2] << 24) | ((uint)request[3] << 16) |
                ((uint)request[4] << 8) | request[5];
            var length = request[1] == 0x14 ? request[6] : (request[6] << 8) | request[7];
            var response = new byte[length + 1];
            response[0] = 0x63;
            Array.Copy(memory, checked((int)(address - start)), response, 1, length);
            if (CorruptSecondPass && Calls > 3) response[1] ^= 0x01;
            if (Truncate) response = response[..^1];
            return Task.FromResult(response);
        }
    }

    private sealed class ProgressCount : IProgress<(int Pass, int BytesRead, int TotalBytes)>
    {
        internal readonly List<(int Pass, int BytesRead, int TotalBytes)> Reports = [];
        public void Report((int Pass, int BytesRead, int TotalBytes) report) =>
            Reports.Add(report);
    }

    internal static int Run()
    {
        var checks = 0;
        void Assert(bool value, string label)
        {
            checks++;
            if (!value) throw new InvalidOperationException(
                "Scoped ECU memory read self-test failed: " + label);
        }
        static void Reject<T>(Action action) where T : Exception
        {
            try { action(); }
            catch (T) { return; }
            throw new InvalidOperationException("Expected " + typeof(T).Name);
        }

        var scope = new J2534MemoryReadScope(
            "1G1FA1RXXR0123456", 0x7E0, 0x7E8, 33, 0x80001000, 300, 128, 3);
        var memory = Enumerable.Range(0, 300).Select(i => (byte)i).ToArray();
        var link = new FakeEcu(scope.StartAddress, memory);
        var progress = new ProgressCount();
        var verified = NativeScopedMemoryReader.ReadTwiceAsync(
            link, scope, progress, CancellationToken.None).GetAwaiter().GetResult();

        Assert(verified.Bytes.SequenceEqual(memory) && verified.Bytes.Length == 300,
            "Two physical reads return exactly the requested memory range");
        Assert(verified.BlocksPerPass == 3 && link.Calls == 6,
            "All three blocks were independently re-read");
        Assert(verified.Sha256.Length == 64 &&
               verified.StartAddress == 0x80001000 &&
               verified.ExpectedVin == "1G1FA1RXXR0123456",
            "Verified memory captures retain scoped VIN, address and SHA256");
        Assert(progress.Reports.Count == 6 &&
               progress.Reports[0] == (1, 128, 300) &&
               progress.Reports[^1] == (2, 300, 300),
            "Independent passes emit monotonic progress");
        Assert(Convert.ToHexString(NativeScopedMemoryReader.BuildReadRequest(
               scope, 0x80001080, 128)) == "23148000108080",
            "UDS 23 ALFI address and one-byte size framing");
        var allowed256 = new J2534MemoryReadScope(
            "1G1FA1RXXR0123456", 0x7E0, 0x7E8, 33, 0x80001000, 300, 256, 3);
        Assert(Convert.ToHexString(NativeScopedMemoryReader.BuildReadRequest(
               allowed256, 0x80001000, 256)) == "2324800010000100",
            "UDS 23 two-byte length framing for 256-byte blocks");
        Reject<UnauthorizedAccessException>(() =>
            NativeScopedMemoryReader.BuildReadRequest(scope, 0x80000FFF, 1));
        checks++;
        Reject<UnauthorizedAccessException>(() =>
            NativeScopedMemoryReader.BuildReadRequest(scope, 0x80001120, 64));
        checks++;

        var mismatch = new FakeEcu(scope.StartAddress, memory) { CorruptSecondPass = true };
        Reject<InvalidDataException>(() =>
            NativeScopedMemoryReader.ReadTwiceAsync(
                mismatch, scope, null, CancellationToken.None).GetAwaiter().GetResult());
        checks++;
        var denied = new FakeEcu(scope.StartAddress, memory) { Deny = true };
        Reject<IOException>(() =>
            NativeScopedMemoryReader.ReadTwiceAsync(
                denied, scope, null, CancellationToken.None).GetAwaiter().GetResult());
        checks++;
        Assert(denied.Calls == 1, "A negative ECU response halts before a second attempt");
        var truncated = new FakeEcu(scope.StartAddress, memory) { Truncate = true };
        Reject<InvalidDataException>(() =>
            NativeScopedMemoryReader.ReadTwiceAsync(
                truncated, scope, null, CancellationToken.None).GetAwaiter().GetResult());
        checks++;
        using (var cts = new CancellationTokenSource())
        {
            cts.Cancel();
            var cancelled = new FakeEcu(scope.StartAddress, memory);
            Reject<OperationCanceledException>(() =>
                NativeScopedMemoryReader.ReadTwiceAsync(
                    cancelled, scope, null, cts.Token).GetAwaiter().GetResult());
            Assert(cancelled.Calls == 0, "Cancelled memory request sends no ECU traffic");
        }
        var oversized = new J2534MemoryReadScope(
            "1G1FA1RXXR0123456", 0x7E0, 0x7E8, 33, 0x80000000,
            NativeScopedMemoryReader.MaximumUiCaptureBytes + 1u, 64, 3);
        Reject<ArgumentOutOfRangeException>(() =>
            NativeScopedMemoryReader.ReadTwiceAsync(
                link, oversized, null, CancellationToken.None).GetAwaiter().GetResult());
        checks++;

        Console.WriteLine($"PASS: {checks} native VIN-bound double-read memory self-tests.");
        return checks;
    }
}
