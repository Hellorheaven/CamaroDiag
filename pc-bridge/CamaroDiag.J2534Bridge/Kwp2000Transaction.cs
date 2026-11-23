namespace CamaroDiag.J2534Bridge;

/// <summary>
/// ISO 14230-3 KWP2000 application SID correlation after K-Line physical
/// frame/address/checksum parsing. Does not grant OEM security access or
/// claim that a positive response proves a write/programming operation.
/// </summary>
internal static class Kwp2000Transaction
{
    internal enum Kind { Positive, Negative, ResponsePending }
    internal sealed record Reply(Kind Status, byte Service,
        byte[] Data, byte? NegativeResponseCode);

    internal static Reply Decode(ReadOnlySpan<byte> request,
        ReadOnlySpan<byte> response)
    {
        if (request.Length == 0 || request[0] is < 0x01 or > 0xBF)
            throw new ArgumentException("KWP request needs a valid service byte.", nameof(request));
        if (response.Length == 0)
            throw new InvalidDataException("Missing KWP application reply.");
        var service = request[0];
        if (response[0] == 0x7F)
        {
            if (response.Length != 3 || response[1] != service ||
                response[2] == 0)
                throw new InvalidDataException(
                    "Malformed or unrelated KWP negative application response.");
            var kind = response[2] == 0x78 ? Kind.ResponsePending : Kind.Negative;
            return new(kind, service, [], response[2]);
        }
        if (response[0] != service + 0x40)
            throw new InvalidDataException(
                "KWP positive response service does not match the transmitted SID.");
        return new(Kind.Positive, service, response[1..].ToArray(), null);
    }
}
