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
            // Import does not imply restored physical data; it verifies the
            // exact archived two acquisitions and declared VIN-bound scope.
            var imported = NativeScopedBackupImport.VerifyZip(zipPath, scope);
            Check(imported.Bytes.SequenceEqual(bytes) &&
                imported.SecondBytes is not null &&
                imported.SecondBytes.SequenceEqual(bytes) &&
                imported.Sha256 == sha && imported.BlocksPerPass == 3,
                "Read-only import preserves both original acquisitions");
            var wrongScope = new J2534MemoryReadScope("1G1FA1RXXR0123457",
                scope.RequestCanId, scope.ResponseCanId, scope.ProtocolNumber,
                scope.StartAddress, scope.Length, scope.MaxReadBytes, scope.Session);
            Reject<InvalidDataException>(() => NativeScopedBackupImport.VerifyZip(zipPath, wrongScope));
            Reject<InvalidDataException>(() => NativeScopedBackupImport.VerifyZip(zipPath,
                new J2534MemoryReadScope(vin, scope.RequestCanId, scope.ResponseCanId,
                    scope.ProtocolNumber, scope.StartAddress + 1, scope.Length,
                    scope.MaxReadBytes, scope.Session)));
            Reject<InvalidDataException>(() => NativeScopedBackupImport.VerifyZip(zipPath,
                new J2534MemoryReadScope(vin, scope.RequestCanId, scope.ResponseCanId,
                    scope.ProtocolNumber, scope.StartAddress, scope.Length - 1,
                    scope.MaxReadBytes, scope.Session)));

            var originalZip = File.ReadAllBytes(zipPath);
            var alteredPath = Path.Combine(directory, "altered.zip");
            File.WriteAllBytes(alteredPath, originalZip);
            using (var alteredZip = ZipFile.Open(alteredPath, ZipArchiveMode.Update))
            {
                alteredZip.GetEntry("capture/0/first.bin")!.Delete();
                using var outStream = alteredZip.CreateEntry("capture/0/first.bin").Open();
                var alteredBytes = bytes.ToArray();
                alteredBytes[15] ^= 1;
                outStream.Write(alteredBytes);
            }
            Reject<InvalidDataException>(() => NativeScopedBackupImport.VerifyZip(alteredPath, scope));
            File.Delete(alteredPath);
            var extraPath = Path.Combine(directory, "extra.zip");
            File.WriteAllBytes(extraPath, originalZip);
            using (var extraZip = ZipFile.Open(extraPath, ZipArchiveMode.Update))
                extraZip.CreateEntry("untrusted/extra.bin");
            Reject<InvalidDataException>(() => NativeScopedBackupImport.VerifyZip(extraPath, scope));
            File.Delete(extraPath);
            var symlinkPath = Path.Combine(directory, "symlink.zip");
            File.WriteAllBytes(symlinkPath, originalZip);
            using (var symlinkZip = ZipFile.Open(symlinkPath, ZipArchiveMode.Update))
            {
                var entry = symlinkZip.GetEntry("capture/0/first.bin")!;
                entry.ExternalAttributes = unchecked((int)(0xA000 << 16));
            }
            Reject<InvalidDataException>(() => NativeScopedBackupImport.VerifyZip(symlinkPath, scope));
            File.Delete(symlinkPath);
            var androidStylePath = Path.Combine(directory, "android-properties.zip");
            File.WriteAllBytes(androidStylePath, originalZip);
            string androidManifest;
            using (var intact = ZipFile.OpenRead(zipPath))
            using (var reader = new StreamReader(intact.GetEntry("manifest.properties")!.Open()))
                androidManifest = reader.ReadToEnd();
            using (var converted = ZipFile.Open(androidStylePath, ZipArchiveMode.Update))
            {
                converted.GetEntry("manifest.properties")!.Delete();
                using var writer = new StreamWriter(converted.CreateEntry("manifest.properties").Open());
                writer.Write("#CamaroDiag ECU offline archive v2\n#Sun Nov 22 2026\n");
                writer.Write(androidManifest.Replace("controller=\n", "controller=E39A\n")
                    .Replace("capture.0.region=OTHER\n", "capture.0.region=CALIBRATION\n")
                    .Replace("capture.0.origin=REPORTED_HARDWARE_READ\n",
                        "capture.0.origin=IMPORTED_FILE\n")
                    .Replace("required=\n", "required=CALIBRATION,EXTERNAL_EEPROM\n")
                    .Replace("capture.0.sha256=" + sha,
                        "capture.0.sha256=" + sha.ToLowerInvariant()));
            }
            Check(NativeScopedBackupImport.VerifyZip(androidStylePath, scope).Bytes.SequenceEqual(bytes),
                "Java Properties comment headers remain importable");
            File.Delete(androidStylePath);
            var oversizedLabelPath = Path.Combine(directory, "oversized-label.zip");
            File.WriteAllBytes(oversizedLabelPath, originalZip);
            using (var labelZip = ZipFile.Open(oversizedLabelPath, ZipArchiveMode.Update))
            {
                labelZip.GetEntry("manifest.properties")!.Delete();
                using var writer = new StreamWriter(labelZip.CreateEntry("manifest.properties").Open());
                writer.Write(androidManifest.Replace("controller=\n",
                    "controller=" + new string('X', 129) + "\n"));
            }
            Reject<InvalidDataException>(() =>
                NativeScopedBackupImport.VerifyZip(oversizedLabelPath, scope));
            File.Delete(oversizedLabelPath);

            var alteredDigestPath = Path.Combine(directory, "bad-digest.zip");
            File.WriteAllBytes(alteredDigestPath, originalZip);
            using (var digestZip = ZipFile.Open(alteredDigestPath, ZipArchiveMode.Update))
            {
                digestZip.GetEntry("manifest.properties")!.Delete();
                using var writer = new StreamWriter(digestZip.CreateEntry("manifest.properties").Open());
                writer.Write(androidManifest.Replace("capture.0.sha256=" + sha,
                    "capture.0.sha256=" + new string('0', 64)));
            }
            Reject<InvalidDataException>(() => NativeScopedBackupImport.VerifyZip(alteredDigestPath, scope));
            File.Delete(alteredDigestPath);

            var duplicatePath = Path.Combine(directory, "duplicated.zip");
            File.WriteAllBytes(duplicatePath, originalZip);
            using (var duplicateZip = ZipFile.Open(duplicatePath, ZipArchiveMode.Update))
                duplicateZip.CreateEntry("capture/0/first.bin");
            Reject<InvalidDataException>(() => NativeScopedBackupImport.VerifyZip(duplicatePath, scope));
            File.Delete(duplicatePath);
            var malformedPath = Path.Combine(directory, "malformed.zip");
            File.WriteAllBytes(malformedPath, originalZip);
            string originalManifest;
            using (var intact = ZipFile.OpenRead(zipPath))
            using (var reader = new StreamReader(intact.GetEntry("manifest.properties")!.Open()))
                originalManifest = reader.ReadToEnd();
            using (var malformed = ZipFile.Open(malformedPath, ZipArchiveMode.Update))
            {
                malformed.GetEntry("manifest.properties")!.Delete();
                using var output = new StreamWriter(malformed.CreateEntry("manifest.properties").Open());
                output.Write(originalManifest + "\n");
            }
            Reject<InvalidDataException>(() => NativeScopedBackupImport.VerifyZip(malformedPath, scope));
            File.Delete(malformedPath);
            var missingPath = Path.Combine(directory, "missing.zip");
            File.WriteAllBytes(missingPath, originalZip);
            using (var incomplete = ZipFile.Open(missingPath, ZipArchiveMode.Update))
                incomplete.GetEntry("capture/0/second.bin")!.Delete();
            Reject<InvalidDataException>(() => NativeScopedBackupImport.VerifyZip(missingPath, scope));
            File.Delete(missingPath);
            Reject<IOException>(() => NativeScopedBackupArchive.SaveNew(zipPath, scope, capture, CancellationToken.None));
            Check(File.ReadAllBytes(zipPath).SequenceEqual(originalZip), "Prior backup never overwritten");

            // The public offline command must work without a J2534 driver.
            var profilePath = Path.Combine(directory, "read-profile.json");
            File.WriteAllText(profilePath, System.Text.Json.JsonSerializer.Serialize(new
            {
                expectedVin = scope.ExpectedVin,
                requestCanIdHex = scope.RequestCanId.ToString("X"),
                responseCanIdHex = scope.ResponseCanId.ToString("X"),
                protocolNumber = scope.ProtocolNumber,
                startAddressHex = scope.StartAddress.ToString("X"),
                lengthBytes = scope.Length,
                maxReadBytes = scope.MaxReadBytes,
                session = scope.Session
            }));
            Check(NativeScopedBackupImportCli.Run([
                NativeScopedBackupImportCli.Command, zipPath, profilePath
            ]) == 0, "Offline ZIP verification CLI accepts a valid archive");
            Check(NativeScopedBackupImportCli.Run([
                NativeScopedBackupImportCli.Command, zipPath
            ]) == 6, "Offline verification CLI rejects incomplete arguments");
            File.Delete(profilePath);

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
