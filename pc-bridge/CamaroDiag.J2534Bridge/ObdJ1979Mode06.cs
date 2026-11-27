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
        (string Unit, double Factor, bool Signed, double Offset)? definition = id switch
        {
            0x01 => ("1", 1d, false, 0d),
            0x02 => ("1", 0.1d, false, 0d),
            0x03 => ("1", 0.01d, false, 0d),
            0x04 => ("1", 0.001d, false, 0d),
            0x05 => ("1", 0.0000305d, false, 0d),
            0x06 => ("1", 0.000305d, false, 0d),
            0x07 => ("rpm", 0.25d, false, 0d),
            0x08 => ("km/h", 0.01d, false, 0d),
            0x09 => ("km/h", 1d, false, 0d),
            0x0A => ("mV", 0.122d, false, 0d),
            0x0B => ("V", 0.001d, false, 0d),
            0x0C => ("V", 0.01d, false, 0d),
            0x0D => ("mA", 0.00390625d, false, 0d),
            0x0E => ("A", 0.001d, false, 0d),
            0x0F => ("A", 0.01d, false, 0d),
            0x10 => ("ms", 1d, false, 0d),
            0x11 => ("ms", 100d, false, 0d),
            0x12 => ("s", 1d, false, 0d),
            0x13 => ("mOhm", 1d, false, 0d),
            0x14 => ("ohm", 1d, false, 0d),
            0x15 => ("kOhm", 1d, false, 0d),
            0x16 => ("degC", 0.1d, false, -40d),
            0x17 => ("kPa", 0.01d, false, 0d),
            0x18 => ("kPa", 0.0117d, false, 0d),
            0x19 => ("kPa", 0.079d, false, 0d),
            0x1A => ("kPa", 1d, false, 0d),
            0x1B => ("kPa", 10d, false, 0d),
            0x1C => ("deg", 0.01d, false, 0d),
            0x1D => ("deg", 0.5d, false, 0d),
            0x1E => ("ratio", 0.0000305d, false, 0d),
            0x1F => ("ratio", 0.05d, false, 0d),
            0x20 => ("ratio", 0.00390625d, false, 0d),
            0x21 => ("mHz", 1d, false, 0d),
            0x22 => ("Hz", 1d, false, 0d),
            0x23 => ("kHz", 1d, false, 0d),
            0x24 => ("count", 1d, false, 0d),
            0x25 => ("km", 1d, false, 0d),
            0x26 => ("mV/ms", 0.1d, false, 0d),
            0x27 => ("g/s", 0.01d, false, 0d),
            0x28 => ("g/s", 1d, false, 0d),
            0x29 => ("Pa/s", 0.25d, false, 0d),
            0x2A => ("kg/h", 0.001d, false, 0d),
            0x2B => ("count", 1d, false, 0d),
            0x2C => ("g/cyl", 0.01d, false, 0d),
            0x2D => ("mg/stroke", 0.01d, false, 0d),
            0x2E => ("bool", 1d, false, 0d),
            0x2F => ("%", 0.01d, false, 0d),
            0x30 => ("%", 0.001526d, false, 0d),
            0x31 => ("L", 0.001d, false, 0d),
            0x32 => ("in", 0.0000305d, false, 0d),
            0x33 => ("ratio", 0.00024414d, false, 0d),
            0x34 => ("min", 1d, false, 0d),
            0x35 => ("ms", 10d, false, 0d),
            0x36 => ("g", 0.01d, false, 0d),
            0x37 => ("g", 0.1d, false, 0d),
            0x38 => ("g", 1d, false, 0d),
            0x39 => ("%", 0.01d, false, -327.68d),
            0x81 => ("1", 1d, true, 0d),
            0x82 => ("1", 0.1d, true, 0d),
            0x83 => ("1", 0.01d, true, 0d),
            0x84 => ("1", 0.001d, true, 0d),
            0x85 => ("1", 0.0000305d, true, 0d),
            0x86 => ("1", 0.000305d, true, 0d),
            0x8A => ("mV", 0.122d, true, 0d),
            0x8B => ("V", 0.001d, true, 0d),
            0x8C => ("V", 0.01d, true, 0d),
            0x8D => ("mA", 0.00390625d, true, 0d),
            0x8E => ("A", 0.001d, true, 0d),
            0x90 => ("ms", 1d, true, 0d),
            0x96 => ("degC", 0.1d, true, 0d),
            0x9C => ("deg", 0.01d, true, 0d),
            0x9D => ("deg", 0.5d, true, 0d),
            0xA8 => ("g/s", 1d, true, 0d),
            0xA9 => ("Pa/s", 0.25d, true, 0d),
            0xAF => ("%", 0.01d, true, 0d),
            0xB0 => ("%", 0.003052d, true, 0d),
            0xB1 => ("mV/s", 2d, true, 0d),
            0xFD => ("kPa", 0.001d, true, 0d),
            0xFE => ("Pa", 0.25d, true, 0d),
            _ => null
        };
        if (definition is not { } scale) return null;
        if (id == 0x2E && (value > 1 || minimum > 1 || maximum > 1)) return null;
        double Decode(ushort raw) =>
            (scale.Signed && raw >= 32768 ? (int)raw - 65536 : raw) * scale.Factor + scale.Offset;
        var actual = Decode(value);
        var low = Decode(minimum);
        var high = Decode(maximum);
        return new(scale.Unit, actual, low, high, low > high ? "INVERTED_LIMITS" :
            actual < low ? "BELOW" : actual > high ? "ABOVE" : "WITHIN");
    }

    internal sealed record EngineeringRecord(string RequestHex,int RecordIndex,byte MonitorId,byte TestId,
        byte UnitScalingId,ushort ValueEncoded,ushort MinimumEncoded,ushort MaximumEncoded,ScaledValue? Scaled);

    internal static IReadOnlyList<EngineeringRecord> Project(string requestHex,string? responseHex,string status)
    {
        if(status!="PASSED"||requestHex.Length!=4||responseHex is null||responseHex.Length>8190)return [];
        try {
            byte[] req=Convert.FromHexString(requestHex);
            if(req[0]!=6||req[1]%32==0)return [];
            return DecodeTests(req,Convert.FromHexString(responseHex)).Select((test,index)=>
                new EngineeringRecord(requestHex.ToUpperInvariant(),index,test.MonitorId,test.TestId,
                    test.UnitScalingId,test.Value,test.Minimum,test.Maximum,test.Scaled)).ToArray();
        } catch(FormatException){return [];}
          catch(InvalidDataException){return [];}
    }

    internal static object Evidence(EngineeringRecord record)=>new {
        request_hex=record.RequestHex,record_index=record.RecordIndex,monitor_id=record.MonitorId,
        test_id=record.TestId,unit_scaling_id=record.UnitScalingId,value_encoded=record.ValueEncoded,
        minimum_encoded=record.MinimumEncoded,maximum_encoded=record.MaximumEncoded,
        scaled=record.Scaled is { } scaled?new {definition=scaled.Definition,unit=scaled.Unit,
            value=scaled.Value,minimum=scaled.Minimum,maximum=scaled.Maximum,
            numeric_relation=scaled.NumericRelation,monitor_verdict=(string?)null}:null
    };

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
