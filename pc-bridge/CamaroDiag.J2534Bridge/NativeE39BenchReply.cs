namespace CamaroDiag.J2534Bridge;

/// <summary>
/// Response correlator for the local E39 programming dialect. The regular
/// network bridge still uses its read-only decoder. Only exact expected
/// services and subfunctions count as native positive responses.
/// </summary>
internal static class NativeE39BenchReply
{
    internal static bool Matches(ReadOnlySpan<byte> request, ReadOnlySpan<byte> reply)
    {
        if (request.IsEmpty || reply.IsEmpty) return false;
        if (reply[0] == 0x7F)
            return reply.Length == 3 && reply[1] == request[0];
        return request[0] switch
        {
            0x10 => request.Length == 2 && request[1] == 2 &&
                reply.Length >= 2 && reply[0] == 0x50 && reply[1] == 2,
            0x28 => request.Length == 1 && reply.Length == 1 && reply[0] is 0x68 or 0x60,
            0x27 => request.Length >= 2 && reply.Length == (request[1] == 1 ? 4 : 2) &&
                request[1] is 1 or 2 && reply[0] == 0x67 && reply[1] == request[1],
            0xA5 => request.Length == 2 && request[1] == 1 && reply.Length == 1 && reply[0] == 0xE5,
            0x34 => request.Length == 5 && request[1] == 0 && reply[0] == 0x74,
            0x36 => request.Length >= 2 && reply.Length == 1 &&
                (request[1] == 0x80 ? reply[0] == 0x99 :
                 request[1] is 0 or 0x44 && reply[0] == 0x76),
            0x3D => request.Length >= 2 && reply.Length >= 2 && reply[0] == 0x7D &&
                reply[1] == request[1] && (request[1] switch
                {
                    0 => request.Length == 2 && reply.Length == 10,
                    2 => request.Length == 8 && reply.Length == 6,
                    5 => request.Length == 5 && reply.Length == 3,
                    _ => false
                }),
            0x23 => MatchesMemoryRead(request, reply),
            _ => false
        };
    }

    private static bool MatchesMemoryRead(ReadOnlySpan<byte> request, ReadOnlySpan<byte> reply)
    {
        if (request.Length is not (7 or 8) || reply[0] != 0x63 ||
            request[1] != (request.Length == 7 ? 0x14 : 0x24))
            return false;
        int count = request.Length == 7 ? request[6] : (request[6] << 8) | request[7];
        return count > 0 && reply.Length == count + 1;
    }
}
