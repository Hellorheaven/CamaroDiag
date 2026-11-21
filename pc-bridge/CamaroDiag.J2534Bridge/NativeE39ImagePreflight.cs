using System.Security.Cryptography;

namespace CamaroDiag.J2534Bridge;

/// <summary>
/// Native E39/E39A image comparison: only an identified, checksum-correct
/// 3MiB image and physically approved erase geometry can generate a write
/// plan. This is offline verification and DOES NOT unlock physical writing.
/// </summary>
internal static class NativeE39ImagePreflight
{
    internal sealed record Report(
        uint OperatingSystemId,
        string OriginalSha256,
        string ModifiedSha256,
        int ChangedBytes,
        NativeEcuSectorPlanner.Plan SectorPlan,
        bool CalibrationOnly);

    internal static Report Prepare(
        byte[] original,
        byte[] modified,
        NativeEcuSectorPlanner.Layout exactFirmwareLayout,
        uint expectedLiveOsId,
        bool calibrationOnly)
    {
        ArgumentNullException.ThrowIfNull(original);
        ArgumentNullException.ThrowIfNull(modified);
        ArgumentNullException.ThrowIfNull(exactFirmwareLayout);
        if (!exactFirmwareLayout.VerifiedForExactFirmware ||
            exactFirmwareLayout.RegionStartAddress != 0 ||
            exactFirmwareLayout.RegionSize != NativeE39ImageInspector.ImageBytes)
            throw new UnauthorizedAccessException(
                "E39 preflight requires a validated full-image, exact-firmware memory layout.");

        var before = NativeE39ImageInspector.Inspect(original);
        var after = NativeE39ImageInspector.Inspect(modified);
        if (before.Status != NativeE39ImageInspector.Verdict.Valid ||
            after.Status != NativeE39ImageInspector.Verdict.Valid)
            throw new InvalidDataException(
                $"Original / target E39 image validation refused: {before.Status} / {after.Status}.");
        if (expectedLiveOsId is 0 or uint.MaxValue ||
            before.OsIdentifier != expectedLiveOsId ||
            after.OsIdentifier != expectedLiveOsId)
            throw new UnauthorizedAccessException(
                "Image OS identifiers do not match the live ECU operating-system ID.");
        if (!original.AsSpan(0, NativeE39ImageInspector.ProtectedBootBytes).SequenceEqual(
            modified.AsSpan(0, NativeE39ImageInspector.ProtectedBootBytes)))
            throw new UnauthorizedAccessException(
                "Native E39 image attempts to modify protected boot memory.");
        // Include the ENTIRE protected boot block in the verified erase map.
        // A single protected byte is insufficient protection against erasure.
        var bootProtected = exactFirmwareLayout.ProtectedRanges.Any(range =>
            range.Start == 0 &&
            range.EndExclusive >= NativeE39ImageInspector.ProtectedBootBytes);
        if (!bootProtected)
            throw new UnauthorizedAccessException("Boot flash is not protected by the erase layout.");

        var allowed = calibrationOnly
            ? after.Segments.Where(s => !s.IsOperatingSystem).ToArray()
            : after.Segments.ToArray();
        for (var address = NativeE39ImageInspector.ProtectedBootBytes;
             address < NativeE39ImageInspector.ImageBytes; address++)
        {
            if (original[address] == modified[address]) continue;
            if (!allowed.Any(s => address >= s.Start && address <= s.EndInclusive))
                throw new UnauthorizedAccessException(
                    $"Modification is outside {(calibrationOnly ? "calibration" : "known E39 image")} regions: 0x{address:X6}.");
        }

        var plan = NativeEcuSectorPlanner.Preview(
            exactFirmwareLayout, original, modified);
        if (!plan.CanAttemptPhysicalWrite || plan.ChangedBytes == 0)
            throw new InvalidOperationException("No approved ECU change to prepare.");
        return new Report(
            expectedLiveOsId,
            Convert.ToHexString(SHA256.HashData(original)),
            Convert.ToHexString(SHA256.HashData(modified)),
            plan.ChangedBytes, plan, calibrationOnly);
    }
}
