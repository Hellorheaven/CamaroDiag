namespace CamaroDiag.J2534Bridge;

/// <summary>
/// SAE J1850 VPW/PWM CRC-8 byte-level reference codec.
/// Polynomial 0x1D, init 0xFF, no reflection, xorout 0xFF, check 0x4B
/// for ASCII "123456789". This is not a J1850 VPW/PWM physical-layer
/// implementation. The J2534 driver may add/strip CRC itself; never append
/// a second CRC blindly to an already framed PassThru message.
/// </summary>
internal static class J1850Crc
{
    internal static byte Compute(ReadOnlySpan<byte> content)
    {
        byte state = 0xFF;
        foreach (var value in content)
        {
            state ^= value;
            for (var bit = 0; bit < 8; bit++)
                state = (byte)((state & 0x80) != 0 ?
                    (state << 1) ^ 0x1D : state << 1);
        }
        return (byte)(state ^ 0xFF);
    }

    /// <summary>
    /// Verify a complete, CRC-bearing J1850 frame captured by a raw
    /// interface. A J2534 driver that strips CRC must not call this method
    /// on its unframed payload.
    /// </summary>
    internal static bool VerifyRawFrame(ReadOnlySpan<byte> frame)
    {
        if (frame.Length < 2) return false;
        return Compute(frame[..^1]) == frame[^1];
    }

    internal static byte[] AppendForRawFraming(ReadOnlySpan<byte> headerAndPayload)
    {
        if (headerAndPayload.Length is < 2 or > 15)
            throw new ArgumentOutOfRangeException(nameof(headerAndPayload));
        var framed = new byte[headerAndPayload.Length + 1];
        headerAndPayload.CopyTo(framed);
        framed[^1] = Compute(headerAndPayload);
        return framed;
    }
}
