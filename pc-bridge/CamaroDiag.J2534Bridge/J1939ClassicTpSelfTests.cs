namespace CamaroDiag.J2534Bridge;

/// <summary>SAE J1939-21 session checks without adapters, vehicle or ECU.</summary>
internal static class J1939ClassicTpSelfTests
{
    private sealed class FakeCan : IsoTpCanFdSession.ICanLink
    {
        internal readonly Queue<IsoTpCanFdSession.CanFrame> Incoming = new();
        internal readonly List<IsoTpCanFdSession.CanFrame> Outgoing = [];
        internal readonly List<TimeSpan> Delays = [];

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
            if (Incoming.Count == 0)
                throw new TimeoutException("No fake J1939 frame queued.");
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
        int count = 0;
        void Check(bool condition, string name)
        {
            count++;
            if (!condition) throw new InvalidOperationException("J1939-21 test failed: " + name);
        }
        void Reject<T>(Action action, string name) where T : Exception
        {
            var thrown = false;
            try { action(); }
            catch (T) { thrown = true; }
            Check(thrown, name);
        }

        var o = new J1939ClassicTpSession.Options(0xF1, 0x10, 0xF004,
            Priority: 6, ReceiveWindow: 2);
        static byte[] Data(int bytes) => Enumerable.Range(0, bytes)
            .Select(x => (byte)(x & 0xFF)).ToArray();
        static void Send(FakeCan link, J1939ClassicTpSession.Options opts,
            byte[] data, bool broadcast = false) =>
            J1939ClassicTpSession.SendAsync(link, opts, data, broadcast,
                TimeSpan.FromSeconds(1), CancellationToken.None).GetAwaiter().GetResult();
        static J1939ClassicTpSession.Received Receive(
            FakeCan link, J1939ClassicTpSession.Options opts) =>
            J1939ClassicTpSession.ReceiveAsync(link, opts,
                TimeSpan.FromSeconds(1), CancellationToken.None).GetAwaiter().GetResult();
        IsoTpCanFdSession.CanFrame PeerCm(params byte[] data) => new(
            J1939ClassicTpSession.ArbitrationId(
                J1939ClassicTpSession.TpCmPgn, 6, o.RemoteAddress, o.LocalAddress),
            false, data);

        Check(J1939ClassicTpSession.ArbitrationId(
            J1939ClassicTpSession.TpCmPgn, 6, 0xF1, 0x10) == 0x18EC10F1,
            "29-bit arbitration ID priority/PDU1 destination/source.");
        Check(StandardVehicleProtocols.ParseJ1939Id(
            J1939ClassicTpSession.ArbitrationId(0xF004, 6, 0xF1)).Pgn ==
            0xF004, "PDU2 PGN group extension preserved.");
        Reject<ArgumentException>(() =>
            J1939ClassicTpSession.ArbitrationId(0xF004, 6, 0xF1, 0x10),
            "PDU2 must not contain a separate destination.");
        Reject<ArgumentException>(() => new J1939ClassicTpSession.Options(
            0xF1, 0x10, 0xEF42).Validate(),
            "PDU1 PGN must have zero PS group extension.");

        var directly = new FakeCan();
        Send(directly, o, [0x01, 0x02]);
        Check(directly.Outgoing.Count == 1 &&
            directly.Outgoing[0].Id ==
                J1939ClassicTpSession.ArbitrationId(0xF004, 6, o.LocalAddress) &&
            directly.Outgoing[0].Data.AsSpan(0, 2).SequenceEqual([0x01, 0x02]) &&
            directly.Outgoing[0].Data[7] == 0xFF,
            "Direct <=8-byte PGN emitted with J1939 filler.");
        var directRead = new FakeCan();
        directRead.Incoming.Enqueue(new(
            J1939ClassicTpSession.ArbitrationId(0xF004, 6, o.RemoteAddress),
            false, [1, 2, 3, 4, 5, 6, 7, 8]));
        Check(Receive(directRead, o).Payload.SequenceEqual(
            new byte[] {1,2,3,4,5,6,7,8}),
            "Single J1939 PGN received in full, including application padding.");

        var send = new FakeCan();
        send.Incoming.Enqueue(PeerCm(0x11, 2, 1, 0xFF, 0xFF, 0x04, 0xF0, 0));
        send.Incoming.Enqueue(PeerCm(0x11, 1, 3, 0xFF, 0xFF, 0x04, 0xF0, 0));
        send.Incoming.Enqueue(PeerCm(0x13, 20, 0, 3, 0xFF, 0x04, 0xF0, 0));
        Send(send, o, Data(20));
        Check(send.Outgoing.Count == 4 && send.Outgoing[0].Data[0] == 0x10 &&
            send.Outgoing[0].Data[1] == 20 && send.Outgoing[0].Data[3] == 3 &&
            send.Outgoing[0].Data[4] == 2, "J1939 RTS declared size/packet count/window.");
        Check(send.Outgoing[1].Data[0] == 1 &&
            send.Outgoing[2].Data[0] == 2 &&
            send.Outgoing[3].Data[0] == 3,
            "J1939 TP.DT sequence and CTS window 2+1.");
        Check(send.Outgoing[3].Data.AsSpan(1, 6).SequenceEqual(Data(20).AsSpan(14)),
            "J1939 last packet exact data bytes.");

        var receive = new FakeCan();
        receive.Incoming.Enqueue(J1939ClassicTpSession.MakeControl(o,
            J1939ClassicTpSession.Rts, 20, 3, 2, false, false));
        for (int i = 0; i < 3; i++)
        {
            var data = Data(20);
            var offset = i * 7;
            receive.Incoming.Enqueue(J1939ClassicTpSession.MakeData(o,
                o.RemoteAddress, o.LocalAddress, i + 1,
                data.AsSpan(offset, Math.Min(7, data.Length - offset))));
        }
        var got = Receive(receive, o);
        Check(got.Payload.SequenceEqual(Data(20)) && !got.Broadcast,
            "J1939 RTS/CTS reconstructs original 20 bytes in the right order.");
        Check(receive.Outgoing.Count == 3 &&
            receive.Outgoing[0].Data[0] == 0x11 &&
            receive.Outgoing[0].Data[1] == 2 &&
            receive.Outgoing[0].Data[2] == 1 &&
            receive.Outgoing[1].Data[1] == 1 &&
            receive.Outgoing[1].Data[2] == 3 &&
            receive.Outgoing[2].Data[0] == 0x13 &&
            receive.Outgoing[2].Data[1] == 20,
            "Receiver sends correct CTS windows and final EOM ACK.");

        var bamSender = new FakeCan();
        Send(bamSender, o, Data(17), broadcast: true);
        Check(bamSender.Outgoing.Count == 4 &&
            bamSender.Outgoing[0].Data[0] == 0x20 &&
            StandardVehicleProtocols.ParseJ1939Id(
                bamSender.Outgoing[0].Id).Destination == 0xFF,
            "BAM announce broadcasts 17 bytes.");
        Check(bamSender.Delays.Count == 3 &&
            bamSender.Delays.All(d => d == TimeSpan.FromMilliseconds(50)),
            "Broadcast TP.DT inter-packet minimum 50 ms delegated to link.");

        var bamReceiver = new FakeCan();
        bamReceiver.Incoming.Enqueue(J1939ClassicTpSession.MakeControl(o,
            J1939ClassicTpSession.Bam, 17, 3, 0xFF, true, false));
        for (int i = 0; i < 3; i++)
        {
            var data = Data(17);
            var offset = i * 7;
            bamReceiver.Incoming.Enqueue(J1939ClassicTpSession.MakeData(o,
                o.RemoteAddress, 0xFF, i + 1,
                data.AsSpan(offset, Math.Min(7, data.Length - offset))));
        }
        var bam = Receive(bamReceiver, o);
        Check(bam.Broadcast && bam.Payload.SequenceEqual(Data(17)) &&
            bamReceiver.Outgoing.Count == 0,
            "Receive broadcast BAM without sending CTS/EOM ACK.");

        var wrongCts = new FakeCan();
        wrongCts.Incoming.Enqueue(PeerCm(0x11, 2, 2, 0xFF, 0xFF, 0x04, 0xF0, 0));
        Reject<InvalidDataException>(() => Send(wrongCts, o, Data(20)),
            "Wrong next sequence in CTS stops subsequent writes.");
        Check(wrongCts.Outgoing.Count == 1, "No TP.DT sent after corrupt CTS.");

        var negative = new FakeCan();
        negative.Incoming.Enqueue(PeerCm(0xFF, 0, 0, 0, 0, 0x04, 0xF0, 0));
        Reject<IOException>(() => Send(negative, o, Data(20)),
            "Standard abort stops transport.");

        var badPacket = new FakeCan();
        badPacket.Incoming.Enqueue(J1939ClassicTpSession.MakeControl(o,
            J1939ClassicTpSession.Rts, 20, 3, 2, false, false));
        badPacket.Incoming.Enqueue(J1939ClassicTpSession.MakeData(o,
            o.RemoteAddress, o.LocalAddress, 2, Data(7)));
        Reject<InvalidDataException>(() => Receive(badPacket, o),
            "Wrong transport packet number refused.");

        var badSize = new FakeCan();
        var cm = J1939ClassicTpSession.MakeControl(o,
            J1939ClassicTpSession.Rts, 20, 3, 2, false, false);
        cm.Data[3] = 4;
        badSize.Incoming.Enqueue(cm);
        Reject<InvalidDataException>(() => Receive(badSize, o),
            "TP.CM packet count inconsistent with total byte count.");

        var wrongAck = new FakeCan();
        wrongAck.Incoming.Enqueue(PeerCm(0x11, 3, 1, 0xFF, 0xFF, 0x04, 0xF0, 0));
        Reject<InvalidDataException>(() => Send(wrongAck, o, Data(20)),
            "Peer CTS beyond declared RTS window rejected.");

        var max = new FakeCan();
        Send(max, o, Data(1785), broadcast: true);
        Check(max.Outgoing.Count == 256 &&
            max.Outgoing[0].Data[3] == 255 &&
            max.Outgoing[^1].Data[0] == 255,
            "J1939-21 BAM supports maximum 1785 bytes / 255 packets.");
        Reject<ArgumentOutOfRangeException>(() =>
            Send(new FakeCan(), o, Data(1786), broadcast: true),
            "J1939 standard 1785-byte maximum enforced.");

        using var cancellation = new CancellationTokenSource();
        cancellation.Cancel();
        var noTx = new FakeCan();
        Reject<OperationCanceledException>(() =>
            J1939ClassicTpSession.SendAsync(noTx, o, Data(11), true,
                TimeSpan.FromSeconds(1), cancellation.Token)
            .GetAwaiter().GetResult(),
            "Cancelled transfer cannot send any PGN.");
        Check(noTx.Outgoing.Count == 0, "No J1939 frames sent after cancellation.");

        return count;
    }
}
