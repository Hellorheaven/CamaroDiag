using System.Text.Json;
using System.Text.Json.Serialization;

namespace CamaroDiag.J2534Bridge;

/// <summary>Portable, read-only assessment of a particular recovery snapshot.
/// No connection, recovery image generation, programming or restore authority.
/// </summary>
internal static class BridgeRecoveryReport
{
    internal sealed record JournalEvidence(string State, bool SequenceValid, int PreparedSectors,
        int WrittenSectors, int FinalReadbackSectors, uint? PossiblyIncompleteAddress,
        string? LastPhase, string? Sha256);
    internal sealed record SectorEvidence(uint Address, int Length, string OriginalSha256, string? EnvelopeSha256);
    internal sealed record BackupEvidence(bool Verified, int ExpectedSectors, long VerifiedBytes,
        string? SnapshotSha256, string Problem, IReadOnlyList<SectorEvidence> Sectors,
        bool? SiliconGeometryMatches);
    internal sealed record Assessment(string Schema, string Version, DateTimeOffset InspectedAtUtc,
        string JournalName, JournalEvidence Journal, NativeE39SavedBlockAssessment.TargetMetadata Target,
        BackupEvidence Backup, string? OriginalBinName, NativeE39RecoveryImageAssessment.Report? OriginalBin,
        bool PhysicalVehicleVerified = false, bool RestoreAuthorized = false);

    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        WriteIndented = true,
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        Converters = { new JsonStringEnumConverter() }
    };

    // A successful BIN comparison must refer to the exact journal and target
    // snapshot shown in the same assessment, not a later replacement.
    internal static bool MatchesBundle(NativeE39SavedBlockAssessment.Bundle bundle,
        NativeE39RecoveryImageAssessment.Report comparison) =>
        comparison.Verified && bundle.Blocks.Verified && bundle.Target.Verified &&
        bundle.Blocks.SnapshotSha256 is not null && bundle.Target.MetadataSnapshotSha256 is not null &&
        comparison.SnapshotSha256 == bundle.Blocks.SnapshotSha256 &&
        comparison.MetadataSnapshotSha256 == bundle.Target.MetadataSnapshotSha256 &&
        string.Equals(comparison.OriginalImageSha256, bundle.Target.OriginalImageSha256,
            StringComparison.OrdinalIgnoreCase) &&
        comparison.VerifiedSectors == bundle.Blocks.Blocks.Count &&
        comparison.VerifiedBytes == bundle.Blocks.Blocks.Sum(x => (long)x.Length);

    internal static Assessment Inspect(string journalPath, string? binPath = null)
    {
        var bundle = NativeE39SavedBlockAssessment.InspectBundle(journalPath);
        NativeE39RecoveryImageAssessment.Report? comparison = null;
        if (binPath is not null)
        {
            comparison = NativeE39RecoveryImageAssessment.Inspect(journalPath, binPath);
            // Reacquire the journal/metadata after the independent BIN read.
            // Even an unsuccessful comparison cannot carry a stale positive
            // baseline into the new view.
            var final = NativeE39SavedBlockAssessment.InspectBundle(journalPath);
            if (!SameBundle(bundle, final))
                throw new InvalidDataException("Recovery evidence changed during comparison.");
            bundle = final;
            if (comparison.Verified && !MatchesBundle(bundle, comparison))
                throw new InvalidDataException("Original BIN comparison refers to another recovery snapshot.");
        }
        var blocks = bundle.Blocks;
        var audit = blocks.JournalInspection ??
            new NativeE39FileWriteJournal.Inspection(blocks.JournalState, false);
        var sectors = blocks.Blocks.Select(block =>
        {
            var key = $"{block.Address:X8}-{block.Length}.ecu-block";
            return new SectorEvidence(block.Address, block.Length, block.OriginalSha256,
                blocks.EnvelopeSha256.GetValueOrDefault(key));
        }).ToArray();
        return new("camarodiag-offline-e39-recovery-assessment-v1",
            typeof(BridgeRecoveryReport).Assembly.GetName().Version?.ToString() ?? "",
            DateTimeOffset.UtcNow, Path.GetFileName(journalPath),
            new(audit.State.ToString(), audit.SequenceValid, audit.PreparedSectors,
                audit.WrittenSectors, audit.FinalReadbackSectors, audit.PossiblyIncompleteAddress,
                audit.LastPhase, blocks.JournalSnapshotSha256),
            bundle.Target, new(blocks.Verified, blocks.ExpectedSectors,
                sectors.Sum(x => (long)x.Length), blocks.SnapshotSha256, blocks.Problem, sectors,
                blocks.Verified ? EcuPostFlashAssessment.MatchesSiliconEraseGeometry(blocks.Blocks) : null),
            binPath is null ? null : Path.GetFileName(binPath), comparison);
    }

    private static bool SameBundle(NativeE39SavedBlockAssessment.Bundle a,
        NativeE39SavedBlockAssessment.Bundle b) =>
        a.Blocks.JournalSnapshotSha256 is not null &&
        a.Blocks.JournalSnapshotSha256 == b.Blocks.JournalSnapshotSha256 &&
        a.Blocks.SnapshotSha256 == b.Blocks.SnapshotSha256 &&
        a.Blocks.Verified == b.Blocks.Verified &&
        a.Blocks.ExpectedSectors == b.Blocks.ExpectedSectors &&
        a.Blocks.JournalState == b.Blocks.JournalState &&
        a.Blocks.JournalInspection == b.Blocks.JournalInspection &&
        a.Blocks.Blocks.SequenceEqual(b.Blocks.Blocks) &&
        a.Blocks.Problem == b.Blocks.Problem && a.Target == b.Target;

    internal static string Encode(Assessment report) => JsonSerializer.Serialize(report, JsonOptions);

    internal static bool SameEvidence(Assessment previous, Assessment current)
    {
        // Comparing canonical structured reports also covers fields added
        // later; timestamps are the only intended difference.
        return previous.Journal.Sha256 is not null &&
            Encode(previous with { InspectedAtUtc = current.InspectedAtUtc }) == Encode(current);
    }

    internal static Assessment ExportCurrent(string output, string journalPath, string? binPath,
        Assessment displayed)
    {
        var protectedPaths = binPath is null ? new[] { journalPath } : new[] { journalPath, binPath };
        BridgeRecoveryHistory.RequireSafeOutput(output, protectedPaths);
        var current = Inspect(journalPath, binPath);
        if (!SameEvidence(displayed, current))
            throw new InvalidDataException("Recovery evidence changed; inspect it again before exporting.");
        BridgeRecoveryHistory.RequireSafeOutput(output, protectedPaths);
        // Refuse to overwrite any file, especially input recovery material.
        using var stream = new FileStream(output, FileMode.CreateNew, FileAccess.Write, FileShare.None);
        using var writer = new StreamWriter(stream);
        writer.WriteLine(Encode(current));
        writer.Flush();
        stream.Flush(true);
        return current;
    }
}
