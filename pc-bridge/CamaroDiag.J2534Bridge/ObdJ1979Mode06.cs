namespace CamaroDiag.J2534Bridge;

/// <summary>
/// SAE J1979 classic Mode 06 ISO 15765-4 CAN on-board monitor records.
/// Availability bitmaps and individual test records use different response
/// layouts. Known engineering scales use an explicit historical Annex E
/// subset; unknown IDs remain raw, and no monitor verdict is inferred.
/// </summary>
internal static class ObdJ1979Mode06
{
    internal sealed record Availability(byte BaseId, IReadOnlyList<byte> MonitorIds,
        bool HasNextPage);
    internal sealed record Test(byte MonitorId, byte TestId, byte UnitScalingId,
        ushort Value, ushort Minimum, ushort Maximum)
    {
        public ScaledValue? Scaled => DecodeScale(UnitScalingId, Value, Minimum, Maximum);
    }

    internal sealed record ScaledValue(string Unit, double Value, double Minimum,
        double Maximum, string NumericRelation)
    {
        public string Definition => "ISO15031-5:2006-ANNEX-E-SUBSET";
    }

    internal static ScaledValue? DecodeScale(byte id, ushort value, ushort minimum, ushort maximum)
    {
        (string Unit, double Factor, bool Signed)? definition = id switch
        {
            0x0A => ("mV", 0.122d, false),
            0x0B => ("V", 0.001d, false),
            0x0D => ("mA", 0.00390625d, false),
            0x0E => ("A", 0.001d, false),
            0x0F => ("A", 0.01d, false),
            0x10 => ("ms", 1d, false),
            0x24 => ("count", 1d, false),
            0x25 => ("km", 1d, false),
            0x2F => ("%", 0.01d, false),
            0x31 => ("L", 0.001d, false),
            0x84 => ("1", 0.001d, true),
            0x85 => ("1", 0.0000305d, true),
            0x86 => ("1", 0.000305d, true),
            0x8E => ("A", 0.001d, true),
            0x90 => ("ms", 1d, true),
            0x96 => ("degC", 0.1d, true),
            _ => null
        };
        if (definition is not { } scale) return null;
        double Decode(ushort raw) =>
            (scale.Signed && raw >= 32768 ? (int)raw - 65536 : raw) * scale.Factor;
        var actual = Decode(value);
        var low = Decode(minimum);
        var high = Decode(maximum);
        return new(scale.Unit, actual, low, high, low > high ? "INVERTED_LIMITS" :
            actual < low ? "BELOW" : actual > high ? "ABOVE" : "WITHIN");
    }

    internal static Availability DecodeAvailability(ReadOnlySpan<byte> request,
        ReadOnlySpan<byte> response)
    {
        if (request.Length != 2 || request[0] != 0x06 ||
            request[1] > 0xE0 || request[1] % 0x20 != 0)
            throw new ArgumentException("Mode 06 availability requires MID 00,20,..,E0.", nameof(request));
        if (response.Length != 6 || response[0] != 0x46 ||
            response[1] != request[1])
            throw new InvalidDataException("Mode 06 availability response requires MID echo and four bytes.");
        var ids = new List<byte>(32);
        for (var bit = 0; bit < 32; bit++)
        {
            if ((response[2 + bit / 8] & (0x80 >> (bit % 8))) == 0)
                continue;
            var id = request[1] + bit + 1;
            if (id > 0xFF)
                throw new InvalidDataException("Mode 06 MID bitmap exceeds FF.");
            ids.Add((byte)id);
        }
        return new(request[1], ids,
            request[1] < 0xE0 && ids.Contains((byte)(request[1] + 0x20)));
    }

    internal static IReadOnlyList<Test> DecodeTests(ReadOnlySpan<byte> request,
        ReadOnlySpan<byte> response)
    {
        if (request.Length != 2 || request[0] != 0x06)
            throw new ArgumentException("Mode 06 test request requires one MID.", nameof(request));
        if (request[1] % 0x20 == 0)
            throw new ArgumentException("Availability MID is not a monitor test.", nameof(request));
        if (response.Length < 10 || response[0] != 0x46 ||
            (response.Length - 1) % 9 != 0)
            throw new InvalidDataException("Mode 06 CAN tests require nine bytes per test record.");

        var results = new List<Test>((response.Length - 1) / 9);
        for (var offset = 1; offset < response.Length; offset += 9)
        {
            if (response[offset] != request[1])
                throw new InvalidDataException("Mode 06 test MID differs from requested MID.");
            static ushort Word(ReadOnlySpan<byte> buffer) =>
                (ushort)(buffer[0] << 8 | buffer[1]);
            results.Add(new Test(response[offset], response[offset + 1],
                response[offset + 2], Word(response.Slice(offset + 3, 2)),
                Word(response.Slice(offset + 5, 2)),
                Word(response.Slice(offset + 7, 2))));
        }
        return results;
    }
}
