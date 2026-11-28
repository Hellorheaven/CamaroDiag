using System.Security.Cryptography;
using System.Text.Json;

namespace CamaroDiag.J2534Bridge;

/// <summary>
/// Read-only, explicitly ordered comparison of complete E39/E39A recovery
/// snapshots. No inferred chronology, merged journals, ECU I/O or restoration.
/// </summary>
internal static class BridgeRecoveryHistory
{
    internal const int MaximumSnapshots = 8;
    private const int MaximumJournalBytes = 8 * 1024 * 1024;
    private const long MaximumCombinedJournalBytes = 64L * 1024 * 1024;
    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        WriteIndented = true
    };

    internal sealed record Snapshot(int Ordinal, string JournalName, int JournalBytes,
        int Records, BridgeRecoveryReport.JournalEvidence Journal,
        NativeE39SavedBlockAssessment.TargetMetadata Target,
        BridgeRecoveryReport.BackupEvidence Backup);

    internal sealed record Transition(int FromOrdinal, int ToOrdinal,
        int AddedBytes, int AddedRecords, bool OriginalEnvelopesUnchanged,
        string PreviousState, string RecordedState);

    internal sealed record Report(string Schema, DateTimeOffset InspectedAtUtc,
        bool Verified, bool OrderedPrefixVerified, IReadOnlyList<Snapshot> Snapshots,
        IReadOnlyList<Transition> Transitions, string? LatestRecordedState,
        uint? LatestRecordedUncertainAddress, string? ProblemCode, int? FailedOrdinal,
        bool Authenticated = false, bool PhysicalVehicleVerified = false,
        bool CurrentEcuStateVerified = false, bool PhysicalRestoreQualified = false,
        bool RestoreAuthorized = false);

    private sealed record Acquired(byte[] Journal, BridgeRecoveryReport.Assessment Assessment);

    private static void Require(bool condition, string reason)
    {
        if (!condition) throw new InvalidDataException(reason);
    }

    private static string[] NormalizePaths(IEnumerable<string> journals)
    {
        ArgumentNullException.ThrowIfNull(journals);
        // Take at most one extra entry to avoid materializing unbounded input.
        var paths = journals.Take(MaximumSnapshots + 1).Select(Path.GetFullPath).ToArray();
        Require(paths.Length is >= 2 and <= MaximumSnapshots, "SnapshotCount");
        Require(paths.Distinct(StringComparer.OrdinalIgnoreCase).Count() == paths.Length,
            "DuplicatePath");
        return paths;
    }

    private static byte[] ReadJournal(string path)
    {
        var attributes = File.GetAttributes(path);
        Require((attributes & (FileAttributes.Directory | FileAttributes.ReparsePoint)) == 0,
            "NotRegularJournal");
        // Refuse concurrent write handles during this read. Independent reads
        // and report hashes still guard the boundaries of the whole inspection.
        using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read);
        Require(stream.Length is > 0 and <= MaximumJournalBytes, "JournalSize");
        var bytes = new byte[checked((int)stream.Length)];
        stream.ReadExactly(bytes);
        Require(stream.ReadByte() == -1 && bytes[^1] == (byte)'\n', "IncompleteJournal");
        return bytes;
    }

    private static int CountRecords(byte[] bytes) =>
        bytes.Count(value => value == (byte)'\n');

    private static bool HashMatches(byte[] journal, string? expected) =>
        expected is not null && string.Equals(Convert.ToHexString(SHA256.HashData(journal)),
            expected, StringComparison.OrdinalIgnoreCase);

    private static Acquired Acquire(string path)
    {
        var journal = ReadJournal(path);
        var assessment = BridgeRecoveryReport.Inspect(path);
        Require(assessment.Backup.Verified && assessment.Target.Verified &&
            assessment.Backup.SnapshotSha256 is not null &&
            assessment.Target.MetadataSnapshotSha256 is not null &&
            assessment.Backup.ExpectedSectors > 0 &&
            assessment.Backup.Sectors.Count == assessment.Backup.ExpectedSectors &&
            assessment.Backup.Sectors.All(sector => sector.EnvelopeSha256 is not null),
            "UnverifiedSnapshot");
        Require(assessment.Journal.SequenceValid ||
            assessment.Journal.State == "RecoveryRequired", "InvalidSequence");
        Require(HashMatches(journal, assessment.Journal.Sha256), "JournalChanged");
        Require(ReadJournal(path).AsSpan().SequenceEqual(journal), "JournalChanged");
        return new Acquired(journal, assessment);
    }

    private static Snapshot Describe(Acquired acquired, int ordinal)
    {
        var assessment = acquired.Assessment;
        return new Snapshot(ordinal, assessment.JournalName, acquired.Journal.Length,
            CountRecords(acquired.Journal), assessment.Journal, assessment.Target,
            assessment.Backup);
    }

    private static bool OriginalEvidenceMatches(BridgeRecoveryReport.Assessment baseline,
        BridgeRecoveryReport.Assessment later)
    {
        return baseline.Target == later.Target &&
            baseline.Backup.ExpectedSectors == later.Backup.ExpectedSectors &&
            baseline.Backup.VerifiedBytes == later.Backup.VerifiedBytes &&
            baseline.Backup.Sectors.OrderBy(sector => sector.Address)
                .SequenceEqual(later.Backup.Sectors.OrderBy(sector => sector.Address));
    }

    internal static Report Inspect(IEnumerable<string> journals, Action? requireCurrent = null)
    {
        var paths = NormalizePaths(journals);
        var snapshots = new List<Snapshot>();
        var transitions = new List<Transition>();
        var acquired = new List<Acquired>();
        int ordinal = 0;
        long total = 0;
        try
        {
            foreach (var path in paths)
            {
                ordinal++;
                requireCurrent?.Invoke();
                var next = Acquire(path);
                total += next.Journal.Length;
                Require(total <= MaximumCombinedJournalBytes, "CombinedJournalSize");
                snapshots.Add(Describe(next, ordinal));

                if (acquired.Count != 0)
                {
                    var previous = acquired[^1];
                    Require(OriginalEvidenceMatches(acquired[0].Assessment, next.Assessment),
                        "OriginalEvidenceChanged");
                    Require(next.Journal.Length > previous.Journal.Length &&
                        next.Journal.AsSpan(0, previous.Journal.Length).SequenceEqual(previous.Journal),
                        "NotStrictExtension");
                    Require(previous.Assessment.Journal.SequenceValid &&
                        previous.Assessment.Journal.State is not ("Completed" or "RecoveryRequired"),
                        "TerminalExtension");
                    var additionalRecords = CountRecords(next.Journal) - CountRecords(previous.Journal);
                    Require(additionalRecords > 0, "NoCompleteNewRecord");
                    transitions.Add(new Transition(ordinal - 1, ordinal,
                        next.Journal.Length - previous.Journal.Length, additionalRecords, true,
                        previous.Assessment.Journal.State, next.Assessment.Journal.State));
                }
                acquired.Add(next);
            }

            // Re-inspect all source bundles before returning success. Holding
            // individual files open is not an atomic multi-directory snapshot.
            for (var index = 0; index < paths.Length; index++)
            {
                ordinal = index + 1;
                requireCurrent?.Invoke();
                var current = Acquire(paths[index]);
                Require(current.Journal.AsSpan().SequenceEqual(acquired[index].Journal) &&
                    BridgeRecoveryReport.SameEvidence(acquired[index].Assessment, current.Assessment),
                    "EvidenceChanged");
            }

            requireCurrent?.Invoke();
            var last = acquired[^1].Assessment.Journal;
            return new Report("camarodiag-windows-recovery-history-v1", DateTimeOffset.UtcNow,
                true, true, snapshots.AsReadOnly(), transitions.AsReadOnly(), last.State,
                last.PossiblyIncompleteAddress, null, null);
        }
        catch (OperationCanceledException)
        {
            return Failed("Cancelled");
        }
        catch (InvalidDataException error)
        {
            return Failed(error.Message);
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException or
            ArgumentException or InvalidOperationException)
        {
            return Failed("EvidenceUnavailable");
        }

        Report Failed(string code) =>
            new("camarodiag-windows-recovery-history-v1", DateTimeOffset.UtcNow,
                false, false, snapshots.AsReadOnly(), transitions.AsReadOnly(), null, null,
                code, ordinal == 0 ? null : ordinal);
    }

    internal static string Encode(Report report) => JsonSerializer.Serialize(report, JsonOptions);

    internal static bool SameEvidence(Report prior, Report current) =>
        prior.Verified && current.Verified &&
        Encode(prior with { InspectedAtUtc = current.InspectedAtUtc }) == Encode(current);

    internal static void RequireSafeOutput(string output, IEnumerable<string> paths)
    {
        var destination = Path.GetFullPath(output);
        foreach (var path in paths.Select(Path.GetFullPath))
        {
            var blocks = path + ".blocks";
            var prefix = blocks + Path.DirectorySeparatorChar;
            Require(!string.Equals(destination, path, StringComparison.OrdinalIgnoreCase) &&
                !string.Equals(destination, path + ".meta.json", StringComparison.OrdinalIgnoreCase) &&
                !string.Equals(destination, blocks, StringComparison.OrdinalIgnoreCase) &&
                !destination.StartsWith(prefix, StringComparison.OrdinalIgnoreCase),
                "ProtectedDestination");
        }
        // Existing junctions/symlinks could redirect an apparently separate
        // output into a protected inventory. Reject those parents explicitly.
        DirectoryInfo? directory = new DirectoryInfo(Path.GetDirectoryName(destination)
            ?? throw new InvalidDataException("ProtectedDestination"));
        while (directory is not null)
        {
            Require(directory.Exists &&
                (directory.Attributes & FileAttributes.ReparsePoint) == 0, "ProtectedDestination");
            directory = directory.Parent;
        }
    }

    internal static Report ExportCurrent(string output, IEnumerable<string> journals, Report displayed)
    {
        var paths = NormalizePaths(journals);
        RequireSafeOutput(output, paths);
        var current = Inspect(paths);
        Require(SameEvidence(displayed, current), "EvidenceChanged");
        // Recheck immediately before create-new; never overwrite originals.
        RequireSafeOutput(output, paths);
        using var stream = new FileStream(output, FileMode.CreateNew, FileAccess.Write, FileShare.None);
        using var writer = new StreamWriter(stream);
        writer.WriteLine(Encode(current));
        writer.Flush();
        stream.Flush(true);
        return current;
    }
}
