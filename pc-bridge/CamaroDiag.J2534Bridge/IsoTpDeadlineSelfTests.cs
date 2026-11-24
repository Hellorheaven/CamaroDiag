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

        var remaining = StandardProtocolCli.RemainingIsoTpExchangeBudget(
            TimeSpan.FromMilliseconds(100), TimeSpan.FromMilliseconds(40));
        Check(remaining == TimeSpan.FromMilliseconds(60),
            "CLI RX budget is reduced by time spent sending request.");
        Reject<TimeoutException>(() =>
            StandardProtocolCli.RemainingIsoTpExchangeBudget(
                TimeSpan.FromMilliseconds(100), TimeSpan.FromMilliseconds(100)),
            "No RX phase may start after full TX deadline is consumed.");
        Reject<TimeoutException>(() =>
            StandardProtocolCli.RemainingIsoTpExchangeBudget(
                TimeSpan.FromMilliseconds(100), TimeSpan.FromMilliseconds(120)),
            "A response that exceeds the TX/RX deadline is never reported as success.");

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

        // A busy bus may contain more than 64 unrelated CAN frames before
        // the reply. Overall time, not an arbitrary noise-frame threshold,
        // determines whether this transaction is allowed to continue.
        var busy = new SlowLink { DelayMs = 0 };
        for (var i = 0; i < 96; i++)
            busy.Incoming.Enqueue(new(0x123, false,
                [3, 0x62, 0xF1, 0x90, 0, 0, 0, 0]));
        busy.Incoming.Enqueue(new(0x7E8, false,
            [3, 0x62, 0xF1, 0x90, 0, 0, 0, 0]));
        var busyResult = IsoTpCanFdSession.ReceiveAsync(busy, settings,
            TimeSpan.FromSeconds(1), CancellationToken.None)
            .GetAwaiter().GetResult();
        Check(busyResult.SequenceEqual(new byte[] { 0x62, 0xF1, 0x90 }) &&
            busy.ReceiveTimeouts.Count == 97,
            "ISO-TP no longer loses valid RX after 64 unrelated CAN packets.");

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
        var flowTimer = new SlowLink { DelayMs = 45 };
        flowTimer.Incoming.Enqueue(new(0x7E8, false,
            [0x30, 0, 0, 0, 0, 0, 0, 0]));
        Reject<TimeoutException>(() =>
            IsoTpCanFdSession.SendAsync(flowTimer,
                settings with { FlowControlTimeoutMs = 15 },
                Enumerable.Repeat((byte)0x55, 20).ToArray(),
                TimeSpan.FromSeconds(1), CancellationToken.None)
                .GetAwaiter().GetResult(),
            "N_Bs expires independently of longer overall TX deadline.");
        Check(flowTimer.Outgoing.Count == 1 &&
            flowTimer.Outgoing[0].Data[0] == 0x10 &&
            flowTimer.ReceiveTimeouts[0] <= TimeSpan.FromMilliseconds(15),
            "N_Bs expiry never authorizes a subsequent CF.");

        var consecutiveTimer = new SlowLink { DelayMs = 45 };
        consecutiveTimer.Incoming.Enqueue(new(0x7E8, false,
            [0x10, 0x13, 1, 2, 3, 4, 5, 6]));
        consecutiveTimer.Incoming.Enqueue(new(0x7E8, false,
            [0x21, 7, 8, 9, 10, 11, 12, 13]));
        Reject<TimeoutException>(() =>
            IsoTpCanFdSession.ReceiveAsync(consecutiveTimer,
                settings with { ConsecutiveFrameTimeoutMs = 15 },
                TimeSpan.FromSeconds(1), CancellationToken.None)
                .GetAwaiter().GetResult(),
            "N_Cr expires independently of longer overall RX deadline.");
        Check(consecutiveTimer.Outgoing.Count == 1 &&
            consecutiveTimer.Outgoing[0].Data[0] == 0x30 &&
            consecutiveTimer.ReceiveTimeouts.Count == 2 &&
            consecutiveTimer.ReceiveTimeouts[1] <= TimeSpan.FromMilliseconds(15),
            "N_Cr times only the next CF after a valid FF/CTS.");

        Reject<ArgumentOutOfRangeException>(() =>
            (settings with { FlowControlTimeoutMs = 0 }).Validate(),
            "N_Bs zero is not an unlimited timeout.");
        Reject<ArgumentOutOfRangeException>(() =>
            (settings with { ConsecutiveFrameTimeoutMs = 30001 }).Validate(),
            "N_Cr out of range rejected.");
        var configured = StandardProtocolCli.ParseIsoTp([
            StandardProtocolCli.RawIsoTpCommand, "--adapter-index", "0",
            "--tx-id", "7E0", "--rx-id", "7E8", "--baud", "500000",
            "--fd", "false", "--n-bs-ms", "150", "--n-cr-ms", "225",
            "--payload", "22F190"
        ]);
        Check(configured.Settings.FlowControlTimeoutMs == 150 &&
            configured.Settings.ConsecutiveFrameTimeoutMs == 225,
            "CAN transport CLI sets independent N_Bs/N_Cr timers.");


        var udsTimed = StandardProtocolCli.ParseIsoTp([
            StandardProtocolCli.RawIsoTpCommand, "--adapter-index", "0",
            "--tx-id", "7E0", "--rx-id", "7E8", "--baud", "500000",
            "--fd", "false", "--uds-validate", "true",
            "--uds-p2-ms", "20", "--uds-p2-star-ms", "150",
            "--payload", "22F190"
        ]);
        Check(udsTimed.ValidateUds && udsTimed.UdsP2Ms == 20 &&
            udsTimed.UdsP2StarMs == 150,
            "UDS P2/P2* CLI is forwarded to validated CAN exchange.");
        Check(configured.UdsP2Ms == 30000 &&
            configured.UdsP2StarMs == 30000,
            "Default P2/P2* leaves historical host timeout compatibility.");
        Reject<ArgumentOutOfRangeException>(() =>
            StandardProtocolCli.ParseIsoTp([
                StandardProtocolCli.RawIsoTpCommand, "--adapter-index", "0",
                "--tx-id", "7E0", "--rx-id", "7E8", "--baud", "500000",
                "--fd", "false", "--uds-validate", "true",
                "--uds-p2-ms", "0", "--payload", "22F190"
            ]), "Zero P2 timeout is not accepted.");
        Reject<ArgumentException>(() =>
            StandardProtocolCli.ParseIsoTp([
                StandardProtocolCli.RawIsoTpCommand, "--adapter-index", "0",
                "--tx-id", "7E0", "--rx-id", "7E8", "--baud", "500000",
                "--fd", "false", "--uds-p2-star-ms", "150",
                "--payload", "22F190"
            ]), "P2* options may not silently apply to unvalidated raw exchange.");

        return checkedCount;
    }
}
