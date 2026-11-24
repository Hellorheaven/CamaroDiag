namespace CamaroDiag.J2534Bridge;

/// <summary>
/// SAE J1939-21 Request PGN 59904 (0xEA00). The three-byte payload carries
/// the requested PGN in least-significant-byte-first order. Requests may
/// trigger OEM-defined actions; the caller must obtain operator consent
/// before sending and must use a valid claimed source address.
/// </summary>
internal static class J1939PgnRequest
{
    internal const int RequestPgn = 0xEA00;

    internal static byte[] Encode(int requestedPgn)
    {
        if (requestedPgn is < 0 or > 0x3FFFF ||
            ((requestedPgn >> 8) & 0xFF) < 240 && (requestedPgn & 0xFF) != 0)
            throw new ArgumentOutOfRangeException(nameof(requestedPgn),
                "Requested PGN must have valid J1939 PDU1/PDU2 representation.");
        return [(byte)requestedPgn, (byte)(requestedPgn >> 8),
            (byte)(requestedPgn >> 16)];
    }

    internal static int Decode(ReadOnlySpan<byte> payload)
    {
        if (payload.Length != 3)
            throw new InvalidDataException("J1939 Request must specify exactly three PGN bytes.");
        var pgn = payload[0] | payload[1] << 8 | payload[2] << 16;
        _ = Encode(pgn); // Keep PDU1 validation identical in both directions.
        return pgn;
    }
}
