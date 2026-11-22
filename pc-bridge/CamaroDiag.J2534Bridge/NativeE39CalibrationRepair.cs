using System.Buffers.Binary;
using System.Security.Cryptography;

namespace CamaroDiag.J2534Bridge;

/// <summary>
/// Offline, independently implemented E39/E39A calibration checksum repair.
///
/// An ECU image should be edited only after preserving an original read
/// backup. This code never accesses J2534, invokes a third-party program,
/// imports a kernel, or asserts that an ECU can safely be flashed.
/// </summary>
internal static class NativeE39CalibrationRepair
{
    internal sealed record SegmentChange(string Name, int BytesChanged);

    internal sealed record Result(
        byte[] RepairedImage,
        uint OperatingSystemId,
        IReadOnlyList<SegmentChange> ChangedSegments,
        int EditedBytesChanged,
        int ChecksumFieldsUpdated,
        string OriginalSha256,
        string EditedSha256,
        string RepairedSha256);

    internal static Result Repair(byte[] original, byte[] edited)
    {
        ArgumentNullException.ThrowIfNull(original);
        ArgumentNullException.ThrowIfNull(edited);

        var before = NativeE39ImageInspector.Inspect(original);
        if (before.Status != NativeE39ImageInspector.Verdict.Valid)
            throw new InvalidDataException(
                "Original E39/E39A image must have a valid structure, SUM and CVN.");

        var candidate = NativeE39ImageInspector.Inspect(edited);
        if (candidate.Status == NativeE39ImageInspector.Verdict.InvalidLayout)
            throw new InvalidDataException("Edited E39/E39A image has an invalid checksum table.");

        if (candidate.OsIdentifier != before.OsIdentifier ||
            candidate.Segments.Count != before.Segments.Count ||
            !before.Segments.SequenceEqual(candidate.Segments))
            throw new UnauthorizedAccessException(
                "Edited E39 image changes firmware identity or memory segment boundaries.");

        if (original.AsSpan(0, NativeE39ImageInspector.ProtectedBootBytes)
            .SequenceEqual(edited.AsSpan(0, NativeE39ImageInspector.ProtectedBootBytes)) is false)
            throw new UnauthorizedAccessException("Editing protected E39 boot flash is forbidden.");

        var calibrations = before.Segments.Where(x => !x.IsOperatingSystem).ToArray();
        var counts = new int[calibrations.Length];
        var differences = 0;
        for (var index = 0; index < original.Length; index++)
        {
            if (original[index] == edited[index]) continue;
            differences++;
            var allowedIndex = Array.FindIndex(calibrations,
                s => index >= s.Start && index <= s.EndInclusive);
            if (allowedIndex < 0)
                throw new UnauthorizedAccessException(
                    $"Edited E39 image changes operating system, boot or unmapped byte 0x{index:X6}.");
            counts[allowedIndex]++;
        }

        if (differences == 0)
            throw new InvalidOperationException("Images are identical: no calibration repair is needed.");

        var repaired = (byte[])edited.Clone();
        var fixedFields = 0;
        // Each SUM and CVN check references the stored checksum location.
        // Only checks belonging to changed calibration segments are repaired.
        // Reinspect after SUM repair because an OEM CVN block may include SUM.
        foreach (var kind in new[] { "SUM", "CVN" })
        {
            var latest = NativeE39ImageInspector.Inspect(repaired);
            if (latest.Status == NativeE39ImageInspector.Verdict.InvalidLayout)
                throw new InvalidDataException("E39 checksum layout changed during repair.");
            for (var i = 0; i < calibrations.Length; i++)
            {
                if (counts[i] == 0) continue;
                var segment = calibrations[i];
                var checksum = latest.Checks.Single(c =>
                    c.Name == segment.Name && c.Kind == kind);
                if (checksum.StoredOffset < segment.Start ||
                    checksum.StoredOffset + 1 > segment.EndInclusive)
                    throw new InvalidDataException(
                        "Checksum storage address is outside its calibration segment.");
                if (checksum.Matches) continue;
                BinaryPrimitives.WriteUInt16BigEndian(
                    repaired.AsSpan(checksum.StoredOffset, 2), checksum.Computed);
                fixedFields++;
            }
        }

        var verified = NativeE39ImageInspector.Inspect(repaired);
        if (verified.Status != NativeE39ImageInspector.Verdict.Valid ||
            verified.OsIdentifier != before.OsIdentifier ||
            !before.Segments.SequenceEqual(verified.Segments))
            throw new InvalidDataException(
                "Repaired E39/E39A image failed independent checksum verification.");

        // Only calibration checksum fields may differ from the caller's edits.
        var permittedChecksumIndices = new HashSet<int>();
        foreach (var change in calibrations.Where((_, i) => counts[i] > 0))
        {
            foreach (var check in verified.Checks.Where(c => c.Name == change.Name))
            {
                permittedChecksumIndices.Add(check.StoredOffset);
                permittedChecksumIndices.Add(check.StoredOffset + 1);
            }
        }
        for (var i = 0; i < repaired.Length; i++)
        {
            if (edited[i] != repaired[i] && !permittedChecksumIndices.Contains(i))
                throw new InvalidDataException("Checksum repair unexpectedly altered unrelated image bytes.");
        }

        return new Result(repaired, before.OsIdentifier!.Value,
            calibrations.Where((_, i) => counts[i] > 0)
                .Select(s => new SegmentChange(s.Name,
                    counts[Array.IndexOf(calibrations, s)])).ToArray(),
            differences, fixedFields,
            HexSha(original), HexSha(edited), HexSha(repaired));
    }

    private static string HexSha(byte[] image) =>
        Convert.ToHexString(SHA256.HashData(image));
}
