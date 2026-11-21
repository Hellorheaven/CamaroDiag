using System.Runtime.InteropServices;
using CamaroDiag.J2534Bridge;

// Actual C kernel plus actual managed transaction, on a RAM-only flash model.
internal static class Program
{
    private static class Model
    {
        private const string Library = "cd_kernel_model";
        [DllImport(Library, CallingConvention = CallingConvention.Cdecl)]
        internal static extern int cd_sim_init([In] byte[] input, uint length);
        [DllImport(Library, CallingConvention = CallingConvention.Cdecl)]
        internal static extern int cd_sim_exchange([In] byte[] request, uint length,
            [Out] byte[] response, uint capacity);
        [DllImport(Library, CallingConvention = CallingConvention.Cdecl)]
        internal static extern uint cd_sim_pulses();
        [DllImport(Library, CallingConvention = CallingConvention.Cdecl)]
        internal static extern uint cd_sim_remaining();
        [DllImport(Library, CallingConvention = CallingConvention.Cdecl)]
        internal static extern int cd_sim_recovery();
        [DllImport(Library, CallingConvention = CallingConvention.Cdecl)]
        internal static extern void cd_sim_fail_next_pulse();
    }

    private static void Check(bool ok, string message)
    {
        if (!ok) throw new InvalidOperationException(message);
    }

    private static async Task Main()
    {
        byte[] original = Enumerable.Range(0, 0x20000).Select(i => (byte)(i * 17)).ToArray();
        byte[] target = original.ToArray();
        target[0x1234] ^= 1;
        target[0x11234] ^= 2;
        var plan = NativeEcuSectorPlanner.Preview(
            new NativeEcuSectorPlanner.Layout(0x20000, original.Length, 0x10000, 8, [], true),
            original, target);
        var options = new NativeE39WriteSequence.Options(1024, 8, true, true, 12000, 14500);
        var folder = Path.Combine(Path.GetTempPath(), "camarodiag-native-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(folder);
        try
        {
            // Three distinct outcomes: completed transaction, wrong original,
            // and physical flash-pulse failure in the native model.
            foreach (var scenario in new[] { "complete", "wrong-original", "pulse-failed" })
            {
                byte[] baseline = original.ToArray();
                if (scenario == "wrong-original") baseline[0x11234] ^= 0x80;
                Check(Model.cd_sim_init(baseline, (uint)baseline.Length) == 1, "Model reset failed.");
                if (scenario == "pulse-failed") Model.cd_sim_fail_next_pulse();
                var link = new NativeE39KernelLink(Exchange, _ => Task.FromResult<uint?>(13500), 1024);
                await link.ProbeAsync(CancellationToken.None);
                string journalPath = Path.Combine(folder, scenario + ".jsonl");
                Exception? failure = null;
                NativeE39WriteSequence.Result? result = null;
                await using (var journal = new NativeE39FileWriteJournal(journalPath))
                {
                    try
                    {
                        result = await NativeE39WriteSequence.RunBenchSequenceAsync(
                            link, journal, plan, options, CancellationToken.None);
                    }
                    catch (Exception error) when (error is IOException or InvalidDataException)
                    {
                        failure = error;
                    }
                }
                var state = NativeE39FileWriteJournal.Inspect(journalPath);
                if (scenario == "complete")
                {
                    Check(failure == null && result?.SectorsWritten == 2 &&
                        result.BytesWritten == target.Length, "Native transaction failed.");
                    Check(Model.cd_sim_remaining() == 0 && Model.cd_sim_recovery() == 0 &&
                        Model.cd_sim_pulses() > 2, "Native model did not finish programming.");
                    Check((await link.ReadFlashBytesAsync(0x20000, target.Length, CancellationToken.None))
                        .SequenceEqual(target), "Full native readback differs.");
                    Check(state == NativeE39FileWriteJournal.CompletionState.Completed, "Journal not completed.");
                }
                else if (scenario == "wrong-original")
                {
                    Check(failure is InvalidDataException && Model.cd_sim_pulses() == 0 &&
                        Model.cd_sim_recovery() == 0 &&
                        state == NativeE39FileWriteJournal.CompletionState.Interrupted,
                        "Second sector mismatch must stop before any erase.");
                }
                else
                {
                    Check(failure is IOException && Model.cd_sim_pulses() == 1 &&
                        Model.cd_sim_recovery() == 1 &&
                        state == NativeE39FileWriteJournal.CompletionState.RecoveryRequired,
                        "Native pulse failure was not persisted as recovery required.");
                }
                Console.WriteLine("PASS: actual C / C# integration " + scenario);
            }
        }
        finally { Directory.Delete(folder, recursive: true); }
    }

    private static Task<byte[]> Exchange(byte[] request, CancellationToken token)
    {
        token.ThrowIfCancellationRequested();
        var response = new byte[4091];
        var count = Model.cd_sim_exchange(request, (uint)request.Length, response, (uint)response.Length);
        if (count <= 0 || count > response.Length) throw new IOException("Invalid native model response.");
        return Task.FromResult(response[..count]);
    }
}
