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
        // Calls counts memory requests only; identity reads are independent.
        internal int Calls;
        internal int VinCalls;
        internal int? ChangeVinAfterMemoryCalls;
        internal bool MalformedVin;
        internal bool RejectDefaultSession;
        internal bool MalformedExtendedReply;
        internal bool ExtendedReplyWithTrailingByte;
        internal bool ExtendedReplyWithTimings;
        internal Action? AfterRead;
        internal readonly List<byte> Sessions = [];

        internal FakeEcu(uint start, byte[] memory)
        {
            this.start = start;
            this.memory = memory;
        }

        public Task<byte[]> ExchangeAsync(byte[] request, CancellationToken token)
        {
            token.ThrowIfCancellationRequested();
            if (request.SequenceEqual(new byte[] { 0x22, 0xF1, 0x90 }))
            {
                VinCalls++;
                if (MalformedVin) return Task.FromResult(new byte[] { 0x62, 0xF1, 0x90 });
                var vin = ChangeVinAfterMemoryCalls is int after && Calls >= after
                    ? "1G1FA1RXXR0123457" : "1G1FA1RXXR0123456";
                return Task.FromResult(new byte[] { 0x62, 0xF1, 0x90 }
                    .Concat(System.Text.Encoding.ASCII.GetBytes(vin)).ToArray());
            }
            if (request.Length == 2 && request[0] == 0x10)
            {
                Sessions.Add(request[1]);
                if (request[1] == 0x03 && MalformedExtendedReply)
                    return Task.FromResult(new byte[] { 0x50 });
                if (request[1] == 0x03 && ExtendedReplyWithTrailingByte)
                    return Task.FromResult(new byte[] { 0x50, 0x03, 0x12 });
                if (request[1] == 0x03 && ExtendedReplyWithTimings)
                    return Task.FromResult(new byte[] { 0x50, 0x03, 0x00, 0x32, 0x01, 0xF4 });
                if (request[1] == 0x01 && RejectDefaultSession)
                    return Task.FromResult(new byte[] { 0x50, 0x03 });
                return Task.FromResult(new byte[] { 0x50, request[1] });
            }
            Calls++;
            AfterRead?.Invoke();
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
        Assert(verified.SecondBytes is not null && verified.SecondBytes.SequenceEqual(memory) &&
            !ReferenceEquals(verified.Bytes, verified.SecondBytes),
            "Both separate acquisitions are retained for export");
        Assert(verified.BlocksPerPass == 3 && link.Calls == 6,
            "All three blocks were independently re-read");
        Assert(link.VinCalls == 3,
            "Live VIN is checked before each pass and after the final block");
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

        var wrongVin = new FakeEcu(scope.StartAddress, memory) { ChangeVinAfterMemoryCalls = 0 };
        Reject<UnauthorizedAccessException>(() =>
            NativeScopedMemoryReader.ReadTwiceAsync(
                wrongVin, scope, null, CancellationToken.None).GetAwaiter().GetResult());
        Assert(wrongVin.Calls == 0, "Incorrect initial VIN prevents every memory read");
        var swapped = new FakeEcu(scope.StartAddress, memory) { ChangeVinAfterMemoryCalls = 3 };
        Reject<UnauthorizedAccessException>(() =>
            NativeScopedMemoryReader.ReadTwiceAsync(
                swapped, scope, null, CancellationToken.None).GetAwaiter().GetResult());
        Assert(swapped.Calls == 3, "Changed VIN blocks the second acquisition");
        var finalSwap = new FakeEcu(scope.StartAddress, memory) { ChangeVinAfterMemoryCalls = 6 };
        Reject<UnauthorizedAccessException>(() =>
            NativeScopedMemoryReader.ReadTwiceAsync(
                finalSwap, scope, null, CancellationToken.None).GetAwaiter().GetResult());
        Assert(finalSwap.Calls == 6 && finalSwap.VinCalls == 3,
            "Final VIN mismatch rejects even identical data");
        var malformedVin = new FakeEcu(scope.StartAddress, memory) { MalformedVin = true };
        Reject<UnauthorizedAccessException>(() =>
            NativeScopedMemoryReader.ReadTwiceAsync(
                malformedVin, scope, null, CancellationToken.None).GetAwaiter().GetResult());
        Assert(malformedVin.Calls == 0, "Truncated VIN is never considered verified");

        var longMemory = new byte[5000];
        var longScope = new J2534MemoryReadScope(
            scope.ExpectedVin, scope.RequestCanId, scope.ResponseCanId, scope.ProtocolNumber,
            scope.StartAddress, (uint)longMemory.Length, 128, 1);
        var midReadSwap = new FakeEcu(scope.StartAddress, longMemory) { ChangeVinAfterMemoryCalls = 32 };
        Reject<UnauthorizedAccessException>(() =>
            NativeScopedMemoryReader.ReadTwiceAsync(
                midReadSwap, longScope, null, CancellationToken.None).GetAwaiter().GetResult());
        Assert(midReadSwap.Calls == 32, "Periodic identity checkpoint stops long capture");

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
            Assert(cancelled.Calls == 0 && cancelled.VinCalls == 0,
                "Cancelled memory request sends no ECU traffic");
        }
        var oversized = new J2534MemoryReadScope(
            "1G1FA1RXXR0123456", 0x7E0, 0x7E8, 33, 0x80000000,
            NativeScopedMemoryReader.MaximumUiCaptureBytes + 1u, 64, 3);
        Reject<ArgumentOutOfRangeException>(() =>
            NativeScopedMemoryReader.ReadTwiceAsync(
                link, oversized, null, CancellationToken.None).GetAwaiter().GetResult());
        checks++;

        var extended = new FakeEcu(scope.StartAddress, memory);
        var result = NativeScopedMemoryReader.ReadWithSessionAsync(
            extended, scope, null, CancellationToken.None).GetAwaiter().GetResult();
        Assert(result.Bytes.SequenceEqual(memory) &&
            extended.Sessions.SequenceEqual(new byte[] { 3, 1 }),
            "Successful extended capture restores the default diagnostic session");

        var failedReset = new FakeEcu(scope.StartAddress, memory) { RejectDefaultSession = true };
        Reject<InvalidDataException>(() => NativeScopedMemoryReader.ReadWithSessionAsync(
            failedReset, scope, null, CancellationToken.None).GetAwaiter().GetResult());
        Assert(failedReset.Calls == 6 && failedReset.Sessions.SequenceEqual(new byte[] { 3, 1 }),
            "Failed default-session confirmation prevents successful capture");

        var failedEnter = new FakeEcu(scope.StartAddress, memory) { MalformedExtendedReply = true };
        Reject<InvalidDataException>(() => NativeScopedMemoryReader.ReadWithSessionAsync(
            failedEnter, scope, null, CancellationToken.None).GetAwaiter().GetResult());
        Assert(failedEnter.Calls == 0 && failedEnter.Sessions.SequenceEqual(new byte[] { 3, 1 }),
            "Ambiguous extended-session response still triggers cleanup");

        var badTiming = new FakeEcu(scope.StartAddress, memory) { ExtendedReplyWithTrailingByte = true };
        Reject<InvalidDataException>(() => NativeScopedMemoryReader.ReadWithSessionAsync(
            badTiming, scope, null, CancellationToken.None).GetAwaiter().GetResult());
        Assert(badTiming.Calls == 0 && badTiming.Sessions.SequenceEqual(new byte[] { 3, 1 }),
            "Malformed session timing response cannot permit memory access");
        var timedSession = new FakeEcu(scope.StartAddress, memory) { ExtendedReplyWithTimings = true };
        var timedCapture = NativeScopedMemoryReader.ReadWithSessionAsync(
            timedSession, scope, null, CancellationToken.None).GetAwaiter().GetResult();
        Assert(timedCapture.Bytes.SequenceEqual(memory) &&
            timedSession.Sessions.SequenceEqual(new byte[] { 3, 1 }),
            "Complete positive UDS session timings remain supported");
        var replacement = new FakeEcu(scope.StartAddress, memory) { ChangeVinAfterMemoryCalls = 3 };
        Reject<UnauthorizedAccessException>(() => NativeScopedMemoryReader.ReadWithSessionAsync(
            replacement, scope, null, CancellationToken.None).GetAwaiter().GetResult());
        Assert(replacement.Sessions.SequenceEqual(new byte[] { 3 }),
            "Never send a session change to a replacement ECU");

        using (var cts = new CancellationTokenSource())
        {
            var stopped = new FakeEcu(scope.StartAddress, memory) { AfterRead = cts.Cancel };
            Reject<OperationCanceledException>(() => NativeScopedMemoryReader.ReadWithSessionAsync(
                stopped, scope, null, cts.Token).GetAwaiter().GetResult());
            Assert(stopped.Calls == 1 && stopped.Sessions.SequenceEqual(new byte[] { 3, 1 }),
                "Cancellation restores the original ECU read session without restarting capture");
        }
        Console.WriteLine($"PASS: {checks} native VIN-bound double-read memory self-tests.");
        return checks;
    }
}
