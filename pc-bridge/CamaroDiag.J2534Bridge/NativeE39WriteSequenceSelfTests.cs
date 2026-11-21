namespace CamaroDiag.J2534Bridge;

/// <summary>Hardware-free isolated test double: never loads a J2534 DLL or ECU.</summary>
internal static class NativeE39WriteSequenceSelfTests
{
    private sealed class TestJournal : NativeE39WriteSequence.IDurableJournal
    {
        internal readonly List<NativeE39WriteSequence.Event> Events = [];
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
        internal bool CorruptVerify;
        internal int Erases;
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
        var opts = new NativeE39WriteSequence.Options(8, 2, true, true);
        var ecu = new TestEcu(origin);
        var journal = new TestJournal();
        var result = NativeE39WriteSequence.RunBenchSequenceAsync(
            ecu, journal, plan, opts, CancellationToken.None)
            .GetAwaiter().GetResult();
        Assert(result.SectorsWritten == 2 && result.BytesWritten == 32,
            "Both changed sectors are written exactly once");
        Assert(ecu.Memory.SequenceEqual(updated) && ecu.Erases == 2,
            "Erase, write and CRC verify produce exact target image");
        Assert(journal.Events.Count(x => x.Phase == NativeE39WriteSequence.Phase.WrittenCrcVerified) == 2 &&
               journal.Events[^1].Phase == NativeE39WriteSequence.Phase.Completed,
            "Every sector has a verification event before completed status");
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

        var corruptEcu = new TestEcu(origin) { CorruptVerify = true };
        var verifyJournal = new TestJournal();
        Expect<InvalidDataException>(() =>
            NativeE39WriteSequence.RunBenchSequenceAsync(corruptEcu, verifyJournal,
                plan, opts, CancellationToken.None).GetAwaiter().GetResult());
        Assert(verifyJournal.Events[^1].Phase == NativeE39WriteSequence.Phase.RecoveryRequired,
            "Post-write CRC mismatch requires intervention");

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
