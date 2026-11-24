using System.Security.Cryptography;
using System.Text.Json;

namespace CamaroDiag.J2534Bridge;

/// <summary>Local bench entry point. Never routed through the network bridge.</summary>
internal static class NativeE39QualifiedBenchCli
{
    internal const string Command = "--program-e39-bench";

    internal static async Task<int> RunAsync(string[] args)
    {
        if (args.Length != 7 || !args[0].Equals(Command, StringComparison.OrdinalIgnoreCase) ||
            !int.TryParse(args[1], out int index) || index < 0)
        {
            Console.Error.WriteLine("Usage: --program-e39-bench <adapter-index> <profile.json> <original.bin> <target.bin> <kernel.bin> <NEW-journal.jsonl>");
            return 6;
        }
        using var cancel = new CancellationTokenSource();
        void Cancel(object? sender, ConsoleCancelEventArgs e) { e.Cancel = true; cancel.Cancel(); }
        Console.CancelKeyPress += Cancel;
        try
        {
            // No CamaroDiag-signing certificate or owner-created authorization
            // key is required. Real ECU SecurityAccess remains protocol-driven.
            NativeEcuWriteAvailability.RequirePhysicalEcuProgrammingEnabled();
            var qualified = NativeE39QualifiedBenchProfile.Load(args[2]);
            var original = NativeE39QualifiedBenchProfile.ReadBounded(args[3], NativeE39ImageInspector.ImageBytes);
            var target = NativeE39QualifiedBenchProfile.ReadBounded(args[4], NativeE39ImageInspector.ImageBytes);
            var kernel = NativeE39QualifiedBenchProfile.ReadBounded(args[5], NativeE39RamLoader.MaximumKernelBytes);
            var journalPath = Path.GetFullPath(args[6]);
            if (File.Exists(journalPath) || File.Exists(journalPath + ".meta.json"))
                throw new IOException("Choose a new journal path; previous recovery evidence is retained.");
            var originalHash = Hash(original);
            var targetHash = Hash(target);
            var q = qualified.Policy.Session;
            var provisional = new NativeE39BackupQualification.Evidence(q.ExpectedVin, q.ExpectedOsId,
                originalHash, original.Length);
            // Offline image validation does not certify the actual ECU backup.
            _ = NativeE39BenchWorkflow.Prepare(qualified.Policy, provisional, original, target, kernel);
            var adapters = J2534Registry.Discover();
            if (index >= adapters.Count)
                throw new ArgumentException("Selected J2534 adapter does not exist.");
            var adapter = adapters[index];
            using var lease = NativeE39PhysicalAdmission.OpenVerifiedDriverLease(
                adapter, qualified.Scope, qualified.Channel);
            var actualDriverSha256 = Convert.ToHexString(SHA256.HashData(lease));
            lease.Position = 0;
            // Invoking the explicit local --program-e39-bench command with
            // its target and original images is already an operator request.
            // Do not require an extra CamaroDiag challenge string or forbid
            // noninteractive CLI usage; the ECU and physical preflights still
            // determine if the transport/programming operation is possible.
            Console.WriteLine("Local E39/E39A bench: physical identity, image, supply and readback remain checked.");
            Console.WriteLine($"ECU: {qualified.Family}; VIN: {q.ExpectedVin}; OS: {q.ExpectedOsId}");
            Console.WriteLine("Adapter: " + adapter.DisplayName);
            Console.WriteLine("Target SHA-256: " + targetHash);
            Console.WriteLine("Journal: " + journalPath);
            Console.WriteLine("No automatic retry, ECU reset or power cycle is performed.");
            // Explicit two-step operator consent before creating a write journal,
            // opening a device or sending any programming command.
            // Redirected stdin cannot acknowledge a real local warning.
            if (Console.IsInputRedirected ||
                !EcuWriteOperatorConsent.AskBeforeProgramming(
                    Console.ReadLine, Console.WriteLine,
                    qualified.Family + " / VIN " + q.ExpectedVin + " / cible " + targetHash))
            {
                Console.WriteLine("NON ou absence de confirmation : aucune écriture ECU.");
                return 7;
            }
            cancel.Token.ThrowIfCancellationRequested();
            // Separate immutable metadata and crash-resilient phase journal.
            // Both must be created and flushed before any ECU is opened.
            using (var metadata = new FileStream(journalPath + ".meta.json", FileMode.CreateNew,
                FileAccess.Write, FileShare.Read, 4096, FileOptions.WriteThrough))
            {
                JsonSerializer.Serialize(metadata, new
                {
                    Schema = 1, Utc = DateTimeOffset.UtcNow, qualified.Family,
                    q.ExpectedVin, q.ExpectedOsId, qualified.ProfileSha256,
                    DriverSha256 = actualDriverSha256, qualified.Channel.RegistryView,
                    OriginalSha256 = originalHash, TargetSha256 = targetHash,
                    KernelSha256 = Hash(kernel)
                });
                metadata.Flush(true);
            }
            await using var journal = new NativeE39FileWriteJournal(journalPath);
            var result = await J2534Device.RunQualifiedNativeE39BenchAsync(adapter, qualified.Scope,
                qualified.Channel, qualified.Policy, original, target, kernel, journal, cancel.Token);
            Console.WriteLine(result);
            Console.WriteLine("Preserve backup and journal. Independent functional bench evaluation remains necessary.");
            return 0;
        }
        catch (Exception error) when (error is IOException or InvalidDataException or UnauthorizedAccessException or
            ArgumentException or InvalidOperationException or OperationCanceledException or
            CryptographicException or JsonException or OverflowException or NotSupportedException or AggregateException)
        {
            Console.Error.WriteLine("Bench transaction refused or interrupted: " + error.Message);
            Console.Error.WriteLine("Preserve original and any journal. Do not automatically retry or reset the ECU.");
            return 8;
        }
        finally { Console.CancelKeyPress -= Cancel; }
    }

    private static string Hash(byte[] bytes) => Convert.ToHexString(SHA256.HashData(bytes));
}
