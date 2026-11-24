namespace CamaroDiag.J2534Bridge;

internal static class J1939FdTransportFramesSelfTests
{
    internal static int Run()
    {
        var checks = 0;
        void Check(bool ok, string label)
        {
            checks++;
            if (!ok) throw new InvalidOperationException("J1939-22 FD.TP test failed: " + label);
        }
        void Reject<T>(Action action, string label) where T : Exception
        {
            var rejected = false;
            try { action(); }
            catch (T) { rejected = true; }
            Check(rejected, label);
        }

        var rts = new J1939FdTransportFrames.Control(
            0xF1, 0x10, 3, J1939FdTransportFrames.RequestToSend,
            125, 3, 2, 0, 0xF004, 6);
        var rtsFrame = J1939FdTransportFrames.EncodeControl(rts);
        Check(rtsFrame.IsFd && rtsFrame.Data.Length == 12 &&
            rtsFrame.Data[0] == 0x30 &&
            rtsFrame.Data[1] == 125 &&
            rtsFrame.Data[4] == 3 &&
            rtsFrame.Data[7] == 2 &&
            rtsFrame.Data[9] == 0x04 &&
            rtsFrame.Data[10] == 0xF0 &&
            rtsFrame.Data[11] == 0,
            "FD.TP.CM RTS encodes session/control and 24-bit fields.");
        Check(J1939FdTransportFrames.DecodeControl(rtsFrame) == rts,
            "FD.TP.CM 12-byte control frame round-trip.");
        var cts = new J1939FdTransportFrames.Control(
            0x10, 0xF1, 3, J1939FdTransportFrames.ClearToSend,
            0xFFFFFF, 2, 2, 0, 0xF004, 7);
        Check(J1939FdTransportFrames.DecodeControl(
            J1939FdTransportFrames.EncodeControl(cts)) == cts,
            "FD.TP.CM CTS supports 24-bit sentinel and packet window.");

        var data = new J1939FdTransportFrames.Segment(
            0xF1, 0x10, 3, 2, 0,
            Enumerable.Range(0, 60).Select(i => (byte)i).ToArray(), 7);
        var dtFrame = J1939FdTransportFrames.EncodeData(data);
        var unpacked = J1939FdTransportFrames.DecodeData(dtFrame);
        Check(dtFrame.IsFd && dtFrame.Data.Length == 64 &&
            dtFrame.Data[0] == 0x30 && dtFrame.Data[1] == 2 &&
            unpacked.Number == 2 && unpacked.Session == 3 &&
            unpacked.Data.SequenceEqual(data.Data),
            "FD.TP.DT sends 60 useful bytes with 4-byte transport header.");
        var shortData = J1939FdTransportFrames.EncodeData(
            data with { Number = 1, Data = [0xAA, 0xFF, 0x42] });
        Check(shortData.Data.Length == 8 &&
            J1939FdTransportFrames.DecodeData(shortData).Data
                .SequenceEqual(new byte[] {0xAA, 0xFF, 0x42, 0xFF}),
            "FD.TP.DT final frame padding is preserved until reassembly.");

        Reject<ArgumentException>(() =>
            J1939FdTransportFrames.EncodeControl(
                rts with { Session = 16 }),
            "Transport session index is 4 bits.");
        Reject<ArgumentException>(() =>
            J1939FdTransportFrames.EncodeData(
                data with { Number = 0 }),
            "FD.TP.DT sequence cannot start at zero.");
        Reject<InvalidDataException>(() =>
            J1939FdTransportFrames.DecodeData(new(
                dtFrame.Id, true, new byte[9])),
            "Invalid FD DLC cannot enter the decoder.");
        Reject<InvalidDataException>(() =>
            J1939FdTransportFrames.DecodeControl(new(
                rtsFrame.Id, false, rtsFrame.Data)),
            "Classical J1939 frame cannot impersonate FD.TP.CM.");
        var invalidControl = rtsFrame.Data.ToArray();
        invalidControl[0] = 0x3E;
        Reject<InvalidDataException>(() =>
            J1939FdTransportFrames.DecodeControl(new(
                rtsFrame.Id, true, invalidControl)),
            "Unknown FD.TP.CM control type rejected.");
        return checks;
    }
}
