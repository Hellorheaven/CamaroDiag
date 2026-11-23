namespace CamaroDiag.J2534Bridge;

internal static class ObdOnUdsCanDiscoverySelfTests
{
    private sealed class FakeCan : IsoTpCanFdSession.ICanLink
    {
        internal readonly Queue<IsoTpCanFdSession.CanFrame> Incoming = new();
        internal readonly List<IsoTpCanFdSession.CanFrame> Sent = [];
        public Task SendAsync(IsoTpCanFdSession.CanFrame frame, CancellationToken token)
        {
            token.ThrowIfCancellationRequested();
            Sent.Add(frame);
            return Task.CompletedTask;
        }
        public Task<IsoTpCanFdSession.CanFrame> ReceiveAsync(
            TimeSpan timeout, CancellationToken token)
        {
            token.ThrowIfCancellationRequested();
            if (Incoming.Count == 0)
                throw new TimeoutException("No fake OBDonUDS ECU response.");
            return Task.FromResult(Incoming.Dequeue());
        }
        public Task WaitMinimumSeparationAsync(
            TimeSpan minimum, CancellationToken token) => Task.CompletedTask;
    }

    internal static int Run()
    {
        var checks = 0;
        void Check(bool condition, string label)
        {
            checks++;
            if (!condition) throw new InvalidOperationException(
                "OBDonUDS functional CAN test: " + label);
        }
        static IsoTpCanFdSession.CanFrame Rx(uint id,
            params byte[] bytes) => new(id, false, bytes);

        var request = ObdOnUdsCanDiscovery.BuildRequest(0xCC);
        Check(request.Id == 0x7DF && !request.IsFd &&
            request.Data.SequenceEqual(new byte[]
                {3, 0x22, 0xF8, 0x10, 0xCC, 0xCC, 0xCC, 0xCC}),
            "ISO15765-4 functional F810 request uses 7DF and classical 8-byte SF.");
        Check(ObdOnUdsCanDiscovery.TryDecode(Rx(0x7E8,
                4, 0x62, 0xF8, 0x10, 1, 0, 0, 0))?.IsObdOnUds == true,
            "OBDonUDS positive physical F810 ECU response accepted.");
        Check(ObdOnUdsCanDiscovery.TryDecode(Rx(0x7EF,
                4, 0x62, 0xF8, 0x10, 0, 0, 0, 0))?.IsObdOnUds == false,
            "Protocol value other than 1 is never considered confirmed support.");
        Check(ObdOnUdsCanDiscovery.TryDecode(Rx(0x7E7,
                4, 0x62, 0xF8, 0x10, 1, 0, 0, 0)) is null,
            "Unknown arbitration ID cannot impersonate OBD responder.");
        Check(ObdOnUdsCanDiscovery.TryDecode(Rx(0x7E8,
                4, 0x62, 0xF8, 0x11, 1, 0, 0, 0)) is null,
            "Wrong DID cannot satisfy F810 detection.");
        Check(ObdOnUdsCanDiscovery.TryDecode(new(0x7E8, true,
                [4, 0x62, 0xF8, 0x10, 1, 0, 0, 0])) is null,
            "Classic OBD ISO-TP discovery must not mistake FD frames.");

        var bus = new FakeCan();
        bus.Incoming.Enqueue(Rx(0x123, 1, 2, 3, 4, 5, 6, 7, 8));
        bus.Incoming.Enqueue(Rx(0x7E8, 4, 0x62, 0xF8, 0x10, 1, 0, 0, 0));
        bus.Incoming.Enqueue(Rx(0x7E9, 4, 0x62, 0xF8, 0x10, 1, 0, 0, 0));
        bus.Incoming.Enqueue(Rx(0x7E8, 4, 0x62, 0xF8, 0x10, 1, 0, 0, 0));
        var found = ObdOnUdsCanDiscovery.DiscoverAsync(bus,
            TimeSpan.FromSeconds(1), CancellationToken.None)
            .GetAwaiter().GetResult();
        Check(bus.Sent.Count == 1 &&
            bus.Sent[0].Data.SequenceEqual(request.Data) &&
            found.Count == 2 &&
            found[0].ResponseId == 0x7E8 &&
            found[1].ResponseId == 0x7E9,
            "One functional request discovers multiple distinct ECUs without duplicates.");
        var noResponse = new FakeCan();
        Check(ObdOnUdsCanDiscovery.DiscoverAsync(noResponse,
                TimeSpan.FromSeconds(1), CancellationToken.None)
                .GetAwaiter().GetResult().Count == 0 &&
            noResponse.Sent.Count == 1,
            "No positive replies never invent protocol compliance.");
        var canOptions = StandardProtocolCli.ParseObdCanDiscovery([
            StandardProtocolCli.ObdCanDiscoverCommand,
            "--adapter-index", "0", "--baud", "500000",
            "--timeout-ms", "1800"]);
        Check(canOptions.AdapterIndex == 0 && canOptions.Baud == 500000 &&
            canOptions.TimeoutMs == 1800,
            "Functional OBDonUDS CLI selects vendor CAN channel and bounded timeout.");
        void Reject<T>(Action action, string label) where T : Exception
        {
            var denied = false;
            try { action(); }
            catch (T) { denied = true; }
            Check(denied, label);
        }
        Reject<ArgumentException>(() => StandardProtocolCli.ParseObdCanDiscovery([
            StandardProtocolCli.ObdCanDiscoverCommand,
            "--adapter-index", "0", "--baud", "500000",
            "--payload", "14FFFFFF"]),
            "Fixed F810 discovery cannot be repurposed for a mutating UDS service.");
        Reject<ArgumentException>(() => StandardProtocolCli.ParseObdCanDiscovery([
            StandardProtocolCli.ObdCanDiscoverCommand,
            "--adapter-index", "0", "--baud", "500000",
            "--timeout-ms", "30001"]),
            "Functional CAN discovery always has a bounded observation interval.");

        return checks;
    }
}
