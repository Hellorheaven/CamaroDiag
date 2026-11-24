using System.Diagnostics;

namespace CamaroDiag.J2534Bridge;

/// <summary>
/// Sequential SAE J1979 Mode 01/02 supported-PID discovery for one physically
/// addressed ECU over an existing ISO-TP CAN link. Does not broadcast, infer
/// PID scaling, perform any mutation, or automatically try missing ECUs.
/// </summary>
internal static class ObdJ1979PidDiscovery
{
    internal sealed record Discovery(byte Mode, byte FreezeFrame,
        IReadOnlyList<ObdJ1979PidSupport.Page> Pages,
        IReadOnlyList<byte> SupportedPids);

    internal static async Task<Discovery> DiscoverAsync(
        IsoTpCanFdSession.ICanLink link,
        IsoTpCanFdSession.Settings settings,
        byte mode, byte freezeFrame,
        TimeSpan timeout, CancellationToken token)
    {
        ArgumentNullException.ThrowIfNull(link);
        if (mode is not (0x01 or 0x02))
            throw new ArgumentException("Discovery requires J1979 mode 01 or 02.", nameof(mode));
        if (timeout <= TimeSpan.Zero)
            throw new ArgumentOutOfRangeException(nameof(timeout));
        settings.Validate();

        var pages = new List<ObdJ1979PidSupport.Page>(8);
        var pids = new List<byte>();
        var watch = Stopwatch.StartNew();
        for (var index = 0; index < 8; index++)
        {
            token.ThrowIfCancellationRequested();
            var remaining = timeout - watch.Elapsed;
            if (remaining <= TimeSpan.Zero)
                throw new TimeoutException("SAE J1979 PID discovery timed out.");
            var request = ObdJ1979PidSupport.BuildRequest(
                mode, checked((byte)(index * 0x20)), freezeFrame);
            await IsoTpCanFdSession.SendAsync(link, settings, request,
                remaining, token);
            remaining = timeout - watch.Elapsed;
            if (remaining <= TimeSpan.Zero)
                throw new TimeoutException("SAE J1979 PID discovery timed out after TX.");
            var response = await IsoTpCanFdSession.ReceiveAsync(
                link, settings, remaining, token);
            var page = ObdJ1979PidSupport.Decode(request, response);
            pages.Add(page);
            pids.AddRange(page.SupportedPids);
            if (!page.HasNextPage)
                return new Discovery(mode, freezeFrame, pages, pids);
        }

        // All eight possible pages were read. A bit beyond FF is invalid
        // and is rejected by ObdJ1979PidSupport, not wrapped into PID 00.
        return new Discovery(mode, freezeFrame, pages, pids);
    }
}
