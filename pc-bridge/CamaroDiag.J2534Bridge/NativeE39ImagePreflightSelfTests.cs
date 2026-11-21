using System.Buffers.Binary;

namespace CamaroDiag.J2534Bridge;

internal static class NativeE39ImagePreflightSelfTests
{
    internal static int Run()
    {
        var count = 0;
        void Assert(bool condition, string message)
        {
            count++;
            if (!condition)
                throw new InvalidOperationException("E39 image preflight self-test failed: " + message);
        }
        static void Expect<T>(Action action) where T : Exception
        {
            try { action(); }
            catch (T) { return; }
            throw new InvalidOperationException("Expected " + typeof(T).Name);
        }

        var original = NativeE39ImageInspectorSelfTests.Fixture();
        var modified = original.ToArray();
        modified[0x20040] ^= 0x31;

        var computedSum = NativeE39ImageInspector.ComputeSum(
            modified, (0x20040, 0x200FF));
        BinaryPrimitives.WriteUInt16BigEndian(modified.AsSpan(0x20000, 2), computedSum);
        var computedCvn = NativeE39ImageInspector.ComputeCvn(
            modified, (0x20040, 0x2004F), (0x20050, 0x2005F));
        BinaryPrimitives.WriteUInt16BigEndian(modified.AsSpan(0x20020, 2), computedCvn);
        Assert(NativeE39ImageInspector.Inspect(modified).Status ==
               NativeE39ImageInspector.Verdict.Valid,
            "Independently patched calibration has new matching SUM and CVN");

        var layout = new NativeEcuSectorPlanner.Layout(
            0, NativeE39ImageInspector.ImageBytes, 0x1000, 4,
            [new(0, NativeE39ImageInspector.ProtectedBootBytes)], true);
        var report = NativeE39ImagePreflight.Prepare(
            original, modified, layout, expectedLiveOsId: 12664221u, calibrationOnly: true);
        Assert(report.CalibrationOnly && report.ChangedBytes >= 1 &&
               report.SectorPlan.Sectors.Count == 1,
            "Only one calibration erase sector needed");
        Assert(report.SectorPlan.Sectors[0].Address == 0x20000 &&
               report.SectorPlan.Sectors[0].Length == 0x1000 &&
               report.OriginalSha256 != report.ModifiedSha256,
            "Exact sector flash image and distinct SHA-256 retained");

        Expect<UnauthorizedAccessException>(() =>
            NativeE39ImagePreflight.Prepare(original, modified, layout,
                expectedLiveOsId: 12664222u, calibrationOnly: true));
        count++;
        Expect<UnauthorizedAccessException>(() =>
            NativeE39ImagePreflight.Prepare(original, modified,
                layout with { VerifiedForExactFirmware = false },
                expectedLiveOsId: 12664221u, calibrationOnly: true));
        count++;
        Expect<UnauthorizedAccessException>(() =>
            NativeE39ImagePreflight.Prepare(original, modified,
                layout with { ProtectedRanges = [] },
                expectedLiveOsId: 12664221u, calibrationOnly: true));
        count++;

        var forbiddenBoot = modified.ToArray();
        forbiddenBoot[0] = 1;
        Expect<UnauthorizedAccessException>(() =>
            NativeE39ImagePreflight.Prepare(original, forbiddenBoot,
                layout, expectedLiveOsId: 12664221u, calibrationOnly: true));
        count++;

        var unlisted = modified.ToArray();
        unlisted[0x20200] ^= 0x30;
        Expect<UnauthorizedAccessException>(() =>
            NativeE39ImagePreflight.Prepare(original, unlisted,
                layout, expectedLiveOsId: 12664221u, calibrationOnly: true));
        count++;

        var brokenSum = modified.ToArray();
        brokenSum[0x20040] ^= 1;
        Expect<InvalidDataException>(() =>
            NativeE39ImagePreflight.Prepare(original, brokenSum,
                layout, expectedLiveOsId: 12664221u, calibrationOnly: true));
        count++;
        var changedOsId = modified.ToArray();
        BinaryPrimitives.WriteUInt32BigEndian(changedOsId.AsSpan(
            NativeE39ImageInspector.MasterChecksumTable + 5, 4), 12664222u);
        Expect<UnauthorizedAccessException>(() =>
            NativeE39ImagePreflight.Prepare(original, changedOsId,
                layout, expectedLiveOsId: 12664221u, calibrationOnly: true));
        count++;

        Console.WriteLine($"PASS: {count} native full-image E39 preflight self-tests.");
        return count;
    }
}
