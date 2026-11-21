using System.Buffers.Binary;

namespace CamaroDiag.J2534Bridge;

internal static class NativeE39CalibrationRepairSelfTests
{
    internal static int Run()
    {
        var passed = 0;
        void Assert(bool value, string message)
        {
            passed++;
            if (!value)
                throw new InvalidOperationException("E39 calibration checksum repair test failed: " + message);
        }
        static void Reject<T>(Action operation) where T : Exception
        {
            try { operation(); }
            catch (T) { return; }
            throw new InvalidOperationException("Expected " + typeof(T).Name);
        }

        var original = NativeE39ImageInspectorSelfTests.Fixture();
        Assert(NativeE39ImageInspector.Inspect(original).Status ==
               NativeE39ImageInspector.Verdict.Valid,
               "Baseline calibration image is independently valid");

        var edited = original.ToArray();
        edited[0x20040] = 0xA5;
        edited[0x50041] = 0x75;
        var copy = edited.ToArray();
        var result = NativeE39CalibrationRepair.Repair(original, edited);

        Assert(copy.SequenceEqual(edited) && !ReferenceEquals(edited, result.RepairedImage),
            "Source files and byte arrays are never modified");
        Assert(result.OperatingSystemId == 12664221 &&
               result.ChangedSegments.Select(s => s.Name).SequenceEqual(
                   new[] { "System", "EngineDiag" }),
            "Only the two edited calibration segments are selected");
        Assert(result.ChangedSegments.All(s => s.BytesChanged == 1) &&
               result.EditedBytesChanged == 2 && result.ChecksumFieldsUpdated == 4,
            "Exactly four independent SUM/CVN fields repaired for two edited bytes");
        Assert(NativeE39ImageInspector.Inspect(result.RepairedImage).Status ==
               NativeE39ImageInspector.Verdict.Valid,
            "Repaired full image has valid sums and CVNs");
        Assert(result.RepairedImage[0x20040] == 0xA5 &&
               result.RepairedImage[0x50041] == 0x75 &&
               result.RepairedImage.AsSpan(0, NativeE39ImageInspector.ProtectedBootBytes)
                   .SequenceEqual(original.AsSpan(0, NativeE39ImageInspector.ProtectedBootBytes)),
            "User calibration edits survive; boot bytes remain untouched");
        Assert(result.OriginalSha256.Length == 64 &&
               result.EditedSha256.Length == 64 &&
               result.RepairedSha256.Length == 64 &&
               result.OriginalSha256 != result.EditedSha256 &&
               result.EditedSha256 != result.RepairedSha256,
            "Source, input and result fingerprints are distinct");

        var runAgain = NativeE39CalibrationRepair.Repair(original, result.RepairedImage);
        Assert(runAgain.ChecksumFieldsUpdated == 0 &&
               runAgain.RepairedSha256 == result.RepairedSha256,
            "A second repair is deterministic, requiring no further changes");

        var boot = edited.ToArray();
        boot[0x1000] = 1;
        Reject<UnauthorizedAccessException>(() =>
            NativeE39CalibrationRepair.Repair(original, boot));
        passed++;
        var operatingSystem = edited.ToArray();
        operatingSystem[0x80040] ^= 1;
        Reject<UnauthorizedAccessException>(() =>
            NativeE39CalibrationRepair.Repair(original, operatingSystem));
        passed++;
        var gap = edited.ToArray();
        gap[0x70040] ^= 1;
        Reject<UnauthorizedAccessException>(() =>
            NativeE39CalibrationRepair.Repair(original, gap));
        passed++;
        var changedOsIdentifier = edited.ToArray();
        BinaryPrimitives.WriteUInt32BigEndian(changedOsIdentifier.AsSpan(
            NativeE39ImageInspector.MasterChecksumTable + 5, 4), 12664222u);
        Reject<UnauthorizedAccessException>(() =>
            NativeE39CalibrationRepair.Repair(original, changedOsIdentifier));
        passed++;

        Reject<InvalidOperationException>(() =>
            NativeE39CalibrationRepair.Repair(original, original));
        passed++;
        var badOriginal = original.ToArray();
        badOriginal[0x30040] ^= 1;
        Reject<InvalidDataException>(() =>
            NativeE39CalibrationRepair.Repair(badOriginal, edited));
        passed++;
        var badLength = new byte[1024];
        Reject<InvalidDataException>(() =>
            NativeE39CalibrationRepair.Repair(original, badLength));
        passed++;

        Console.WriteLine($"PASS: {passed} E39 native offline calibration repair tests.");
        return passed;
    }
}
