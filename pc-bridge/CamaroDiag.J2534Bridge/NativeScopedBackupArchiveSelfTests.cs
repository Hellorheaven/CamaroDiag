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
                    Check(manifest.Contains("format=CamaroDiag ECU offline archive v3\n") &&
                        manifest.Contains("channel.requestCanId=2016\n") &&
                        manifest.Contains("channel.responseCanId=2024\n") &&
                        manifest.Contains("channel.protocolNumber=33\n") &&
                        manifest.Contains("channel.session=1\n") &&
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
            // The public importer refuses an unfinished .partial suffix.
            // Only the trusted writer's explicit pre-publication validation
            // may inspect a hidden staged ZIP with its original bytes.
            var temporaryStage = Path.Combine(directory, ".camarodiag-manual.partial");
            File.Copy(zipPath, temporaryStage);
            Reject<InvalidDataException>(() =>
                NativeScopedBackupImport.VerifyZip(temporaryStage, scope));
            Check(NativeScopedBackupImport.VerifyZip(
                temporaryStage, scope, allowInternalStage: true).Sha256 == sha,
                "Internal staged ZIP is fully verified before publication");
            File.Delete(temporaryStage);

            // Import does not imply restored physical data; it verifies the
            // exact archived two acquisitions and declared VIN-bound scope.
            var imported = NativeScopedBackupImport.VerifyZip(zipPath, scope);
            Check(imported.ByteCount == bytes.Length &&
                imported.Vin == vin && imported.StartAddress == scope.StartAddress &&
                imported.Sha256 == sha && imported.DeclaredRegion == "OTHER" &&
                imported.DeclaredOrigin == "REPORTED_HARDWARE_READ" &&
                imported.RequiredRegions.Count == 0 && imported.ChannelBound,
                "Read-only inspection verifies backup integrity without exposing programming bytes");
            Reject<InvalidDataException>(() => NativeScopedBackupImport.VerifyZip(zipPath,
                new J2534MemoryReadScope(vin, 0x7E1, 0x7E9, 33, scope.StartAddress,
                    scope.Length, scope.MaxReadBytes, scope.Session)));
            Reject<InvalidDataException>(() => NativeScopedBackupImport.VerifyZip(zipPath,
                new J2534MemoryReadScope(vin, scope.RequestCanId, scope.ResponseCanId, 35,
                    scope.StartAddress, scope.Length, scope.MaxReadBytes, scope.Session)));
            Reject<InvalidDataException>(() => NativeScopedBackupImport.VerifyZip(zipPath,
                new J2534MemoryReadScope(vin, scope.RequestCanId, scope.ResponseCanId, 33,
                    scope.StartAddress, scope.Length, scope.MaxReadBytes, 3)));
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

            var firmwarePath = Path.Combine(directory, "observed-osid.zip");
            var observedFirmware = new NativeScopedBackupArchive.FirmwareIdentity(0x123456, 0xC1);
            NativeScopedBackupArchive.SaveNew(
                firmwarePath, scope, capture, CancellationToken.None, observedFirmware);
            var observed = NativeScopedBackupImport.VerifyZip(firmwarePath, scope);
            Check(observed.Firmware == observedFirmware && observed.ChannelBound,
                "Both physical channel and the observed GM OSID are preserved in v3 ZIP");
            var incompleteOsidPath = Path.Combine(directory, "missing-osdid.zip");
            File.Copy(firmwarePath, incompleteOsidPath);
            using (var changedZip = ZipFile.Open(incompleteOsidPath, ZipArchiveMode.Update))
            {
                var entry = changedZip.GetEntry("manifest.properties")!;
                string textManifest;
                using (var reader = new StreamReader(entry.Open(), Encoding.ASCII))
                    textManifest = reader.ReadToEnd();
                entry.Delete();
                using var writer = new StreamWriter(changedZip.CreateEntry("manifest.properties").Open(),
                    Encoding.ASCII);
                writer.Write(string.Join("\n", textManifest.Split('\n').Where(line =>
                    !line.StartsWith("ecu.osDid=", StringComparison.Ordinal))));
            }
            Reject<InvalidDataException>(() =>
                NativeScopedBackupImport.VerifyZip(incompleteOsidPath, scope));
            File.Delete(incompleteOsidPath);
            File.Delete(firmwarePath);
            var typedScope = new J2534MemoryReadScope(vin, scope.RequestCanId,
                scope.ResponseCanId, scope.ProtocolNumber, scope.StartAddress,
                scope.Length, scope.MaxReadBytes, scope.Session, "CALIBRATION");
            var typedPath = Path.Combine(directory, "declared-calibration.zip");
            NativeScopedBackupArchive.SaveNew(typedPath, typedScope, capture, CancellationToken.None);
            Check(NativeScopedBackupImport.VerifyZip(typedPath, typedScope).DeclaredRegion ==
                "CALIBRATION", "Explicit calibration region survives portable ZIP export");
            Reject<InvalidDataException>(() => NativeScopedBackupImport.VerifyZip(typedPath, scope));
            File.Delete(typedPath);
            // Legacy v2 files have no channel evidence. They are still
            // inspectable, but the UI must clearly flag missing route binding.
            var legacyPath = Path.Combine(directory, "legacy-v2.zip");
            File.Copy(zipPath, legacyPath);
            using (var legacy = ZipFile.Open(legacyPath, ZipArchiveMode.Update))
            {
                var entry = legacy.GetEntry("manifest.properties")!;
                string textManifest;
                using (var reader = new StreamReader(entry.Open(), Encoding.ASCII))
                    textManifest = reader.ReadToEnd();
                entry.Delete();
                using var writer = new StreamWriter(legacy.CreateEntry("manifest.properties").Open(),
                    Encoding.ASCII);
                writer.Write(string.Join("\n", textManifest.Split('\n').Where(line =>
                    !line.StartsWith("channel.", StringComparison.Ordinal)))
                    .Replace(NativeScopedBackupArchive.Format, NativeScopedBackupArchive.LegacyFormat));
            }
            Check(!NativeScopedBackupImport.VerifyZip(legacyPath, scope).ChannelBound,
                "Legacy v2 archive is accepted without falsely claiming a bound CAN channel");
            File.Delete(legacyPath);
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
                writer.Write(androidManifest.Replace("controller=\n", "controller=LFX\\u00E9\\=ECU\n")
                    .Replace("capture.0.region=OTHER\n", "capture.0.region=CALIBRATION\n")
                    .Replace("capture.0.origin=REPORTED_HARDWARE_READ\n",
                        "capture.0.origin=IMPORTED_FILE\n")
                    .Replace("required=\n", "required=CALIBRATION,EXTERNAL_EEPROM\n")
                    .Replace("capture.0.sha256=" + sha,
                        "capture.0.sha256=" + sha.ToLowerInvariant()));
            }
            var mobile = NativeScopedBackupImport.VerifyZip(androidStylePath,
                new J2534MemoryReadScope(vin, scope.RequestCanId, scope.ResponseCanId,
                    scope.ProtocolNumber, scope.StartAddress, scope.Length,
                    scope.MaxReadBytes, scope.Session, "CALIBRATION"));
            Check(mobile.ByteCount == bytes.Length && mobile.Sha256 == sha &&
                mobile.DeclaredRegion == "CALIBRATION" &&
                mobile.DeclaredOrigin == "IMPORTED_FILE" &&
                mobile.RequiredRegions.SequenceEqual(new[] { "CALIBRATION", "EXTERNAL_EEPROM" }),
                "Android properties with separate origin and required regions remain importable");
            File.Delete(androidStylePath);
            foreach (var malformedLabel in new[] {
                @"Bad\uZZZZ", @"Bad\u123", @"Bad\q", @"Bad\", "Bad=ECU"
            })
            {
                var invalidLabelPath = Path.Combine(directory, "bad-controller.zip");
                File.WriteAllBytes(invalidLabelPath, originalZip);
                using (var invalidZip = ZipFile.Open(invalidLabelPath, ZipArchiveMode.Update))
                {
                    invalidZip.GetEntry("manifest.properties")!.Delete();
                    using var writer = new StreamWriter(invalidZip.CreateEntry("manifest.properties").Open());
                    writer.Write(androidManifest.Replace("controller=\n",
                        "controller=" + malformedLabel + "\n"));
                }
                Reject<InvalidDataException>(() =>
                    NativeScopedBackupImport.VerifyZip(invalidLabelPath, scope));
                File.Delete(invalidLabelPath);
            }
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

            foreach (var invalidRequired in new[] {
                "CALIBRATION,CALIBRATION", "CALIBRATION,UNKNOWN", ",CALIBRATION"
            })
            {
                var invalidRequiredPath = Path.Combine(directory, "bad-regions.zip");
                File.WriteAllBytes(invalidRequiredPath, originalZip);
                using (var regionZip = ZipFile.Open(invalidRequiredPath, ZipArchiveMode.Update))
                {
                    regionZip.GetEntry("manifest.properties")!.Delete();
                    using var writer = new StreamWriter(regionZip.CreateEntry("manifest.properties").Open());
                    writer.Write(androidManifest.Replace("required=\n",
                        "required=" + invalidRequired + "\n"));
                }
                Reject<InvalidDataException>(() =>
                    NativeScopedBackupImport.VerifyZip(invalidRequiredPath, scope));
                File.Delete(invalidRequiredPath);
            }

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
            Check(NativeScopedBackupImportCli.Run([
                NativeScopedBackupImportCli.Command, missingPath, profilePath
            ]) == 8, "Offline verification CLI reports archive rejection");
            File.WriteAllText(profilePath, "{}");
            Check(NativeScopedBackupImportCli.Run([
                NativeScopedBackupImportCli.Command, zipPath, profilePath
            ]) == 8, "Offline verification CLI rejects an incomplete profile");
            File.Delete(missingPath);
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
