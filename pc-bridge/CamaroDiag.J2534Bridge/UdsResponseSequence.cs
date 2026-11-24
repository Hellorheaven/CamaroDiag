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
        TimeSpan totalTimeout, CancellationToken token,
        TimeSpan? p2Timeout = null, TimeSpan? p2StarTimeout = null)
    {
        ArgumentNullException.ThrowIfNull(request);
        ArgumentNullException.ThrowIfNull(receive);
        if (request.Length == 0 ||
            totalTimeout <= TimeSpan.Zero ||
            totalTimeout > TimeSpan.FromMinutes(10))
            throw new ArgumentException("UDS final response needs a request and bounded timeout.");
        // Default limits preserve existing host behavior. Real ECU P2/P2*
        // timings remain dependent on the negotiated ECU session profile.
        var p2 = p2Timeout ?? TimeSpan.FromSeconds(30);
        var p2Star = p2StarTimeout ?? TimeSpan.FromSeconds(30);
        if (p2 <= TimeSpan.Zero || p2 > TimeSpan.FromMinutes(10) ||
            p2Star <= TimeSpan.Zero || p2Star > TimeSpan.FromMinutes(10))
            throw new ArgumentOutOfRangeException(nameof(p2Timeout),
                "UDS P2 and P2* must be bounded and positive.");
        var elapsed = System.Diagnostics.Stopwatch.StartNew();
        var stage = System.Diagnostics.Stopwatch.StartNew();
        var pending = false;
        while (true)
        {
            token.ThrowIfCancellationRequested();
            var remaining = totalTimeout - elapsed.Elapsed;
            if (remaining <= TimeSpan.Zero)
                throw new TimeoutException(
                    "UDS final diagnostic response exceeded overall deadline.");
            var stageLimit = pending ? p2Star : p2;
            var stageRemaining = stageLimit - stage.Elapsed;
            if (stageRemaining <= TimeSpan.Zero)
                throw new TimeoutException(pending ?
                    "UDS P2* response deadline exceeded." :
                    "UDS P2 initial response deadline exceeded.");
            var budget = remaining < stageRemaining ? remaining : stageRemaining;
            var response = await receive(budget, token);
            // Even if a vendor driver ignores the supplied receive timeout,
            // its late positive reply cannot retroactively complete the
            // earlier diagnostic transaction or an ECU programming step.
            token.ThrowIfCancellationRequested();
            if (elapsed.Elapsed >= totalTimeout)
                throw new TimeoutException(
                    "UDS final diagnostic response arrived after the overall deadline.");
            if (stage.Elapsed >= stageLimit)
                throw new TimeoutException(pending ?
                    "UDS reply arrived after P2*." : "UDS reply arrived after P2.");
            if (response is null || response.Length == 0)
                throw new InvalidDataException("Empty UDS diagnostic response.");
            if (response[0] == 0x7F)
            {
                if (response.Length != 3 || response[1] != request[0])
                    throw new InvalidDataException(
                        "UDS NRC does not correlate with the active service.");
                if (response[2] == 0x78)
                {
                    pending = true;
                    stage.Restart(); // Restart P2*, never the total deadline.
                    continue;
                }
            }
            return UdsStandardServices.RequireResponse(request, response);
        }
    }
}
