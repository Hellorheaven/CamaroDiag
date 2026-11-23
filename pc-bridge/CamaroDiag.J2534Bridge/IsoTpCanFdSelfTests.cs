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

    private sealed class FakeRawJ2534 : IsoTpJ2534RawCanLink.IRawChannel
    {
        internal readonly List<(byte[] Data, uint Flags)> Transmitted = [];
        internal readonly Queue<J2534StandardChannel.Frame?> Incoming = new();
        internal readonly List<int> RequestedTimeouts = [];
        internal bool FailTransmit;
        internal Action? AfterReceive;
        public uint ProtocolId { get; init; } = J2534StandardChannel.Can;
        public void Transmit(byte[] bytes, uint flags)
        {
            if (FailTransmit) throw new IOException("Simulated vendor write failure.");
            Transmitted.Add((bytes.ToArray(), flags));
        }
        public J2534StandardChannel.Frame? Receive(int timeoutMs)
        {
            RequestedTimeouts.Add(timeoutMs);
            var result = Incoming.Count == 0 ? null : Incoming.Dequeue();
            AfterReceive?.Invoke();
            return result;
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

        var classicSingleBoundary = new QueueLink();
        Send(classicSingleBoundary, classic, Fill(7));
        Check(classicSingleBoundary.Sent.Count == 1 &&
            classicSingleBoundary.Sent[0].Data[0] == 7,
            "Classic CAN accepts the largest seven-byte single frame.");
        var classicSegmentBoundary = new QueueLink();
        classicSegmentBoundary.Replies.Enqueue(RX(false, 0x30, 0, 0, 0, 0, 0, 0, 0));
        Send(classicSegmentBoundary, classic, Fill(8));
        Check(classicSegmentBoundary.Sent.Count == 2 &&
            classicSegmentBoundary.Sent[0].Data[0] == 0x10 &&
            classicSegmentBoundary.Sent[0].Data[1] == 8 &&
            classicSegmentBoundary.Sent[1].Data[0] == 0x21,
            "Classic CAN eight-byte payload uses FF/FC/CF, never invalid SF.");

        var classicOverCapacity = new QueueLink();
        // 0x00100001 (1 MiB + 1) is valid 32-bit FF_DL syntax, but
        // exceeds this application's explicit bounded reassembly buffer.
        classicOverCapacity.Replies.Enqueue(RX(false,
            0x10, 0x00, 0x00, 0x10, 0x00, 0x01, 0xAB, 0xCD));
        Reject<InvalidDataException>(() => Receive(classicOverCapacity, classic),
            "An over-capacity classic FirstFrame is rejected before allocation.");
        Check(classicOverCapacity.Sent.Count == 1 &&
            classicOverCapacity.Sent[0].Id == classic.TxId &&
            classicOverCapacity.Sent[0].Data.Length == 8 &&
            classicOverCapacity.Sent[0].Data[0] == 0x32,
            "Classic CAN receiver sends standard FC/OVFLW for oversized FF.");

        var fdOverCapacity = new QueueLink();
        fdOverCapacity.Replies.Enqueue(RX(true,
            0x10, 0x00, 0x00, 0x10, 0x00, 0x01,
            0x01, 0x02, 0x03, 0x04, 0x05, 0x06));
        Reject<InvalidDataException>(() => Receive(fdOverCapacity, fd12),
            "An over-capacity CAN-FD FirstFrame is rejected without allocating.");
        Check(fdOverCapacity.Sent.Count == 1 &&
            fdOverCapacity.Sent[0].Data.Length == 12 &&
            fdOverCapacity.Sent[0].Data[0] == 0x32,
            "CAN-FD FlowControl overflow follows the selected TX_DL.");

        var malformedFirst = new QueueLink();
        malformedFirst.Replies.Enqueue(RX(false,
            0x10, 0x05, 0, 0, 0, 0, 0, 0));
        Reject<InvalidDataException>(() => Receive(malformedFirst, classic),
            "Impossible FF shorter than SF capacity is a framing error.");
        Check(malformedFirst.Sent.Count == 0,
            "Malformed classic FF does not pretend to be receiver overflow.");

        var maxClassicPayload = Fill(4095);
        var maxClassicTx = new QueueLink();
        maxClassicTx.Replies.Enqueue(RX(false,
            0x30, 0x00, 0x00, 0, 0, 0, 0, 0));
        Send(maxClassicTx, classic, maxClassicPayload);
        Check(maxClassicTx.Sent.Count > 580 &&
            maxClassicTx.Sent[0].Data[0] == 0x1F &&
            maxClassicTx.Sent[0].Data[1] == 0xFF,
            "Classic CAN accepts FF_DL 4095 without a 32-bit escape.");
        var maxClassicRx = new QueueLink();
        foreach (var frame in maxClassicTx.Sent)
            maxClassicRx.Replies.Enqueue(new(0x7E8, false, frame.Data));
        Check(Receive(maxClassicRx, classic with { BlockSize = 0 })
            .SequenceEqual(maxClassicPayload) &&
            maxClassicRx.Sent.Count == 1 &&
            maxClassicRx.Sent[0].Data[0] == 0x30,
            "Classic CAN reassembles 4095 bytes and wraps CF sequence.");

        var longClassic = new QueueLink();
        longClassic.Replies.Enqueue(RX(false,
            0x30, 0, 0, 0, 0, 0, 0, 0));
        Send(longClassic, classic, Fill(4096));
        Check(longClassic.Sent[0].Data[0] == 0x10 &&
            longClassic.Sent[0].Data[1] == 0 &&
            longClassic.Sent[0].Data[2] == 0 &&
            longClassic.Sent[0].Data[3] == 0 &&
            longClassic.Sent[0].Data[4] == 0x10 &&
            longClassic.Sent[0].Data[5] == 0 &&
            longClassic.Sent.Count > 580,
            "Classic CAN 4096-byte payload uses the extended 32-bit FF_DL.");
        var longClassicRx = new QueueLink();
        foreach (var frame in longClassic.Sent)
            longClassicRx.Replies.Enqueue(new(0x7E8, false, frame.Data));
        Check(Receive(longClassicRx, classic with { BlockSize = 0 })
            .SequenceEqual(Fill(4096)),
            "Classic CAN 32-bit FF_DL is reassembled across sequence wraps.");

        Check(J2534StandardChannel.IsAuxiliaryReceiveStatus(
            J2534StandardChannel.RxTxEcho) &&
            J2534StandardChannel.IsAuxiliaryReceiveStatus(
                J2534StandardChannel.RxStartOfMessage) &&
            J2534StandardChannel.IsAuxiliaryReceiveStatus(
                J2534StandardChannel.RxTxIndication) &&
            !J2534StandardChannel.IsAuxiliaryReceiveStatus(0) &&
            !J2534StandardChannel.IsAuxiliaryReceiveStatus(0x100),
            "Raw CAN J2534 receive path already discards TX echoes/status, not 29-bit ID.");

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
        var addressedOverCapacity = new QueueLink();
        addressedOverCapacity.Replies.Enqueue(RX(false,
            0x10, 0x10, 0x00, 0x00, 0x10, 0x00, 0x01, 0xAB));
        Reject<InvalidDataException>(() =>
            Receive(addressedOverCapacity, extendedAddress),
            "ISO-TP extended logical address does not bypass receive limit.");
        Check(addressedOverCapacity.Sent.Count == 1 &&
            addressedOverCapacity.Sent[0].Data[0] == 0xF1 &&
            addressedOverCapacity.Sent[0].Data[1] == 0x32,
            "Overflow FC preserves selected TX logical addressing.");

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
            mixedCli.Settings.RxAddress == 0xA7 &&
            mixedCli.FilterTxFlags == 0x100u,
            "CLI sets 29-bit CAN filter TxFlags alongside mixed ISO-TP address.");
        var mixed11Bit = StandardProtocolCli.ParseIsoTp([
            StandardProtocolCli.RawIsoTpCommand, "--adapter-index", "0",
            "--tx-id", "7E0", "--rx-id", "7E8",
            "--baud", "500000", "--fd", "false",
            "--mixed-address", "0xA7", "--payload", "22F190"]);
        Check(mixed11Bit.Settings.MixedAddressing &&
            !mixed11Bit.ExtendedId &&
            mixed11Bit.FilterTxFlags == 0u &&
            mixed11Bit.Settings.TxAddress == 0xA7,
            "ISO-TP mixed N_AE retains 11-bit J2534 filter flags.");
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
            multi.Separations[0] == TimeSpan.Zero &&
            multi.Separations[1] == TimeSpan.Zero &&
            multi.Separations[2] == TimeSpan.Zero,
            "STmin does not delay the first CF or an FC-cleared zero-STmin block.");
        Check(IsoTpCanFdSession.RemainingCfSeparation(
                TimeSpan.FromMilliseconds(5), TimeSpan.Zero) ==
                    TimeSpan.FromMilliseconds(5) &&
            IsoTpCanFdSession.RemainingCfSeparation(
                TimeSpan.FromMilliseconds(5),
                TimeSpan.FromMilliseconds(2)) == TimeSpan.FromMilliseconds(3) &&
            IsoTpCanFdSession.RemainingCfSeparation(
                TimeSpan.FromMilliseconds(5),
                TimeSpan.FromMilliseconds(8)) == TimeSpan.Zero,
            "STmin subtracts elapsed time since last CF; no cumulative over-delay.");
        Reject<ArgumentOutOfRangeException>(() =>
            IsoTpCanFdSession.RemainingCfSeparation(
                TimeSpan.FromMilliseconds(5), TimeSpan.FromTicks(-1)),
            "Negative elapsed CF timestamp is not accepted.");

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
            wait.Separations[0] == TimeSpan.Zero &&
            wait.Separations[1] >= TimeSpan.Zero &&
            wait.Separations[1] <= TimeSpan.FromTicks(2000),
            "FC/WAIT then CTS: microsecond STmin separates CFs, not FC to first CF.");

        // A short FC/CTS processing interval is itself part of the
        // separation between two CF transmissions. It must not be
        // followed by a full extra STmin delay on the next block.
        var blockBoundary = new QueueLink();
        blockBoundary.Replies.Enqueue(RX(false, 0x30, 1, 0, 0, 0, 0, 0, 0));
        blockBoundary.Replies.Enqueue(RX(false, 0x30, 0, 5, 0, 0, 0, 0, 0));
        Send(blockBoundary, classic, Fill(20));
        Check(blockBoundary.Sent.Count == 3 &&
            blockBoundary.Separations.Count == 2 &&
            blockBoundary.Separations[0] == TimeSpan.Zero &&
            blockBoundary.Separations[1] <= TimeSpan.FromMilliseconds(5),
            "Reissued FC across BS boundary cannot reset CF-to-CF separation timing.");

        Check(IsoTpCanFdSession.IsReservedStMin(0x80) &&
            IsoTpCanFdSession.IsReservedStMin(0xF0) &&
            IsoTpCanFdSession.IsReservedStMin(0xFA) &&
            IsoTpCanFdSession.IsReservedStMin(0xFF) &&
            !IsoTpCanFdSession.IsReservedStMin(0x7F) &&
            !IsoTpCanFdSession.IsReservedStMin(0xF1) &&
            !IsoTpCanFdSession.IsReservedStMin(0xF9),
            "Reserved ISO-TP STmin ranges are classified without rounding valid codes.");
        Check(IsoTpCanFdSession.EffectiveTransferStMin(
                TimeSpan.Zero, true) == TimeSpan.FromMilliseconds(127) &&
            IsoTpCanFdSession.EffectiveTransferStMin(
                TimeSpan.FromTicks(1000), true) == TimeSpan.FromMilliseconds(127) &&
            IsoTpCanFdSession.EffectiveTransferStMin(
                TimeSpan.FromMilliseconds(5), false) ==
                    TimeSpan.FromMilliseconds(5),
            "Reserved STmin remains latched across a later FC with STmin zero.");
        var reservedThenClear = new QueueLink();
        reservedThenClear.Replies.Enqueue(RX(false,
            0x30, 1, 0x80, 0, 0, 0, 0, 0));
        reservedThenClear.Replies.Enqueue(RX(false,
            0x30, 0, 0, 0, 0, 0, 0, 0));
        Send(reservedThenClear, classic, Fill(20));
        Check(reservedThenClear.Sent.Count == 3 &&
            reservedThenClear.Separations.Count == 2 &&
            reservedThenClear.Separations[0] == TimeSpan.Zero &&
            reservedThenClear.Separations[1] >= TimeSpan.Zero &&
            reservedThenClear.Separations[1] <= TimeSpan.FromMilliseconds(127),
            "Reserved STmin transfer accepts subsequent FC and retains CF pacing.");

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
        Check(IsoTpCanFdSession.DecodeStMin(0x80) ==
                TimeSpan.FromMilliseconds(127) &&
            IsoTpCanFdSession.DecodeStMin(0xF0) ==
                TimeSpan.FromMilliseconds(127) &&
            IsoTpCanFdSession.DecodeStMin(0xFA) ==
                TimeSpan.FromMilliseconds(127) &&
            IsoTpCanFdSession.DecodeStMin(0xFF) ==
                TimeSpan.FromMilliseconds(127),
            "Reserved peer STmin values safely fall back to 127ms.");
        Reject<InvalidDataException>(() =>
            new IsoTpCanFdSession.Settings(0x7E0, 0x7E8,
                8, false, StMin: 0x80).Validate(),
            "Locally advertised reserved STmin is forbidden.");
        Reject<InvalidDataException>(() =>
            IsoTpCanFdSession.BuildFlowControl(8, 0, 8, 0xFA),
            "Constructed FC cannot advertise a reserved STmin.");

        var peerReservedMin = new QueueLink();
        peerReservedMin.Replies.Enqueue(RX(false,
            0x30, 0, 0x80, 0, 0, 0, 0, 0));
        Send(peerReservedMin, classic, Fill(14));
        Check(peerReservedMin.Separations.Count == 2 &&
            peerReservedMin.Separations[0] == TimeSpan.Zero &&
            peerReservedMin.Separations[1] >= TimeSpan.Zero &&
            peerReservedMin.Separations[1] <= TimeSpan.FromMilliseconds(127),
            "Reserved peer STmin regulates only CF-to-CF, not the first CF.");
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
        var extendedRaw = IsoTpJ2534RawCanLink.Pack(
            0x18DA10F1, [0x03, 0x22, 0xF1, 0x90], extended: true);
        Check(IsoTpJ2534RawCanLink.Unpack(
            extendedRaw, 0x100, false, true).Id == 0x18DA10F1,
            "29-bit J2534 CAN requires explicit CAN_29BIT_ID receive flag.");
        Reject<InvalidDataException>(() =>
            IsoTpJ2534RawCanLink.Unpack(extendedRaw, 0, false, true),
            "29-bit J2534 frame missing CAN_29BIT_ID cannot be misattributed.");
        Reject<InvalidDataException>(() =>
            IsoTpJ2534RawCanLink.Unpack(raw, 0x100, false, false),
            "11-bit J2534 frame cannot masquerade as 29-bit extended arbitration.");
        Reject<InvalidDataException>(() =>
            IsoTpJ2534RawCanLink.Unpack(rawFd, 0, true, false),
            "Classical frame presented as CAN-FD without vendor FD flag refused.");
        Reject<ArgumentOutOfRangeException>(() =>
            IsoTpJ2534RawCanLink.Pack(0x800,
                [1, 2, 3], extended: false),
            "J2534 11-bit arbitration ID overflow rejected.");

        // Exercise the actual J2534 raw-channel adapter contract without
        // loading a vendor DLL, not only ISO-TP reassembly with QueueLink.
        var vendorClassic = new FakeRawJ2534();
        var classicLink = new IsoTpJ2534RawCanLink(vendorClassic,
            canFd: false, extendedId: false, bitRateSwitch: false);
        var classicPayload = new byte[] { 3, 0x22, 0xF1, 0x90, 0, 0, 0, 0 };
        classicLink.SendAsync(new(0x7E0, false, classicPayload),
            CancellationToken.None).GetAwaiter().GetResult();
        Check(vendorClassic.Transmitted.Count == 1 &&
            vendorClassic.Transmitted[0].Flags == 0 &&
            vendorClassic.Transmitted[0].Data.SequenceEqual(
                IsoTpJ2534RawCanLink.Pack(0x7E0, classicPayload, false)),
            "Classic J2534 emits raw 4-byte CAN ID and zero FD/29-bit flags.");
        vendorClassic.Incoming.Enqueue(new(J2534StandardChannel.Can,
            0, 0, IsoTpJ2534RawCanLink.Pack(0x7E8,
                [3, 0x62, 0xF1, 0x90, 0, 0, 0, 0], false)));
        var classicRead = classicLink.ReceiveAsync(
            TimeSpan.FromMilliseconds(230), CancellationToken.None)
            .GetAwaiter().GetResult();
        Check(classicRead.Id == 0x7E8 && !classicRead.IsFd &&
            classicRead.Data[1] == 0x62 &&
            vendorClassic.RequestedTimeouts.SequenceEqual(new[] { 230 }),
            "Classic J2534 unpacks one real-shape RX and honors timeout milliseconds.");

        // Exercise the complete classic CAN ISO-TP state machine through
        // the J2534 raw adapter rather than connecting the assembler directly
        // to QueueLink. This is a deterministic fake-DLL test, NOT an ECU test.
        var segmentedVendor = new FakeRawJ2534();
        var segmentedLink = new IsoTpJ2534RawCanLink(segmentedVendor,
            canFd: false, extendedId: false, bitRateSwitch: false);
        var segmentedSettings = classic with { BlockSize = 1 };
        void EnqueueSegmented(uint id, byte[] frame) =>
            segmentedVendor.Incoming.Enqueue(new(J2534StandardChannel.Can,
                0, 0, IsoTpJ2534RawCanLink.Pack(id, frame, false)));
        // One CTS for each CF: the sender must obey the ECU's BS=1
        // even when the tester advertises a different local block size.
        EnqueueSegmented(0x7E8, [0x30, 1, 0, 0, 0, 0, 0, 0]);
        EnqueueSegmented(0x7E8, [0x30, 1, 0, 0, 0, 0, 0, 0]);
        var segmentedRequest = Fill(20);
        IsoTpCanFdSession.SendAsync(segmentedLink, segmentedSettings,
            segmentedRequest, TimeSpan.FromSeconds(2),
            CancellationToken.None).GetAwaiter().GetResult();
        var rawTx = segmentedVendor.Transmitted.Select(item =>
            IsoTpJ2534RawCanLink.Unpack(item.Data, 0, false, false)).ToArray();
        var reassembledTx = rawTx[0].Data.AsSpan(2, 6).ToArray()
            .Concat(rawTx.Skip(1).SelectMany(item => item.Data.Skip(1)))
            .Take(segmentedRequest.Length).ToArray();
        Check(rawTx.Length == 3 &&
            rawTx.All(item => item.Id == 0x7E0 && !item.IsFd) &&
            rawTx[0].Data[0] == 0x10 && rawTx[0].Data[1] == 20 &&
            rawTx[1].Data[0] == 0x21 && rawTx[2].Data[0] == 0x22 &&
            reassembledTx.SequenceEqual(segmentedRequest),
            "Classic CAN full-stack segmented TX/RX via fake J2534: FF and BS=1 CFs.");

        var segmentedReply = Fill(20);
        EnqueueSegmented(0x7E9, [3, 0x62, 0xF1, 0x90, 0, 0, 0, 0]);
        EnqueueSegmented(0x7E8, new byte[] { 0x10, 20 }
            .Concat(segmentedReply.Take(6)).ToArray());
        EnqueueSegmented(0x7E9, [3, 0x62, 0xF1, 0x90, 0, 0, 0, 0]);
        EnqueueSegmented(0x7E8, new byte[] { 0x21 }
            .Concat(segmentedReply.Skip(6).Take(7)).ToArray());
        EnqueueSegmented(0x7E8, new byte[] { 0x22 }
            .Concat(segmentedReply.Skip(13).Take(7)).ToArray());
        var segmentedRead = IsoTpCanFdSession.ReceiveAsync(
            segmentedLink, segmentedSettings, TimeSpan.FromSeconds(2),
            CancellationToken.None).GetAwaiter().GetResult();
        var allTx = segmentedVendor.Transmitted.Select(item =>
            IsoTpJ2534RawCanLink.Unpack(item.Data, 0, false, false)).ToArray();
        Check(segmentedRead.SequenceEqual(segmentedReply) &&
            allTx.Length == 5 &&
            allTx.Skip(3).All(item => item.Id == 0x7E0 &&
                item.Data[0] == 0x30 && item.Data[1] == 1),
            "Classic CAN fake J2534 RX filters unrelated IDs and issues FC per BS=1.");

        var overflowVendor = new FakeRawJ2534();
        var overflowLink = new IsoTpJ2534RawCanLink(overflowVendor,
            canFd: false, extendedId: false, bitRateSwitch: false);
        overflowVendor.Incoming.Enqueue(new(J2534StandardChannel.Can,
            0, 0, IsoTpJ2534RawCanLink.Pack(0x7E8,
                [0x32, 0, 0, 0, 0, 0, 0, 0], false)));
        Reject<IOException>(() => IsoTpCanFdSession.SendAsync(
            overflowLink, classic, Fill(20), TimeSpan.FromSeconds(2),
            CancellationToken.None).GetAwaiter().GetResult(),
            "Classic CAN J2534 fake ECU FC/OVFLW prevents further CF transmission.");
        Check(overflowVendor.Transmitted.Count == 1 &&
            overflowVendor.Transmitted[0].Data[4] == 0x10,
            "Classic CAN J2534 overflow path emits only the initial FF.");

        // Vendor status messages and TX echoes are not ISO-TP frames,
        // even if their CAN ID/data happen to resemble a positive reply.
        var statusVendor = new FakeRawJ2534();
        var statusLink = new IsoTpJ2534RawCanLink(statusVendor,
            canFd: false, extendedId: false, bitRateSwitch: false);
        statusVendor.Incoming.Enqueue(new(J2534StandardChannel.Can,
            J2534StandardChannel.RxTxEcho, 0,
            IsoTpJ2534RawCanLink.Pack(0x7E8,
                [3, 0x62, 0xF1, 0x90, 0, 0, 0, 0], false)));
        statusVendor.Incoming.Enqueue(new(J2534StandardChannel.Can,
            0, 0, IsoTpJ2534RawCanLink.Pack(0x7E8,
                [3, 0x7F, 0x22, 0x31, 0, 0, 0, 0], false)));
        var actualReply = statusLink.ReceiveAsync(
            TimeSpan.FromMilliseconds(500), CancellationToken.None)
            .GetAwaiter().GetResult();
        Check(actualReply.Data[1] == 0x7F &&
            statusVendor.RequestedTimeouts.Count == 2 &&
            statusVendor.RequestedTimeouts[1] <=
                statusVendor.RequestedTimeouts[0],
            "J2534 raw RX skips TX echo and retains one receive deadline.");

        var wrongProtocolVendor = new FakeRawJ2534();
        var wrongProtocolLink = new IsoTpJ2534RawCanLink(wrongProtocolVendor,
            false, false, false);
        wrongProtocolVendor.Incoming.Enqueue(new(J2534StandardChannel.Iso15765,
            0, 0, IsoTpJ2534RawCanLink.Pack(0x7E8, Fill(8), false)));
        Reject<InvalidDataException>(() =>
            wrongProtocolLink.ReceiveAsync(TimeSpan.FromMilliseconds(500),
                CancellationToken.None).GetAwaiter().GetResult(),
            "Unsegmented J2534 CAN RX cannot accept another channel protocol.");

        using var lateCancellation = new CancellationTokenSource();
        var cancellationVendor = new FakeRawJ2534
        {
            AfterReceive = lateCancellation.Cancel
        };
        cancellationVendor.Incoming.Enqueue(new(J2534StandardChannel.Can,
            0, 0, IsoTpJ2534RawCanLink.Pack(0x7E8,
                [3, 0x62, 0xF1, 0x90, 0, 0, 0, 0], false)));
        var cancellationLink = new IsoTpJ2534RawCanLink(cancellationVendor,
            false, false, false);
        Reject<OperationCanceledException>(() =>
            cancellationLink.ReceiveAsync(TimeSpan.FromMilliseconds(500),
                lateCancellation.Token).GetAwaiter().GetResult(),
            "Cancellation during vendor receive discards late ECU responses.");

        // Distinguish classic 29-bit arbitration from CAN-FD: the raw
        // channel must set CAN_29BIT_ID without FD_CAN_FORMAT or BRS.
        var classic29Vendor = new FakeRawJ2534();
        var classic29Link = new IsoTpJ2534RawCanLink(classic29Vendor,
            canFd: false, extendedId: true, bitRateSwitch: false);
        var classic29Settings = new IsoTpCanFdSession.Settings(
            0x18DA10F1, 0x18DAF110, 8, false);
        IsoTpCanFdSession.SendAsync(classic29Link, classic29Settings,
            [0x22, 0xF1, 0x90], TimeSpan.FromSeconds(1),
            CancellationToken.None).GetAwaiter().GetResult();
        Check(classic29Vendor.Transmitted.Count == 1 &&
            classic29Vendor.Transmitted[0].Flags == 0x100u &&
            classic29Vendor.Transmitted[0].Data.SequenceEqual(
                IsoTpJ2534RawCanLink.Pack(0x18DA10F1,
                    [3, 0x22, 0xF1, 0x90, 0xCC, 0xCC, 0xCC, 0xCC], true)),
            "Classic CAN 29-bit J2534 transport preserves ID and avoids FD flags.");
        classic29Vendor.Incoming.Enqueue(new(J2534StandardChannel.Can,
            0x100u, 0, IsoTpJ2534RawCanLink.Pack(0x18DAF110,
                [3, 0x62, 0xF1, 0x90, 0xCC, 0xCC, 0xCC, 0xCC], true)));
        var classic29Reply = IsoTpCanFdSession.ReceiveAsync(
            classic29Link, classic29Settings, TimeSpan.FromSeconds(1),
            CancellationToken.None).GetAwaiter().GetResult();
        Check(classic29Reply.SequenceEqual(new byte[] {0x62, 0xF1, 0x90}),
            "Classic CAN 29-bit J2534 software loop accepts matching RX arbitration.");

        var vendorFdExtended = new FakeRawJ2534
        {
            ProtocolId = J2534StandardChannel.FdCan
        };
        var fdLink = new IsoTpJ2534RawCanLink(vendorFdExtended,
            canFd: true, extendedId: true, bitRateSwitch: true);
        var fdWirePayload = Fill(12);
        fdLink.SendAsync(new(0x18DA10F1, true, fdWirePayload),
            CancellationToken.None).GetAwaiter().GetResult();
        Check(vendorFdExtended.Transmitted.Count == 1 &&
            vendorFdExtended.Transmitted[0].Flags ==
                (0x100u | 0x0008_0000u | 0x0010_0000u) &&
            vendorFdExtended.Transmitted[0].Data.SequenceEqual(
                IsoTpJ2534RawCanLink.Pack(
                    0x18DA10F1, fdWirePayload, true)),
            "FD J2534 sends 29-bit CAN ID plus FD_CAN_FORMAT and BRS bits.");
        vendorFdExtended.Incoming.Enqueue(new(J2534StandardChannel.FdCan,
            0x100u | 0x0010_0000u, 0,
            IsoTpJ2534RawCanLink.Pack(0x18DAF110, Fill(12), true)));
        var fdRead = fdLink.ReceiveAsync(
            TimeSpan.FromMilliseconds(400), CancellationToken.None)
            .GetAwaiter().GetResult();
        Check(fdRead.Id == 0x18DAF110 && fdRead.IsFd &&
            fdRead.Data.Length == 12,
            "FD J2534 reconstructs 29-bit CAN RX from actual raw PDU shape.");

        Reject<ArgumentException>(() => new IsoTpJ2534RawCanLink(
            new FakeRawJ2534 { ProtocolId = J2534StandardChannel.Iso15765 },
            false, false, false),
            "Pre-segmented ISO15765 channel cannot double-apply ISO-TP.");
        Reject<ArgumentException>(() => new IsoTpJ2534RawCanLink(
            vendorClassic, false, false, true),
            "Classic CAN cannot advertise FD bit-rate switching.");
        Reject<ArgumentException>(() => new IsoTpJ2534RawCanLink(
            vendorClassic, true, false, false),
            "Raw CAN channel cannot masquerade as FD_CAN.");
        Reject<InvalidDataException>(() =>
            classicLink.SendAsync(new(0x7E0, true, Fill(8)),
                CancellationToken.None).GetAwaiter().GetResult(),
            "Frame CAN-FD cannot leak into the classical J2534 channel.");
        Reject<ArgumentOutOfRangeException>(() =>
            classicLink.SendAsync(new(0x800, false, Fill(8)),
                CancellationToken.None).GetAwaiter().GetResult(),
            "11-bit J2534 link rejects arbitration ID 0x800.");
        Check(vendorClassic.Transmitted.Count == 1,
            "Invalid CAN frames do not reach vendor PassThruWriteMsgs.");

        var vendorFailure = new FakeRawJ2534 { FailTransmit = true };
        var failingLink = new IsoTpJ2534RawCanLink(vendorFailure,
            false, false, false);
        Reject<IOException>(() =>
            failingLink.SendAsync(new(0x7E0, false, Fill(8)),
                CancellationToken.None).GetAwaiter().GetResult(),
            "Vendor PassThruWriteMsgs failure propagates unmodified.");
        Reject<TimeoutException>(() =>
            failingLink.ReceiveAsync(TimeSpan.FromMilliseconds(10),
                CancellationToken.None).GetAwaiter().GetResult(),
            "Vendor timeout with no RX is not interpreted as a valid frame.");
        var faultyRxVendor = new FakeRawJ2534();
        var faultyRxLink = new IsoTpJ2534RawCanLink(faultyRxVendor,
            false, false, false);
        faultyRxVendor.Incoming.Enqueue(new(J2534StandardChannel.Can,
            0x100, 0, IsoTpJ2534RawCanLink.Pack(0x7E8, Fill(8), false)));
        Reject<InvalidDataException>(() =>
            faultyRxLink.ReceiveAsync(TimeSpan.FromMilliseconds(100),
                CancellationToken.None).GetAwaiter().GetResult(),
            "Vendor 29-bit RX flag mismatch is never accepted on 11-bit bus.");
        using var cancelledJ2534 = new CancellationTokenSource();
        cancelledJ2534.Cancel();
        Reject<OperationCanceledException>(() =>
            classicLink.SendAsync(new(0x7E0, false, Fill(8)),
                cancelledJ2534.Token).GetAwaiter().GetResult(),
            "Canceled J2534 send never calls vendor DLL.");
        Check(vendorClassic.Transmitted.Count == 1,
            "Canceled raw J2534 send leaves vendor TX queue unchanged.");

        var traceCli = StandardProtocolCli.ParseIsoTp([
            StandardProtocolCli.RawIsoTpCommand, "--adapter-index", "0",
            "--tx-id", "7E0", "--rx-id", "7E8", "--baud", "500000",
            "--fd", "false", "--payload", "22F190",
            "--trace-file", "isotp-bench-01.csv"]);
        Check(traceCli.TraceFile == "isotp-bench-01.csv",
            "ISO-TP trace is explicitly configured and disabled by default.");
        Reject<ArgumentException>(() => StandardProtocolCli.ParseIsoTp([
            StandardProtocolCli.RawIsoTpCommand, "--adapter-index", "0",
            "--tx-id", "7E0", "--rx-id", "7E8", "--baud", "500000",
            "--fd", "false", "--payload", "22F190",
            "--trace-file", ""]),
            "Blank trace path cannot select an unintended output file.");

        var traceInner = new QueueLink();
        traceInner.Replies.Enqueue(RX(false,
            3, 0x62, 0xF1, 0x90, 0, 0, 0, 0));
        using var traceOutput = new StringWriter(
            System.Globalization.CultureInfo.InvariantCulture);
        var traceLink = new IsoTpHostTraceLink(traceInner, traceOutput);
        IsoTpCanFdSession.SendAsync(traceLink, classic,
            [0x22, 0xF1, 0x90], TimeSpan.FromSeconds(1),
            CancellationToken.None).GetAwaiter().GetResult();
        var traceReply = IsoTpCanFdSession.ReceiveAsync(traceLink, classic,
            TimeSpan.FromSeconds(1), CancellationToken.None)
            .GetAwaiter().GetResult();
        var traceLines = traceOutput.ToString().Split(
            '\n', StringSplitOptions.RemoveEmptyEntries);
        Check(traceReply.SequenceEqual(new byte[] {0x62, 0xF1, 0x90}) &&
            traceLines.Length == 6 &&
            traceLines[0].Contains("CAMARODIAG_ISOTP_HOST_TRACE_V1",
                StringComparison.Ordinal) &&
            traceLines[1].Contains("HOST_CLOCK_NOT_WIRE_TIMESTAMP",
                StringComparison.Ordinal) &&
            traceLines[3].Contains(",TX_REQUEST,0x000007E0,CAN,8,",
                StringComparison.Ordinal) &&
            traceLines[4].Contains(",TX_QUEUED,0x000007E0,CAN,8,",
                StringComparison.Ordinal) &&
            traceLines[5].Contains(",RX,0x000007E8,CAN,8,",
                StringComparison.Ordinal),
            "Trace records TX intent/queued/RX without claiming physical bus timing.");

        var passiveTrace = StandardProtocolCli.ParseIsoTp([
            StandardProtocolCli.RawIsoTpCommand, "--adapter-index", "0",
            "--tx-id", "7E0", "--rx-id", "7E8", "--baud", "500000",
            "--fd", "false", "--mode", "receive",
            "--trace-file", "isotp-listen.csv"]);
        Check(passiveTrace.Mode == "receive" &&
            passiveTrace.TraceFile == "isotp-listen.csv",
            "Receive-only host trace never constructs a TX payload.");

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

        var snapshotIsoTp = StandardProtocolCli.ParseIsoTp([
            StandardProtocolCli.RawIsoTpCommand, "--adapter-index", "0",
            "--tx-id", "7E0", "--rx-id", "7E8", "--baud", "500000",
            "--fd", "false", "--obd-snapshot", "0x123456"]);
        Check(snapshotIsoTp.Request.SequenceEqual(
                new byte[] {0x19, 0x04, 0x12, 0x34, 0x56, 0x01}) &&
            snapshotIsoTp.ValidateUds,
            "ISO-TP DTC snapshot defaults to record 1 and validates the reply.");
        Reject<ArgumentException>(() => StandardProtocolCli.ParseIsoTp([
            StandardProtocolCli.RawIsoTpCommand, "--adapter-index", "0",
            "--tx-id", "7E0", "--rx-id", "7E8", "--baud", "500000",
            "--fd", "false", "--mode", "receive",
            "--obd-snapshot", "0x123456"]),
            "Passive ISO-TP never sends DTC snapshot read request.");

        var extendedIsoTp = StandardProtocolCli.ParseIsoTp([
            StandardProtocolCli.RawIsoTpCommand, "--adapter-index", "0",
            "--tx-id", "7E0", "--rx-id", "7E8", "--baud", "500000",
            "--fd", "false", "--obd-extended-dtc", "0x123456",
            "--obd-extended-record", "0x90"]);
        Check(extendedIsoTp.Request.SequenceEqual(
                new byte[] {0x19, 0x06, 0x12, 0x34, 0x56, 0x90}) &&
            extendedIsoTp.ValidateUds,
            "ISO-TP DTC extended record is correlated before display.");
        Reject<ArgumentException>(() => StandardProtocolCli.ParseIsoTp([
            StandardProtocolCli.RawIsoTpCommand, "--adapter-index", "0",
            "--tx-id", "7E0", "--rx-id", "7E8", "--baud", "500000",
            "--fd", "false", "--mode", "receive",
            "--obd-extended-dtc", "0x123456"]),
            "Passive ISO-TP forbids DTC extended read request.");

        var multiDid = StandardProtocolCli.ParseIsoTp([
            StandardProtocolCli.RawIsoTpCommand, "--adapter-index", "0",
            "--tx-id", "7E0", "--rx-id", "7E8", "--baud", "500000",
            "--fd", "false", "--uds-dids", "0xF190:17,0xF187:4"]);
        Check(multiDid.Request.SequenceEqual(
            new byte[] {0x22, 0xF1, 0x90, 0xF1, 0x87}) &&
            multiDid.ValidateUds,
            "ISO-TP multi-DID request uses standard UDS and enables validation.");
        Reject<ArgumentException>(() => StandardProtocolCli.ParseIsoTp([
            StandardProtocolCli.RawIsoTpCommand, "--adapter-index", "0",
            "--tx-id", "7E0", "--rx-id", "7E8", "--baud", "500000",
            "--fd", "false", "--mode", "receive",
            "--uds-dids", "0xF190:17,0xF187:4"]),
            "ISO-TP passive mode rejects outgoing multi-DID reads.");

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
