namespace CamaroDiag.J2534Bridge;

/// <summary>Offline CAN 11-bit two-stage protocol detection vectors.</summary>
internal static class ObdCanProtocolDetectionSelfTests
{
    private sealed class FakeCan : IsoTpCanFdSession.ICanLink
    {
        internal bool ReplyUds;
        internal bool ReplyClassic;
        internal bool UdsValueZero;
        internal readonly List<IsoTpCanFdSession.CanFrame> Sent = [];
        private readonly Queue<IsoTpCanFdSession.CanFrame> pending = new();

        public Task SendAsync(IsoTpCanFdSession.CanFrame frame,
            CancellationToken token)
        {
            token.ThrowIfCancellationRequested();
            Sent.Add(frame);
            if (frame.Id != 0x7DF || frame.IsFd)
                throw new InvalidOperationException("OBD protocol probe must use classic functional CAN.");
            if (frame.Data.Take(4).SequenceEqual(new byte[] { 3, 0x22, 0xF8, 0x10 }))
            {
                if (ReplyUds)
                    pending.Enqueue(new(0x7E8, false,
                        [4, 0x62, 0xF8, 0x10, UdsValueZero ? (byte)0 : (byte)1,
                            0xCC, 0xCC, 0xCC]));
            }
            else if (frame.Data.Take(3).SequenceEqual(new byte[] { 2, 1, 0 }))
            {
                if (ReplyClassic)
                    pending.Enqueue(new(0x7E9, false,
                        [6, 0x41, 0, 0x80, 0, 0, 0x01, 0xCC]));
            }
            else throw new InvalidOperationException("Unexpected OBD detection request.");
            return Task.CompletedTask;
        }

        public Task<IsoTpCanFdSession.CanFrame> ReceiveAsync(
            TimeSpan timeout, CancellationToken token)
        {
            token.ThrowIfCancellationRequested();
            if (pending.Count == 0) throw new TimeoutException("No mock ECU reply");
            return Task.FromResult(pending.Dequeue());
        }

        public Task WaitMinimumSeparationAsync(
            TimeSpan minimum, CancellationToken token) => Task.CompletedTask;
    }

    internal static int Run()
    {
        int checks = 0;
        void Check(bool value, string description)
        {
            checks++;
            if (!value)
                throw new InvalidOperationException("OBD protocol auto detection: " + description);
        }
        static ObdCanProtocolDetection.Detection Detect(FakeCan link) =>
            ObdCanProtocolDetection.DetectAsync(link,
                TimeSpan.FromSeconds(1), CancellationToken.None)
                .GetAwaiter().GetResult();

        var uds = new FakeCan { ReplyUds = true, ReplyClassic = true };
        var udsResult = Detect(uds);
        Check(udsResult.Kind == ObdCanProtocolDetection.ProtocolKind.ObdOnUds &&
            udsResult.ResponseIds.SequenceEqual(new uint[] { 0x7E8 }) &&
            !udsResult.ClassicFallbackAttempted &&
            uds.Sent.Count == 1, "Positive F810 chooses UDS without classic fallback.");

        var classic = new FakeCan { ReplyClassic = true };
        var classicResult = Detect(classic);
        Check(classicResult.Kind == ObdCanProtocolDetection.ProtocolKind.ClassicJ1979 &&
            classicResult.ResponseIds.SequenceEqual(new uint[] { 0x7E9 }) &&
            classicResult.ClassicFallbackAttempted &&
            classic.Sent.Count == 2,
            "No UDS detection triggers classic Mode 01/00 fallback only.");

        var unsupported = new FakeCan {
            ReplyUds = true, UdsValueZero = true, ReplyClassic = true
        };
        Check(Detect(unsupported).Kind ==
            ObdCanProtocolDetection.ProtocolKind.ClassicJ1979 &&
            unsupported.Sent.Count == 2,
            "F810 protocol value 0 does not claim OBDonUDS support.");

        var silent = new FakeCan();
        var none = Detect(silent);
        Check(none.Kind == ObdCanProtocolDetection.ProtocolKind.NoConfirmedProtocol &&
            none.ResponseIds.Count == 0 && none.ClassicFallbackAttempted &&
            silent.Sent.Count == 2,
            "No replies cannot be reported as OBD classic or UDS.");

        using var stop = new CancellationTokenSource();
        stop.Cancel();
        var aborted = new FakeCan();
        var cancelled = false;
        try
        {
            _ = ObdCanProtocolDetection.DetectAsync(aborted,
                TimeSpan.FromSeconds(1), stop.Token)
                .GetAwaiter().GetResult();
        }
        catch (OperationCanceledException) { cancelled = true; }
        Check(cancelled && aborted.Sent.Count == 0,
            "Pre-cancelled scan does not transmit.");
        return checks;
    }
}
