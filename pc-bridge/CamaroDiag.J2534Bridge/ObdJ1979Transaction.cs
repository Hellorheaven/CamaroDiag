namespace CamaroDiag.J2534Bridge;

/// <summary>
/// SAE J1979 request/response correlation above a transport already selected by
/// the caller (ISO 15765-4, J1850 or ISO 9141/KWP as applicable).
/// Does not grant ECU permissions, perform a CAN exchange, or infer support
/// for an optional service merely from a syntactically valid answer.
/// </summary>
internal static class ObdJ1979Transaction
{
    internal enum ReplyKind { Positive, Negative }

    internal sealed record Reply(ReplyKind Kind, byte Service,
        byte[] Data, byte? NegativeResponseCode);

    /// <summary>
    /// Builds the raw diagnostic application payload. All emission-related
    /// services 01..0A, including mutating 04 and optional 08, are representable.
    /// The operator consent and supported parameter definitions belong to
    /// the actual transaction caller; this codec is not an authorization gate.
    /// </summary>
    internal static byte[] Request(byte service, ReadOnlySpan<byte> parameters)
    {
        if (service is < 0x01 or > 0x0A)
            throw new ArgumentOutOfRangeException(nameof(service));
        if (parameters.Length > 4095)
            throw new ArgumentOutOfRangeException(nameof(parameters));
        var data = new byte[parameters.Length + 1];
        data[0] = service;
        parameters.CopyTo(data.AsSpan(1));
        return data;
    }

    /// <summary>
    /// Correlates one completed application response with its outgoing service
    /// and optional echoed identifier. A service 7F/XX/NRC is a legitimate
    /// negative reply, never a successful command. Framing and multi-ECU
    /// routing have already been checked by the lower transport layer.
    /// </summary>
    internal static Reply Decode(ReadOnlySpan<byte> request,
        ReadOnlySpan<byte> response, bool expectIdentifierEcho = false)
    {
        if (request.Length == 0 || request[0] is < 0x01 or > 0x0A)
            throw new ArgumentException("Invalid SAE J1979 request service.", nameof(request));
        if (response.Length == 0)
            throw new InvalidDataException("Empty OBD response.");

        var service = request[0];
        if (response[0] == 0x7F)
        {
            if (response.Length != 3 || response[1] != service ||
                response[2] == 0x00)
                throw new InvalidDataException("Unrelated or malformed negative OBD response.");
            return new Reply(ReplyKind.Negative, service, [],
                response[2]);
        }

        if (response[0] != service + 0x40)
            throw new InvalidDataException("OBD positive response service mismatch.");
        if (expectIdentifierEcho)
        {
            if (request.Length < 2 ||
                response.Length < 2 ||
                response[1] != request[1])
                throw new InvalidDataException("OBD response identifier mismatch.");
        }

        return new Reply(ReplyKind.Positive, service,
            response[1..].ToArray(), null);
    }
}
