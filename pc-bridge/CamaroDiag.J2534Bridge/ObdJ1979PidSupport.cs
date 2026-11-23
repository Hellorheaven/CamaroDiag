namespace CamaroDiag.J2534Bridge;

/// <summary>
/// Decodes the standardized four-octet support bitmap exchanged by SAE J1979
/// Mode 01 and Mode 02 (PID 00,20,...,E0). This is NOT a PID value decoder:
/// support bits do not imply ECU permissions, scaling, or successful reads.
/// </summary>
internal static class ObdJ1979PidSupport
{
    internal sealed record Page(byte Mode, byte RequestedBase,
        IReadOnlyList<byte> SupportedPids, bool HasNextPage);

    internal static byte[] BuildRequest(byte mode, byte pageBase)
    {
        ValidatePage(mode, pageBase);
        return ObdJ1979Transaction.Request(mode, [pageBase]);
    }

    internal static Page Decode(ReadOnlySpan<byte> request,
        ReadOnlySpan<byte> response)
    {
        if (request.Length != 2)
            throw new ArgumentException("Support page query requires exactly one PID.", nameof(request));
        ValidatePage(request[0], request[1]);
        var result = ObdJ1979Transaction.Decode(request, response,
            expectIdentifierEcho: true);
        if (result.Kind != ObdJ1979Transaction.ReplyKind.Positive)
            throw new InvalidDataException(
                "ECU did not positively acknowledge the requested PID support page.");
        if (result.Data.Length != 5)
            throw new InvalidDataException(
                "OBD PID support response must contain echoed base and four bitmap bytes.");

        var basePid = request[1];
        var supported = new List<byte>(32);
        for (var bit = 0; bit < 32; bit++)
        {
            if ((result.Data[1 + bit / 8] & (0x80 >> (bit % 8))) == 0)
                continue;
            supported.Add(checked((byte)(basePid + bit + 1)));
        }
        var nextPage = basePid < 0xE0 &&
            supported.Contains((byte)(basePid + 0x20));
        return new Page(request[0], basePid, supported, nextPage);
    }

    private static void ValidatePage(byte mode, byte pageBase)
    {
        if (mode is not (0x01 or 0x02) ||
            pageBase > 0xE0 || pageBase % 0x20 != 0)
            throw new ArgumentException(
                "Supported PID bitmap pages require Mode 01/02 and base 00..E0 by 20.");
    }
}
