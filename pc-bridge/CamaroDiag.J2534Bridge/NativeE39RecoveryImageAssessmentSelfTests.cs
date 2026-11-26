using System.Security.Cryptography;
using System.Text.Json;

namespace CamaroDiag.J2534Bridge;

internal static class NativeE39RecoveryImageAssessmentSelfTests
{
    internal static int Run()
    {
        var checks = 0;
        void Check(bool condition, string what)
        {
            checks++;
            if (!condition) throw new InvalidOperationException(
                "E39 recovery image test failed: " + what);
        }

        var folder = Path.Combine(Path.GetTempPath(),
            "camarodiag-recovery-image-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(folder);
        try
        {
            const uint address = 0x20000;
            const int sectorBytes = 0x10000;
            const string vin = "1G1FA1RXXR0123456";
            var journalPath = Path.Combine(folder, "transaction.jsonl");
            var imagePath = Path.Combine(folder, "original.bin");
            var image = new byte[NativeE39ImageInspector.ImageBytes];
            for (var i = 0; i < image.Length; i++) image[i] = (byte)(i * 23 + i / 4096);
            var sector = image.AsSpan((int)address, sectorBytes).ToArray();
            var sectorSha = Convert.ToHexString(SHA256.HashData(sector));
            var imageSha = Convert.ToHexString(SHA256.HashData(image));
            File.WriteAllBytes(imagePath, image);
            var journal = new NativeE39FileWriteJournal(journalPath);
            try
            {
                void Add(NativeE39WriteSequence.Phase phase, string detail) =>
                    journal.AppendAsync(new(phase, address, detail), CancellationToken.None)
                        .GetAwaiter().GetResult();
                Add(NativeE39WriteSequence.Phase.Prepared, sectorSha);
                Add(NativeE39WriteSequence.Phase.OriginalCrcVerified, "12345678");
                journal.PreserveOriginalBlockAsync(address, sector,
                    sectorSha, CancellationToken.None).GetAwaiter().GetResult();
                Add(NativeE39WriteSequence.Phase.OriginalBytesVerified, sectorSha);
            }
            finally { journal.DisposeAsync().AsTask().GetAwaiter().GetResult(); }

            var metadataPath = journalPath + ".meta.json";
            string Metadata(string digest) => JsonSerializer.Serialize(new
            {
                Schema = 1, Utc = DateTimeOffset.UtcNow, Family = "E39",
                ExpectedVin = vin, ExpectedOsId = 12664221u,
                ProfileSha256 = imageSha, DriverSha256 = imageSha,
                RegistryView = "Registry64", OriginalSha256 = digest,
                TargetSha256 = imageSha, KernelSha256 = imageSha
            });
            File.WriteAllText(metadataPath, Metadata(imageSha));
            var initialMetadata = NativeE39SavedBlockAssessment.InspectTargetMetadata(journalPath);
            Check(initialMetadata.Verified && initialMetadata.MetadataSnapshotSha256 is { Length: 64 },
                "Recovery metadata retains its complete byte snapshot digest");
            var unchangedMetadata = File.ReadAllText(metadataPath);
            File.WriteAllText(metadataPath, unchangedMetadata + " ");
            var modifiedMetadata = NativeE39SavedBlockAssessment.InspectTargetMetadata(journalPath);
            Check(modifiedMetadata.Verified && modifiedMetadata.Vin == initialMetadata.Vin &&
                modifiedMetadata.MetadataSnapshotSha256 != initialMetadata.MetadataSnapshotSha256,
                "A metadata-only change is detectable even when parsed identity remains unchanged");
            File.WriteAllText(metadataPath, unchangedMetadata);
            File.WriteAllText(metadataPath, unchangedMetadata.Replace("\"Schema\":1", "\"Schema\":\"1\""));
            Check(!NativeE39SavedBlockAssessment.InspectTargetMetadata(journalPath).Verified,
                "A wrong JSON value type fails closed instead of throwing");
            File.WriteAllText(metadataPath, unchangedMetadata);

            var pass = NativeE39RecoveryImageAssessment.Inspect(journalPath, imagePath);
            Check(pass.Verified && pass.VerifiedSectors == 1 && pass.FailedSectorAddress is null,
                "Full original BIN and live ECU saved sector bytes match the same evidence");

            var blockDirectory = journalPath + ".blocks";
            var originalEvidence = NativeE39SavedBlockAssessment.Inspect(journalPath);
            Check(originalEvidence.Verified && originalEvidence.SnapshotSha256 is { Length: 64 },
                "Exact journal and sector-envelope digest available");
            var journalBytes = File.ReadAllText(journalPath);
            File.WriteAllText(journalPath, journalBytes.Replace("\"Detail\":", "\"Detail\" :"));
            var changedJournal = NativeE39SavedBlockAssessment.Inspect(journalPath);
            Check(changedJournal.Verified && changedJournal.JournalState == originalEvidence.JournalState &&
                changedJournal.Blocks.SequenceEqual(originalEvidence.Blocks) &&
                changedJournal.SnapshotSha256 != originalEvidence.SnapshotSha256,
                "Journal-only formatting change detected despite identical decoded records");
            File.WriteAllText(journalPath, journalBytes);
            var blockPath = Directory.GetFiles(blockDirectory).Single();
            var originalEnvelope = File.ReadAllBytes(blockPath);
            var cut = Array.IndexOf(originalEnvelope, (byte)'\n');
            var changedEnvelope = System.Text.Encoding.UTF8.GetBytes(
                System.Text.Encoding.UTF8.GetString(originalEnvelope, 0, cut) + " \n")
                .Concat(originalEnvelope.Skip(cut + 1)).ToArray();
            File.WriteAllBytes(blockPath, changedEnvelope);
            var changedHeader = NativeE39SavedBlockAssessment.Inspect(journalPath);
            Check(changedHeader.Verified && changedHeader.Blocks.SequenceEqual(originalEvidence.Blocks) &&
                changedHeader.SnapshotSha256 != originalEvidence.SnapshotSha256,
                "Sector envelope metadata-only change detected");
            File.WriteAllBytes(blockPath, originalEnvelope);
            Check(NativeE39SavedBlockAssessment.Inspect(journalPath).SnapshotSha256 ==
                originalEvidence.SnapshotSha256, "Snapshot digest is reproducible");

            var unexpectedDirectory = Path.Combine(blockDirectory, "unexpected");
            Directory.CreateDirectory(unexpectedDirectory);
            Check(!NativeE39SavedBlockAssessment.Inspect(journalPath).Verified,
                "Unexpected directory is not ignored in the physical backup inventory");
            Directory.Delete(unexpectedDirectory);
            Check(NativeE39SavedBlockAssessment.Inspect(journalPath).Verified,
                "Original unmodified inventory still verifies");

            var changed = image.ToArray();
            changed[0x40000] ^= 1; // untouched region: full BIN SHA must still match
            File.WriteAllBytes(imagePath, changed);
            Check(!NativeE39RecoveryImageAssessment.Inspect(journalPath, imagePath).Verified,
                "Full-image hash mismatch outside changed sectors is detected");
            File.WriteAllBytes(imagePath, image);

            // Fake self-consistent full image digest, but physical original
            // sector still differs. Both digest AND live ECU bytes are required.
            changed = image.ToArray();
            changed[checked((int)address) + 42] ^= 1;
            File.WriteAllBytes(imagePath, changed);
            File.WriteAllText(metadataPath, Metadata(
                Convert.ToHexString(SHA256.HashData(changed))));
            var mismatch = NativeE39RecoveryImageAssessment.Inspect(journalPath, imagePath);
            Check(!mismatch.Verified && mismatch.VerifiedSectors == 0 &&
                  mismatch.FailedSectorAddress == address,
                "Rewritten manifest hash does not conceal a changed physical ECU sector");
            File.WriteAllBytes(imagePath, image);
            File.WriteAllText(metadataPath, Metadata(imageSha));

            File.WriteAllBytes(imagePath, image[..^1]);
            Check(!NativeE39RecoveryImageAssessment.Inspect(journalPath, imagePath).Verified,
                "Truncated 3 MiB original BIN cannot qualify physical recovery evidence");
            File.WriteAllBytes(imagePath, image);
            File.Delete(metadataPath);
            Check(!NativeE39RecoveryImageAssessment.Inspect(journalPath, imagePath).Verified,
                "A missing VIN/OSID sidecar cannot bind a full BIN to saved ECU sectors");

            Check(File.Exists(journalPath) &&
                  File.Exists(Path.Combine(journalPath + ".blocks",
                      "00020000-65536.ecu-block")),
                "All recovery inspection paths are offline and non-destructive");
        }
        finally { Directory.Delete(folder, recursive: true); }
        return checks;
    }
}
