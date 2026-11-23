namespace CamaroDiag.J2534Bridge;

/// <summary>
/// ISO 15765-4 OBDonUDS functional 11-bit CAN discovery.
/// Sends only standardized read DID F810 (0x22 F8 10) in a single CAN frame
/// to functional arbitration ID 0x7DF. Accepts physical replies 0x7E8..7EF.
/// Does not infer programming access, replace OEM discovery, or send writes.
/// </summary>
internal static class ObdOnUdsCanDiscovery
{
    internal const uint FunctionalRequestId = 0x7DF;
    internal const uint FirstResponseId = 0x7E8;
    internal const uint LastResponseId = 0x7EF;

    internal sealed record IdentifiedEcu(uint ResponseId, byte ProtocolValue)
    {
        internal bool IsObdOnUds => ProtocolValue == 0x01;
    }

    internal static IsoTpCanFdSession.CanFrame BuildRequest(byte padding = 0xCC)
    {
        var bytes = Enumerable.Repeat(padding, 8).ToArray();
        bytes[0] = 3;
        bytes[1] = 0x22;
        bytes[2] = 0xF8;
        bytes[3] = 0x10;
        return new(FunctionalRequestId, false, bytes);
    }

    internal static IdentifiedEcu? TryDecode(
        IsoTpCanFdSession.CanFrame frame)
    {
        if (frame.IsFd || frame.Id < FirstResponseId ||
            frame.Id > LastResponseId ||
            frame.Data is null || frame.Data.Length != 8)
            return null;
        var data = frame.Data;
        // Functional identification must produce one standard ISO-TP SF.
        if (data[0] != 4 || data[1] != 0x62 ||
            data[2] != 0xF8 || data[3] != 0x10)
            return null;
        return new IdentifiedEcu(frame.Id, data[4]);
    }

    internal static async Task<IReadOnlyList<IdentifiedEcu>> DiscoverAsync(
        IsoTpCanFdSession.ICanLink link, TimeSpan timeout,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(link);
        if (timeout <= TimeSpan.Zero || timeout > TimeSpan.FromSeconds(30))
            throw new ArgumentOutOfRangeException(nameof(timeout));
        await link.SendAsync(BuildRequest(), cancellationToken);
        var clock = System.Diagnostics.Stopwatch.StartNew();
        var identities = new Dictionary<uint, IdentifiedEcu>();
        while (identities.Count < 8)
        {
            cancellationToken.ThrowIfCancellationRequested();
            var remaining = timeout - clock.Elapsed;
            if (remaining <= TimeSpan.Zero) break;
            IsoTpCanFdSession.CanFrame frame;
            try
            {
                frame = await link.ReceiveAsync(remaining, cancellationToken);
            }
            catch (TimeoutException)
            {
                break;
            }
            var found = TryDecode(frame);
            if (found is null) continue;
            identities[found.ResponseId] = found;
        }
        return identities.Values.OrderBy(x => x.ResponseId).ToArray();
    }
}
