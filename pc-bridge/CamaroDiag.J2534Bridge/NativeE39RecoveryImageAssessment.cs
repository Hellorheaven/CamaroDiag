using System.Security.Cryptography;

namespace CamaroDiag.J2534Bridge;

/// <summary>
/// Offline comparison of a complete ORIGINAL ECU BIN against independently
/// persisted live ECU sector captures. This does not authorize programming,
/// prove OEM compatibility, connect to hardware or perform restoration.
/// </summary>
internal static class NativeE39RecoveryImageAssessment
{
    internal sealed record Report(
        bool Verified, int VerifiedSectors, string Problem, uint? FailedSectorAddress = null);

    internal static Report Inspect(string journalPath, string originalImagePath)
    {
        var verified = 0;
        uint? currentAddress = null;
        try
        {
            ArgumentException.ThrowIfNullOrWhiteSpace(journalPath);
            ArgumentException.ThrowIfNullOrWhiteSpace(originalImagePath);
            var target = NativeE39SavedBlockAssessment.InspectTargetMetadata(journalPath);
            if (!target.Verified || target.OriginalImageSha256 is null)
                throw new InvalidDataException(
                    "The original BIN cannot be tied to verified VIN/OSID recovery metadata.");
            var physical = NativeE39SavedBlockAssessment.Inspect(journalPath);
            if (!physical.Verified)
                throw new InvalidDataException(
                    "Original ECU sectors were not durably verified: " + physical.Problem);

            // The original E39/E39A BIN must be complete; no implicit padding,
            // truncation, variable-length calibration file or synthetic offset.
            byte[] ReadOriginal()
            {
                if ((File.GetAttributes(originalImagePath) &
                    (FileAttributes.ReparsePoint | FileAttributes.Directory)) != 0)
                    throw new InvalidDataException("Expected an unlinked original BIN.");
                using var source = File.OpenRead(originalImagePath);
                if (source.Length != NativeE39ImageInspector.ImageBytes)
                    throw new InvalidDataException("Expected an exact full-size E39 original BIN.");
                var bytes = new byte[NativeE39ImageInspector.ImageBytes];
                source.ReadExactly(bytes);
                if (source.ReadByte() != -1)
                    throw new InvalidDataException("Original BIN changed while being read.");
                return bytes;
            }
            var image = ReadOriginal();
            var digest = Convert.ToHexString(SHA256.HashData(image));
            if (!digest.Equals(target.OriginalImageSha256, StringComparison.OrdinalIgnoreCase))
                throw new InvalidDataException(
                    "The selected original BIN digest differs from the saved ECU target manifest.");

            foreach (var block in physical.Blocks)
            {
                currentAddress = block.Address;
                var end = (ulong)block.Address + (uint)block.Length;
                if (block.Length <= 0 || end > (ulong)image.Length)
                    throw new InvalidDataException(
                        "Original ECU block extends outside the full BIN image.");
                // Read the on-disk capture a second time after the journal and
                // sector manifest were inspected; reject a late changed file.
                var physicalBytes = NativeE39SavedBlockAssessment.ReadPhysicalOriginal(
                    journalPath, block);
                if (!physicalBytes.AsSpan().SequenceEqual(
                    image.AsSpan(checked((int)block.Address), block.Length)))
                    throw new InvalidDataException(
                        "Original BIN bytes differ from the live ECU sector capture.");
                verified++;
            }
            if (verified == 0)
                throw new InvalidDataException("No physical ECU sectors to compare.");
            currentAddress = null;
            // Bind the result to the same original image and manifest, not
            // to a later file selected under the same path.
            if (!CryptographicOperations.FixedTimeEquals(SHA256.HashData(image),
                    SHA256.HashData(ReadOriginal())) ||
                NativeE39SavedBlockAssessment.InspectTargetMetadata(journalPath) != target)
                throw new InvalidDataException("Original BIN or ECU metadata changed during inspection.");
            var final = NativeE39SavedBlockAssessment.Inspect(journalPath);
            if (!final.Verified || final.JournalState != physical.JournalState ||
                !final.Blocks.SequenceEqual(physical.Blocks))
                throw new InvalidDataException("Physical backup evidence changed during original comparison.");
            return new Report(true, verified, "");
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException or
            InvalidDataException or ArgumentException or OverflowException or
            System.Text.Json.JsonException or KeyNotFoundException)
        {
            return new Report(false, verified, error.Message, currentAddress);
        }
    }
}
