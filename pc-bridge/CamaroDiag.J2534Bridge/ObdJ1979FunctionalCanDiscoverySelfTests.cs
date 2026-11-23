namespace CamaroDiag.J2534Bridge;

internal static class ObdJ1979FunctionalCanDiscoverySelfTests
{
    private sealed class FakeCan : IsoTpCanFdSession.ICanLink
    {
        internal readonly Queue<IsoTpCanFdSession.CanFrame> Incoming = new();
        internal readonly List<IsoTpCanFdSession.CanFrame> Outgoing = [];
        public Task SendAsync(IsoTpCanFdSession.CanFrame frame,
            CancellationToken token)
        {
            token.ThrowIfCancellationRequested();
            Outgoing.Add(frame);
            return Task.CompletedTask;
        }
        public Task<IsoTpCanFdSession.CanFrame> ReceiveAsync(
            TimeSpan timeout, CancellationToken token)
        {
            token.ThrowIfCancellationRequested();
            if (Incoming.Count == 0) throw new TimeoutException("No mock ECU.");
            return Task.FromResult(Incoming.Dequeue());
        }
        public Task WaitMinimumSeparationAsync(
            TimeSpan minimum, CancellationToken token) => Task.CompletedTask;
    }

    internal static int Run()
    {
        var count = 0;
        void Check(bool ok, string label)
        {
            count++;
            if (!ok) throw new InvalidOperationException("J1979 functional CAN: " + label);
        }
        var request = ObdJ1979FunctionalCanDiscovery.BuildRequest();
        Check(!request.IsFd && request.Id == 0x7DF &&
            request.Data.SequenceEqual(new byte[] {2, 1, 0, 0xCC, 0xCC, 0xCC, 0xCC, 0xCC}),
            "Single fixed functional request only.");
        static IsoTpCanFdSession.CanFrame Rx(uint id, params byte[] values) =>
            new(id, false, values);
        var sample = Rx(0x7E8, 6, 0x41, 0, 0x80, 0, 0, 1, 0xCC);
        var parsed = ObdJ1979FunctionalCanDiscovery.TryDecode(sample);
        Check(parsed is { ResponseId: 0x7E8, NextPage: true } &&
            parsed.SupportedPids.SequenceEqual(new byte[] { 1, 0x20 }),
            "Correlated PID page for physical responder.");
        Check(ObdJ1979FunctionalCanDiscovery.TryDecode(Rx(0x7E7,
            6, 0x41, 0, 0x80, 0, 0, 1, 0)) is null,
            "Wrong CAN response ID ignored.");
        Check(ObdJ1979FunctionalCanDiscovery.TryDecode(Rx(0x7E8,
            6, 0x41, 0x20, 0x80, 0, 0, 1, 0)) is null,
            "Unrequested PID page ignored.");
        Check(ObdJ1979FunctionalCanDiscovery.TryDecode(Rx(0x7E8,
            3, 0x7F, 1, 0x12, 0, 0, 0, 0)) is null,
            "Negative response does not claim PID support.");
        Check(ObdJ1979FunctionalCanDiscovery.TryDecode(new(0x7E8, true,
            [6, 0x41, 0, 0x80, 0, 0, 1, 0])) is null,
            "CAN FD cannot be mistaken for classic functional OBD.");
        var bus = new FakeCan();
        bus.Incoming.Enqueue(Rx(0x123, 6, 0x41, 0, 0x80, 0, 0, 1, 0));
        bus.Incoming.Enqueue(sample);
        bus.Incoming.Enqueue(Rx(0x7E9, 6, 0x41, 0, 0x40, 0, 0, 0, 0));
        bus.Incoming.Enqueue(sample);
        var responders = ObdJ1979FunctionalCanDiscovery.DiscoverAsync(
            bus, TimeSpan.FromSeconds(1), CancellationToken.None)
            .GetAwaiter().GetResult();
        Check(bus.Outgoing.Count == 1 &&
            bus.Outgoing[0].Data.SequenceEqual(request.Data) &&
            responders.Count == 2 && responders[0].ResponseId == 0x7E8 &&
            responders[1].ResponseId == 0x7E9,
            "Single request finds multiple ECUs and deduplicates traffic.");
        var silent = new FakeCan();
        Check(ObdJ1979FunctionalCanDiscovery.DiscoverAsync(
            silent, TimeSpan.FromSeconds(1), CancellationToken.None)
            .GetAwaiter().GetResult().Count == 0,
            "Silent bus produces no invented ECU.");
        using var cancellation = new CancellationTokenSource();
        cancellation.Cancel();
        var stopped = new FakeCan();
        bool cancelled = false;
        try
        {
            _ = ObdJ1979FunctionalCanDiscovery.DiscoverAsync(
                stopped, TimeSpan.FromSeconds(1), cancellation.Token)
                .GetAwaiter().GetResult();
        }
        catch (OperationCanceledException) { cancelled = true; }
        Check(cancelled && stopped.Outgoing.Count == 0,
            "Precancelled scan does not transmit.");
        return count;
    }
}
