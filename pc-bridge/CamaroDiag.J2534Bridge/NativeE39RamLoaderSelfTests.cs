namespace CamaroDiag.J2534Bridge;

internal static class NativeE39RamLoaderSelfTests
{
    private sealed class FakeLink : NativeE39RamLoader.ILink
    {
        internal readonly List<byte[]> Transmitted = [];
        internal uint? Supply = 13500;
        internal int LoseSupplyAfterFrames = int.MaxValue;
        public Task<uint?> ReadSupplyMillivoltsAsync(CancellationToken token)
        {
            token.ThrowIfCancellationRequested();
            return Task.FromResult(Transmitted.Count >= LoseSupplyAfterFrames ? (uint?)null : Supply);
        }
        internal Func<byte[], byte[]> Response = request => request[0] switch
        {
            0x34 => [0x74],
            0x36 when request[1] == 0x80 => [0x99],
            0x36 => [0x76],
            _ => [0x7F, request[0], 0x11]
        };

        public Task<byte[]> ExchangeAsync(byte[] request, CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            Transmitted.Add(request.ToArray());
            return Task.FromResult(Response(request));
        }
    }

    internal static int Run()
    {
        var checks = 0;
        void Assert(bool success, string reason)
        {
            checks++;
            if (!success)
                throw new InvalidOperationException("Native E39 loader self-test failed: " + reason);
        }

        static void MustFail<TException>(Action work) where TException : Exception
        {
            try { work(); }
            catch (TException) { return; }
            throw new InvalidOperationException(
                "Missing expected " + typeof(TException).Name + " failure.");
        }

        var image = Enumerable.Range(0, 2092).Select(x => (byte)x).ToArray();
        var plan = NativeE39RamLoader.Build(image, 2048);
        Assert(plan.Frames.Count == 2 &&
            plan.Frames[0].Payload.Length == 60 &&
            plan.Frames[1].Payload.Length == 2048, "RAM upload splits at ISO-TP payload boundary");
        Assert(Convert.ToHexString(plan.RequestDownload) == "340000083C",
            "GMLAN 3-byte framed transfer length, not generic ISO-14229 ALFI");
        Assert(Convert.ToHexString(plan.Frames[0].Payload.AsSpan(0, 6)) ==
               "3600400077FA" && !plan.Frames[0].Executes,
               "Tail copied to high RAM address plus 4-byte entry seam");
        Assert(Convert.ToHexString(plan.Frames[1].Payload.AsSpan(0, 10)) ==
               "36804000700040007004" && plan.Frames[1].Executes,
               "Final frame launches kernel only after copying remaining bytes");
        Assert(plan.KernelSha256.Length == 64, "Kernel SHA-256 attached to plan");

        var accepted = new FakeLink();
        NativeE39RamLoader.StageAsync(accepted, plan, 12000, 15000, CancellationToken.None)
            .GetAwaiter().GetResult();
        Assert(accepted.Transmitted.Count == 3 &&
               accepted.Transmitted[0].SequenceEqual(plan.RequestDownload) &&
               accepted.Transmitted[2].SequenceEqual(plan.Frames[^1].Payload),
            "Native staged transfer sends begin + copies + execute in order");

        // Reject altered plan contents, wire length, entry point and target addresses
        // before RequestDownload can affect the connected ECU.
        foreach (var change in new Action<NativeE39RamLoader.Plan>[]
        {
            p => p.Frames[0].Payload[2] ^= 1,
            p => p.Frames[^1].Payload[9] ^= 1,
            p => p.Frames[0].Payload[^1] ^= 1,
            p => p.RequestDownload[^1] ^= 1
        })
        {
            var altered = NativeE39RamLoader.Build(image, 2048);
            change(altered);
            var unused = new FakeLink();
            MustFail<InvalidDataException>(() =>
                NativeE39RamLoader.StageAsync(unused, altered, 12000, 15000, CancellationToken.None)
                    .GetAwaiter().GetResult());
            Assert(unused.Transmitted.Count == 0, "Altered staging plan rejected before I/O");
        }

        var noSupply = new FakeLink { Supply = null };
        MustFail<IOException>(() =>
            NativeE39RamLoader.StageAsync(noSupply, plan, 12000, 15000, CancellationToken.None)
                .GetAwaiter().GetResult());
        Assert(noSupply.Transmitted.Count == 0, "Missing supply prevents download request.");

        var supplyLost = new FakeLink { LoseSupplyAfterFrames = 2 };
        MustFail<IOException>(() =>
            NativeE39RamLoader.StageAsync(supplyLost, plan, 12000, 15000, CancellationToken.None)
                .GetAwaiter().GetResult());
        Assert(supplyLost.Transmitted.Count == 2 &&
            supplyLost.Transmitted.All(x => x[0] != 0x36 || x[1] != 0x80),
            "Supply loss after first copy prevents execution frame.");

        var copyDenied = new FakeLink
        {
            Response = req => req[0] == 0x34 ? [0x74] : [0x7F, 0x36, 0x22]
        };
        MustFail<IOException>(() =>
            NativeE39RamLoader.StageAsync(copyDenied, plan, 12000, 15000, CancellationToken.None)
                .GetAwaiter().GetResult());
        Assert(copyDenied.Transmitted.Count == 2,
            "Negative copy response halts without executing kernel");

        var refusedDownload = new FakeLink
        {
            Response = req => [0x7F, req[0], 0x22]
        };
        MustFail<IOException>(() =>
            NativeE39RamLoader.StageAsync(refusedDownload, plan, 12000, 15000, CancellationToken.None)
                .GetAwaiter().GetResult());
        Assert(refusedDownload.Transmitted.Count == 1,
            "Download rejection prevents copying any bytes to RAM");

        var badLaunch = new FakeLink
        {
            Response = req => req[0] == 0x34 ? [0x74] : [0x76]
        };
        MustFail<InvalidDataException>(() =>
            NativeE39RamLoader.StageAsync(badLaunch, plan, 12000, 15000, CancellationToken.None)
                .GetAwaiter().GetResult());
        Assert(badLaunch.Transmitted.Count == 3,
            "Copy acknowledgement is not a valid kernel launch acknowledgement");

        var oneBlock = NativeE39RamLoader.Build([0x11, 0x22], 11);
        Assert(oneBlock.Frames.Count == 2 && oneBlock.Frames[^1].Executes,
            "Small 11-byte adapter buffers split safely");
        Assert(oneBlock.Frames.All(x => x.Payload.Length <= 11),
            "Every block fits smallest supported transport capacity");

        MustFail<ArgumentOutOfRangeException>(() => NativeE39RamLoader.Build([], 2048));
        checks++;
        MustFail<ArgumentOutOfRangeException>(() => NativeE39RamLoader.Build(image, 5000));
        checks++;
        MustFail<ArgumentOutOfRangeException>(() => NativeE39RamLoader.Build(
            new byte[NativeE39RamLoader.MaximumKernelBytes + 1], 2048));
        checks++;

        var cancelled = new FakeLink();
        using (var token = new CancellationTokenSource())
        {
            token.Cancel();
            MustFail<OperationCanceledException>(() =>
                NativeE39RamLoader.StageAsync(cancelled, plan, 12000, 15000, token.Token)
                    .GetAwaiter().GetResult());
        }
        Assert(cancelled.Transmitted.Count == 0,
            "Cancellation before I/O never begins RAM transfer");

        Console.WriteLine($"PASS: {checks} native E39 RAM loader self-tests.");
        return checks;
    }
}
