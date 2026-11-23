using System.Diagnostics;
using System.Globalization;

namespace CamaroDiag.J2534Bridge;

/// <summary>
/// Opt-in host-clock trace of raw ISO-TP CAN frames. This is NOT a hardware
/// timestamp, ECU confirmation or substitute for an independent CAN capture.
/// </summary>
internal sealed class IsoTpHostTraceLink : IsoTpCanFdSession.ICanLink
{
    private readonly IsoTpCanFdSession.ICanLink inner;
    private readonly TextWriter output;
    private readonly Stopwatch clock = Stopwatch.StartNew();

    internal IsoTpHostTraceLink(IsoTpCanFdSession.ICanLink inner, TextWriter output)
    {
        this.inner = inner ?? throw new ArgumentNullException(nameof(inner));
        this.output = output ?? throw new ArgumentNullException(nameof(output));
        output.WriteLine("# CAMARODIAG_ISOTP_HOST_TRACE_V1");
        output.WriteLine("# HOST_CLOCK_NOT_WIRE_TIMESTAMP; may contain VIN/ECU data; keep private.");
        output.WriteLine("elapsed_us,event,can_id,frame_type,dlc_bytes,payload_hex");
    }

    private void Record(string eventName, IsoTpCanFdSession.CanFrame frame)
    {
        output.WriteLine(string.Join(",",
            (clock.Elapsed.Ticks / 10).ToString(CultureInfo.InvariantCulture),
            eventName,
            "0x" + frame.Id.ToString("X8", CultureInfo.InvariantCulture),
            frame.IsFd ? "CAN-FD" : "CAN",
            frame.Data.Length.ToString(CultureInfo.InvariantCulture),
            Convert.ToHexString(frame.Data)));
    }

    public async Task SendAsync(IsoTpCanFdSession.CanFrame frame,
        CancellationToken token)
    {
        token.ThrowIfCancellationRequested();
        // A log failure before this call prevents sending an unrecorded frame.
        Record("TX_REQUEST", frame);
        await inner.SendAsync(frame, token);
        // A log failure after queuing cannot undo transmission. No auto retry.
        Record("TX_QUEUED", frame);
    }

    public async Task<IsoTpCanFdSession.CanFrame> ReceiveAsync(
        TimeSpan timeout, CancellationToken token)
    {
        token.ThrowIfCancellationRequested();
        var frame = await inner.ReceiveAsync(timeout, token);
        Record("RX", frame);
        return frame;
    }

    public Task WaitMinimumSeparationAsync(TimeSpan minimum,
        CancellationToken token) =>
        inner.WaitMinimumSeparationAsync(minimum, token);
}
