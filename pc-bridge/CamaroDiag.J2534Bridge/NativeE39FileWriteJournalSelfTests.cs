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
            var completed = new NativeE39FileWriteJournal(path);
            completed.AppendAsync(new(NativeE39WriteSequence.Phase.Prepared,
                0x20000, "offline baseline"), CancellationToken.None)
                .GetAwaiter().GetResult();
            completed.AppendAsync(new(NativeE39WriteSequence.Phase.EraseStarted,
                0x20000, "offline simulated erase"), CancellationToken.None)
                .GetAwaiter().GetResult();
            completed.AppendAsync(new(NativeE39WriteSequence.Phase.WrittenCrcVerified,
                0x20000, "offline simulated CRC"), CancellationToken.None)
                .GetAwaiter().GetResult();
            completed.AppendAsync(new(NativeE39WriteSequence.Phase.Completed,
                0, "offline simulated success"), CancellationToken.None)
                .GetAwaiter().GetResult();
            completed.DisposeAsync().AsTask().GetAwaiter().GetResult();
            Assert(NativeE39FileWriteJournal.Inspect(path) ==
                NativeE39FileWriteJournal.CompletionState.Completed,
                "Durable last completed entry recognized");

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
            Assert(NativeE39FileWriteJournal.Inspect(path) !=
                NativeE39FileWriteJournal.CompletionState.Completed,
                "Recovery-required state cannot be overwritten by later success");

            File.Delete(path);
            var failed = new NativeE39FileWriteJournal(path);
            failed.AppendAsync(new(NativeE39WriteSequence.Phase.RecoveryRequired,
                0x20000, "IOException"), CancellationToken.None)
                .GetAwaiter().GetResult();
            failed.DisposeAsync().AsTask().GetAwaiter().GetResult();
            Assert(NativeE39FileWriteJournal.Inspect(path) ==
                NativeE39FileWriteJournal.CompletionState.RecoveryRequired,
                "Durable recovery-required status survives process exit");
        }
        finally
        {
            if (File.Exists(path)) File.Delete(path);
        }
        Console.WriteLine($"PASS: {tests} native ECU durable journal self-tests.");
        return tests;
    }

    private static void AssertThrows<T>(Action action) where T : Exception
    {
        try { action(); }
        catch (T) { return; }
        throw new InvalidOperationException("Expected " + typeof(T).Name);
    }
}
