namespace CamaroDiag.J2534Bridge;

/// <summary>Offline RTS/CTS and EOM checks without CAN hardware or ECU.</summary>
internal static class J1939FdCmdtSelfTests
{
    private sealed class FakeLink : IsoTpCanFdSession.ICanLink
    {
        internal readonly Queue<IsoTpCanFdSession.CanFrame> RX = new();
        internal readonly List<IsoTpCanFdSession.CanFrame> TX = [];
        public Task SendAsync(IsoTpCanFdSession.CanFrame frame, CancellationToken token)
        {
            token.ThrowIfCancellationRequested();
            TX.Add(new(frame.Id, frame.IsFd, frame.Data.ToArray()));
            return Task.CompletedTask;
        }
        public Task<IsoTpCanFdSession.CanFrame> ReceiveAsync(TimeSpan timeout, CancellationToken token)
        {
            token.ThrowIfCancellationRequested();
            if (RX.Count == 0) throw new TimeoutException("No simulated FD.TP frame.");
            return Task.FromResult(RX.Dequeue());
        }
        public Task WaitMinimumSeparationAsync(TimeSpan minimum, CancellationToken token)
        {
            token.ThrowIfCancellationRequested();
            return Task.CompletedTask;
        }
    }

    internal static int Run()
    {
        var checks = 0;
        void Check(bool value, string name)
        {
            checks++;
            if (!value) throw new InvalidOperationException("J1939-22 CMDT: " + name);
        }
        void Reject<T>(Action action, string name) where T : Exception
        {
            var thrown = false;
            try { action(); }
            catch (T) { thrown = true; }
            Check(thrown, name);
        }

        var o = new J1939FdCmdtSession.Options(0xF1, 0x10, 0xEF00,
            Session: 1, Window: 2);
        static byte[] Fill(int size) =>
            Enumerable.Range(0, size).Select(i => (byte)i).ToArray();
        static IsoTpCanFdSession.CanFrame Control(
            J1939FdCmdtSession.Options opt, bool fromPeer, byte code,
            int size, int count, byte aux7, byte aux8) =>
            J1939FdTransportFrames.EncodeControl(new(
                fromPeer ? opt.Peer : opt.Local,
                fromPeer ? opt.Local : opt.Peer,
                opt.Session, code, size, count, aux7, aux8,
                opt.Pgn, opt.Priority));
        static void Send(FakeLink link, J1939FdCmdtSession.Options o, byte[] data) =>
            J1939FdCmdtSession.SendAsync(link, o, data,
                TimeSpan.FromSeconds(1), CancellationToken.None)
                .GetAwaiter().GetResult();
        static byte[] Receive(FakeLink link, J1939FdCmdtSession.Options o) =>
            J1939FdCmdtSession.ReceiveAsync(link, o,
                TimeSpan.FromSeconds(1), CancellationToken.None)
                .GetAwaiter().GetResult();

        var tx = new FakeLink();
        tx.RX.Enqueue(Control(o, true, J1939FdTransportFrames.ClearToSend,
            0xFFFFFF, 1, 2, 0));
        tx.RX.Enqueue(Control(o, true, J1939FdTransportFrames.ClearToSend,
            0xFFFFFF, 3, 1, 0));
        tx.RX.Enqueue(Control(o, true, J1939FdTransportFrames.EndOfMessageAck,
            131, 3, 0xFF, 0xFF));
        Send(tx, o, Fill(131));
        Check(tx.TX.Count == 5 &&
            J1939FdTransportFrames.DecodeControl(tx.TX[0]).Type ==
                J1939FdTransportFrames.RequestToSend &&
            J1939FdTransportFrames.DecodeControl(tx.TX[^1]).Type ==
                J1939FdTransportFrames.EndOfMessageStatus,
            "RTS, three DT packets and EOM_STATUS sent under two CTS grants.");
        Check(J1939FdTransportFrames.DecodeData(tx.TX[1]).Number == 1 &&
            J1939FdTransportFrames.DecodeData(tx.TX[2]).Number == 2 &&
            J1939FdTransportFrames.DecodeData(tx.TX[3]).Number == 3,
            "Directed FD.TP data sequences are contiguous.");

        var rx = new FakeLink();
        foreach (var frame in tx.TX)
        {
            var id = StandardVehicleProtocols.ParseJ1939Id(frame.Id);
            rx.RX.Enqueue(new(
                J1939ClassicTpSession.ArbitrationId(
                    id.Pgn, (byte)id.Priority, o.Peer, o.Local),
                true, frame.Data.ToArray()));
        }
        Check(Receive(rx, o).SequenceEqual(Fill(131)) &&
            rx.TX.Count == 3 &&
            J1939FdTransportFrames.DecodeControl(rx.TX[0]).Aux7 == 2 &&
            J1939FdTransportFrames.DecodeControl(rx.TX[1]).SegmentCount == 3 &&
            J1939FdTransportFrames.DecodeControl(rx.TX[2]).Type ==
                J1939FdTransportFrames.EndOfMessageAck,
            "FD.TP receiver reconstructs data and emits two CTS plus final EOM_ACK.");

        var noCts = new FakeLink();
        Reject<TimeoutException>(() => Send(noCts, o, Fill(131)),
            "Missing CTS aborts outgoing connection-mode session.");
        Check(noCts.TX.Count == 2 &&
            J1939FdTransportFrames.DecodeControl(noCts.TX[1]).Type ==
                J1939FdTransportFrames.ConnectionAbort,
            "CMDT timeout sends a standard abort rather than pretending success.");

        var wrongOrder = new FakeLink();
        wrongOrder.RX.Enqueue(Control(o, true,
            J1939FdTransportFrames.RequestToSend, 131, 3, 2, 0));
        wrongOrder.RX.Enqueue(J1939FdTransportFrames.EncodeData(new(
            o.Peer, o.Local, o.Session, 2, 0, Fill(60), 7)));
        Reject<InvalidDataException>(() => Receive(wrongOrder, o),
            "First segment number 2 cannot satisfy segment 1.");
        Reject<ArgumentException>(() =>
            (o with { Pgn = 0xF004 }).Validate(),
            "Connection-mode requires a directed PDU1 PGN.");
        var cli = StandardProtocolCli.ParseJ1939Fd([
            StandardProtocolCli.J1939FdCmdtCommand,
            "--adapter-index", "0", "--baud", "500000",
            "--source", "0xF1", "--target", "0x10",
            "--pgn", "0xEF00", "--session", "1", "--window", "2",
            "--mode", "send", "--payload", Convert.ToHexString(Fill(131))]);
        Check(cli.Cmdt && !cli.Bam && cli.Session == 1 &&
            cli.Window == 2 && cli.Payload.Length == 131,
            "CMDT CLI selects 131-byte directed FD.TP session and CTS window.");
        var rxCli = StandardProtocolCli.ParseJ1939Fd([
            StandardProtocolCli.J1939FdCmdtCommand,
            "--adapter-index", "0", "--baud", "500000",
            "--source", "0xF1", "--target", "0x10",
            "--pgn", "0xEF00", "--session", "1",
            "--mode", "receive"]);
        Check(rxCli.Cmdt && rxCli.Payload.Length == 0,
            "Directed FD.TP receive never manufactures an outgoing request.");
        Reject<ArgumentException>(() => StandardProtocolCli.ParseJ1939Fd([
            StandardProtocolCli.J1939FdCmdtCommand,
            "--adapter-index", "0", "--baud", "500000",
            "--source", "0xF1", "--target", "0xFF",
            "--pgn", "0xEF00", "--mode", "send",
            "--payload", Convert.ToHexString(Fill(131))]),
            "CMDT refuses broadcast destination.");
        Reject<ArgumentException>(() => StandardProtocolCli.ParseJ1939Fd([
            StandardProtocolCli.J1939FdCmdtCommand,
            "--adapter-index", "0", "--baud", "500000",
            "--source", "0xF1", "--target", "0x10",
            "--pgn", "0xF004", "--mode", "send",
            "--payload", Convert.ToHexString(Fill(131))]),
            "CMDT refuses broadcast-only PDU2 PGNs.");
        return checks;
    }
}
