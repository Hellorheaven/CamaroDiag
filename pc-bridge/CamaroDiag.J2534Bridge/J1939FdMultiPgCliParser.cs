namespace CamaroDiag.J2534Bridge;

/// <summary>
/// Parse CLI input for multiple SAE J1939-22 FEFF/FBFF C-PGs within one
/// CAN-FD frame. Syntax: 0xF004:010203;0xFECA:C7FF. Parsing never sends.
/// The final wire-size/destination validation is performed by the codec.
/// </summary>
internal static class J1939FdMultiPgCliParser
{
    internal static IReadOnlyList<J1939FdMultiPg.ContainedGroup> Parse(
        string text)
    {
        if (string.IsNullOrWhiteSpace(text) || text.Length > 256)
            throw new ArgumentException("J1939-22 groups require a bounded PGN:HEX list.");

        var entries = text.Split(';', StringSplitOptions.None);
        if (entries.Length is < 2 or > 8)
            throw new ArgumentException(
                "Supply 2–8 contained PGNs separated by semicolons.");
        var groups = new List<J1939FdMultiPg.ContainedGroup>(entries.Length);
        foreach (var entry in entries)
        {
            var colon = entry.IndexOf(':');
            if (colon <= 0 ||
                entry.LastIndexOf(':') != colon)
                throw new ArgumentException(
                    "Each J1939-22 group must be PGN:HEX without additional separators.");
            var pgnText = entry[..colon];
            if (!pgnText.StartsWith("0x", StringComparison.OrdinalIgnoreCase) ||
                pgnText.Length is < 3 or > 7 ||
                !pgnText[2..].All(Uri.IsHexDigit))
                throw new ArgumentException("Contained PGN must be explicit 0x-prefixed hex.");
            var pgn = Convert.ToInt32(pgnText[2..], 16);
            var hex = entry[(colon + 1)..];
            if (hex.Length > 120 || hex.Length % 2 != 0 ||
                !hex.All(Uri.IsHexDigit))
                throw new ArgumentException("Contained C-PG payload must contain 0–60 bytes.");
            groups.Add(new J1939FdMultiPg.ContainedGroup(
                pgn, Convert.FromHexString(hex)));
        }
        return groups;
    }
}
