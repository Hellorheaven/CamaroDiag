namespace CamaroDiag.J2534Bridge;

/// <summary>Offline J1939-81 NAME arbitration with a simulated raw CAN link.</summary>
internal static class J1939AddressClaimSessionSelfTests
{
    private sealed class FakeCan : IsoTpCanFdSession.ICanLink
    {
        internal readonly Queue<IsoTpCanFdSession.CanFrame> RX = new();
        internal readonly List<IsoTpCanFdSession.CanFrame> TX = [];
        public Task SendAsync(IsoTpCanFdSession.CanFrame frame,
            CancellationToken token)
        {
            token.ThrowIfCancellationRequested();
            TX.Add(new(frame.Id, frame.IsFd, frame.Data.ToArray()));
            return Task.CompletedTask;
        }
        public Task<IsoTpCanFdSession.CanFrame> ReceiveAsync(
            TimeSpan timeout, CancellationToken token)
        {
            token.ThrowIfCancellationRequested();
            if (RX.Count == 0)
                throw new TimeoutException("Fake J1939-81 input empty.");
            return Task.FromResult(RX.Dequeue());
        }
        public Task WaitMinimumSeparationAsync(TimeSpan minimum,
            CancellationToken token) => Task.CompletedTask;
    }

    internal static int Run()
    {
        var checks = 0;
        void Check(bool result, string label)
        {
            checks++;
            if (!result) throw new InvalidOperationException(
                "J1939-81 address claim: " + label);
        }
        void Reject<T>(Action action, string label) where T : Exception
        {
            var rejected = false;
            try { action(); }
            catch (T) { rejected = true; }
            Check(rejected, label);
        }
        static J1939AddressClaimSession.Outcome Claim(
            FakeCan link, ulong name, bool fd = false,
            byte address = 0xF1) =>
            J1939AddressClaimSession.ClaimAsync(link, address, name, fd,
                CancellationToken.None).GetAwaiter().GetResult();

        const ulong localName = 0x8000000200000001;
        var idle = new FakeCan();
        var watch = System.Diagnostics.Stopwatch.StartNew();
        var result = Claim(idle, localName);
        Check(result == J1939AddressClaimSession.Outcome.Claimed &&
            idle.TX.Count == 1 && !idle.TX[0].IsFd &&
            watch.Elapsed >= TimeSpan.FromMilliseconds(240),
            "Claim waits for 250ms contention interval before allowing PGN TX.");

        var lowerName = new FakeCan();
        lowerName.RX.Enqueue(J1939AddressClaim.Build(0xF1,
            localName - 1));
        result = Claim(lowerName, localName);
        Check(result ==
            J1939AddressClaimSession.Outcome.LostToHigherPriorityName &&
            lowerName.TX.Count == 2 &&
            StandardVehicleProtocols.ParseJ1939Id(
                lowerName.TX[1].Id).Source == 0xFE,
            "Competing lower NAME wins; loser advertises Cannot Claim from 0xFE.");

        var stronger = new FakeCan();
        stronger.RX.Enqueue(J1939AddressClaim.Build(0xF1,
            localName + 1) with { IsFd = true });
        result = Claim(stronger, localName, fd: true);
        Check(result == J1939AddressClaimSession.Outcome.Claimed &&
            stronger.TX.Count == 2 &&
            stronger.TX.All(t => t.IsFd) &&
            stronger.TX[0].Id == stronger.TX[1].Id,
            "Strongest local NAME reasserts the same FD claim once.");

        var unrelated = new FakeCan();
        unrelated.RX.Enqueue(J1939AddressClaim.Build(0xF2,
            localName - 1));
        unrelated.RX.Enqueue(J1939AddressClaim.Build(0xF1, localName));
        result = Claim(unrelated, localName);
        Check(result == J1939AddressClaimSession.Outcome.Claimed &&
            unrelated.TX.Count == 1,
            "Unrelated addresses and possible adapter self-echo do not cause false collisions.");

        var addressRequest = new FakeCan();
        addressRequest.RX.Enqueue(new(
            J1939ClassicTpSession.ArbitrationId(
                J1939PgnRequest.RequestPgn, 6, 0x10, 0xFF),
            false, [0x00, 0xEE, 0x00]));
        result = Claim(addressRequest, localName);
        Check(result == J1939AddressClaimSession.Outcome.Claimed &&
            addressRequest.TX.Count == 2 &&
            addressRequest.TX[0].Data.SequenceEqual(
                addressRequest.TX[1].Data),
            "J1939-81 claim answers a global request for PGN 60928.");
        var unrelatedRequest = new FakeCan();
        unrelatedRequest.RX.Enqueue(new(
            J1939ClassicTpSession.ArbitrationId(
                J1939PgnRequest.RequestPgn, 6, 0x10, 0xFF),
            false, [0xCA, 0xFE, 0x00]));
        result = Claim(unrelatedRequest, localName);
        Check(result == J1939AddressClaimSession.Outcome.Claimed &&
            unrelatedRequest.TX.Count == 1,
            "J1939-81 does not treat a DM1 Request as an address enquiry.");

        var aborted = new FakeCan();
        using var canceled = new CancellationTokenSource();
        canceled.Cancel();
        Reject<OperationCanceledException>(() =>
            J1939AddressClaimSession.ClaimAsync(
                aborted, 0xF1, localName, false,
                canceled.Token).GetAwaiter().GetResult(),
            "Canceled J1939-81 claim does not send frames.");
        Check(aborted.TX.Count == 0, "No TX follows cancellation.");

        Reject<ArgumentException>(() => Claim(new FakeCan(),
            ulong.MaxValue), "All-FF NAME is never used for a claim.");
        Reject<ArgumentException>(() => Claim(new FakeCan(),
            localName, address: 0xFE),
            "Null source address cannot become a claimable address.");
        return checks;
    }
}
