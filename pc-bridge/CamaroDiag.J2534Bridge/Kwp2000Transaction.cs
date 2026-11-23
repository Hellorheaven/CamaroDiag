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
        ReadOnlySpan<byte> response, int echoedRequestBytes = 0)
    {
        if (request.Length == 0)
            throw new ArgumentException("KWP request needs a service byte.", nameof(request));
        if (echoedRequestBytes < 0 || echoedRequestBytes > request.Length - 1)
            throw new ArgumentOutOfRangeException(nameof(echoedRequestBytes),
                "KWP echoed parameter count must fit the outgoing request.");
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
        if (response[0] != unchecked((byte)(service + 0x40)))
            throw new InvalidDataException(
                "KWP positive response service does not match the transmitted SID.");
        // Echo format depends on the service. Caller explicitly opts in
        // to an ECU/profile-defined number of echoed request parameters.
        // Never apply that requirement to NRCs, and never infer it for
        // arbitrary manufacturer service IDs.
        if (echoedRequestBytes != 0 &&
            (response.Length < 1 + echoedRequestBytes ||
             !response.Slice(1, echoedRequestBytes)
                 .SequenceEqual(request.Slice(1, echoedRequestBytes))))
            throw new InvalidDataException(
                "KWP positive reply does not echo the expected request parameters.");
        return new(Kind.Positive, service, response[1..].ToArray(), null);
    }
}
