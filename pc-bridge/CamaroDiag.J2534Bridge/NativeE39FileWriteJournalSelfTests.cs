namespace CamaroDiag.J2534Bridge;

internal static class NativeE39FileWriteJournalSelfTests
{
    internal static int Run()
    {
        var tests = 0;
        void Assert(bool cond, string description)
        {
            tests++;
            if (!cond) throw new InvalidOperationException(
                "Durable ECU journal test failed: " + description);
        }
        var path = Path.Combine(Path.GetTempPath(),
            "camarodiag-e39-" + Guid.NewGuid().ToString("N") + ".jsonl");
        try
        {
            Assert(!File.Exists(path), "Unique journal path is unused");
            AssertThrows<FileNotFoundException>(() =>
                NativeE39FileWriteJournal.Inspect(path));
            tests++;
            var journal = new NativeE39FileWriteJournal(path);
            journal.AppendAsync(new(NativeE39WriteSequence.Phase.Prepared,
                0x20000, "initial-baseline-sha256"), CancellationToken.None)
                .GetAwaiter().GetResult();
            journal.AppendAsync(new(NativeE39WriteSequence.Phase.EraseStarted,
                0x20000, "pending erase"), CancellationToken.None)
                .GetAwaiter().GetResult();
            journal.DisposeAsync().AsTask().GetAwaiter().GetResult();
            Assert(NativeE39FileWriteJournal.Inspect(path) ==
                NativeE39FileWriteJournal.CompletionState.Interrupted,
                "Unfinished erase is conservatively treated as interrupted");
            AssertThrows<IOException>(() => new NativeE39FileWriteJournal(path));
            tests++;
            using (var file = new FileStream(path, FileMode.Append, FileAccess.Write))
            {
                var corruption = System.Text.Encoding.UTF8.GetBytes("{\"Schema\":");
                file.Write(corruption);
                file.Flush(true);
            }
            Assert(NativeE39FileWriteJournal.Inspect(path) ==
                NativeE39FileWriteJournal.CompletionState.Interrupted,
                "Torn JSON entry cannot masquerade as a completed flash");

            File.Delete(path);
            var cancellable = new NativeE39FileWriteJournal(path);
            using (var cancellation = new CancellationTokenSource())
            {
                cancellation.Cancel();
                AssertThrows<OperationCanceledException>(() =>
                    cancellable.AppendAsync(new(NativeE39WriteSequence.Phase.Prepared,
                        0x20000, "cancelled"), cancellation.Token).GetAwaiter().GetResult());
                tests++;
            }
            Assert(new FileInfo(path).Length == 0, "Cancelled request cannot create a journal event");
            cancellable.AppendAsync(new(NativeE39WriteSequence.Phase.Prepared,
                0x20000, "valid after cancellation before append"), CancellationToken.None)
                .GetAwaiter().GetResult();
            cancellable.DisposeAsync().AsTask().GetAwaiter().GetResult();
            cancellable.DisposeAsync().AsTask().GetAwaiter().GetResult();
            AssertThrows<ObjectDisposedException>(() =>
                cancellable.AppendAsync(new(NativeE39WriteSequence.Phase.Completed,
                    0, "closed journal"), CancellationToken.None).GetAwaiter().GetResult());
            tests++;
            Assert(File.ReadLines(path).Count() == 1 &&
                NativeE39FileWriteJournal.Inspect(path) == NativeE39FileWriteJournal.CompletionState.Interrupted,
                "Only the accepted event is persisted; disposing twice cannot append completion");

            File.Delete(path);
            // Windows normally prevents external writers while the descriptor
            // is open. On a platform permitting rename, verify path replacement.
            var active=new NativeE39FileWriteJournal(path);
            string moved=path+".moved";
            try
            {
                active.AppendAsync(new(NativeE39WriteSequence.Phase.Prepared,0x20000,"baseline"),
                    CancellationToken.None).GetAwaiter().GetResult();
                bool renamed=false;
                try { File.Move(path,moved);renamed=true; }
                catch(IOException) { }
                catch(UnauthorizedAccessException) { }
                if(renamed)
                {
                    File.WriteAllText(path,"replacement");
                    AssertThrows<IOException>(()=>active.AppendAsync(new(NativeE39WriteSequence.Phase.OriginalCrcVerified,
                        0x20000,"must not append"),CancellationToken.None).GetAwaiter().GetResult());
                    tests++;
                    Assert(File.ReadAllText(path)=="replacement","Replacement journal is untouched");
                    File.Delete(path);File.Move(moved,path);
                    AssertThrows<IOException>(()=>active.AppendAsync(new(NativeE39WriteSequence.Phase.OriginalCrcVerified,
                        0x20000,"must remain blocked"),CancellationToken.None).GetAwaiter().GetResult());
                    tests++;
                }
                else
                {
                    Assert(File.Exists(path)&&!File.Exists(moved),"Active journal cannot be renamed");
                    active.AppendAsync(new(NativeE39WriteSequence.Phase.OriginalCrcVerified,0x20000,"next"),
                        CancellationToken.None).GetAwaiter().GetResult();
                }
            }
            finally
            {
                active.DisposeAsync().AsTask().GetAwaiter().GetResult();
                if(File.Exists(moved))File.Delete(moved);
            }
            File.Delete(path);
            WriteScenario(path, true, false);
            Assert(NativeE39FileWriteJournal.Inspect(path) ==
                NativeE39FileWriteJournal.CompletionState.Completed,
                "All sectors individually verified before completion");
            var detailed = NativeE39FileWriteJournal.InspectDetailed(path);
            Assert(detailed.SequenceValid && !detailed.RequiresAssessment &&
                detailed.PreparedSectors == 2 && detailed.WrittenSectors == 2 &&
                detailed.FinalReadbackSectors == 2 && detailed.PossiblyIncompleteAddress is null &&
                detailed.LastPhase == "Completed", "Completed offline journal reports validated sector counts");

            // A full JSON object without the flushed line terminator is not
            // sufficient to declare the flash complete.
            var completeText = File.ReadAllText(path);
            File.WriteAllText(path, completeText.TrimEnd('\r', '\n'));
            Assert(NativeE39FileWriteJournal.Inspect(path) ==
                NativeE39FileWriteJournal.CompletionState.Interrupted,
                "Completion without record terminator is interrupted");

            File.WriteAllText(path, completeText.Replace(
                "\"Schema\":3,", "\"Schema\":2,\"Schema\":3,"));
            Assert(NativeE39FileWriteJournal.Inspect(path) ==
                NativeE39FileWriteJournal.CompletionState.Interrupted,
                "Duplicate schema keys cannot masquerade as completion");

            File.WriteAllText(path, completeText.Replace(
                "\"Phase\":\"Completed\"", "\"Phase\":\"" +
                ((int)NativeE39WriteSequence.Phase.Completed).ToString() + "\""));
            Assert(NativeE39FileWriteJournal.Inspect(path) ==
                NativeE39FileWriteJournal.CompletionState.Interrupted,
                "Numeric enum spelling is not an accepted phase name");

            File.Delete(path);
            using (var oversized = new FileStream(path, FileMode.CreateNew))
                oversized.SetLength(8L * 1024 * 1024 + 1);
            Assert(NativeE39FileWriteJournal.Inspect(path) ==
                NativeE39FileWriteJournal.CompletionState.Interrupted,
                "Oversized journal never authorizes completion");

            File.Delete(path);
            WriteScenario(path, true, false);
            var prefix = File.ReadLines(path).Take(9).ToArray(); // 2 prepared, 6 preflight, first erase marker
            File.WriteAllLines(path, prefix);
            var inFlight = NativeE39FileWriteJournal.InspectDetailed(path);
            Assert(inFlight.SequenceValid && inFlight.RequiresAssessment &&
                inFlight.PreparedSectors == 2 && inFlight.WrittenSectors == 0 &&
                inFlight.PossiblyIncompleteAddress == 0x20000u && inFlight.LastPhase == "EraseStarted",
                "Durable erase marker identifies the possibly incomplete sector");

            File.Delete(path);
            WriteScenario(path, false, false);
            Assert(NativeE39FileWriteJournal.Inspect(path) ==
                NativeE39FileWriteJournal.CompletionState.Interrupted,
                "Completion with one prepared but unwritten sector is rejected");

            File.Delete(path);
            WriteScenario(path, false, true);
            Assert(NativeE39FileWriteJournal.Inspect(path) ==
                NativeE39FileWriteJournal.CompletionState.Interrupted,
                "Verification for the wrong sector cannot complete a transaction");

            File.Delete(path);
            WriteScenario(path, true, false, omitBytes: true);
            Assert(NativeE39FileWriteJournal.Inspect(path) ==
                NativeE39FileWriteJournal.CompletionState.Interrupted,
                "CRC-only journal cannot prove successful byte readback");
            File.Delete(path);
            WriteScenario(path, true, false, omitFinalPass: true);
            Assert(NativeE39FileWriteJournal.Inspect(path) ==
                NativeE39FileWriteJournal.CompletionState.Interrupted,
                "Individual readback cannot substitute for final whole-transaction verification");
            File.Delete(path);
            var forged = new NativeE39FileWriteJournal(path);
            forged.AppendAsync(new(NativeE39WriteSequence.Phase.Completed,
                0, "fake success without physical write"), CancellationToken.None)
                .GetAwaiter().GetResult();
            forged.DisposeAsync().AsTask().GetAwaiter().GetResult();
            Assert(NativeE39FileWriteJournal.Inspect(path) ==
                NativeE39FileWriteJournal.CompletionState.Interrupted,
                "Lone completion marker never proves an ECU flash");
            File.Delete(path);
            var stale = new NativeE39FileWriteJournal(path);
            stale.AppendAsync(new(NativeE39WriteSequence.Phase.EraseStarted,
                0x20000, "offline erase"), CancellationToken.None)
                .GetAwaiter().GetResult();
            stale.AppendAsync(new(NativeE39WriteSequence.Phase.RecoveryRequired,
                0x20000, "failed flash"), CancellationToken.None)
                .GetAwaiter().GetResult();
            stale.AppendAsync(new(NativeE39WriteSequence.Phase.WrittenCrcVerified,
                0x20000, "suspicious after failure"), CancellationToken.None)
                .GetAwaiter().GetResult();
            stale.AppendAsync(new(NativeE39WriteSequence.Phase.Completed,
                0, "forged success after recovery"), CancellationToken.None)
                .GetAwaiter().GetResult();
            stale.DisposeAsync().AsTask().GetAwaiter().GetResult();
            Assert(NativeE39FileWriteJournal.Inspect(path) ==
                NativeE39FileWriteJournal.CompletionState.RecoveryRequired,
                "Recovery-required state cannot be hidden by invalid earlier transitions or later success");

            File.Delete(path);
            File.WriteAllLines(path, [
                "{broken earlier record",
                "{\"Schema\":2,\"Phase\":\"RecoveryRequired\",\"Address\":131072}"
            ]);
            Assert(NativeE39FileWriteJournal.Inspect(path) ==
                NativeE39FileWriteJournal.CompletionState.RecoveryRequired,
                "Legacy recovery record remains visible after malformed history");

            File.Delete(path);
            var failed = new NativeE39FileWriteJournal(path);
            failed.AppendAsync(new(NativeE39WriteSequence.Phase.RecoveryRequired,
                0x20000, "IOException"), CancellationToken.None)
                .GetAwaiter().GetResult();
            failed.DisposeAsync().AsTask().GetAwaiter().GetResult();
            Assert(NativeE39FileWriteJournal.Inspect(path) ==
                NativeE39FileWriteJournal.CompletionState.RecoveryRequired,
                "Durable recovery-required status survives process exit");

            // Original ECU bytes, not just a sector digest or source BIN, must
            // survive a process restart before destructive programming.
            File.Delete(path);
            var liveBackup = new NativeE39FileWriteJournal(path);
            var physicalBytes = Enumerable.Range(0, 16).Select(i => (byte)i).ToArray();
            var originalSha = Convert.ToHexString(
                System.Security.Cryptography.SHA256.HashData(physicalBytes));
            liveBackup.PreserveOriginalBlockAsync(
                0x20000u, physicalBytes, originalSha, CancellationToken.None)
                .GetAwaiter().GetResult();
            var physicalFile = Path.Combine(path + ".blocks", "00020000-16.ecu-block");
            Assert(File.Exists(physicalFile) &&
                File.ReadAllBytes(physicalFile).AsSpan()[^16..].SequenceEqual(physicalBytes),
                "Physical ECU sector backup is durably persisted with exact bytes");
            physicalBytes[0] ^= 0xFF;
            Assert(File.ReadAllBytes(physicalFile)[^16] != physicalBytes[0],
                "Mutating a caller's buffer cannot alter saved physical ECU bytes");
            AssertThrows<IOException>(() =>
                liveBackup.PreserveOriginalBlockAsync(0x20000u,
                    physicalBytes.Select((b, i) => i == 0 ? (byte)0 : b).ToArray(),
                    originalSha, CancellationToken.None).GetAwaiter().GetResult());
            tests++;
            liveBackup.DisposeAsync().AsTask().GetAwaiter().GetResult();
            File.Delete(path);
            Directory.Delete(path + ".blocks", recursive:true);
            var invalidated = new NativeE39FileWriteJournal(path);
            try {
                physicalBytes[0] ^= 0xFF;
                invalidated.PreserveOriginalBlockAsync(0x20000u,physicalBytes,originalSha,CancellationToken.None).GetAwaiter().GetResult();
                invalidated.RequireOriginalBlocksIntactAsync(CancellationToken.None).GetAwaiter().GetResult();
                var intact = File.ReadAllBytes(physicalFile);
                var changed = intact.ToArray();changed[^1] ^= 1;File.WriteAllBytes(physicalFile,changed);
                AssertThrows<InvalidDataException>(()=>invalidated.RequireOriginalBlocksIntactAsync(CancellationToken.None).GetAwaiter().GetResult());
                File.WriteAllBytes(physicalFile,intact);
                AssertThrows<InvalidDataException>(()=>invalidated.RequireOriginalBlocksIntactAsync(CancellationToken.None).GetAwaiter().GetResult());
                AssertThrows<InvalidDataException>(()=>invalidated.AppendAsync(new(NativeE39WriteSequence.Phase.Completed,0,"not allowed"),CancellationToken.None).GetAwaiter().GetResult());
                invalidated.AppendAsync(new(NativeE39WriteSequence.Phase.RecoveryRequired,0x20000,"backup changed"),CancellationToken.None).GetAwaiter().GetResult();
                tests+=3;
            }finally{invalidated.DisposeAsync().AsTask().GetAwaiter().GetResult();}
            Assert(NativeE39FileWriteJournal.Inspect(path)==NativeE39FileWriteJournal.CompletionState.RecoveryRequired,
                "Detected backup change permanently invalidates session while allowing recovery evidence");
        }
        finally
        {
            if (File.Exists(path)) File.Delete(path);
            if (Directory.Exists(path + ".blocks"))
                Directory.Delete(path + ".blocks", recursive: true);
        }
        Console.WriteLine($"PASS: {tests} native ECU durable journal self-tests.");
        return tests;
    }

    private static void WriteScenario(string path, bool writeSecond, bool wrongAddress, bool omitBytes = false, bool omitFinalPass = false)
    {
        var journal = new NativeE39FileWriteJournal(path);
        void Add(NativeE39WriteSequence.Phase phase, uint address) =>
            journal.AppendAsync(new(phase, address, "offline test"),
                CancellationToken.None).GetAwaiter().GetResult();

        uint[] addresses = [0x20000, 0x20010];
        foreach (var address in addresses)
            Add(NativeE39WriteSequence.Phase.Prepared, address);
        foreach (var address in addresses)
        {
            Add(NativeE39WriteSequence.Phase.OriginalCrcVerified, address);
            Add(NativeE39WriteSequence.Phase.OriginalBytesVerified, address);
            Add(NativeE39WriteSequence.Phase.TestWriteAccepted, address);
        }

        foreach (var address in writeSecond ? addresses : addresses.Take(1))
        {
            Add(NativeE39WriteSequence.Phase.EraseStarted, address);
            Add(NativeE39WriteSequence.Phase.EraseAcknowledged, address);
            Add(NativeE39WriteSequence.Phase.WriteStarted, address);
            Add(NativeE39WriteSequence.Phase.WrittenCrcVerified,
                wrongAddress ? 0x20010u : address);
            if (!omitBytes) Add(NativeE39WriteSequence.Phase.WrittenBytesVerified, address);
        }
        if (writeSecond && !omitBytes && !omitFinalPass)
            foreach (var address in addresses)
                Add(NativeE39WriteSequence.Phase.FinalBytesVerified, address);
        Add(NativeE39WriteSequence.Phase.Completed, 0);
        journal.DisposeAsync().AsTask().GetAwaiter().GetResult();
    }

    private static void AssertThrows<T>(Action action) where T : Exception
    {
        try { action(); }
        catch (T) { return; }
        throw new InvalidOperationException("Expected " + typeof(T).Name);
    }
}
