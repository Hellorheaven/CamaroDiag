namespace CamaroDiag.J2534Bridge;

/// <summary>
/// SAE J1939-73 DM1 (active) and DM2 (previously active) diagnostic
/// payload decoder, after the J1939-21 transport has already reassembled
/// the PGN. No DTC clearing or proprietary SPN interpretation is performed.
/// </summary>
internal static class J1939DmDiagnostics
{
    internal const int Dm1Pgn = 0xFECA;
    internal const int Dm2Pgn = 0xFECB;

    internal sealed record Dtc(int? Spn, int Fmi, int OccurrenceCount,
        bool AlternativeSpnConversion, byte[] RawBytes);

    internal sealed record Report(int Pgn, byte LampStatus, byte LampFlashStatus,
        IReadOnlyList<Dtc> Codes);

    internal static Report Decode(int pgn, ReadOnlySpan<byte> payload)
    {
        if (pgn is not (Dm1Pgn or Dm2Pgn))
            throw new ArgumentException("Only J1939-73 DM1 and DM2 are decoded.");
        if (payload.Length < 2)
            throw new InvalidDataException("J1939 DM lamp status missing.");

        // Classical direct CAN messages may pad the final two bytes with FF.
        // Multi-frame TP carries its exact original data size.
        var remaining = payload.Length - 2;
        if (remaining % 4 == 2 && payload.Length >= 8 &&
            payload[^1] == 0xFF && payload[^2] == 0xFF)
            remaining -= 2;
        if (remaining % 4 != 0)
            throw new InvalidDataException("Malformed J1939 DM diagnostic code length.");
        var codes = new List<Dtc>();
        for (var offset = 2; offset < 2 + remaining; offset += 4)
        {
            var b0 = payload[offset];
            var b1 = payload[offset + 1];
            var b2 = payload[offset + 2];
            var b3 = payload[offset + 3];
            if (b0 == 0 && b1 == 0 && b2 == 0 && b3 == 0)
                continue; // Standard zero-code indicator; not an active fault.
            var legacyConversion = (b3 & 0x80) != 0;
            // Conversion method 1 does not use the modern three-byte SPN
            // bit layout. Expose the original four DTC bytes rather than
            // reporting a misleading modern SPN.
            int? spn = legacyConversion ? null :
                b0 | b1 << 8 | (b2 & 0xE0) << 11;
            codes.Add(new Dtc(spn, b2 & 0x1F, b3 & 0x7F,
                legacyConversion, payload.Slice(offset, 4).ToArray()));
        }
        return new Report(pgn, payload[0], payload[1], codes);
    }
}
