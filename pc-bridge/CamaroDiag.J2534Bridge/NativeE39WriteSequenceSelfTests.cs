namespace CamaroDiag.J2534Bridge;

/// <summary>Hardware-free isolated test double: never loads a J2534 DLL or ECU.</summary>
internal static class NativeE39WriteSequenceSelfTests
{
    private sealed class TestJournal : NativeE39WriteSequence.IDurableJournal
    {
        internal readonly List<NativeE39WriteSequence.Event> Events = [];
        internal readonly Dictionary<uint, byte[]> SavedBlocks = [];
        public Task PreserveOriginalBlockAsync(uint address, byte[] liveBytes,
            string sha256, CancellationToken token)
        {
            token.ThrowIfCancellationRequested();
            if (!string.Equals(Convert.ToHexString(
                System.Security.Cryptography.SHA256.HashData(liveBytes)), sha256,
                StringComparison.OrdinalIgnoreCase) ||
                !SavedBlocks.TryAdd(address, liveBytes.ToArray()))
                throw new InvalidDataException("Cannot save fresh physical sector.");
            return Task.CompletedTask;
        }
        public Task AppendAsync(NativeE39WriteSequence.Event evidence, CancellationToken token)
        {
            token.ThrowIfCancellationRequested();
            Events.Add(evidence);
            return Task.CompletedTask;
        }
    }

    private sealed class TestEcu : NativeE39WriteSequence.IKernelLink
    {
        internal readonly byte[] Memory;
        internal readonly List<byte[]> Requests = [];
        internal bool RejectDryRun;
        internal bool FailAfterErase;
        internal bool UnexpectedFailureAfterErase;
        internal bool CorruptVerify;
        internal int Erases;
        internal uint? SupplyMillivolts = 13500;
        internal bool LoseSupplyAfterErase;
        internal int VoltageReads;
        internal int ByteReads;
        internal bool CorruptEarlierSectorOnSecondErase;
        internal bool CorruptNextSectorOnFirstErase;
        internal bool WrongOriginalReadback;
        internal bool WrongWrittenReadback;
        internal bool TruncatedWrittenReadback;

        public Task<uint?> ReadSupplyMillivoltsAsync(CancellationToken token)
        {
            token.ThrowIfCancellationRequested();
            VoltageReads++;
            return Task.FromResult(LoseSupplyAfterErase && Erases > 0
                ? (uint?)null : SupplyMillivolts);
        }
        public Task<byte[]> ReadFlashBytesAsync(uint address, int length, CancellationToken token)
        {
            token.ThrowIfCancellationRequested();
            ByteReads++;
            var bytes = Memory.AsSpan(checked((int)address - 0x20000), length).ToArray();
            if ((!wroteAnything && WrongOriginalReadback) ||
                (wroteAnything && WrongWrittenReadback))
                bytes[0] ^= 0x80;
            if (wroteAnything && TruncatedWrittenReadback)
                bytes = bytes[..^1];
            return Task.FromResult(bytes);
        }
        private bool wroteAnything;
        internal TestEcu(byte[] initial) => Memory = initial.ToArray();

        public Task<byte[]> ExchangeAsync(byte[] request, CancellationToken token)
        {
            token.ThrowIfCancellationRequested();
            Requests.Add(request.ToArray());
            var address = 0;
            if (request[0] == 0x3D && request[1] == 0x02)
            {
                var count = (request[2] << 16) | (request[3] << 8) | request[4];
                address = (request[5] << 16) | (request[6] << 8) | request[7];
                var offset = address - 0x20000;
                if (CorruptVerify && wroteAnything)
                    return Task.FromResult(new byte[] { 0x7D, 0x02, 0, 0, 0, 0 });
                var crc = NativeE39FlashCommands.ComputeCrc32(Memory.AsSpan(offset, count));
                return Task.FromResult(new byte[] {
                    0x7D, 0x02, (byte)(crc >> 24), (byte)(crc >> 16),
                    (byte)(crc >> 8), (byte)crc
                });
            }
            if (request[0] == 0x3D && request[1] == 0x05)
            {
                address = (request[2] << 16) | (request[3] << 8) | request[4];
                Erases++;
                if (CorruptNextSectorOnFirstErase && Erases == 1)
                    Memory[16] ^= 0x01;
                if (CorruptEarlierSectorOnSecondErase && Erases == 2)
                    Memory[0] ^= 0x01;
                Array.Fill(Memory, (byte)0xFF, address - 0x20000, 16);
                return Task.FromResult(new byte[] { 0x7D, 0x05, 0 });
            }
            if (request[0] != 0x36)
                throw new IOException("Unexpected fake ECU command.");

            var len = (request[2] << 8) | request[3];
            address = (request[4] << 16) | (request[5] << 8) | request[6];
            if (request[1] == 0x44 && RejectDryRun)
                return Task.FromResult(new byte[] { 0x7F, 0x36, 0x72 });
            if (request[1] == 0x00)
            {
                if (FailAfterErase) throw new IOException("Bench transport disconnected.");
                if (UnexpectedFailureAfterErase) throw new InvalidOperationException("Driver invalid state.");
                Buffer.BlockCopy(request, 7, Memory, address - 0x20000, len);
                wroteAnything = true;
            }
            return Task.FromResult(new byte[] { 0x76 });
        }
    }

    internal static int Run()
    {
        var count = 0;
        void Assert(bool condition, string name)
        {
            count++;
            if (!condition) throw new InvalidOperationException("E39 sequencing failed: " + name);
        }
        static void Expect<T>(Action callback) where T : Exception
        {
            try { callback(); }
            catch (T) { return; }
            throw new InvalidOperationException("Expected " + typeof(T).Name);
        }

        byte[] origin = Enumerable.Range(0, 32).Select(x => (byte)x).ToArray();
        byte[] updated = origin.ToArray();
        updated[1] = 77;
        updated[21] = 99;
        var layout = new NativeEcuSectorPlanner.Layout(
            0x20000, 32, 16, 2, [], true);
        var plan = NativeEcuSectorPlanner.Preview(layout, origin, updated);
        var opts = new NativeE39WriteSequence.Options(8, 2, true, true, 12000, 15000);
        // An embedded ECU address, payload, count or packet checksum that
        // does not describe the requested block must NEVER reach ExchangeAsync.
        var firstSector = plan.Sectors[0];
        var correctFrame = NativeE39FlashCommands.BuildWriteBlock(
            firstSector.Address, firstSector.Updated[..8], dryRun: false);
        Assert(NativeE39WriteBlockVerifier.RequireExactFrame(
            firstSector, correctFrame, 0, dryRun: false) == 8,
            "Correct wire packet matches the exact target sector bytes");
        foreach (var byteIndex in new[] { 1, 2, 3, 4, 5, 6, 7, correctFrame.Length - 1 })
        {
            var altered = correctFrame.ToArray();
            altered[byteIndex] ^= 1;
            Expect<InvalidDataException>(() =>
                NativeE39WriteBlockVerifier.RequireExactFrame(
                    firstSector, altered, 0, dryRun: false));
            count++;
        }
        Expect<InvalidDataException>(() =>
            NativeE39WriteBlockVerifier.RequireExactFrame(
                firstSector, correctFrame, 8, dryRun: false));
        count++;
        Expect<InvalidDataException>(() =>
            NativeE39WriteBlockVerifier.RequireCompleteBatch(
                firstSector, [correctFrame], dryRun: false));
        count++;
        var completeFrames = new[] {
            correctFrame, NativeE39FlashCommands.BuildWriteBlock(
                firstSector.Address + 8, firstSector.Updated[8..16], dryRun: false)
        };
        NativeE39WriteBlockVerifier.RequireCompleteBatch(
            firstSector, completeFrames, dryRun: false);
        count++;
        var ecu = new TestEcu(origin);
        var journal = new TestJournal();
        var result = NativeE39WriteSequence.RunBenchSequenceAsync(
            ecu, journal, plan, opts, CancellationToken.None)
            .GetAwaiter().GetResult();
        Assert(journal.SavedBlocks.Count == 2 &&
               journal.SavedBlocks[0x20000].SequenceEqual(origin[..16]) &&
               journal.SavedBlocks[0x20010].SequenceEqual(origin[16..]),
            "Both original ECU sectors physically captured before any erase");
        Assert(result.SectorsWritten == 2 && result.BytesWritten == 32,
            "Both changed sectors are written exactly once");
        Assert(ecu.Memory.SequenceEqual(updated) && ecu.Erases == 2,
            "Erase, write and CRC verify produce exact target image");
        Assert(journal.Events.Count(x => x.Phase == NativeE39WriteSequence.Phase.WrittenCrcVerified) == 2 &&
               journal.Events[^1].Phase == NativeE39WriteSequence.Phase.Completed,
            "Every sector has a verification event before completed status");
        Assert(ecu.ByteReads == 8 &&
               journal.Events.Count(x => x.Phase == NativeE39WriteSequence.Phase.OriginalBytesVerified) == 2 &&
               journal.Events.Count(x => x.Phase == NativeE39WriteSequence.Phase.WrittenBytesVerified) == 2 &&
               journal.Events.Count(x => x.Phase == NativeE39WriteSequence.Phase.FinalBytesVerified) == 2,
            "Fresh byte comparisons before and after programming every sector");
        var requests = ecu.Requests;
        var firstErase = requests.FindIndex(x => x[0] == 0x3D && x[1] == 0x05);
        Assert(requests.Take(firstErase).Count(x => x[0] == 0x3D && x[1] == 0x02) == 2 &&
               requests.Take(firstErase).Count(x => x[0] == 0x36 && x[1] == 0x44) == 4,
            "Both original CRC and all dry-run blocks validated BEFORE first erase");
        Assert(journal.Events.FindIndex(e => e.Phase == NativeE39WriteSequence.Phase.EraseStarted) <
               journal.Events.FindIndex(e => e.Phase == NativeE39WriteSequence.Phase.EraseAcknowledged),
            "Erase-start event written before physical erase");

        var invalid = plan with { VerifiedMemoryMap = false };
        var noTraffic = new TestEcu(origin);
        Expect<UnauthorizedAccessException>(() =>
            NativeE39WriteSequence.RunBenchSequenceAsync(noTraffic, new TestJournal(),
                invalid, opts, CancellationToken.None).GetAwaiter().GetResult());
        Assert(noTraffic.Requests.Count == 0, "Unsupported firmware never sends messages");
        Expect<UnauthorizedAccessException>(() =>
            NativeE39WriteSequence.RunBenchSequenceAsync(noTraffic, new TestJournal(),
                plan, opts with { RecoveryProcedureTested = false }, CancellationToken.None)
                .GetAwaiter().GetResult());
        Assert(noTraffic.Requests.Count == 0, "No recovery proof means no ECU I/O");

        Assert(ecu.VoltageReads >= 7,
            "Supply voltage checked before and during destructive operations");
        foreach (uint? voltage in new uint?[] { null, 11000, 16000 })
        {
            var unsafeSupply = new TestEcu(origin) { SupplyMillivolts = voltage };
            Expect<IOException>(() =>
                NativeE39WriteSequence.RunBenchSequenceAsync(unsafeSupply, new TestJournal(),
                    plan, opts, CancellationToken.None).GetAwaiter().GetResult());
            Assert(unsafeSupply.Requests.Count == 0,
                "Unavailable or out-of-window voltage blocks all ECU requests");
        }
        var lostSupply = new TestEcu(origin) { LoseSupplyAfterErase = true };
        var lostSupplyJournal = new TestJournal();
        Expect<IOException>(() =>
            NativeE39WriteSequence.RunBenchSequenceAsync(lostSupply, lostSupplyJournal,
                plan, opts, CancellationToken.None).GetAwaiter().GetResult());
        Assert(lostSupply.Erases == 1 &&
               lostSupplyJournal.Events[^1].Phase == NativeE39WriteSequence.Phase.RecoveryRequired &&
               !lostSupply.Requests.Any(x => x[0] == 0x36 && x[1] == 0),
            "Voltage loss after erase prevents programming and requires recovery");

        var wrongBaseline = origin.ToArray();
        wrongBaseline[1] ^= 0x05;
        var wrongEcu = new TestEcu(wrongBaseline);
        var wrongJournal = new TestJournal();
        Expect<InvalidDataException>(() =>
            NativeE39WriteSequence.RunBenchSequenceAsync(wrongEcu, wrongJournal,
                plan, opts, CancellationToken.None).GetAwaiter().GetResult());
        Assert(wrongEcu.Erases == 0 &&
               wrongJournal.Events.All(e => e.Phase != NativeE39WriteSequence.Phase.RecoveryRequired),
            "Physical original mismatch stops before erase and does not invent recovery");

        var denied = new TestEcu(origin) { RejectDryRun = true };
        Expect<IOException>(() =>
            NativeE39WriteSequence.RunBenchSequenceAsync(denied, new TestJournal(),
                plan, opts, CancellationToken.None).GetAwaiter().GetResult());
        Assert(denied.Erases == 0, "Dry-run rejection never erases flash");

        var disconnected = new TestEcu(origin) { FailAfterErase = true };
        var recoveryJournal = new TestJournal();
        Expect<IOException>(() =>
            NativeE39WriteSequence.RunBenchSequenceAsync(disconnected, recoveryJournal,
                plan, opts, CancellationToken.None).GetAwaiter().GetResult());
        Assert(disconnected.Erases == 1 &&
               recoveryJournal.Events[^1].Phase == NativeE39WriteSequence.Phase.RecoveryRequired,
            "Transport loss after erase preserves explicit manual-recovery state");
        Assert(disconnected.Requests.Count(x => x[0] == 0x3D && x[1] == 0x05) == 1,
            "No automatic retry after destructive transport failure");

        var unexpected = new TestEcu(origin) { UnexpectedFailureAfterErase = true };
        var unexpectedJournal = new TestJournal();
        Expect<InvalidOperationException>(() =>
            NativeE39WriteSequence.RunBenchSequenceAsync(unexpected, unexpectedJournal,
                plan, opts, CancellationToken.None).GetAwaiter().GetResult());
        Assert(unexpected.Erases == 1 &&
               unexpectedJournal.Events[^1].Phase == NativeE39WriteSequence.Phase.RecoveryRequired,
            "Unexpected driver exception after erase requires recovery too");

        var corruptEcu = new TestEcu(origin) { CorruptVerify = true };
        var verifyJournal = new TestJournal();
        Expect<InvalidDataException>(() =>
            NativeE39WriteSequence.RunBenchSequenceAsync(corruptEcu, verifyJournal,
                plan, opts, CancellationToken.None).GetAwaiter().GetResult());
        Assert(verifyJournal.Events[^1].Phase == NativeE39WriteSequence.Phase.RecoveryRequired,
            "Post-write CRC mismatch requires intervention");

        var wrongBytes = new TestEcu(origin) { WrongOriginalReadback = true };
        Expect<InvalidDataException>(() =>
            NativeE39WriteSequence.RunBenchSequenceAsync(wrongBytes, new TestJournal(),
                plan, opts, CancellationToken.None).GetAwaiter().GetResult());
        Assert(wrongBytes.Erases == 0, "Matching original CRC alone cannot authorize erase");

        foreach (bool truncated in new[] { false, true })
        {
            var wrongReadback = new TestEcu(origin) {
                WrongWrittenReadback = !truncated, TruncatedWrittenReadback = truncated
            };
            var byteJournal = new TestJournal();
            Expect<InvalidDataException>(() =>
                NativeE39WriteSequence.RunBenchSequenceAsync(wrongReadback, byteJournal,
                    plan, opts, CancellationToken.None).GetAwaiter().GetResult());
            Assert(wrongReadback.Erases == 1 &&
                byteJournal.Events[^1].Phase == NativeE39WriteSequence.Phase.RecoveryRequired &&
                byteJournal.Events.All(e => e.Phase != NativeE39WriteSequence.Phase.Completed),
                "Matching CRC but wrong or truncated post-write bytes require recovery");
        }

        var disturbedEcu = new TestEcu(origin) { CorruptEarlierSectorOnSecondErase = true };
        var disturbedJournal = new TestJournal();
        Expect<InvalidDataException>(() =>
            NativeE39WriteSequence.RunBenchSequenceAsync(disturbedEcu, disturbedJournal,
                plan, opts, CancellationToken.None).GetAwaiter().GetResult());
        Assert(disturbedEcu.Erases == 2 &&
               disturbedJournal.Events.Count(e => e.Phase == NativeE39WriteSequence.Phase.WrittenBytesVerified) == 2 &&
               disturbedJournal.Events[^1].Phase == NativeE39WriteSequence.Phase.RecoveryRequired &&
               disturbedJournal.Events.All(e => e.Phase != NativeE39WriteSequence.Phase.Completed),
            "Final pass catches corruption of a previously verified sector");

        var changedNext = new TestEcu(origin) { CorruptNextSectorOnFirstErase = true };
        var changedNextJournal = new TestJournal();
        Expect<InvalidDataException>(() =>
            NativeE39WriteSequence.RunBenchSequenceAsync(changedNext, changedNextJournal,
                plan, opts, CancellationToken.None).GetAwaiter().GetResult());
        Assert(changedNext.Erases == 1 &&
               changedNextJournal.Events[^1].Phase == NativeE39WriteSequence.Phase.RecoveryRequired &&
               changedNextJournal.Events.Count(e => e.Phase == NativeE39WriteSequence.Phase.EraseStarted) == 1,
            "A sector changed since all-sector preflight must not be erased");

        var duplicate = plan with { Sectors = new[] { plan.Sectors[0], plan.Sectors[0] } };
        var overlapEcu = new TestEcu(origin);
        Expect<ArgumentException>(() =>
            NativeE39WriteSequence.RunBenchSequenceAsync(overlapEcu, new TestJournal(),
                duplicate, opts, CancellationToken.None).GetAwaiter().GetResult());
        Assert(overlapEcu.Requests.Count == 0, "Duplicate sector never sends ECU requests");

        var damagedPlan = NativeEcuSectorPlanner.Preview(layout, origin, updated);
        damagedPlan.Sectors[0].Updated[0] ^= 0x44;
        var tamperEcu = new TestEcu(origin);
        Expect<InvalidDataException>(() =>
            NativeE39WriteSequence.RunBenchSequenceAsync(tamperEcu, new TestJournal(),
                damagedPlan, opts, CancellationToken.None).GetAwaiter().GetResult());
        Assert(tamperEcu.Requests.Count == 0, "Mutated sector rejected before any ECU request");

        Console.WriteLine($"PASS: {count} native E39 offline write transaction self-tests.");
        return count;
    }
}
