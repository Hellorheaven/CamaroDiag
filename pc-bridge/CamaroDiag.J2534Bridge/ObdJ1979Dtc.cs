namespace CamaroDiag.J2534Bridge;

/// <summary>
/// SAE J2012 two-byte emissions DTC representation used in classic SAE J1979
/// services 03/07/0A. Caller must remove the service-specific framing,
/// identifiers and any count field before passing the raw code pairs.
/// This codec does not map manufacturer-specific code descriptions.
/// </summary>
internal static class ObdJ1979Dtc
{
    internal sealed record Code(ushort Raw, string Display);

    internal static Code Decode(ushort raw)
    {
        var system = (raw >> 14) switch
        {
            0 => 'P',
            1 => 'C',
            2 => 'B',
            _ => 'U'
        };
        var text = string.Create(5, (system, raw), static (span, value) =>
        {
            span[0] = value.system;
            span[1] = (char)('0' + ((value.raw >> 12) & 0x03));
            const string hex = "0123456789ABCDEF";
            span[2] = hex[(value.raw >> 8) & 0x0F];
            span[3] = hex[(value.raw >> 4) & 0x0F];
            span[4] = hex[value.raw & 0x0F];
        });
        return new Code(raw, text);
    }

    /// <summary>
    /// ISO 15765-4 reassembled application data after positive service
    /// 0x43 / 0x47 / 0x4A: first byte is the number of DTC entries and
    /// each declared DTC is a two-byte SAE J2012 code. Optional trailing
    /// zero-pair padding cannot become a reported fault. This is NOT the
    /// older K-Line / J1850 response representation.
    /// </summary>
    internal static IReadOnlyList<Code> DecodeCanCounted(ReadOnlySpan<byte> data)
    {
        if (data.IsEmpty)
            throw new InvalidDataException("Missing SAE J1979 CAN DTC count.");
        var declared = data[0];
        var bytes = data[1..];
        var requiredBytes = checked(declared * 2);
        if (bytes.Length < requiredBytes || (bytes.Length % 2) != 0)
            throw new InvalidDataException("CAN DTC count and payload length disagree.");

        var result = new List<Code>(declared);
        for (var i = 0; i < declared; i++)
        {
            var code = (ushort)((bytes[i * 2] << 8) | bytes[i * 2 + 1]);
            if (code == 0)
                throw new InvalidDataException("Declared CAN DTC cannot be zero.");
            result.Add(Decode(code));
        }

        if (bytes[requiredBytes..].IndexOfAnyExcept((byte)0) >= 0)
            throw new InvalidDataException("Nonzero bytes after declared CAN DTC count.");
        return result;
    }

    internal static IReadOnlyList<Code> DecodePairs(ReadOnlySpan<byte> bytes)
    {
        if ((bytes.Length & 1) != 0)
            throw new InvalidDataException("OBD DTC data has an incomplete two-byte code.");
        var result = new List<Code>(bytes.Length / 2);
        for (var offset = 0; offset < bytes.Length; offset += 2)
        {
            var raw = (ushort)(bytes[offset] << 8 | bytes[offset + 1]);
            if (raw != 0) result.Add(Decode(raw));
        }
        return result;
    }
}
