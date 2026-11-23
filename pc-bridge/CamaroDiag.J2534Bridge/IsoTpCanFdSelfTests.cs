namespace CamaroDiag.J2534Bridge;

/// <summary>No J2534 driver, network interface, or actual ECU used.</summary>
internal static class IsoTpCanFdSelfTests
{
    private sealed class QueueLink : IsoTpCanFdSession.ICanLink
    {
        internal readonly Queue<IsoTpCanFdSession.CanFrame> Replies = new();
        internal readonly List<IsoTpCanFdSession.CanFrame> Sent = [];
        internal readonly List<TimeSpan> Separations = [];

        public Task SendAsync(IsoTpCanFdSession.CanFrame frame, CancellationToken token)
        {
            token.ThrowIfCancellationRequested();
            Sent.Add(new(frame.Id, frame.IsFd, frame.Data.ToArray()));
            return Task.CompletedTask;
        }

        public Task<IsoTpCanFdSession.CanFrame> ReceiveAsync(
            TimeSpan timeout, CancellationToken token)
        {
            token.ThrowIfCancellationRequested();
            if (Replies.Count == 0) throw new TimeoutException("Simulated missing CAN response.");
            return Task.FromResult(Replies.Dequeue());
        }

        public Task WaitMinimumSeparationAsync(TimeSpan separation, CancellationToken token)
        {
            token.ThrowIfCancellationRequested();
            Separations.Add(separation);
            return Task.CompletedTask;
        }
    }

    internal static int Run()
    {
        var checkedCount = 0;
        void Check(bool ok, string label)
        {
            checkedCount++;
            if (!ok) throw new InvalidOperationException("ISO-TP CAN-FD failed: " + label);
        }
        void Reject<T>(Action action, string label) where T : Exception
        {
            var failed = false;
            try { action(); }
            catch (T) { failed = true; }
            Check(failed, label);
        }

        static void Send(QueueLink link, IsoTpCanFdSession.Settings settings,
            byte[] bytes) => IsoTpCanFdSession.SendAsync(link, settings, bytes,
                TimeSpan.FromSeconds(1), CancellationToken.None).GetAwaiter().GetResult();
        static byte[] Receive(QueueLink link, IsoTpCanFdSession.Settings settings) =>
            IsoTpCanFdSession.ReceiveAsync(link, settings,
                TimeSpan.FromSeconds(1), CancellationToken.None).GetAwaiter().GetResult();
        static IsoTpCanFdSession.CanFrame RX(bool fd, params byte[] data) =>
            new(0x7E8, fd, data);
        static byte[] Fill(int count) => Enumerable.Range(0, count)
            .Select(i => (byte)(i & 0xFF)).ToArray();

        var classic = new IsoTpCanFdSession.Settings(0x7E0, 0x7E8, 8, false);
        var fd12 = new IsoTpCanFdSession.Settings(0x7E0, 0x7E8, 12, true,
            BlockSize: 1, StMin: 5);

        var single = new QueueLink();
        Send(single, classic, [0x22, 0xF1, 0x90]);
        Check(single.Sent.Count == 1 && single.Sent[0].Data.Length == 8 &&
            single.Sent[0].Data[0] == 3 && single.Sent[0].Data[1] == 0x22 &&
            single.Sent[0].Data[4] == 0xCC, "Classic CAN single frame/8-byte DLC.");
        var rxClassic = new QueueLink();
        rxClassic.Replies.Enqueue(RX(false, 3, 0x62, 0xF1, 0x90, 0, 0, 0, 0));
        Check(Receive(rxClassic, classic).SequenceEqual(new byte[] {0x62, 0xF1, 0x90}),
            "Classic single-frame reception.");

        var escaped = new QueueLink();
        Send(escaped, fd12, Fill(10));
        Check(escaped.Sent.Count == 1 && escaped.Sent[0].Data[0] == 0 &&
            escaped.Sent[0].Data[1] == 10 &&
            escaped.Sent[0].Data.AsSpan(2).SequenceEqual(Fill(10)),
            "CAN FD SF_DL escape for CAN_DL > 8.");
        var rxEscaped = new QueueLink();
        rxEscaped.Replies.Enqueue(RX(true, escaped.Sent[0].Data));
        Check(Receive(rxEscaped, fd12).SequenceEqual(Fill(10)),
            "CAN FD escaped single frame received.");

        // ISO 15765-2 extended addressing adds a logical address in
        // every SF, FF, CF and FC (not just the initial request).
        var extendedAddress = new IsoTpCanFdSession.Settings(0x7E0, 0x7E8,
            8, false, BlockSize: 1, TxAddress: 0xF1, RxAddress: 0x10);
        var addressedSingle = new QueueLink();
        Send(addressedSingle, extendedAddress, [0x22, 0xF1, 0x90]);
        Check(addressedSingle.Sent.Count == 1 &&
            addressedSingle.Sent[0].Data[0] == 0xF1 &&
            addressedSingle.Sent[0].Data[1] == 3 &&
            addressedSingle.Sent[0].Data[2] == 0x22,
            "Extended addressing sends logical target before SF PCI.");

        var addressedRx = new QueueLink();
        addressedRx.Replies.Enqueue(RX(false, 0x11, 3, 0, 0, 0, 0, 0, 0));
        addressedRx.Replies.Enqueue(RX(false, 0x10, 3, 0x62, 0xF1, 0x90, 0, 0, 0));
        Check(Receive(addressedRx, extendedAddress).SequenceEqual(
            new byte[] {0x62, 0xF1, 0x90}),
            "Extended address filters unrelated traffic on the same CAN ID.");

        var addressedMulti = new QueueLink();
        addressedMulti.Replies.Enqueue(RX(false, 0x10, 0x30, 0, 0, 0, 0, 0, 0));
        addressedMulti.Replies.Enqueue(RX(false, 0x10, 0x30, 0, 0, 0, 0, 0, 0));
        addressedMulti.Replies.Enqueue(RX(false, 0x10, 0x30, 0, 0, 0, 0, 0, 0));
        Send(addressedMulti, extendedAddress, Fill(19));
        Check(addressedMulti.Sent.Count == 4 &&
            addressedMulti.Sent[0].Data[0] == 0xF1 &&
            addressedMulti.Sent[0].Data[1] == 0x10 &&
            addressedMulti.Sent[0].Data[2] == 19 &&
            addressedMulti.Sent[1].Data[0] == 0xF1 &&
            addressedMulti.Sent[1].Data[1] == 0x21 &&
            addressedMulti.Sent[2].Data[1] == 0x22 &&
            addressedMulti.Sent[3].Data[1] == 0x23,
            "Extended addressing correctly segments 5 + 6 + 6 + 2 bytes.");

        var addressedReassembly = new QueueLink();
        foreach (var frame in addressedMulti.Sent)
            addressedReassembly.Replies.Enqueue(new(0x7E8, false,
                new byte[] {0x10}.Concat(frame.Data.AsSpan(1).ToArray()).ToArray()));
        Check(Receive(addressedReassembly, extendedAddress)
            .SequenceEqual(Fill(19)) &&
            addressedReassembly.Sent.Count == 3 &&
            addressedReassembly.Sent.All(frame =>
                frame.Data[0] == 0xF1 && frame.Data[1] == 0x30),
            "Extended addressing reassembles CF and emits addressed FC windows.");

        Reject<ArgumentException>(() => new IsoTpCanFdSession.Settings(
            0x7E0, 0x7E8, 8, false, TxAddress: 0xF1).Validate(),
            "One-sided extended address is rejected.");
        var extendedCommand = StandardProtocolCli.ParseIsoTp([
            StandardProtocolCli.RawIsoTpCommand, "--adapter-index", "0",
            "--tx-id", "7E0", "--rx-id", "7E8", "--baud", "500000",
            "--fd", "false", "--tx-address", "0xF1",
            "--rx-address", "0x10", "--payload", "22F190"]);
        Check(extendedCommand.Settings.TxAddress == 0xF1 &&
            extendedCommand.Settings.RxAddress == 0x10 &&
            !extendedCommand.ExtendedId,
            "CLI ISO-TP logical addresses do not imply 29-bit CAN IDs.");

        var sharedCanId = new IsoTpCanFdSession.Settings(0x7E0, 0x7E0,
            8, false, TxAddress: 0xF1, RxAddress: 0x10);
        sharedCanId.Validate();
        var sharedAddressTx = new QueueLink();
        Send(sharedAddressTx, sharedCanId, [0x22]);
        Check(sharedAddressTx.Sent[0].Id == 0x7E0 &&
            sharedAddressTx.Sent[0].Data[0] == 0xF1,
            "Distinct logical addresses permit one shared physical CAN ID.");
        Reject<ArgumentException>(() => new IsoTpCanFdSession.Settings(
            0x7E0, 0x7E0, 8, false, TxAddress: 0xF1,
            RxAddress: 0xF1).Validate(),
            "Shared CAN ID cannot use identical TX and RX logical address.");

        var mixed = new IsoTpCanFdSession.Settings(0x18DA10F1, 0x18DAF110,
            8, false, TxAddress: 0xA7, RxAddress: 0xA7,
            MixedAddressing: true);
        var mixedTx = new QueueLink();
        Send(mixedTx, mixed, [0x22, 0xF1, 0x90]);
        Check(mixedTx.Sent.Count == 1 && mixedTx.Sent[0].Id == mixed.TxId &&
            mixedTx.Sent[0].Data[0] == 0xA7 &&
            mixedTx.Sent[0].Data[1] == 3,
            "Mixed ISO-TP keeps one N_AE in the first data byte.");
        var mixedRx = new QueueLink();
        mixedRx.Replies.Enqueue(new(mixed.RxId, false,
            [0xA7, 3, 0x62, 0xF1, 0x90, 0xCC, 0xCC, 0xCC]));
        Check(Receive(mixedRx, mixed).SequenceEqual(
            new byte[] {0x62, 0xF1, 0x90}),
            "Mixed ISO-TP correlates response by RX CAN ID and N_AE.");
        var mixedMultiTx = new QueueLink();
        mixedMultiTx.Replies.Enqueue(new(mixed.RxId, false,
            [0xA7, 0x30, 0, 0, 0, 0, 0, 0]));
        Send(mixedMultiTx, mixed, Fill(19));
        Check(mixedMultiTx.Sent.Count == 4 &&
            mixedMultiTx.Sent[0].Data[0] == 0xA7 &&
            mixedMultiTx.Sent[0].Data[1] == 0x10 &&
            mixedMultiTx.Sent[1].Data[1] == 0x21 &&
            mixedMultiTx.Sent[2].Data[1] == 0x22 &&
            mixedMultiTx.Sent[3].Data[1] == 0x23,
            "Mixed-addressed multi-frame TX uses the same N_AE for FF and CF.");
        var mixedMultiRx = new QueueLink();
        foreach (var frame in mixedMultiTx.Sent)
            mixedMultiRx.Replies.Enqueue(new(mixed.RxId, false,
                frame.Data.ToArray()));
        Check(Receive(mixedMultiRx, mixed).SequenceEqual(Fill(19)) &&
            mixedMultiRx.Sent.Count == 1 &&
            mixedMultiRx.Sent[0].Id == mixed.TxId &&
            mixedMultiRx.Sent[0].Data[0] == 0xA7 &&
            mixedMultiRx.Sent[0].Data[1] == 0x30,
            "Mixed-addressed RX reassembles 19 bytes and emits addressed FC.");
        Reject<ArgumentException>(() =>
            (mixed with { RxAddress = 0xA8 }).Validate(),
            "Mixed ISO-TP rejects different TX/RX address extensions.");
        Reject<ArgumentException>(() =>
            (mixed with { RxId = mixed.TxId }).Validate(),
            "Mixed ISO-TP requires different CAN IDs.");
        var mixedCli = StandardProtocolCli.ParseIsoTp([
            StandardProtocolCli.RawIsoTpCommand, "--adapter-index", "0",
            "--tx-id", "18DA10F1", "--rx-id", "18DAF110",
            "--baud", "500000", "--extended", "true", "--fd", "false",
            "--mixed-address", "0xA7", "--payload", "22F190"]);
        Check(mixedCli.ExtendedId && mixedCli.Settings.MixedAddressing &&
            mixedCli.Settings.TxAddress == 0xA7 &&
            mixedCli.Settings.RxAddress == 0xA7,
            "CLI uses mixed 29-bit ISO-TP address extension explicitly.");
        var mixed11Bit = StandardProtocolCli.ParseIsoTp([
            StandardProtocolCli.RawIsoTpCommand, "--adapter-index", "0",
            "--tx-id", "7E0", "--rx-id", "7E8",
            "--baud", "500000", "--fd", "false",
            "--mixed-address", "0xA7", "--payload", "22F190"]);
        Check(mixed11Bit.Settings.MixedAddressing &&
            !mixed11Bit.ExtendedId &&
            mixed11Bit.Settings.TxAddress == 0xA7,
            "ISO-TP mixed N_AE also supports CAN 11-bit identifiers.");
        Reject<ArgumentException>(() => StandardProtocolCli.ParseIsoTp([
            StandardProtocolCli.RawIsoTpCommand, "--adapter-index", "0",
            "--tx-id", "7E0", "--rx-id", "7E8", "--baud", "500000",
            "--fd", "false", "--mixed-address", "0xA7",
            "--tx-address", "0xF1", "--payload", "22F190"]),
            "Mixed N_AE cannot be combined with extended TX logical address.");

        var fdExtended = new IsoTpCanFdSession.Settings(0x7E0, 0x7E8,
            12, true, BlockSize: 0, TxAddress: 0xF1, RxAddress: 0x10);
        var fdAddressedSingle = new QueueLink();
        Send(fdAddressedSingle, fdExtended, Fill(9));
        Check(fdAddressedSingle.Sent.Count == 1 &&
            fdAddressedSingle.Sent[0].Data[0] == 0xF1 &&
            fdAddressedSingle.Sent[0].Data[1] == 0 &&
            fdAddressedSingle.Sent[0].Data[2] == 9,
            "CAN FD extended addressing uses escaped SF and adjusted capacity.");
        var fdAddressedMulti = new QueueLink();
        fdAddressedMulti.Replies.Enqueue(RX(true,
            0x10, 0x30, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0));
        Send(fdAddressedMulti, fdExtended, Fill(20));
        Check(fdAddressedMulti.Sent.Count == 3 &&
            fdAddressedMulti.Sent[0].Data[0] == 0xF1 &&
            fdAddressedMulti.Sent[0].Data[1] == 0x10 &&
            fdAddressedMulti.Sent[0].Data[2] == 20 &&
            fdAddressedMulti.Sent[1].Data[1] == 0x21 &&
            fdAddressedMulti.Sent[2].Data[1] == 0x22,
            "CAN FD extended addressing segments first frame and CF.");
        var fdAddressedReceive = new QueueLink();
        foreach (var frame in fdAddressedMulti.Sent)
            fdAddressedReceive.Replies.Enqueue(new(0x7E8, true,
                new byte[] {0x10}.Concat(frame.Data.AsSpan(1).ToArray()).ToArray()));
        Check(Receive(fdAddressedReceive, fdExtended).SequenceEqual(Fill(20)) &&
            fdAddressedReceive.Sent.Count == 1 &&
            fdAddressedReceive.Sent[0].Data[0] == 0xF1 &&
            fdAddressedReceive.Sent[0].Data[1] == 0x30,
            "CAN FD addressed response reassembly and FC.");

        var wrongLogicalFc = new QueueLink();
        wrongLogicalFc.Replies.Enqueue(RX(false, 0x22, 0x30, 0, 0, 0, 0, 0, 0));
        wrongLogicalFc.Replies.Enqueue(RX(false, 0x10, 0x30, 0, 0, 0, 0, 0, 0));
        Send(wrongLogicalFc,
            extendedAddress with { BlockSize = 0 }, Fill(8));
        Check(wrongLogicalFc.Sent.Count == 2,
            "Unrelated logical FlowControl ignored on shared arbitration ID.");

        var multi = new QueueLink();
        multi.Replies.Enqueue(RX(true, 0x30, 1, 5, 0, 0, 0, 0, 0, 0, 0, 0, 0));
        multi.Replies.Enqueue(RX(true, 0x30, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0));
        Send(multi, fd12, Fill(35));
        Check(multi.Sent.Count == 4 && multi.Sent[0].Data[0] == 0x10 &&
            multi.Sent[0].Data[1] == 35 &&
            multi.Sent[1].Data[0] == 0x21 &&
            multi.Sent[2].Data[0] == 0x22 &&
            multi.Sent[3].Data[0] == 0x23,
            "CAN FD first frame/flow-control window/consecutive sequences.");
        Check(multi.Separations.Count == 3 &&
            multi.Separations[0] == TimeSpan.FromMilliseconds(5) &&
            multi.Separations[1] == TimeSpan.Zero,
            "Sender honors peer BS and STmin in each window.");

        var reconstruct = new QueueLink();
        foreach (var frame in multi.Sent)
            reconstruct.Replies.Enqueue(new(0x7E8, true, frame.Data));
        Check(Receive(reconstruct, fd12).SequenceEqual(Fill(35)) &&
            reconstruct.Sent.Count == 3,
            "Fully round-tripped CAN FD PDU and repeated CTS per receiver BS=1.");

        // Local TX/FC DLC may be 64 while the ECU sends RX FF/CF
        // with DLC 12. ISO 15765-2 derives RX_DL from the peer's FF.
        var differingDl = new QueueLink();
        foreach (var frame in multi.Sent)
            differingDl.Replies.Enqueue(new(0x7E8, true, frame.Data));
        var local64 = new IsoTpCanFdSession.Settings(0x7E0, 0x7E8,
            64, true, BlockSize: 0);
        Check(Receive(differingDl, local64).SequenceEqual(Fill(35)) &&
            differingDl.Sent.Count == 1 &&
            differingDl.Sent[0].Data.Length == 64,
            "Peer RX_DL=12 is accepted independently of local FC TX_DL=64.");

        var corruptedPeerDl = new QueueLink();
        corruptedPeerDl.Replies.Enqueue(new(0x7E8, true, multi.Sent[0].Data));
        corruptedPeerDl.Replies.Enqueue(new(0x7E8, true,
            multi.Sent[1].Data.AsSpan(0, 8).ToArray()));
        Reject<InvalidDataException>(() => Receive(corruptedPeerDl, local64),
            "Premature short peer CF rejected using RX_DL from its FF.");

        var tooLongCf = new QueueLink();
        tooLongCf.Replies.Enqueue(new(0x7E8, true, multi.Sent[0].Data));
        tooLongCf.Replies.Enqueue(new(0x7E8, true,
            new byte[] {0x21}.Concat(Fill(15)).ToArray()));
        Reject<InvalidDataException>(() => Receive(tooLongCf, local64),
            "Peer CF longer than RX_DL announced in FF is rejected.");

        var undersizedFirst = new QueueLink();
        undersizedFirst.Replies.Enqueue(RX(true, 0x10, 0x20, 0x11, 0x22));
        Reject<InvalidDataException>(() => Receive(undersizedFirst, fd12),
            "Segmented FirstFrame with less than eight CAN bytes is rejected.");

        var longTx = new QueueLink();
        longTx.Replies.Enqueue(RX(true, 0x30, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0));
        var fd64 = new IsoTpCanFdSession.Settings(0x7E0, 0x7E8, 64, true,
            BlockSize: 0, StMin: 0);
        var giant = Fill(4300);
        Send(longTx, fd64, giant);
        Check(longTx.Sent.Count > 60 && longTx.Sent[0].Data[0] == 0x10 &&
            longTx.Sent[0].Data[1] == 0x00 &&
            longTx.Sent[0].Data[2] == 0 && longTx.Sent[0].Data[3] == 0 &&
            longTx.Sent[0].Data[4] == (4300 >> 8) &&
            longTx.Sent[0].Data[5] == (4300 & 0xFF),
            "Extended first-frame 32-bit payload length.");
        var giantRx = new QueueLink();
        foreach (var frame in longTx.Sent)
            giantRx.Replies.Enqueue(new(0x7E8, true, frame.Data));
        Check(Receive(giantRx, fd64).SequenceEqual(giant),
            "Round-trip extended FF length and wrapped 4-bit CF sequence.");

        var wait = new QueueLink();
        wait.Replies.Enqueue(RX(false, 0x31, 0, 0, 0, 0, 0, 0, 0));
        wait.Replies.Enqueue(RX(false, 0x30, 0, 0xF2, 0, 0, 0, 0, 0));
        Send(wait, classic, Fill(14));
        Check(wait.Separations.Count == 2 &&
            wait.Separations[0] == TimeSpan.FromTicks(2000),
            "FC/WAIT then CTS microsecond STmin decoded correctly.");

        var boundedWait = new QueueLink();
        boundedWait.Replies.Enqueue(RX(false, 0x31, 0, 0, 0, 0, 0, 0, 0));
        boundedWait.Replies.Enqueue(RX(false, 0x31, 0, 0, 0, 0, 0, 0, 0));
        boundedWait.Replies.Enqueue(RX(false, 0x30, 0, 0, 0, 0, 0, 0, 0));
        Send(boundedWait, classic with { MaxWaitFrames = 2 }, Fill(14));
        Check(boundedWait.Sent.Count == 3,
            "Configured WFTmax=2 accepts two FC WAIT responses before CTS.");

        var tooManyWaits = new QueueLink();
        tooManyWaits.Replies.Enqueue(RX(false, 0x31, 0, 0, 0, 0, 0, 0, 0));
        Reject<TimeoutException>(() => Send(tooManyWaits,
            classic with { MaxWaitFrames = 0 }, Fill(14)),
            "WFTmax=0 rejects the first peer FC WAIT.");
        Check(tooManyWaits.Sent.Count == 1,
            "No consecutive frames after exceeding configured WFTmax.");
        Reject<ArgumentOutOfRangeException>(() => Send(new QueueLink(),
            classic with { MaxWaitFrames = 256 }, [0x22]),
            "WFTmax outside supported 0..255 is rejected.");

        var configurableWait = StandardProtocolCli.ParseIsoTp([
            StandardProtocolCli.RawIsoTpCommand, "--adapter-index", "0",
            "--tx-id", "7E0", "--rx-id", "7E8", "--baud", "500000",
            "--fd", "false", "--wftmax", "7", "--payload", "22F190"]);
        Check(configurableWait.Settings.MaxWaitFrames == 7,
            "ISO-TP CLI exposes FC WAIT-frame tolerance.");

        var overflow = new QueueLink();
        overflow.Replies.Enqueue(RX(false, 0x32, 0, 0, 0, 0, 0, 0, 0));
        Reject<IOException>(() => Send(overflow, classic, Fill(16)),
            "Receiver overflow aborts any further CF transmission.");
        Check(overflow.Sent.Count == 1, "Only FF on FC overflow.");

        var wrongSeq = new QueueLink();
        wrongSeq.Replies.Enqueue(RX(true, multi.Sent[0].Data));
        wrongSeq.Replies.Enqueue(RX(true,
            new byte[] {0x22}.Concat(Fill(11)).ToArray()));
        Reject<InvalidDataException>(() => Receive(wrongSeq, fd12),
            "Wrong CF sequence number fails.");
        var wrongDlc = new QueueLink();
        wrongDlc.Replies.Enqueue(RX(true, 0, 3, 0x62, 0xF1, 0x90,
            0, 0, 0, 0));
        Reject<ArgumentOutOfRangeException>(() => Receive(wrongDlc, fd12),
            "CAN-FD invalid DLC is rejected at decoder.");
        Reject<InvalidDataException>(() => IsoTpCanFdSession.DecodeStMin(0x80),
            "Reserved STmin explicitly rejected.");
        Reject<ArgumentException>(() => Send(new QueueLink(),
            new IsoTpCanFdSession.Settings(0x7E0, 0x7E0, 12, true), [0x22]),
            "Identical physical TX/RX arbitration IDs rejected.");
        Reject<ArgumentOutOfRangeException>(() => Send(new QueueLink(), fd64,
            new byte[IsoTpCanFdSession.MaximumPayloadBytes + 1]),
            "Bound application allocation against malicious length.");

        using var cancelled = new CancellationTokenSource();
        cancelled.Cancel();
        var none = new QueueLink();
        Reject<OperationCanceledException>(() =>
            IsoTpCanFdSession.SendAsync(none, classic, [0x22],
                TimeSpan.FromSeconds(1), cancelled.Token).GetAwaiter().GetResult(),
            "Cancelled ISO-TP send never emits a frame.");
        Check(none.Sent.Count == 0, "No TX after cancellation.");

        var raw = IsoTpJ2534RawCanLink.Pack(0x7E0,
            [0x03, 0x22, 0xF1, 0x90, 0, 0, 0, 0], extended: false);
        Check(Convert.ToHexString(raw) == "000007E00322F19000000000",
            "J2534 raw CAN frame prepends big-endian physical arbitration ID.");
        Check(IsoTpJ2534RawCanLink.Unpack(raw, 0, false, false).Id == 0x7E0,
            "Classic J2534 CAN frame reconstructed without ISO-TP double wrapping.");
        var rawFd = IsoTpJ2534RawCanLink.Pack(0x7E0,
            Enumerable.Repeat((byte)0xF0, 64).ToArray(), extended: false);
        Check(IsoTpJ2534RawCanLink.Unpack(rawFd, 0x0010_0000,
            true, false).Data.Length == 64,
            "FD_CAN_FORMAT flag and raw DLC 64 accepted.");
        Reject<InvalidDataException>(() =>
            IsoTpJ2534RawCanLink.Unpack(rawFd, 0, true, false),
            "Classical frame presented as CAN-FD without vendor FD flag refused.");
        Reject<ArgumentOutOfRangeException>(() =>
            IsoTpJ2534RawCanLink.Pack(0x800,
                [1, 2, 3], extended: false),
            "J2534 11-bit arbitration ID overflow rejected.");

        var protocolIsoTp = StandardProtocolCli.ParseIsoTp([
            StandardProtocolCli.RawIsoTpCommand, "--adapter-index", "0",
            "--tx-id", "7E0", "--rx-id", "7E8",
            "--baud", "500000", "--fd", "false", "--obd-identify"]);
        Check(protocolIsoTp.Request.SequenceEqual(
            new byte[] {0x22, 0xF8, 0x10}),
            "ISO-TP OBDonUDS detection composes SAE J1979-2 F810 request.");
        Reject<ArgumentException>(() => StandardProtocolCli.ParseIsoTp([
            StandardProtocolCli.RawIsoTpCommand, "--adapter-index", "0",
            "--tx-id", "7E0", "--rx-id", "7E8",
            "--baud", "500000", "--fd", "false",
            "--mode", "receive", "--obd-identify"]),
            "Passive ISO-TP reception cannot perform protocol detection TX.");

        var udsStrictIsoTp = StandardProtocolCli.ParseIsoTp([
            StandardProtocolCli.RawIsoTpCommand,
            "--adapter-index", "0", "--tx-id", "7E0", "--rx-id", "7E8",
            "--baud", "500000", "--fd", "false",
            "--payload", "22F190", "--uds-validate", "true"]);
        Check(udsStrictIsoTp.ValidateUds &&
            udsStrictIsoTp.Request.SequenceEqual(
                new byte[] {0x22, 0xF1, 0x90}),
            "ISO-TP opt-in UDS validation retains arbitrary read and write SID.");
        var opaqueIsoTp = StandardProtocolCli.ParseIsoTp([
            StandardProtocolCli.RawIsoTpCommand,
            "--adapter-index", "0", "--tx-id", "7E0", "--rx-id", "7E8",
            "--baud", "500000", "--fd", "false", "--payload", "AABB"]);
        Check(!opaqueIsoTp.ValidateUds,
            "ISO-TP arbitrary manufacturer protocol is not forced into UDS.");
        Reject<ArgumentException>(() => StandardProtocolCli.ParseIsoTp([
            StandardProtocolCli.RawIsoTpCommand,
            "--adapter-index", "0", "--tx-id", "7E0", "--rx-id", "7E8",
            "--baud", "500000", "--fd", "false",
            "--mode", "receive", "--uds-validate", "true"]),
            "Passive ISO-TP cannot claim an active UDS request/response correlation.");
        var monitorIsoTp = StandardProtocolCli.ParseIsoTp([
            StandardProtocolCli.RawIsoTpCommand, "--adapter-index", "0",
            "--tx-id", "7E0", "--rx-id", "7E8", "--baud", "500000",
            "--fd", "false", "--obd-did", "0xF600"]);
        Check(monitorIsoTp.Request.SequenceEqual(
            new byte[] {0x22, 0xF6, 0x00}),
            "ISO-TP OBDonUDS monitoring DID request is standardized.");
        var vehicleIsoTp = StandardProtocolCli.ParseIsoTp([
            StandardProtocolCli.RawIsoTpCommand, "--adapter-index", "0",
            "--tx-id", "7E0", "--rx-id", "7E8", "--baud", "500000",
            "--fd", "false", "--obd-did", "0xF810"]);
        Check(vehicleIsoTp.Request.SequenceEqual(
            new byte[] {0x22, 0xF8, 0x10}),
            "ISO-TP OBDonUDS F810 protocol information DID can be queried.");

        var wwhIsoTp = StandardProtocolCli.ParseIsoTp([
            StandardProtocolCli.RawIsoTpCommand, "--adapter-index", "0",
            "--tx-id", "7E0", "--rx-id", "7E8", "--baud", "500000",
            "--fd", "false", "--obd-wwh-status", "0x08",
            "--obd-wwh-severity", "0x40"]);
        Check(wwhIsoTp.Request.SequenceEqual(
            new byte[] {0x19, 0x42, 0x33, 0x40, 0x08}) &&
            wwhIsoTp.ValidateUds,
            "ISO-TP WWH status DTC request is correlated to UDS response.");
        var wwhPermanentIsoTp = StandardProtocolCli.ParseIsoTp([
            StandardProtocolCli.RawIsoTpCommand, "--adapter-index", "0",
            "--tx-id", "7E0", "--rx-id", "7E8", "--baud", "500000",
            "--fd", "false", "--obd-wwh-permanent"]);
        Check(wwhPermanentIsoTp.Request.SequenceEqual(
            new byte[] {0x19, 0x55, 0x33}),
            "ISO-TP WWH permanent emission DTC selector.");
        Reject<ArgumentException>(() => StandardProtocolCli.ParseIsoTp([
            StandardProtocolCli.RawIsoTpCommand, "--adapter-index", "0",
            "--tx-id", "7E0", "--rx-id", "7E8", "--baud", "500000",
            "--fd", "false", "--mode", "receive",
            "--obd-wwh-permanent"]),
            "ISO-TP passive receive cannot silently request permanent DTCs.");

        var readinessIsoTp = StandardProtocolCli.ParseIsoTp([
            StandardProtocolCli.RawIsoTpCommand, "--adapter-index", "0",
            "--tx-id", "7E0", "--rx-id", "7E8", "--baud", "500000",
            "--fd", "false", "--obd-readiness-group", "0x06"]);
        Check(readinessIsoTp.Request.SequenceEqual(
                new byte[] {0x19, 0x56, 0x33, 0x06}) &&
            readinessIsoTp.ValidateUds,
            "ISO-TP readiness query enforces standard UDS reply correlation.");
        Reject<ArgumentException>(() => StandardProtocolCli.ParseIsoTp([
            StandardProtocolCli.RawIsoTpCommand, "--adapter-index", "0",
            "--tx-id", "7E0", "--rx-id", "7E8", "--baud", "500000",
            "--fd", "false", "--mode", "receive",
            "--obd-readiness-group", "0x06"]),
            "Passive ISO-TP mode forbids an outgoing readiness request.");

        var obdIsoTp = StandardProtocolCli.ParseIsoTp([
            StandardProtocolCli.RawIsoTpCommand, "--adapter-index", "0",
            "--tx-id", "7E0", "--rx-id", "7E8", "--baud", "500000",
            "--fd", "false", "--obd-did", "0xF40C"]);
        Check(obdIsoTp.Request.SequenceEqual(
            new byte[] {0x22, 0xF4, 0x0C}),
            "ISO-TP OBDonUDS emissions DID selector composes read request.");
        var obdDtcIsoTp = StandardProtocolCli.ParseIsoTp([
            StandardProtocolCli.RawIsoTpCommand, "--adapter-index", "0",
            "--tx-id", "7E0", "--rx-id", "7E8", "--baud", "500000",
            "--fd", "false", "--obd-dtc-mask", "0xFF"]);
        Check(obdDtcIsoTp.Request.SequenceEqual(
            new byte[] {0x19, 0x02, 0xFF}),
            "ISO-TP OBDonUDS DTC mask selector composes request.");
        Reject<ArgumentException>(() => StandardProtocolCli.ParseIsoTp([
            StandardProtocolCli.RawIsoTpCommand, "--adapter-index", "0",
            "--tx-id", "7E0", "--rx-id", "7E8", "--baud", "500000",
            "--fd", "false", "--mode", "receive", "--obd-did", "F40C"]),
            "Passive ISO-TP cannot silently transmit OBDonUDS DID request.");
        Reject<ArgumentException>(() => StandardProtocolCli.ParseIsoTp([
            StandardProtocolCli.RawIsoTpCommand, "--adapter-index", "0",
            "--tx-id", "7E0", "--rx-id", "7E8", "--baud", "500000",
            "--fd", "false", "--payload", "22F40C",
            "--obd-did", "F40C"]),
            "ISO-TP request selectors cannot combine raw and OBD payloads.");

        var fdCommand = StandardProtocolCli.ParseIsoTp([
            StandardProtocolCli.RawIsoTpCommand, "--adapter-index", "0",
            "--tx-id", "7E0", "--rx-id", "7E8",
            "--baud", "500000", "--fd", "true", "--tx-dl", "64",
            "--brs", "true", "--data-baud", "2000000",
            "--payload", "2EF19001"]);
        Check(fdCommand.Settings.TxFrameLength == 64 &&
            fdCommand.Settings.CanFd && fdCommand.Brs &&
            fdCommand.DataBaud == 2000000 &&
            fdCommand.Request.SequenceEqual(new byte[] {0x2E, 0xF1, 0x90, 1}),
            "CLI negotiates FD bit-rate switching and accepts write UDS SID.");
        var receiveOnly = StandardProtocolCli.ParseIsoTp([
            StandardProtocolCli.RawIsoTpCommand, "--adapter-index", "0",
            "--tx-id", "7E0", "--rx-id", "7E8", "--baud", "500000",
            "--fd", "false", "--mode", "receive"]);
        Check(receiveOnly.Mode == "receive" && receiveOnly.Request.Length == 0,
            "CLI receives ISO-TP without constructing a diagnostic request.");
        Reject<ArgumentException>(() => StandardProtocolCli.ParseIsoTp([
            StandardProtocolCli.RawIsoTpCommand, "--adapter-index", "0",
            "--tx-id", "7E0", "--rx-id", "7E8", "--baud", "500000",
            "--fd", "false", "--mode", "receive", "--payload", "22F190"]),
            "Receive-only CLI rejects an outgoing diagnostic payload.");
        Reject<ArgumentException>(() => StandardProtocolCli.ParseIsoTp([
            StandardProtocolCli.RawIsoTpCommand, "--adapter-index", "0",
            "--tx-id", "7E0", "--rx-id", "7E8",
            "--baud", "500000", "--brs", "true", "--payload", "22F190"]),
            "BRS without actual vendor data-phase bitrate is not presented as operational.");

        return checkedCount;
    }
}
