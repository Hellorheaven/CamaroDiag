using System.Security.Cryptography;

namespace CamaroDiag.J2534Bridge;

internal static class EcuPostFlashAssessmentSelfTests
{
    internal static int Run()
    {
        var checks = 0;
        void Check(bool ok, string what)
        {
            checks++;
            if (!ok) throw new InvalidOperationException("Post-flash test failed: " + what);
        }
        void Reject(Action action, string what)
        {
            var rejected = false;
            try { action(); }
            catch (InvalidDataException) { rejected = true; }
            Check(rejected, what);
        }

        const string vin = "1G1FA1RXXR0123456";
        var hashes = new Dictionary<uint, string> { [0x20000] = new string('A',64) };
        var good = new EcuPostFlashAssessment.Evidence(vin, vin, "OS-1", "OS-1",
            hashes, hashes, true, true, true, true, true, true, true);
        var pass = EcuPostFlashAssessment.Evaluate(good);
        Check(pass.Status == EcuPostFlashAssessment.Verdict.Passed &&
            !pass.AutomaticRollbackPermitted, "Full pass never triggers rollback.");
        var noRoad = EcuPostFlashAssessment.Evaluate(good with {
            OperatorRoadTestPassed = null, ApplicationRespondsAfterPowerCycle = null
        });
        Check(noRoad.Status == EcuPostFlashAssessment.Verdict.Inconclusive &&
            noRoad.MissingEvidence.Count == 2,
            "No inferred successful vehicle test without measurements.");
        Check(EcuPostFlashAssessment.Evaluate(good with {
            LiveVin = "WRONG", NoNewCriticalDtcRelativeToBaseline = false
        }).Failures.Count == 2, "Wrong identity and newly critical DTC fail.");
        Check(EcuPostFlashAssessment.Evaluate(good with {
            LiveSectorHashes = new Dictionary<uint,string> { [0x20000] = new string('B',64) }
        }).Status == EcuPostFlashAssessment.Verdict.Failed,
            "Sector readback mismatch does not pass.");

        var flashBlocks = NativeMpc5566FlashMap.Blocks();
        var qualifiedGeometry = flashBlocks.First(b => b.Start == 0x20000u);
        var knownLength = checked((int)(qualifiedGeometry.EndExclusive - qualifiedGeometry.Start));
        var previewBlock = new NativeE39SavedBlockAssessment.Block(
            qualifiedGeometry.Start, knownLength, new string('A', 64));
        Check(EcuPostFlashAssessment.MatchesSiliconEraseGeometry([previewBlock]),
            "Exact known silicon erase boundary is accepted for offline consistency only");
        Check(!EcuPostFlashAssessment.MatchesSiliconEraseGeometry([
            previewBlock with { Length = knownLength / 2 }
        ]), "Fragment of silicon erase sector is not a valid restore geometry");
        Check(!EcuPostFlashAssessment.MatchesSiliconEraseGeometry([
            previewBlock with { Address = previewBlock.Address + 8u }
        ]), "Misaligned physical sector address is rejected");
        Check(!EcuPostFlashAssessment.MatchesSiliconEraseGeometry([previewBlock, previewBlock]),
            "Duplicate original ECU backup sector is rejected");
        Check(!EcuPostFlashAssessment.MatchesSiliconEraseGeometry([]),
            "No physical backups is not a valid restore geometry");

        var folder = Path.Combine(Path.GetTempPath(),
            "camarodiag-restoration-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(folder);
        try
        {
            var journalPath = Path.Combine(folder, "journal.jsonl");
            var original = Enumerable.Repeat((byte)0x55, 64).ToArray();
            var sha = Convert.ToHexString(SHA256.HashData(original));
            var journal = new NativeE39FileWriteJournal(journalPath);
            try
            {
                journal.AppendAsync(new(NativeE39WriteSequence.Phase.Prepared,
                    0x20000, sha), CancellationToken.None).GetAwaiter().GetResult();
                journal.AppendAsync(new(NativeE39WriteSequence.Phase.OriginalCrcVerified,
                    0x20000, "DEADBEEF"), CancellationToken.None).GetAwaiter().GetResult();
                journal.PreserveOriginalBlockAsync(0x20000, original,
                    sha, CancellationToken.None).GetAwaiter().GetResult();
                journal.AppendAsync(new(NativeE39WriteSequence.Phase.OriginalBytesVerified,
                    0x20000, sha), CancellationToken.None).GetAwaiter().GetResult();
            }
            finally { journal.DisposeAsync().AsTask().GetAwaiter().GetResult(); }
            var metadataPath = journalPath + ".meta.json";
            var metadata = System.Text.Json.JsonSerializer.Serialize(new
            {
                Schema = 1, Utc = DateTimeOffset.UtcNow,
                Family = "E39", ExpectedVin = vin, ExpectedOsId = 12664221u,
                ProfileSha256 = sha, DriverSha256 = sha,
                RegistryView = "Registry64", OriginalSha256 = sha,
                TargetSha256 = sha, KernelSha256 = sha
            });
            File.WriteAllText(metadataPath, metadata);
            var inspectedTarget = NativeE39SavedBlockAssessment.InspectTargetMetadata(journalPath);
            Check(inspectedTarget.Verified && inspectedTarget.Family == "E39" &&
                inspectedTarget.Vin == vin && inspectedTarget.OsId == 12664221u,
                "Saved VIN and firmware identifiers are parsed from bounded offline metadata");
            File.WriteAllText(metadataPath, metadata.Replace(vin, "INVALID_TARGET_VIN"));
            Check(!NativeE39SavedBlockAssessment.InspectTargetMetadata(journalPath).Verified,
                "Changed or malformed ECU target identity cannot validate recovery metadata");
            File.WriteAllText(metadataPath, metadata.Replace("\"Schema\":1",
                "\"Schema\":1,\"Schema\":1"));
            Check(!NativeE39SavedBlockAssessment.InspectTargetMetadata(journalPath).Verified,
                "Duplicate target metadata fields cannot masquerade as original qualification");
            File.WriteAllText(metadataPath, metadata.Replace("\"ExpectedOsId\":12664221",
                "\"ExpectedOsId\":0"));
            Check(!NativeE39SavedBlockAssessment.InspectTargetMetadata(journalPath).Verified,
                "Zero ECU OSID can never validate the recorded recovery target");
            File.WriteAllText(metadataPath, metadata.Replace("\"DriverSha256\":\"" + sha + "\"",
                "\"DriverSha256\":\"BAD\""));
            Check(!NativeE39SavedBlockAssessment.InspectTargetMetadata(journalPath).Verified,
                "Invalid original driver digest prevents trusting recovery target metadata");
            File.Delete(metadataPath);
            Check(!NativeE39SavedBlockAssessment.InspectTargetMetadata(journalPath).Verified,
                "Missing target metadata is reported without inventing VIN or firmware identity");
            File.WriteAllText(metadataPath, metadata);
            var saved = NativeE39SavedBlockAssessment.Inspect(journalPath);
            Check(saved.Verified && saved.Blocks.Count == 1 &&
                !saved.PhysicalRestoreQualified, "Original PHYSICAL sector saved and verified.");
            Check(NativeE39SavedBlockAssessment.ReadPhysicalOriginal(journalPath,
                saved.Blocks[0]).SequenceEqual(original), "Exact saved physical bytes re-read.");
            var preview = EcuPostFlashAssessment.PrepareRestorationPreview(journalPath,
                new Dictionary<uint,int> { [0x20000] = 64 });
            Check(preview.Count == 1 && preview[0].Address == 0x20000 &&
                preview[0].Original.SequenceEqual(original),
                "Interrupted programming retains original sectors for OFFLINE recovery review.");
            Reject(() => EcuPostFlashAssessment.PrepareRestorationPreview(journalPath,
                new Dictionary<uint,int> { [0x20000] = 32 }),
                "Wrong erase-sector size forbids restoration preview.");
            var blockFile = Path.Combine(journalPath + ".blocks", "00020000-64.ecu-block");
            var knownGoodBlock = File.ReadAllBytes(blockFile);
            var lineEnd = Array.IndexOf(knownGoodBlock, (byte)'\n');
            var originalBlockHeader = System.Text.Encoding.UTF8.GetString(knownGoodBlock, 0, lineEnd);
            void ChangeBlockHeader(string changed)
            {
                var prefix = System.Text.Encoding.UTF8.GetBytes(changed + "\n");
                File.WriteAllBytes(blockFile, prefix.Concat(original).ToArray());
            }
            ChangeBlockHeader(originalBlockHeader.Replace("\"Schema\":1",
                "\"Schema\":1,\"Schema\":1"));
            Check(!NativeE39SavedBlockAssessment.Inspect(journalPath).Verified,
                "Duplicate physical-block JSON keys cannot be treated as original proof");
            ChangeBlockHeader(originalBlockHeader.Replace("\"Schema\":1",
                "\"Schema\":1,\"Unexpected\":true"));
            Check(!NativeE39SavedBlockAssessment.Inspect(journalPath).Verified,
                "Additional physical-block metadata fields are rejected");
            File.WriteAllBytes(blockFile, knownGoodBlock);
            var journalText = File.ReadAllText(journalPath);
            File.WriteAllText(journalPath, journalText.Replace(
                "\"Phase\":\"OriginalBytesVerified\"",
                "\"Phase\":\"OriginalCrcVerified\""));
            Check(!NativeE39SavedBlockAssessment.Inspect(journalPath).Verified,
                "Original-block bytes alone cannot substitute for a durable physical-read receipt");
            File.WriteAllText(journalPath, journalText);
            File.WriteAllText(journalPath, journalText.Replace(
                "\"Phase\":\"OriginalCrcVerified\"",
                "\"Phase\":\"TestWriteAccepted\""));
            Check(!NativeE39SavedBlockAssessment.Inspect(journalPath).Verified,
                "Original-byte receipt without a prior physical CRC receipt is refused");
            File.WriteAllText(journalPath, journalText);
            File.WriteAllText(journalPath, journalText.Replace(
                "\"Detail\":\"DEADBEEF\"", "\"Detail\":\"BADCRC\""));
            Check(!NativeE39SavedBlockAssessment.Inspect(journalPath).Verified,
                "Malformed original CRC evidence cannot authorize physical backup integrity");
            File.WriteAllText(journalPath, journalText);
            Check(NativeE39SavedBlockAssessment.Inspect(journalPath).Verified,
                "Untampered CRC, original-sector receipt and disk backup remain valid");
            var tampered = File.ReadAllBytes(blockFile);
            tampered[^1] ^= 0x02;
            File.WriteAllBytes(blockFile, tampered);
            Check(!NativeE39SavedBlockAssessment.Inspect(journalPath).Verified,
                "Altered physical backup must be rejected.");
            Reject(() => NativeE39SavedBlockAssessment.ReadPhysicalOriginal(
                journalPath, saved.Blocks[0]), "Restore preview cannot use altered backup.");
        }
        finally { Directory.Delete(folder, recursive: true); }
        return checks;
    }
}
