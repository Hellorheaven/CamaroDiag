namespace CamaroDiag.J2534Bridge;

/// <summary>
/// Offline results from live/read-only and operator-led vehicle checks.
/// A successful byte readback is never represented as a successful road test.
/// No ECU reset, erase, write or automatic rollback is performed here.
/// </summary>
internal static class EcuPostFlashAssessment
{
    internal enum Verdict { Failed, Inconclusive, Passed }
    internal sealed record Evidence(
        string ExpectedVin, string? LiveVin,
        string ExpectedOsId, string? LiveOsId,
        IReadOnlyDictionary<uint, string> ExpectedSectorHashes,
        IReadOnlyDictionary<uint, string> LiveSectorHashes,
        bool? OemChecksumAccepted,
        bool? ApplicationRespondsAfterPowerCycle,
        bool? NoNewCriticalDtcRelativeToBaseline,
        bool? EngineStartsAndIdles,
        bool? StationaryTestsPassed,
        bool? OperatorRoadTestPassed,
        bool PowerStable);

    internal sealed record Report(Verdict Status, IReadOnlyList<string> Failures,
        IReadOnlyList<string> MissingEvidence, bool AutomaticRollbackPermitted);

    internal static Report Evaluate(Evidence evidence)
    {
        ArgumentNullException.ThrowIfNull(evidence);
        var errors = new List<string>();
        var missing = new List<string>();
        if (!evidence.PowerStable) errors.Add("Supply voltage not stable.");
        if (string.IsNullOrWhiteSpace(evidence.ExpectedVin) ||
            string.IsNullOrWhiteSpace(evidence.ExpectedOsId) ||
            evidence.ExpectedSectorHashes.Count == 0)
            errors.Add("ECU identity or modified sector plan missing.");
        if (string.IsNullOrWhiteSpace(evidence.LiveVin))
            missing.Add("No live VIN response.");
        else if (evidence.ExpectedVin != evidence.LiveVin)
            errors.Add("ECU VIN differs from the target.");
        if (string.IsNullOrWhiteSpace(evidence.LiveOsId))
            missing.Add("No live OSID/firmware response.");
        else if (evidence.ExpectedOsId != evidence.LiveOsId)
            errors.Add("ECU OSID differs from the target.");
        foreach (var sector in evidence.ExpectedSectorHashes)
        {
            if (!evidence.LiveSectorHashes.TryGetValue(sector.Key, out var actual))
                missing.Add($"Sector 0x{sector.Key:X8} not read back.");
            else if (!string.Equals(actual, sector.Value, StringComparison.OrdinalIgnoreCase))
                errors.Add($"Sector 0x{sector.Key:X8} readback differs.");
        }
        Observe(evidence.OemChecksumAccepted, "OEM checksum/routine");
        Observe(evidence.ApplicationRespondsAfterPowerCycle, "Post-cycle ECU application");
        Observe(evidence.NoNewCriticalDtcRelativeToBaseline, "New critical DTC");
        Observe(evidence.EngineStartsAndIdles, "Engine start / idle");
        Observe(evidence.StationaryTestsPassed, "Stationary vehicle checks");
        Observe(evidence.OperatorRoadTestPassed, "Operator road test");
        return new Report(errors.Count != 0 ? Verdict.Failed :
            missing.Count != 0 ? Verdict.Inconclusive : Verdict.Passed,
            errors, missing, AutomaticRollbackPermitted: false);

        void Observe(bool? result, string label)
        {
            if (!result.HasValue) missing.Add(label + " not verified.");
            else if (!result.Value) errors.Add(label + " failed.");
        }
    }

    /// <summary>
    /// Compare archived, verified physical blocks with known MPC5566 silicon
    /// erase geometry. This is an OFFLINE consistency check, NEVER a claim
    /// that the installed ECU uses this board/recovery configuration.
    /// Partial (interrupted) transactions may have only some sectors.
    /// </summary>
    internal static bool MatchesSiliconEraseGeometry(
        IReadOnlyList<NativeE39SavedBlockAssessment.Block> blocks)
    {
        if (blocks.Count == 0 || blocks.Select(b => b.Address).Distinct().Count() != blocks.Count)
            return false;
        var expected = NativeMpc5566FlashMap.Blocks()
            .ToDictionary(b => b.Start, b => checked((int)(b.EndExclusive - b.Start)));
        return blocks.All(block =>
            expected.TryGetValue(block.Address, out var length) && block.Length == length);
    }

    /// <summary>
    /// Build a byte-accurate OFFLINE preview. The actual qualified ECU writer
    /// must first independently prove live ECU identity, firmware, original
    /// target sector geometry, programming power and recovery bootloader.
    /// Restoring the bytes requires NEW OK then Oui/Non authorization.
    /// </summary>
    internal static IReadOnlyList<(uint Address, byte[] Original)> PrepareRestorationPreview(
        string journalPath, IReadOnlyDictionary<uint, int> actualSectorGeometry)
    {
        var report = NativeE39SavedBlockAssessment.Inspect(journalPath);
        if (!report.Verified || report.Blocks.Count != actualSectorGeometry.Count)
            throw new InvalidDataException(
                "Restoration preview requires all durable original blocks even after an interrupted write; physical ECU assessment remains mandatory.");
        var sectors = new List<(uint, byte[])>();
        foreach (var block in report.Blocks)
        {
            if (!actualSectorGeometry.TryGetValue(block.Address, out var length) ||
                length != block.Length)
                throw new InvalidDataException("Stored original does not match ECU erase geometry.");
            sectors.Add((block.Address,
                NativeE39SavedBlockAssessment.ReadPhysicalOriginal(journalPath, block)));
        }
        return sectors;
    }
}
