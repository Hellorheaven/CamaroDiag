using System.Globalization;

namespace CamaroDiag.J2534Bridge;

/// <summary>
/// Optional ISO 14229 multi-DID read selection for physical ISO-TP and DoIP.
/// Data lengths come from a user's ECU/ODX definition; no unknown length or
/// proprietary value is guessed from the DID number.
/// Example: 0xF190:17,0xF187:4
/// </summary>
internal static class UdsMultiDidCli
{
    internal sealed record Entry(ushort Did, int Length);

    internal static IReadOnlyList<Entry> Parse(string specification)
    {
        if (string.IsNullOrWhiteSpace(specification) ||
            specification.Length > 256)
            throw new ArgumentException("Multi-DID input exceeds parser bounds.");
        var parts = specification.Split(',', StringSplitOptions.None);
        if (parts.Length is < 2 or > 16)
            throw new ArgumentException("Multi-DID selection needs 2–16 DIDs.");
        var parsed = new List<Entry>(parts.Length);
        var seen = new HashSet<ushort>();
        foreach (var part in parts)
        {
            var tokens = part.Split(':', StringSplitOptions.None);
            if (tokens.Length != 2 ||
                !tokens[0].StartsWith("0x", StringComparison.OrdinalIgnoreCase) ||
                tokens[0].Length is < 3 or > 6 ||
                !tokens[0].AsSpan(2).ToString().All(Uri.IsHexDigit) ||
                !ushort.TryParse(tokens[0][2..],
                    NumberStyles.AllowHexSpecifier, CultureInfo.InvariantCulture,
                    out var did) ||
                !int.TryParse(tokens[1], NumberStyles.None,
                    CultureInfo.InvariantCulture, out var length) ||
                length is < 0 or > 4095 || !seen.Add(did))
                throw new ArgumentException(
                    "Multi-DID requires unique 0xHHHH:decimalLength entries.");
            parsed.Add(new Entry(did, length));
        }
        return parsed;
    }

    internal static byte[] BuildRequest(IReadOnlyList<Entry> entries) =>
        UdsStandardServices.ReadDids(entries.Select(e => e.Did).ToArray());

    internal static IReadOnlyList<(ushort Did, byte[] Data)> Decode(
        IReadOnlyList<Entry> entries, ReadOnlySpan<byte> request,
        ReadOnlySpan<byte> reply)
    {
        if (!request.SequenceEqual(BuildRequest(entries)))
            throw new InvalidDataException(
                "Multi-DID decoder selection does not match the outbound request.");
        return UdsStandardServices.DecodeDids(
            request, reply, entries.Select(e => e.Length).ToArray());
    }
}
