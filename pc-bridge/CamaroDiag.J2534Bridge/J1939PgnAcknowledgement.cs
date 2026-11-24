namespace CamaroDiag.J2534Bridge;

/// <summary>
/// SAE J1939-21 PGN 59392 (E800): a response to a requested parameter
/// group, distinct from the requested data PGN. This codec never equates
/// a positive acknowledgement with proof of completing an ECU operation.
/// </summary>
internal static class J1939PgnAcknowledgement
{
    internal const int AcknowledgementPgn = 0xE800;
    internal sealed record Message(
        byte Control, byte GroupFunction, byte AcknowledgedAddress,
        int RequestedPgn)
    {
        internal bool Positive => Control == 0;
    }

    internal static Message Decode(ReadOnlySpan<byte> data,
        int expectedPgn)
    {
        if (data.Length != 8)
            throw new InvalidDataException("J1939 ACKM must contain eight bytes.");
        if (data[0] > 3 || data[2] != 0xFF || data[3] != 0xFF)
            throw new InvalidDataException("Invalid J1939 acknowledgement control/reserved bytes.");
        var pgn = data[5] | data[6] << 8 | data[7] << 16;
        if (pgn != expectedPgn)
            throw new InvalidDataException("J1939 ACKM PGN does not match the request.");
        return new Message(data[0], data[1], data[4], pgn);
    }
}
