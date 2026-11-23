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
            directly.Outgoing[0].Data.AsSpan(0, 2).SequenceEqual(new byte[] {0x01, 0x02}) &&
            directly.Outgoing[0].Data[7] == 0xFF,
            "Direct <=8-byte PGN emitted with J1939 filler.");
        var directRead = new FakeCan();
        directRead.Incoming.Enqueue(new(
            J1939ClassicTpSession.ArbitrationId(0xF004, 6, o.RemoteAddress),
            false, [1, 2, 3, 4, 5, 6, 7, 8]));
        Check(Receive(directRead, o).Payload.SequenceEqual(
            new byte[] {1,2,3,4,5,6,7,8}),
            "Single J1939 PGN received in full, including application padding.");

        var noisyBus = new FakeCan();
        noisyBus.Incoming.Enqueue(new(0x80000000u, false, Data(8)));
        noisyBus.Incoming.Enqueue(new(0x18EC10F1u, true, Data(8)));
        noisyBus.Incoming.Enqueue(new(0x18EC10F1u, false, Data(4)));
        noisyBus.Incoming.Enqueue(new(
            J1939ClassicTpSession.ArbitrationId(
                o.Pgn, 6, o.RemoteAddress), false, Data(8)));
        Check(Receive(noisyBus, o).Payload.SequenceEqual(Data(8)),
            "Unrelated malformed bus frames are ignored before ID parsing.");

        var concurrentControl = new FakeCan();
        concurrentControl.Incoming.Enqueue(PeerCm(
            J1939ClassicTpSession.Cts, 1, 1, 0xFF, 0xFF, 0x05, 0xF0, 0));
        concurrentControl.Incoming.Enqueue(PeerCm(
            J1939ClassicTpSession.Cts, 2, 1, 0xFF, 0xFF, 0x04, 0xF0, 0));
        concurrentControl.Incoming.Enqueue(PeerCm(
            J1939ClassicTpSession.Cts, 1, 3, 0xFF, 0xFF, 0x04, 0xF0, 0));
        concurrentControl.Incoming.Enqueue(PeerCm(
            J1939ClassicTpSession.EomAck, 20, 0, 3, 0xFF, 0x04, 0xF0, 0));
        Send(concurrentControl, o, Data(20));
        Check(concurrentControl.Outgoing.Count == 4,
            "Unrelated TP.CM PGN does not interrupt active RTS/CTS send.");

        var unrelatedStart = new FakeCan();
        var otherStart = J1939ClassicTpSession.MakeControl(
            o with { Pgn = 0xF005 },
            J1939ClassicTpSession.Rts, 13, 2, 2, false, false);
        unrelatedStart.Incoming.Enqueue(otherStart);
        unrelatedStart.Incoming.Enqueue(new(
            J1939ClassicTpSession.ArbitrationId(o.Pgn, 6,
                o.RemoteAddress), false, Data(8)));
        Check(Receive(unrelatedStart, o).Payload.SequenceEqual(Data(8)),
            "Unrelated TP.CM PGN ignored before expected direct PGN.");

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

        // The sender's RTS maximum packets per CTS is authoritative.
        // A receiver with a larger local window must still honor that limit.
        var limitedRts = new FakeCan();
        limitedRts.Incoming.Enqueue(J1939ClassicTpSession.MakeControl(o,
            J1939ClassicTpSession.Rts, 20, 3, 1, false, false));
        for (int i = 0; i < 3; i++)
        {
            var data = Data(20);
            var offset = i * 7;
            limitedRts.Incoming.Enqueue(J1939ClassicTpSession.MakeData(o,
                o.RemoteAddress, o.LocalAddress, i + 1,
                data.AsSpan(offset, Math.Min(7, data.Length - offset))));
        }
        Check(Receive(limitedRts, o).Payload.SequenceEqual(Data(20)),
            "Peer-limited RTS transfer reconstructs all packets.");
        Check(limitedRts.Outgoing.Count == 4 &&
            limitedRts.Outgoing[0].Data[1] == 1 &&
            limitedRts.Outgoing[0].Data[2] == 1 &&
            limitedRts.Outgoing[1].Data[1] == 1 &&
            limitedRts.Outgoing[1].Data[2] == 2 &&
            limitedRts.Outgoing[2].Data[1] == 1 &&
            limitedRts.Outgoing[2].Data[2] == 3 &&
            limitedRts.Outgoing[3].Data[0] == J1939ClassicTpSession.EomAck,
            "CTS windows never exceed the peer RTS maximum.");

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

        var missingCts = new FakeCan();
        Reject<TimeoutException>(() => Send(missingCts, o, Data(20)),
            "Missing J1939 CTS times out a sender after RTS.");
        Check(missingCts.Outgoing.Count == 2 &&
            missingCts.Outgoing[0].Data[0] == J1939ClassicTpSession.Rts &&
            missingCts.Outgoing[1].Data[0] == J1939ClassicTpSession.Abort &&
            missingCts.Outgoing[1].Data[1] == 3,
            "Sender timeout closes CMDT with TP.Conn_Abort reason 3.");

        var negative = new FakeCan();
        negative.Incoming.Enqueue(PeerCm(0xFF, 0, 0, 0, 0, 0x04, 0xF0, 0));
        Reject<IOException>(() => Send(negative, o, Data(20)),
            "Standard abort stops transport.");

        var unrelatedAbort = new FakeCan();
        unrelatedAbort.Incoming.Enqueue(J1939ClassicTpSession.MakeControl(o,
            J1939ClassicTpSession.Rts, 13, 2, 2, false, false));
        // Same source, but unrelated PGN: must not interrupt active TP.DT.
        var otherPgnAbort = new IsoTpCanFdSession.CanFrame(
            J1939ClassicTpSession.ArbitrationId(
                J1939ClassicTpSession.TpCmPgn, 6, o.RemoteAddress,
                o.LocalAddress), false,
            [J1939ClassicTpSession.Abort, 0, 0, 0, 0, 0x05, 0xF0, 0]);
        unrelatedAbort.Incoming.Enqueue(otherPgnAbort);
        unrelatedAbort.Incoming.Enqueue(J1939ClassicTpSession.MakeData(o,
            o.RemoteAddress, o.LocalAddress, 1, Data(7)));
        unrelatedAbort.Incoming.Enqueue(J1939ClassicTpSession.MakeData(o,
            o.RemoteAddress, o.LocalAddress, 2, Data(13).AsSpan(7, 6)));
        Check(Receive(unrelatedAbort, o).Payload.SequenceEqual(Data(13)),
            "Unrelated J1939 TP.CM abort must not interrupt another PGN.");

        var activeAbort = new FakeCan();
        activeAbort.Incoming.Enqueue(J1939ClassicTpSession.MakeControl(o,
            J1939ClassicTpSession.Rts, 13, 2, 2, false, false));
        activeAbort.Incoming.Enqueue(PeerCm(
            J1939ClassicTpSession.Abort, 0, 0, 0, 0, 0x04, 0xF0, 0));
        Reject<IOException>(() => Receive(activeAbort, o),
            "Matching J1939 TP.CM abort terminates active transfer.");

        var badPacket = new FakeCan();
        badPacket.Incoming.Enqueue(J1939ClassicTpSession.MakeControl(o,
            J1939ClassicTpSession.Rts, 20, 3, 2, false, false));
        badPacket.Incoming.Enqueue(J1939ClassicTpSession.MakeData(o,
            o.RemoteAddress, o.LocalAddress, 2, Data(7)));
        Reject<InvalidDataException>(() => Receive(badPacket, o),
            "Wrong transport packet number refused.");
        Check(badPacket.Outgoing.Count == 2 &&
            badPacket.Outgoing[1].Data[0] == J1939ClassicTpSession.Abort &&
            badPacket.Outgoing[1].Data[1] == 7,
            "Bad J1939 sequence sends correlated TP.Conn_Abort reason 7.");

        var timedOut = new FakeCan();
        timedOut.Incoming.Enqueue(J1939ClassicTpSession.MakeControl(o,
            J1939ClassicTpSession.Rts, 13, 2, 2, false, false));
        Reject<TimeoutException>(() => Receive(timedOut, o),
            "Missing J1939 TP.DT packet is a transport timeout.");
        Check(timedOut.Outgoing.Count == 2 &&
            timedOut.Outgoing[0].Data[0] == J1939ClassicTpSession.Cts &&
            timedOut.Outgoing[1].Data[0] == J1939ClassicTpSession.Abort &&
            timedOut.Outgoing[1].Data[1] == 3,
            "CMDT packet timeout sends reason 3 after CTS.");

        var malformedAbort = J1939ClassicTpSession.MakeAbort(o, 7);
        Check(malformedAbort.Data[0] == 0xFF &&
            malformedAbort.Data[2] == 0xFF &&
            malformedAbort.Data[5] == 0x04 &&
            StandardVehicleProtocols.ParseJ1939Id(
                malformedAbort.Id).Destination == o.RemoteAddress,
            "TP.Conn_Abort uses standard PGN and peer destination.");

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

        const ulong name = 0x12345UL | (0x345UL << 21) |
            (2UL << 32) | (3UL << 35) | (0x10UL << 40) |
            (4UL << 49) | (1UL << 56) | (2UL << 60) | (1UL << 63);
        var addressFrameId = J1939ClassicTpSession.ArbitrationId(
            J1939AddressClaim.AddressClaimPgn, 6, 0x80, 0xFF);
        var claim = J1939AddressClaim.Decode(addressFrameId,
            BitConverter.GetBytes(name));
        Check(claim.SourceAddress == 0x80 && claim.Name == name &&
            claim.IdentityNumber == 0x12345 &&
            claim.ManufacturerCode == 0x345 &&
            claim.EcuInstance == 2 && claim.FunctionInstance == 3 &&
            claim.Function == 0x10 && claim.VehicleSystem == 4 &&
            claim.VehicleSystemInstance == 1 && claim.IndustryGroup == 2 &&
            claim.ArbitraryAddressCapable,
            "J1939-81 NAME bit fields decoded from broadcast address claim.");
        Reject<InvalidDataException>(() => J1939AddressClaim.Decode(
            addressFrameId, [1, 2, 3]),
            "Short J1939 address claim is rejected.");
        Reject<InvalidDataException>(() => J1939AddressClaim.Decode(
            J1939ClassicTpSession.ArbitrationId(
                J1939AddressClaim.AddressClaimPgn, 6, 0x80, 0x10),
            BitConverter.GetBytes(name)),
            "Unicast J1939 address claim is not a broadcast identity.");
        Reject<InvalidDataException>(() => J1939AddressClaim.Decode(
            addressFrameId, Enumerable.Repeat((byte)0xFF, 8).ToArray()),
            "Unspecified all-FF node NAME is rejected.");
        var claimBuilt = J1939AddressClaim.Build(0x80, name);
        Check(claimBuilt.Id == addressFrameId &&
            claimBuilt.Data.SequenceEqual(BitConverter.GetBytes(name)) &&
            J1939AddressClaim.Decode(claimBuilt.Id, claimBuilt.Data).Name == name,
            "J1939-81 address claim frame round-trips the 64-bit NAME.");
        var strongerName = name - 1;
        Check(J1939AddressClaim.Compare(claim, 0x80, strongerName) ==
            J1939AddressClaim.ClaimConflict.LocalNameWins,
            "Lower numeric NAME wins address-claim priority.");
        Check(J1939AddressClaim.Compare(claim, 0x80, name + 1) ==
            J1939AddressClaim.ClaimConflict.PeerNameWins,
            "Higher numeric NAME yields address to peer.");
        Check(J1939AddressClaim.Compare(claim, 0x81, name) ==
            J1939AddressClaim.ClaimConflict.DifferentAddress,
            "Peer claim on another source address does not conflict.");
        Check(J1939AddressClaim.Compare(claim, 0x80, name) ==
            J1939AddressClaim.ClaimConflict.DuplicateName,
            "Identical NAME is not silently accepted as a valid claim.");
        var cannotClaim = J1939AddressClaim.Build(0xFE, name);
        Check(StandardVehicleProtocols.ParseJ1939Id(cannotClaim.Id).Source == 0xFE &&
            J1939AddressClaim.Decode(cannotClaim.Id, cannotClaim.Data)
                .SourceAddress == 0xFE,
            "SAE J1939-81 0xFE Cannot Claim Address is legal only for management.");
        Reject<ArgumentException>(() =>
            J1939AddressClaim.Build(0xFF, name),
            "Broadcast source address cannot claim J1939 identity.");
        Reject<ArgumentException>(() =>
            J1939AddressClaim.Build(0x80, ulong.MaxValue),
            "Unspecified all-FF NAME cannot be claimed.");


        var dm1 = J1939DmDiagnostics.Decode(J1939DmDiagnostics.Dm1Pgn,
            [0xC7, 0xFF, 0x6E, 0x00, 0x05, 0x03, 0xFF, 0xFF]);
        Check(dm1.LampStatus == 0xC7 && dm1.LampFlashStatus == 0xFF &&
            dm1.Codes.Count == 1 && dm1.Codes[0].Spn == 110 &&
            dm1.Codes[0].Fmi == 5 && dm1.Codes[0].OccurrenceCount == 3 &&
            !dm1.Codes[0].AlternativeSpnConversion,
            "DM1 decodes lamp bytes, SPN, FMI and occurrence count.");
        var dm2 = J1939DmDiagnostics.Decode(J1939DmDiagnostics.Dm2Pgn,
            [0x00, 0x00, 0x6E, 0x00, 0x05, 0x83,
             0x64, 0x00, 0x01, 0x02]);
        Check(dm2.Codes.Count == 2 &&
            dm2.Codes[0].AlternativeSpnConversion &&
            dm2.Codes[0].Spn is null &&
            dm2.Codes[0].RawBytes.SequenceEqual(
                new byte[] {0x6E, 0x00, 0x05, 0x83}) &&
            dm2.Codes[1].Spn == 100 && dm2.Codes[1].Fmi == 1,
            "DM2 supports reassembled multiple DTCs and alternate CM flag.");
        Check(J1939DmDiagnostics.Decode(J1939DmDiagnostics.Dm1Pgn,
            [0, 0, 0, 0, 0, 0, 0xFF, 0xFF]).Codes.Count == 0,
            "DM1 zero-code sentinel does not invent an active DTC.");
        Reject<InvalidDataException>(() =>
            J1939DmDiagnostics.Decode(J1939DmDiagnostics.Dm1Pgn,
                [0, 0, 1, 2, 3]),
            "Malformed DM1 diagnostic code length is rejected.");

        Check(J1939PgnRequest.RequestPgn == 0xEA00 &&
            J1939PgnRequest.Encode(J1939DmDiagnostics.Dm1Pgn)
                .SequenceEqual(new byte[] {0xCA, 0xFE, 0x00}) &&
            J1939PgnRequest.Decode([0xCB, 0xFE, 0x00]) ==
                J1939DmDiagnostics.Dm2Pgn,
            "J1939-21 Request PGN EA00 uses three little-endian bytes.");
        Reject<ArgumentOutOfRangeException>(() =>
            J1939PgnRequest.Encode(0xEF42),
            "Request codec rejects PGNs with invalid PDU1 group extension.");
        Reject<InvalidDataException>(() =>
            J1939PgnRequest.Decode([0xCA, 0xFE]),
            "Request PGN requires all three bytes.");

        var acknowledgement = J1939PgnAcknowledgement.Decode(
            [1, 0xFF, 0xFF, 0xFF, 0xF1, 0xCA, 0xFE, 0x00],
            J1939DmDiagnostics.Dm1Pgn);
        Check(acknowledgement.Control == 1 && !acknowledgement.Positive &&
            acknowledgement.RequestedPgn == J1939DmDiagnostics.Dm1Pgn &&
            acknowledgement.AcknowledgedAddress == 0xF1,
            "J1939 ACKM identifies a negative response for the requested PGN.");
        Reject<InvalidDataException>(() =>
            J1939PgnAcknowledgement.Decode(
                [0, 0xFF, 0xFF, 0xFF, 0xF1, 0xCA, 0xFE, 0x00],
                J1939DmDiagnostics.Dm2Pgn),
            "ACKM of a different PGN cannot satisfy the active request.");

        var ackOptions = o with {
            Pgn = J1939DmDiagnostics.Dm1Pgn,
            AcceptAcknowledgement = true
        };
        var ackRx = new FakeCan();
        ackRx.Incoming.Enqueue(new(
            J1939ClassicTpSession.ArbitrationId(
                J1939PgnAcknowledgement.AcknowledgementPgn, 6,
                o.RemoteAddress, o.LocalAddress), false,
            [1, 0xFF, 0xFF, 0xFF, o.LocalAddress, 0xCB, 0xFE, 0]));
        ackRx.Incoming.Enqueue(new(
            J1939ClassicTpSession.ArbitrationId(
                J1939PgnAcknowledgement.AcknowledgementPgn, 6,
                o.RemoteAddress, o.LocalAddress), false,
            [1, 0xFF, 0xFF, 0xFF, o.LocalAddress, 0xCA, 0xFE, 0]));
        var receivedAck = Receive(ackRx, ackOptions);
        Check(receivedAck.Pgn == J1939PgnAcknowledgement.AcknowledgementPgn &&
            J1939PgnAcknowledgement.Decode(receivedAck.Payload,
                J1939DmDiagnostics.Dm1Pgn).Control == 1,
            "Receive skips another PGN acknowledgement and delivers correlated NACK.");
        var normalAckRx = new FakeCan();
        normalAckRx.Incoming.Enqueue(new(
            J1939ClassicTpSession.ArbitrationId(
                J1939PgnAcknowledgement.AcknowledgementPgn, 6,
                o.RemoteAddress, o.LocalAddress), false,
            [1, 0xFF, 0xFF, 0xFF, o.LocalAddress, 0xCA, 0xFE, 0]));
        Reject<TimeoutException>(() => Receive(normalAckRx,
            ackOptions with { AcceptAcknowledgement = false }),
            "Ordinary J1939 receiving does not treat ACKM as requested data.");

        var requestCli = StandardProtocolCli.ParseJ1939([
            StandardProtocolCli.J1939Command, "--adapter-index", "0",
            "--baud", "250000", "--source", "0xF1", "--target", "0x10",
            "--pgn", "0xFECA", "--mode", "request"]);
        Check(requestCli.Mode == "request" &&
            requestCli.Settings.Pgn == J1939DmDiagnostics.Dm1Pgn &&
            requestCli.Request.SequenceEqual(new byte[] {0xCA, 0xFE, 0x00}),
            "J1939 CLI creates standard directed DM1 request without raw hex.");
        Reject<ArgumentException>(() => StandardProtocolCli.ParseJ1939([
            StandardProtocolCli.J1939Command, "--adapter-index", "0",
            "--baud", "250000", "--source", "0xF1", "--target", "0x10",
            "--pgn", "0xFECA", "--mode", "request", "--payload", "AA"]),
            "J1939 request mode forbids additional arbitrary TX payload.");

        var CLI = StandardProtocolCli.ParseJ1939([
            StandardProtocolCli.J1939Command,
            "--adapter-index", "0", "--baud", "250000",
            "--source", "0xF1", "--target", "0x10",
            "--pgn", "0xF004", "--mode", "send",
            "--payload", "01020304"]);
        Check(CLI.Settings.LocalAddress == 0xF1 &&
            CLI.Settings.RemoteAddress == 0x10 &&
            CLI.Settings.Pgn == 0xF004 &&
            CLI.Request.SequenceEqual(new byte[] {1, 2, 3, 4}),
            "J1939 transmit CLI can select real source/target/PGN with arbitrary data.");
        var listen = StandardProtocolCli.ParseJ1939([
            StandardProtocolCli.J1939Command,
            "--adapter-index", "0", "--baud", "250000",
            "--source", "0xF1", "--target", "0x10",
            "--pgn", "0xF004", "--mode", "receive"]);
        Check(listen.Request.Length == 0 && listen.Mode == "receive",
            "J1939 receive-only mode needs no emission or write permission.");
        Reject<ArgumentException>(() => StandardProtocolCli.ParseJ1939([
            StandardProtocolCli.J1939Command,
            "--adapter-index", "0", "--baud", "250000",
            "--source", "0xF1", "--target", "0x10",
            "--pgn", "0xEF44", "--mode", "send",
            "--payload", "01"]),
            "CLI rejects PGN that encodes destination incorrectly.");

        return count;
    }
}
