namespace CamaroDiag.J2534Bridge;

/// <summary>No hardware, vendor library or live J1939 node is exercised.</summary>
internal static class J1939FdBamSelfTests
{
    private sealed class FakeLink : IsoTpCanFdSession.ICanLink
    {
        internal readonly Queue<IsoTpCanFdSession.CanFrame> Incoming = new();
        internal readonly List<IsoTpCanFdSession.CanFrame> Outgoing = [];
        internal readonly List<TimeSpan> Delays = [];
        internal readonly List<TimeSpan> ReceiveTimeouts = [];
        public Task SendAsync(IsoTpCanFdSession.CanFrame frame, CancellationToken token)
        {
            token.ThrowIfCancellationRequested();
            Outgoing.Add(new(frame.Id, frame.IsFd, frame.Data.ToArray()));
            return Task.CompletedTask;
        }
        public Task<IsoTpCanFdSession.CanFrame> ReceiveAsync(
            TimeSpan timeout, CancellationToken token)
        {
            token.ThrowIfCancellationRequested();
            ReceiveTimeouts.Add(timeout);
            if (Incoming.Count == 0)
                throw new TimeoutException("Fake J1939-22 BAM input exhausted.");
            return Task.FromResult(Incoming.Dequeue());
        }
        public Task WaitMinimumSeparationAsync(TimeSpan minimum, CancellationToken token)
        {
            token.ThrowIfCancellationRequested();
            Delays.Add(minimum);
            return Task.CompletedTask;
        }
    }

    internal static int Run()
    {
        var checkedCount = 0;
        void Check(bool ok, string description)
        {
            checkedCount++;
            if (!ok) throw new InvalidOperationException(
                "J1939-22 BAM failed: " + description);
        }
        void Reject<T>(Action action, string description) where T : Exception
        {
            var rejected = false;
            try { action(); }
            catch (T) { rejected = true; }
            Check(rejected, description);
        }
        static byte[] Fill(int n) => Enumerable.Range(0, n)
            .Select(i => (byte)(i & 0xFF)).ToArray();
        static void Send(FakeLink link, byte[] payload) =>
            J1939FdBamSession.SendAsync(link, 0xF1, 0xF004, 2,
                payload, CancellationToken.None).GetAwaiter().GetResult();
        static byte[] Receive(FakeLink link) =>
            J1939FdBamSession.ReceiveAsync(link, 0xF1, 0xF004,
                TimeSpan.FromSeconds(1), CancellationToken.None)
                .GetAwaiter().GetResult();

        var sender = new FakeLink();
        Send(sender, Fill(131));
        Check(sender.Outgoing.Count == 5 &&
            sender.Outgoing.All(f => f.IsFd) &&
            sender.Outgoing[0].Data.Length == 12 &&
            sender.Outgoing[0].Data[0] == 0x24 &&
            sender.Outgoing[^1].Data[0] == 0x22,
            "FD.TP BAM, three DT packets and EOM_STATUS are sent.");
        Check(sender.Delays.Count == 3 &&
            sender.Delays.All(d => d == TimeSpan.FromMilliseconds(10)),
            "J1939-22 broadcast sends no unpaced DT burst.");
        Check(sender.Outgoing[1].Data.Length == 64 &&
            sender.Outgoing[2].Data.Length == 64 &&
            sender.Outgoing[3].Data.Length == 16,
            "BAM 131-byte payload segments into CAN FD 60+60+11 bytes.");

        var receiver = new FakeLink();
        receiver.Incoming.Enqueue(new(0x18F00400, false, Fill(8)));
        foreach (var frame in sender.Outgoing)
            receiver.Incoming.Enqueue(frame);
        Check(Receive(receiver).SequenceEqual(Fill(131)) &&
            receiver.Outgoing.Count == 0,
            "FD.TP broadcast round-trip retains exact data and requires no CTS.");
        Check(receiver.ReceiveTimeouts.Count == 5 &&
            receiver.ReceiveTimeouts[0] <= TimeSpan.FromSeconds(1) &&
            receiver.ReceiveTimeouts.Skip(1).All(t =>
                t > TimeSpan.Zero && t <= TimeSpan.FromMilliseconds(750)),
            "BAM receive sends a bounded 750ms phase timeout to the CAN adapter.");

        var badSequence = new FakeLink();
        foreach (var frame in sender.Outgoing)
            badSequence.Incoming.Enqueue(new(frame.Id, frame.IsFd,
                frame.Data.ToArray()));
        var queued = badSequence.Incoming.ToArray();
        queued[2].Data[1] = 4;
        Reject<InvalidDataException>(() => Receive(badSequence),
            "Wrong FD.TP.DT segment number aborts reassembly.");
        var unrelatedBamSession = new FakeLink();
        foreach (var frame in sender.Outgoing)
        {
            unrelatedBamSession.Incoming.Enqueue(new(frame.Id,
                frame.IsFd, frame.Data.ToArray()));
            if (J1939FdTransportFrames.DataPgn ==
                StandardVehicleProtocols.ParseJ1939Id(frame.Id).Pgn)
            {
                var other = frame.Data.ToArray();
                other[0] = (byte)((other[0] & 0x0F) | 0x30);
                unrelatedBamSession.Incoming.Enqueue(new(
                    frame.Id, true, other));
            }
        }
        Check(Receive(unrelatedBamSession).SequenceEqual(Fill(131)),
            "FD.TP ignores interleaved data from another BAM session on the same PGN.");

        var wrongSessionBam = new FakeLink();
        foreach (var frame in sender.Outgoing)
        {
            var copy = frame.Data.ToArray();
            if (J1939FdTransportFrames.DataPgn ==
                StandardVehicleProtocols.ParseJ1939Id(frame.Id).Pgn)
                copy[0] = 0x30;
            wrongSessionBam.Incoming.Enqueue(new(frame.Id, true, copy));
        }
        Reject<TimeoutException>(() => Receive(wrongSessionBam),
            "BAM cannot complete using only DT segments from another session.");

        var noFinalStatus = new FakeLink();
        foreach (var frame in sender.Outgoing.Take(4))
            noFinalStatus.Incoming.Enqueue(frame);
        Reject<TimeoutException>(() => Receive(noFinalStatus),
            "Missing EOM_STATUS cannot be treated as completed broadcast.");
        var corruptedStatus = new FakeLink();
        foreach (var frame in sender.Outgoing)
            corruptedStatus.Incoming.Enqueue(new(frame.Id, frame.IsFd,
                frame.Data.ToArray()));
        var all = corruptedStatus.Incoming.ToArray();
        all[^1].Data[1] = 0xFE;
        Reject<InvalidDataException>(() => Receive(corruptedStatus),
            "Incorrect EOM_STATUS length must not certify transfer.");

        Reject<ArgumentException>(() =>
            Send(new FakeLink(), Fill(60)),
            "Messages within a 60-byte C-PG belong to Multi-PG, not BAM.");
        Reject<ArgumentException>(() =>
            J1939FdBamSession.SendAsync(new FakeLink(), 0xF1, 0xEF00,
                2, Fill(100), CancellationToken.None).GetAwaiter().GetResult(),
            "Broadcast-only FD.TP does not impersonate unicast PDU1.");
        var bamCommand = StandardProtocolCli.ParseJ1939Fd([
            StandardProtocolCli.J1939FdBamCommand,
            "--adapter-index", "0", "--baud", "500000",
            "--data-baud", "2000000", "--brs", "true",
            "--source", "0xF1", "--target", "0xFF",
            "--pgn", "0xF004", "--session", "2",
            "--mode", "send",
            "--payload", Convert.ToHexString(Fill(131))]);
        Check(bamCommand.Bam && bamCommand.Mode == "send" &&
            bamCommand.Session == 2 && bamCommand.Payload.Length == 131 &&
            bamCommand.Priority == 7,
            "Explicit CLI BAM session accepts large PDU2 PGN and confirmed TX profile.");
        var extendedBamDeadline = StandardProtocolCli.ParseJ1939Fd([
            StandardProtocolCli.J1939FdBamCommand,
            "--adapter-index", "0", "--baud", "500000",
            "--source", "0xF1", "--target", "0x10",
            "--pgn", "0xF004", "--mode", "receive",
            "--timeout-ms", "600000"]);
        Check(extendedBamDeadline.TimeoutMs == 600000,
            "Large BAM transfers accept an explicit bounded ten-minute deadline.");
        Reject<ArgumentException>(() => StandardProtocolCli.ParseJ1939Fd([
            StandardProtocolCli.J1939FdBamCommand,
            "--adapter-index", "0", "--baud", "500000",
            "--source", "0xF1", "--target", "0x10",
            "--pgn", "0xF004", "--mode", "receive",
            "--timeout-ms", "600001"]),
            "BAM cannot allocate unbounded overall receive timeout.");

        var bamPassive = StandardProtocolCli.ParseJ1939Fd([
            StandardProtocolCli.J1939FdBamCommand,
            "--adapter-index", "0", "--baud", "500000",
            "--source", "0xF1", "--target", "0x10",
            "--pgn", "0xF004", "--mode", "receive"]);
        Check(bamPassive.Bam && bamPassive.Payload.Length == 0 &&
            bamPassive.Pgn == 0xF004,
            "J1939-22 BAM receive-only command accepts no TX data.");
        Reject<ArgumentException>(() => StandardProtocolCli.ParseJ1939Fd([
            StandardProtocolCli.J1939FdBamCommand,
            "--adapter-index", "0", "--baud", "500000",
            "--source", "0xF1", "--target", "0x10",
            "--pgn", "0xF004", "--mode", "receive",
            "--payload", "AA"]),
            "J1939-22 BAM passive mode rejects a hidden TX buffer.");
        Reject<ArgumentException>(() => StandardProtocolCli.ParseJ1939Fd([
            StandardProtocolCli.J1939FdBamCommand,
            "--adapter-index", "0", "--baud", "500000",
            "--source", "0xF1", "--target", "0x10",
            "--pgn", "0xF004", "--mode", "send",
            "--payload", Convert.ToHexString(Fill(131))]),
            "J1939-22 BAM send is broadcast, never silently unicast.");
        Reject<ArgumentException>(() => StandardProtocolCli.ParseJ1939Fd([
            StandardProtocolCli.J1939FdBamCommand,
            "--adapter-index", "0", "--baud", "500000",
            "--source", "0xF1", "--target", "0xFF",
            "--pgn", "0xEF00", "--mode", "send",
            "--payload", Convert.ToHexString(Fill(131))]),
            "J1939-22 BAM refuses directed PDU1 PGNs.");
        Reject<ArgumentException>(() => StandardProtocolCli.ParseJ1939Fd([
            StandardProtocolCli.J1939FdBamCommand,
            "--adapter-index", "0", "--baud", "500000",
            "--source", "0xF1", "--target", "0xFF",
            "--pgn", "0xF004", "--session", "4",
            "--mode", "send",
            "--payload", Convert.ToHexString(Fill(131))]),
            "J1939-22 BAM advertises only session IDs 0 through 3.");

        return checkedCount;
    }
}
