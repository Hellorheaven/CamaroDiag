namespace CamaroDiag.J2534Bridge;

/// <summary>
/// Two-step ISO 15765-4 11-bit CAN protocol detection for legislated OBD:
/// first functional UDS ReadDID F810; only if none positively identifies
/// OBDonUDS does it send functional classic J1979 01 00. Does not open
/// a transport itself and cannot infer ECU programming or OEM capabilities.
/// </summary>
internal static class ObdCanProtocolDetection
{
    internal enum ProtocolKind { ObdOnUds, ClassicJ1979, NoConfirmedProtocol }

    internal sealed record Detection(ProtocolKind Kind,
        IReadOnlyList<uint> ResponseIds, bool ClassicFallbackAttempted);

    internal static async Task<Detection> DetectAsync(
        IsoTpCanFdSession.ICanLink link, TimeSpan phaseTimeout,
        CancellationToken token)
    {
        ArgumentNullException.ThrowIfNull(link);
        if (phaseTimeout <= TimeSpan.Zero ||
            phaseTimeout > TimeSpan.FromSeconds(30))
            throw new ArgumentOutOfRangeException(nameof(phaseTimeout));
        token.ThrowIfCancellationRequested();

        var uds = await ObdOnUdsCanDiscovery.DiscoverAsync(
            link, phaseTimeout, token);
        var confirmed = uds.Where(x => x.IsObdOnUds)
            .Select(x => x.ResponseId).Distinct().OrderBy(x => x).ToArray();
        if (confirmed.Length > 0)
            return new(ProtocolKind.ObdOnUds, confirmed, false);

        token.ThrowIfCancellationRequested();
        var classic = await ObdJ1979FunctionalCanDiscovery.DiscoverAsync(
            link, phaseTimeout, token);
        if (classic.Count > 0)
            return new(ProtocolKind.ClassicJ1979,
                classic.Select(x => x.ResponseId).Distinct().OrderBy(x => x).ToArray(),
                true);
        return new(ProtocolKind.NoConfirmedProtocol, [], true);
    }
}
