namespace CamaroDiag.J2534Bridge;

/// <summary>
/// SAE J1979 / ISO 15765-4 classic OBD functional discovery on 11-bit CAN.
/// The only outgoing request is Mode 01 PID 00 to 7DF. This scans which
/// physical ECUs respond (7E8..7EF); it does not infer additional PID pages,
/// perform ECU programming, or fabricate a response for silent modules.
/// </summary>
internal static class ObdJ1979FunctionalCanDiscovery
{
    internal sealed record DiscoveredEcu(
        uint ResponseId, IReadOnlyList<byte> SupportedPids, bool NextPage);

    internal static IsoTpCanFdSession.CanFrame BuildRequest()
        => new(0x7DF, false, [0x02, 0x01, 0x00, 0xCC, 0xCC, 0xCC, 0xCC, 0xCC]);

    internal static DiscoveredEcu? TryDecode(IsoTpCanFdSession.CanFrame frame)
    {
        if (frame.IsFd || frame.Id is < 0x7E8 or > 0x7EF ||
            frame.Data is null || frame.Data.Length != 8)
            return null;
        var bytes = frame.Data;
        if (bytes[0] != 6 || bytes[1] != 0x41 || bytes[2] != 0)
            return null;
        var page = ObdJ1979PidSupport.Decode(
            [0x01, 0x00], bytes.AsSpan(1, 6));
        return new(frame.Id, page.SupportedPids, page.HasNextPage);
    }

    internal static async Task<IReadOnlyList<DiscoveredEcu>> DiscoverAsync(
        IsoTpCanFdSession.ICanLink link, TimeSpan timeout, CancellationToken token)
    {
        ArgumentNullException.ThrowIfNull(link);
        if (timeout <= TimeSpan.Zero || timeout > TimeSpan.FromSeconds(30))
            throw new ArgumentOutOfRangeException(nameof(timeout));
        token.ThrowIfCancellationRequested();
        await link.SendAsync(BuildRequest(), token);
        var timer = System.Diagnostics.Stopwatch.StartNew();
        var responders = new Dictionary<uint, DiscoveredEcu>();
        while (responders.Count < 8)
        {
            token.ThrowIfCancellationRequested();
            var remaining = timeout - timer.Elapsed;
            if (remaining <= TimeSpan.Zero) break;
            IsoTpCanFdSession.CanFrame candidate;
            try { candidate = await link.ReceiveAsync(remaining, token); }
            catch (TimeoutException) { break; }
            var ecu = TryDecode(candidate);
            if (ecu is not null) responders[ecu.ResponseId] = ecu;
        }
        return responders.Values.OrderBy(x => x.ResponseId).ToArray();
    }
}
