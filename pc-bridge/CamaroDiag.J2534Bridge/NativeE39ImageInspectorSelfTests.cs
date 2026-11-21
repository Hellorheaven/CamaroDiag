using System.Buffers.Binary;

namespace CamaroDiag.J2534Bridge;

internal static class NativeE39ImageInspectorSelfTests
{
    private const int Table = NativeE39ImageInspector.MasterChecksumTable;

    private static void Put32(byte[] image, int index, uint value) =>
        BinaryPrimitives.WriteUInt32BigEndian(image.AsSpan(index, 4), value);

    private static byte[] Fixture()
    {
        var image = new byte[NativeE39ImageInspector.ImageBytes];
        Put32(image, Table + 5, 12664221u);
        // Independently constructed synthetic checksum tables. No OEM ROM,
        // GM kernel or source-project fixture binary is redistributed.
        Put32(image, 0xCB026, 0x80000);
        void Ranges(int offset, params (int First, int Last)[] spans)
        {
            for (var i = 0; i < spans.Length; i++)
            {
                Put32(image, offset + 8 * i, (uint)spans[i].First);
                Put32(image, offset + 8 * i + 4, (uint)spans[i].Last);
            }
        }

        Ranges(0xCB046, (0x80040, 0x8004F), (0x80050, 0x800FF));
        Ranges(0xCB02E, (0x80040, 0x8004F), (0x80050, 0x8005F), (0x80060, 0x8006F));

        var calibrations = new[]
        {
            (0x20000, 0xCB05A, 0xCB072, 0xCB062),
            (0x30000, 0xCB07E, 0xCB096, 0xCB086),
            (0x40000, 0xCB0A2, 0xCB0BA, 0xCB0AA),
            (0x50000, 0xCB0C6, 0xCB0DE, 0xCB0CE),
            (0x60000, 0xCB0EA, 0xCB102, 0xCB0F2)
        };
        foreach (var (start, startPtr, sumPtr, cvnPtr) in calibrations)
        {
            Put32(image, startPtr, (uint)start);
            Ranges(sumPtr, (start + 0x40, start + 0xFF));
            Ranges(cvnPtr, (start + 0x40, start + 0x4F),
                (start + 0x50, start + 0x5F));
        }
        return image;
    }

    internal static int Run()
    {
        var checks = 0;
        void Assert(bool expression, string why)
        {
            checks++;
            if (!expression)
                throw new InvalidOperationException("E39 image inspector self-test failed: " + why);
        }

        var baseline = Fixture();
        var ok = NativeE39ImageInspector.Inspect(baseline);
        Assert(ok.Status == NativeE39ImageInspector.Verdict.Valid &&
               ok.ChecksumsAcceptable && ok.AllCvnValuesMatch,
               "Synthetic E39 checksum table accepted");
        Assert(ok.OsIdentifier == 12664221u && ok.Segments.Count == 6 &&
               ok.Checks.Count == 12, "OSID and all six checksum/CVN pairs extracted");
        Assert(ok.Segments[0].Start == 0x80000 &&
               ok.Segments.Skip(1).All(x => x.Start >= 0x20000 && x.EndInclusive < 0x80000),
               "Protected boot never appears in writable master segments");

        var mismatch = (byte[])baseline.Clone();
        mismatch[0x20040] ^= 0x02;
        Assert(NativeE39ImageInspector.Inspect(mismatch).Status ==
               NativeE39ImageInspector.Verdict.SumMismatch,
               "Altered calibration byte invalidates mandatory SUM check");

        var cvnOnly = (byte[])baseline.Clone();
        cvnOnly[0x20020] = 0x01;
        var cvnReport = NativeE39ImageInspector.Inspect(cvnOnly);
        Assert(cvnReport.Status == NativeE39ImageInspector.Verdict.CvnMismatch &&
               cvnReport.ChecksumsAcceptable && !cvnReport.AllCvnValuesMatch,
               "CVN mismatch remains explicitly reported");

        var sumOnly = (byte[])baseline.Clone();
        sumOnly[0x20000] = 0x01;
        Assert(NativeE39ImageInspector.Inspect(sumOnly).Status ==
               NativeE39ImageInspector.Verdict.SumMismatch,
               "Stored checksum corruption is rejected");

        var invalidPtr = (byte[])baseline.Clone();
        Put32(invalidPtr, 0xCB05A, 0x100);
        Assert(NativeE39ImageInspector.Inspect(invalidPtr).Status ==
               NativeE39ImageInspector.Verdict.InvalidLayout,
               "Calibration region cannot overlap protected boot");
        var invalidBound = (byte[])baseline.Clone();
        Put32(invalidBound, 0xCB072 + 4, 0xFFFFFFFF);
        Assert(NativeE39ImageInspector.Inspect(invalidBound).Status ==
               NativeE39ImageInspector.Verdict.InvalidLayout,
               "Malformed checksum pointers are bounded");

        var badOs = (byte[])baseline.Clone();
        Put32(badOs, Table + 5, 0);
        Assert(NativeE39ImageInspector.Inspect(badOs).Status ==
               NativeE39ImageInspector.Verdict.InvalidLayout,
               "Blank operating system identifier is rejected");
        Assert(NativeE39ImageInspector.Inspect(new byte[512]).Status ==
               NativeE39ImageInspector.Verdict.InvalidLayout,
               "Incorrect image length rejected before pointer dereference");

        byte[] words = [0, 1, 0, 2, 0, 3, 0, 4];
        Assert(NativeE39ImageInspector.ComputeSum(words, (0, 7)) == 0xFFFA,
            "GM 16-bit two's-complement checksum over declared end-exclusive word pairs");
        var knownCrc = System.Text.Encoding.ASCII.GetBytes("123456789");
        Assert(NativeE39ImageInspector.ComputeCvn(knownCrc, (0, 8)) == 0x3DBB,
            "CRC16 reflected A001/zero initial value with GM byte-swapped storage");

        Console.WriteLine($"PASS: {checks} native E39 image checksum self-tests.");
        return checks;
    }
}
