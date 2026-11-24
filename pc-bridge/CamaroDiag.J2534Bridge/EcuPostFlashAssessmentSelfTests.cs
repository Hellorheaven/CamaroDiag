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
                    0x20000, "DUMMY"), CancellationToken.None).GetAwaiter().GetResult();
                journal.PreserveOriginalBlockAsync(0x20000, original,
                    sha, CancellationToken.None).GetAwaiter().GetResult();
                journal.AppendAsync(new(NativeE39WriteSequence.Phase.OriginalBytesVerified,
                    0x20000, sha), CancellationToken.None).GetAwaiter().GetResult();
            }
            finally { journal.DisposeAsync().AsTask().GetAwaiter().GetResult(); }
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
