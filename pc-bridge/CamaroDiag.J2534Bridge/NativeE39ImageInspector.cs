using System.Buffers.Binary;

namespace CamaroDiag.J2534Bridge;

/// <summary>
/// Native, offline GM E39/E39A 3 MiB image inspector. The public E39/A
/// checksum-table offsets and byte-level algorithm were cross-checked with
/// published implementations (including PCM Hammer), but this implementation
/// contains no third-party code or ECU kernel.
///
/// An image checksum is necessary for flashing but never sufficient:
/// physical ECU identity, memory coverage, OEM security, power and recovery
/// remain independent requirements.
/// </summary>
internal static class NativeE39ImageInspector
{
    internal const int ImageBytes = 0x300000;
    internal const int ProtectedBootBytes = 0x20000;
    internal const int MasterChecksumTable = 0xCB000;
    internal const int OperatingSystemStart = 0x80000;

    internal enum Verdict { Valid, CvnMismatch, SumMismatch, InvalidLayout }

    internal sealed record Check(string Name, string Kind, int StoredOffset,
        ushort Stored, ushort Computed, int FirstByte, int LastByte)
    {
        internal bool Matches => Stored == Computed;
    }

    internal sealed record Segment(string Name, int Start, int EndInclusive,
        bool IsOperatingSystem);

    internal sealed record Report(
        Verdict Status,
        uint? OsIdentifier,
        IReadOnlyList<Segment> Segments,
        IReadOnlyList<Check> Checks,
        string? Reason)
    {
        internal bool ChecksumsAcceptable => Status is Verdict.Valid or Verdict.CvnMismatch;
        internal bool AllCvnValuesMatch => Status == Verdict.Valid;
    }

    private readonly record struct SpanRange(int Start, int Last);
    private readonly record struct TableSpec(
        string Name, int AddressPointer, int SumTable, int SumRanges,
        int CvnTable, int CvnRanges, bool IsOs = false);

    private static readonly TableSpec[] Specs =
    [
        new("OS", 0xCB026, 0xCB046, 2, 0xCB02E, 3, true),
        new("System", 0xCB05A, 0xCB072, 1, 0xCB062, 2),
        new("Fuel", 0xCB07E, 0xCB096, 1, 0xCB086, 2),
        new("Speedo", 0xCB0A2, 0xCB0BA, 1, 0xCB0AA, 2),
        new("EngineDiag", 0xCB0C6, 0xCB0DE, 1, 0xCB0CE, 2),
        new("Engine", 0xCB0EA, 0xCB102, 1, 0xCB0F2, 2)
    ];

    internal static Report Inspect(ReadOnlySpan<byte> data)
    {
        if (data.Length != ImageBytes)
            return Invalid("E39/E39A images must be exactly 0x300000 bytes.");

        try
        {
            var osid = Read32(data, MasterChecksumTable + 5);
            if (osid is 0 or uint.MaxValue)
                throw new InvalidDataException("Missing E39/E39A operating-system identifier.");

            var segments = new List<Segment>(Specs.Length);
            var checks = new List<Check>(Specs.Length * 2);
            foreach (var spec in Specs)
            {
                var start = CheckedImageAddress(Read32(data, spec.AddressPointer), data.Length);
                var sumRanges = GetRanges(data, spec.SumTable, spec.SumRanges, requireWords: true);
                var cvnRanges = GetRanges(data, spec.CvnTable, spec.CvnRanges, requireWords: false);
                var last = sumRanges[^1].Last;
                if (last <= start ||
                    (spec.IsOs && start != OperatingSystemStart) ||
                    (!spec.IsOs && (start < ProtectedBootBytes || last >= OperatingSystemStart)))
                    throw new InvalidDataException(
                        $"E39 {spec.Name} segment location contradicts 3 MiB memory map.");

                var sumStoredAt = spec.IsOs ? MasterChecksumTable : start;
                var cvnStoredAt = spec.IsOs ? MasterChecksumTable + 0x20 : start + 0x20;
                if (sumStoredAt + 1 >= data.Length || cvnStoredAt + 1 >= data.Length)
                    throw new InvalidDataException("E39 checksum values are outside the image.");

                foreach (var range in sumRanges.Concat(cvnRanges))
                {
                    if (range.Start < start || range.Last > last)
                        throw new InvalidDataException(
                            $"E39 {spec.Name} checksum blocks extend outside the segment.");
                }
                segments.Add(new Segment(spec.Name, start, last, spec.IsOs));
                checks.Add(ValidateChecksum(data, spec.Name, "SUM", sumStoredAt, sumRanges));
                checks.Add(ValidateChecksum(data, spec.Name, "CVN", cvnStoredAt, cvnRanges));
            }

            var calibrations = segments.Where(x => !x.IsOperatingSystem)
                .OrderBy(x => x.Start).ToArray();
            for (var i = 1; i < calibrations.Length; i++)
                if (calibrations[i].Start <= calibrations[i - 1].EndInclusive)
                    throw new InvalidDataException("E39 calibration segments overlap.");
            if (calibrations.Any(x => x.Start < ProtectedBootBytes))
                throw new InvalidDataException("E39 image overlaps protected boot flash.");

            var badSum = checks.Any(x => x.Kind == "SUM" && !x.Matches);
            var badCvn = checks.Any(x => x.Kind == "CVN" && !x.Matches);
            return new Report(
                badSum ? Verdict.SumMismatch :
                badCvn ? Verdict.CvnMismatch : Verdict.Valid,
                osid, segments, checks, null);
        }
        catch (Exception error) when (error is InvalidDataException or ArgumentException or OverflowException)
        {
            return Invalid(error.Message);
        }
    }

    private static Report Invalid(string why) =>
        new(Verdict.InvalidLayout, null, [], [], why);

    private static int CheckedImageAddress(uint value, int imageLength)
    {
        if (value >= imageLength)
            throw new InvalidDataException("Out-of-bounds E39 segment pointer.");
        return (int)value;
    }

    private static uint Read32(ReadOnlySpan<byte> bytes, int offset) =>
        BinaryPrimitives.ReadUInt32BigEndian(bytes.Slice(offset, 4));

    private static ushort Read16(ReadOnlySpan<byte> bytes, int offset) =>
        BinaryPrimitives.ReadUInt16BigEndian(bytes.Slice(offset, 2));

    private static SpanRange[] GetRanges(ReadOnlySpan<byte> image, int table, int count, bool requireWords)
    {
        var ranges = new SpanRange[count];
        for (var i = 0; i < count; i++)
        {
            var first = CheckedImageAddress(Read32(image, table + 8 * i), image.Length);
            var last = CheckedImageAddress(Read32(image, table + 8 * i + 4), image.Length);
            // SUM is a sequence of 16-bit words; CVN CRC16 counts bytes and
            // can cover odd-length blocks in valid firmware layouts.
            if (last < first ||
                (requireWords && (last == first || (last - first + 1) % 2 != 0)) ||
                first < ProtectedBootBytes)
                throw new InvalidDataException("E39 checksum block boundaries are invalid.");
            ranges[i] = new SpanRange(first, last);
        }
        return ranges;
    }

    private static Check ValidateChecksum(
        ReadOnlySpan<byte> bytes, string segment, string kind,
        int offset, SpanRange[] ranges)
    {
        var expected = kind == "SUM" ? ComputeSum(bytes, ranges) : ComputeCvn(bytes, ranges);
        return new Check(segment, kind, offset, Read16(bytes, offset),
            expected, ranges[0].Start, ranges[^1].Last);
    }

    internal static ushort ComputeSum(ReadOnlySpan<byte> image, params (int Start, int Last)[] spans) =>
        ComputeSum(image, spans.Select(x => new SpanRange(x.Start, x.Last)).ToArray());

    private static ushort ComputeSum(ReadOnlySpan<byte> image, SpanRange[] spans)
    {
        uint sum = 0;
        foreach (var range in spans)
        {
            for (var offset = range.Start; offset < range.Last; offset += 2)
                sum = unchecked(sum + Read16(image, offset));
        }
        return unchecked((ushort)(0u - sum));
    }

    internal static ushort ComputeCvn(ReadOnlySpan<byte> image, params (int Start, int Last)[] spans) =>
        ComputeCvn(image, spans.Select(x => new SpanRange(x.Start, x.Last)).ToArray());

    private static ushort ComputeCvn(ReadOnlySpan<byte> image, SpanRange[] spans)
    {
        ushort crc = 0;
        foreach (var range in spans)
        {
            for (var pos = range.Start; pos <= range.Last; pos++)
            {
                crc ^= image[pos];
                for (var bit = 0; bit < 8; bit++)
                    crc = (ushort)((crc & 1) != 0 ? (crc >> 1) ^ 0xA001 : crc >> 1);
            }
        }
        return (ushort)((crc >> 8) | (crc << 8));
    }
}
