namespace CamaroDiag.J2534Bridge;

/// <summary>Offline RTS/CTS and EOM checks without CAN hardware or ECU.</summary>
internal static class J1939FdCmdtSelfTests
{
    private sealed class FakeLink : IsoTpCanFdSession.ICanLink
    {
        internal readonly Queue<IsoTpCanFdSession.CanFrame> RX = new();
        internal readonly List<IsoTpCanFdSession.CanFrame> TX = [];
        internal readonly List<TimeSpan> ReceiveTimeouts = [];
        public Task SendAsync(IsoTpCanFdSession.CanFrame frame, CancellationToken token)
        {
            token.ThrowIfCancellationRequested();
            TX.Add(new(frame.Id, frame.IsFd, frame.Data.ToArray()));
            return Task.CompletedTask;
        }
        public Task<IsoTpCanFdSession.CanFrame> ReceiveAsync(TimeSpan timeout, CancellationToken token)
        {
            token.ThrowIfCancellationRequested();
            ReceiveTimeouts.Add(timeout);
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
                TimeSpan.FromSeconds(5), CancellationToken.None)
                .GetAwaiter().GetResult();
        static byte[] Receive(FakeLink link, J1939FdCmdtSession.Options o) =>
            J1939FdCmdtSession.ReceiveAsync(link, o,
                TimeSpan.FromSeconds(5), CancellationToken.None)
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

        Check(tx.ReceiveTimeouts.Count == 3 &&
            tx.ReceiveTimeouts.Take(2).All(t =>
                t > TimeSpan.Zero && t <= TimeSpan.FromMilliseconds(1250)) &&
            tx.ReceiveTimeouts[2] > TimeSpan.Zero &&
            tx.ReceiveTimeouts[2] <= TimeSpan.FromSeconds(3),
            "CMDT RTS/CTS windows and EOM_ACK use bounded J1939-22 phase timers.");

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

        Check(rx.ReceiveTimeouts.Count == 5 &&
            rx.ReceiveTimeouts[0] > TimeSpan.Zero &&
            rx.ReceiveTimeouts[0] <= TimeSpan.FromSeconds(5) &&
            rx.ReceiveTimeouts.Skip(1).Take(3).All(t =>
                t > TimeSpan.Zero && t <= TimeSpan.FromMilliseconds(1250)) &&
            rx.ReceiveTimeouts[4] > TimeSpan.Zero &&
            rx.ReceiveTimeouts[4] <= TimeSpan.FromMilliseconds(750),
            "CMDT receiver bounds each DT and final EOM_STATUS wait.");

        var wrongCts = new FakeLink();
        wrongCts.RX.Enqueue(Control(o, true,
            J1939FdTransportFrames.ClearToSend,
            0xFFFFFF, 2, 2, 0));
        Reject<InvalidDataException>(() => Send(wrongCts, o, Fill(131)),
            "Wrong CTS next-segment cannot change the current TX sequence.");
        Check(wrongCts.TX.Count == 1,
            "Invalid CTS cannot authorize any FD.TP.DT segment.");

        var peerAbort = new FakeLink();
        peerAbort.RX.Enqueue(Control(o, true,
            J1939FdTransportFrames.ConnectionAbort,
            0xFFFFFF, 0xFFFFFF, 0xFF, 3));
        Reject<IOException>(() => Send(peerAbort, o, Fill(131)),
            "Peer CMDT abort terminates a pending send without DT emission.");
        Check(peerAbort.TX.Count == 1,
            "Peer abort leaves only original RTS in transmitter history.");

        var wrongSession = new FakeLink();
        wrongSession.RX.Enqueue(Control(o with { Session = 2 }, true,
            J1939FdTransportFrames.ClearToSend,
            0xFFFFFF, 1, 2, 0));
        wrongSession.RX.Enqueue(Control(o, true,
            J1939FdTransportFrames.ClearToSend,
            0xFFFFFF, 1, 2, 0));
        wrongSession.RX.Enqueue(Control(o, true,
            J1939FdTransportFrames.ClearToSend,
            0xFFFFFF, 3, 1, 0));
        wrongSession.RX.Enqueue(Control(o, true,
            J1939FdTransportFrames.EndOfMessageAck,
            131, 3, 0xFF, 0xFF));
        Send(wrongSession, o, Fill(131));
        Check(wrongSession.TX.Count == 5,
            "CTS for a second session is ignored without interrupting active transfer.");

        var peerLimited = new FakeLink();
        foreach (var frame in tx.TX)
        {
            var id = StandardVehicleProtocols.ParseJ1939Id(frame.Id);
            var altered = frame.Data.ToArray();
            if (id.Pgn == J1939FdTransportFrames.ControlPgn &&
                (altered[0] & 0x0F) == J1939FdTransportFrames.RequestToSend)
                altered[7] = 1; // Peer RTS allows one DT per CTS.
            peerLimited.RX.Enqueue(new(
                J1939ClassicTpSession.ArbitrationId(
                    id.Pgn, (byte)id.Priority, o.Peer, o.Local),
                true, altered));
        }
        Check(Receive(peerLimited, o with { Window = 3 })
                .SequenceEqual(Fill(131)) &&
            peerLimited.TX.Count == 4 &&
            J1939FdTransportFrames.DecodeControl(peerLimited.TX[0]).Aux7 == 1 &&
            J1939FdTransportFrames.DecodeControl(peerLimited.TX[1]).Aux7 == 1 &&
            J1939FdTransportFrames.DecodeControl(peerLimited.TX[2]).Aux7 == 1,
            "Receiver respects peer RTS maximum CTS window even if local window is larger.");

        var malformedEom = new FakeLink();
        foreach (var frame in tx.TX)
        {
            var id = StandardVehicleProtocols.ParseJ1939Id(frame.Id);
            var altered = frame.Data.ToArray();
            if (id.Pgn == J1939FdTransportFrames.ControlPgn &&
                (altered[0] & 0x0F) == J1939FdTransportFrames.EndOfMessageStatus)
                altered[7] = 1;
            malformedEom.RX.Enqueue(new(
                J1939ClassicTpSession.ArbitrationId(
                    id.Pgn, (byte)id.Priority, o.Peer, o.Local),
                true, altered));
        }
        Reject<InvalidDataException>(() => Receive(malformedEom, o),
            "Receiver rejects EOM_STATUS with invalid reserved fields.");
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
        Check(wrongOrder.TX.Count == 2 &&
            J1939FdTransportFrames.DecodeControl(wrongOrder.TX[1]).Type ==
                J1939FdTransportFrames.ConnectionAbort &&
            J1939FdTransportFrames.DecodeControl(wrongOrder.TX[1]).Aux8 == 7,
            "Invalid CMDT segment triggers reason 7 abort and never EOM_ACK.");
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
        var namedFd = StandardProtocolCli.ParseJ1939Fd([
            StandardProtocolCli.J1939FdCmdtCommand,
            "--adapter-index", "0", "--baud", "500000",
            "--source", "0xF1", "--target", "0x10",
            "--pgn", "0xEF00", "--session", "1", "--window", "2",
            "--mode", "send", "--payload",
            Convert.ToHexString(Fill(131)),
            "--name", "8000000200000001"]);
        Check(namedFd.Name == 0x8000000200000001UL,
            "J1939-22 FD.TP accepts a 64-bit local NAME for explicit claim.");

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
        Reject<ArgumentException>(() => StandardProtocolCli.ParseJ1939Fd([
            StandardProtocolCli.J1939FdCmdtCommand,
            StandardProtocolCli.J1939FdBamCommand,
            "--adapter-index", "0", "--baud", "500000",
            "--source", "0xF1", "--target", "0x10",
            "--pgn", "0xEF00", "--mode", "receive"]),
            "Conflicting J1939 FD.TP transport options fail before opening driver.");
        return checks;
    }
}
