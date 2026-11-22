using System.IO.Compression;
using System.Security.Cryptography;
using System.Text;

namespace CamaroDiag.J2534Bridge;

internal static class NativeScopedBackupArchiveSelfTests
{
    internal static int Run()
    {
        int count = 0;
        void Check(bool condition, string label)
        {
            if (!condition) throw new InvalidOperationException("Backup archive test failed: " + label);
            count++;
        }
        void Reject<T>(Action action) where T : Exception
        {
            try { action(); }
            catch (T) { count++; return; }
            throw new InvalidOperationException("Expected " + typeof(T).Name);
        }

        const string vin = "1G1FA1RXXR0123456";
        var scope = new J2534MemoryReadScope(vin, 0x7E0, 0x7E8, 33, 0x1000, 300, 128, 1);
        var bytes = Enumerable.Range(0, 300).Select(i => (byte)i).ToArray();
        var sha = Convert.ToHexString(SHA256.HashData(bytes));
        var capture = new NativeScopedMemoryReader.VerifiedCapture(bytes, scope.StartAddress, vin,
            sha, 3, bytes.ToArray());
        var directory = Path.Combine(Path.GetTempPath(), "camarodiag-backup-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(directory);
        try
        {
            var zipPath = Path.Combine(directory, "verified.zip");
            NativeScopedBackupArchive.SaveNew(zipPath, scope, capture, CancellationToken.None);
            using (var zip = ZipFile.OpenRead(zipPath))
            {
                Check(zip.Entries.Count == 3, "Portable archive contains both reads and manifest");
                using (var reader = new StreamReader(zip.GetEntry("manifest.properties")!.Open(), Encoding.ASCII))
                {
                    var manifest = reader.ReadToEnd();
                    Check(manifest.Contains("format=CamaroDiag ECU offline archive v2\n") &&
                        manifest.Contains("vin=" + vin + "\n") &&
                        manifest.Contains("required=\n") &&
                        manifest.Contains("capture.0.start=4096\n") &&
                        manifest.Contains("capture.0.origin=REPORTED_HARDWARE_READ\n") &&
                        manifest.Contains("capture.0.sha256=" + sha + "\n") &&
                        manifest.Contains("capture.0.second=true\n"),
                        "Android-compatible manifest and deliberately partial coverage");
                }
                foreach (var name in new[] { "capture/0/first.bin", "capture/0/second.bin" })
                {
                    using var input = zip.GetEntry(name)!.Open();
                    using var output = new MemoryStream();
                    input.CopyTo(output);
                    Check(output.ToArray().SequenceEqual(bytes), "Actual acquisition retained: " + name);
                }
            }
            var originalZip = File.ReadAllBytes(zipPath);
            Reject<IOException>(() => NativeScopedBackupArchive.SaveNew(zipPath, scope, capture, CancellationToken.None));
            Check(File.ReadAllBytes(zipPath).SequenceEqual(originalZip), "Prior backup never overwritten");

            var raw = Path.Combine(directory, "original.bin");
            NativeScopedBackupArchive.SaveNew(raw, scope, capture, CancellationToken.None);
            Check(File.ReadAllBytes(raw).SequenceEqual(bytes), "Raw option preserves exact primary bytes");

            string unique = Path.Combine(directory, "rejected.zip");
            Reject<InvalidDataException>(() => NativeScopedBackupArchive.SaveNew(unique, scope,
                capture with { SecondBytes = null }, CancellationToken.None));
            Reject<InvalidDataException>(() => NativeScopedBackupArchive.SaveNew(unique, scope,
                capture with { SecondBytes = bytes }, CancellationToken.None));
            var changed = bytes.ToArray(); changed[0] ^= 1;
            Reject<InvalidDataException>(() => NativeScopedBackupArchive.SaveNew(unique, scope,
                capture with { SecondBytes = changed }, CancellationToken.None));
            Reject<InvalidDataException>(() => NativeScopedBackupArchive.SaveNew(unique, scope,
                capture with { Bytes = changed, SecondBytes = changed.ToArray() }, CancellationToken.None));
            Reject<InvalidDataException>(() => NativeScopedBackupArchive.SaveNew(unique, scope,
                capture with { ExpectedVin = "1G1FA1RXXR0123457" }, CancellationToken.None));
            Reject<ArgumentException>(() => NativeScopedBackupArchive.SaveNew(Path.ChangeExtension(unique, ".txt"),
                scope, capture, CancellationToken.None));
            using (var cancellation = new CancellationTokenSource())
            {
                cancellation.Cancel();
                Reject<OperationCanceledException>(() => NativeScopedBackupArchive.SaveNew(unique, scope,
                    capture, cancellation.Token));
            }
            Check(!File.Exists(unique) && Directory.GetFiles(directory).Length == 2,
                "Rejected or cancelled captures leave no completed or temporary output");
        }
        finally
        {
            Directory.Delete(directory, recursive: true);
        }
        Console.WriteLine($"PASS: {count} portable ECU backup archive self-tests (simulated).");
        return count;
    }
}
