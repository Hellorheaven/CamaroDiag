namespace CamaroDiag.J2534Bridge;

/// <summary>
/// Decodes the standardized four-octet support bitmap exchanged by SAE J1979
/// Mode 01 and Mode 02 (PID 00,20,...,E0). This is NOT a PID value decoder:
/// support bits do not imply ECU permissions, scaling, or successful reads.
/// </summary>
internal static class ObdJ1979PidSupport
{
    internal sealed record Page(byte Mode, byte RequestedBase,
        IReadOnlyList<byte> SupportedPids, bool HasNextPage,
        byte? FreezeFrameNumber);

    internal static byte[] BuildRequest(byte mode, byte pageBase,
        byte freezeFrameNumber = 0)
    {
        ValidatePage(mode, pageBase);
        return mode == 0x02 ?
            ObdJ1979Transaction.Request(mode, [pageBase, freezeFrameNumber]) :
            ObdJ1979Transaction.Request(mode, [pageBase]);
    }

    internal static Page Decode(ReadOnlySpan<byte> request,
        ReadOnlySpan<byte> response)
    {
        if (request.Length != (request.Length > 0 && request[0] == 0x02 ? 3 : 2))
            throw new ArgumentException("Invalid support PID/frame request length.", nameof(request));
        ValidatePage(request[0], request[1]);
        var result = ObdJ1979Transaction.Decode(request, response,
            expectIdentifierEcho: true);
        if (result.Kind != ObdJ1979Transaction.ReplyKind.Positive)
            throw new InvalidDataException(
                "ECU did not positively acknowledge the requested PID support page.");
        var bitmapOffset = request[0] == 0x02 ? 2 : 1;
        if (result.Data.Length != bitmapOffset + 4 ||
            (request[0] == 0x02 && result.Data[1] != request[2]))
            throw new InvalidDataException(
                "OBD support reply must echo PID/frame and contain four bitmap bytes.");

        var basePid = request[1];
        var supported = new List<byte>(32);
        for (var bit = 0; bit < 32; bit++)
        {
            if ((result.Data[bitmapOffset + bit / 8] & (0x80 >> (bit % 8))) == 0)
                continue;
            var pid = basePid + bit + 1;
            if (pid > byte.MaxValue)
                throw new InvalidDataException("PID support page sets a bit beyond PID FF.");
            supported.Add((byte)pid);
        }
        var nextPage = basePid < 0xE0 &&
            supported.Contains((byte)(basePid + 0x20));
        return new Page(request[0], basePid, supported, nextPage,
            request[0] == 0x02 ? request[2] : null);
    }

    private static void ValidatePage(byte mode, byte pageBase)
    {
        if (mode is not (0x01 or 0x02) ||
            pageBase > 0xE0 || pageBase % 0x20 != 0)
            throw new ArgumentException(
                "Supported PID bitmap pages require Mode 01/02 and base 00..E0 by 20.");
    }
}
