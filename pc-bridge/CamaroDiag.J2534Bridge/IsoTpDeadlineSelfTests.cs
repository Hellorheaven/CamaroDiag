namespace CamaroDiag.J2534Bridge;

/// <summary>
/// Simulated slow CAN links demonstrate that ISO-TP timeouts are budgets
/// for the entire operation, not a fresh per-packet allowance.
/// No J2534 device or real vehicle is involved.
/// </summary>
internal static class IsoTpDeadlineSelfTests
{
    private sealed class SlowLink : IsoTpCanFdSession.ICanLink
    {
        internal readonly Queue<IsoTpCanFdSession.CanFrame> Incoming = [];
        internal readonly List<IsoTpCanFdSession.CanFrame> Outgoing = [];
        internal readonly List<TimeSpan> ReceiveTimeouts = [];
        internal int DelayMs = 45;
        public Task SendAsync(IsoTpCanFdSession.CanFrame frame,
            CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            Outgoing.Add(frame);
            return Task.CompletedTask;
        }

        public async Task<IsoTpCanFdSession.CanFrame> ReceiveAsync(
            TimeSpan timeout, CancellationToken token)
        {
            token.ThrowIfCancellationRequested();
            ReceiveTimeouts.Add(timeout);
            // The deliberately nonconforming link returns after the deadline.
            // The ISO-TP session must not accept its late positive answer.
            await Task.Delay(DelayMs, token);
            if (Incoming.Count == 0)
                throw new TimeoutException("No more synthetic CAN frames.");
            return Incoming.Dequeue();
        }
        public Task WaitMinimumSeparationAsync(TimeSpan min,
            CancellationToken token) => Task.CompletedTask;
    }

    internal static int Run()
    {
        var checkedCount = 0;
        void Check(bool result, string message)
        {
            checkedCount++;
            if (!result)
                throw new InvalidOperationException("ISO-TP deadline: " + message);
        }
        void Reject<T>(Action action, string message) where T : Exception
        {
            bool rejected = false;
            try { action(); }
            catch (T) { rejected = true; }
            Check(rejected, message);
        }

        var settings = new IsoTpCanFdSession.Settings(0x7E0, 0x7E8, 8, false);
        var lateSingle = new SlowLink();
        lateSingle.Incoming.Enqueue(new(0x7E8, false,
            [3, 0x62, 0xF1, 0x90, 0, 0, 0, 0]));
        Reject<TimeoutException>(() =>
            IsoTpCanFdSession.ReceiveAsync(lateSingle, settings,
                TimeSpan.FromMilliseconds(15), CancellationToken.None)
                .GetAwaiter().GetResult(),
            "A late single-frame RX cannot be reported as successful.");

        var multi = new SlowLink();
        multi.Incoming.Enqueue(new(0x7E8, false,
            [0x10, 0x13, 1, 2, 3, 4, 5, 6]));
        multi.Incoming.Enqueue(new(0x7E8, false,
            [0x21, 7, 8, 9, 10, 11, 12, 13]));
        multi.Incoming.Enqueue(new(0x7E8, false,
            [0x22, 14, 15, 16, 17, 18, 19, 0]));
        Reject<TimeoutException>(() =>
            IsoTpCanFdSession.ReceiveAsync(multi, settings,
                TimeSpan.FromMilliseconds(65), CancellationToken.None)
                .GetAwaiter().GetResult(),
            "CF reception cannot reset the deadline after FirstFrame.");
        Check(multi.ReceiveTimeouts.Count >= 2 &&
            multi.ReceiveTimeouts[1] < multi.ReceiveTimeouts[0],
            "Subsequent CF receives get a strictly smaller remaining timeout.");
        Check(multi.Outgoing.Count == 1 &&
            multi.Outgoing[0].Data[0] == 0x30,
            "One FC/CTS is emitted after an admitted FirstFrame.");

        var waiting = new SlowLink();
        waiting.Incoming.Enqueue(new(0x7E8, false,
            [0x30, 0, 0, 0, 0, 0, 0, 0]));
        Reject<TimeoutException>(() =>
            IsoTpCanFdSession.SendAsync(waiting, settings,
                Enumerable.Repeat((byte)0x5A, 20).ToArray(),
                TimeSpan.FromMilliseconds(15), CancellationToken.None)
                .GetAwaiter().GetResult(),
            "Late FC after transmit FF cannot authorize further CF frames.");
        Check(waiting.Outgoing.Count == 1 &&
            waiting.Outgoing[0].Data[0] == 0x10,
            "Only FirstFrame was sent before late FC was rejected.");
        return checkedCount;
    }
}
