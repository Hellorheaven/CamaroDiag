namespace CamaroDiag.J2534Bridge;

/// <summary>
/// ISO 14229 application response finalization over any ISO-TP transport.
/// The transport keeps its own framing and physical addressing; this helper
/// only checks the request SID, ResponsePending 0x78 and final correlation.
/// No OEM session/security algorithm is invented.
/// </summary>
internal static class UdsResponseSequence
{
    internal static async Task<byte[]> AwaitFinalAsync(
        byte[] request,
        Func<TimeSpan, CancellationToken, Task<byte[]>> receive,
        TimeSpan totalTimeout, CancellationToken token)
    {
        ArgumentNullException.ThrowIfNull(request);
        ArgumentNullException.ThrowIfNull(receive);
        if (request.Length == 0 ||
            totalTimeout <= TimeSpan.Zero ||
            totalTimeout > TimeSpan.FromMinutes(10))
            throw new ArgumentException("UDS final response needs a request and bounded timeout.");
        var elapsed = System.Diagnostics.Stopwatch.StartNew();
        while (true)
        {
            token.ThrowIfCancellationRequested();
            var remaining = totalTimeout - elapsed.Elapsed;
            if (remaining <= TimeSpan.Zero)
                throw new TimeoutException(
                    "UDS final diagnostic response exceeded overall deadline.");
            var response = await receive(
                remaining > TimeSpan.FromSeconds(30) ?
                    TimeSpan.FromSeconds(30) : remaining, token);
            if (response is null || response.Length == 0)
                throw new InvalidDataException("Empty UDS diagnostic response.");
            if (response[0] == 0x7F)
            {
                if (response.Length != 3 || response[1] != request[0])
                    throw new InvalidDataException(
                        "UDS NRC does not correlate with the active service.");
                if (response[2] == 0x78)
                    continue; // ResponsePending, bounded by total deadline.
            }
            return UdsStandardServices.RequireResponse(request, response);
        }
    }
}
