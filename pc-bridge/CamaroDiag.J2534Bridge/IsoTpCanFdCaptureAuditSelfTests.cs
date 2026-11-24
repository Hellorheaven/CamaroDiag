namespace CamaroDiag.J2534Bridge;

/// <summary>CAN-FD capture contract vectors: entirely synthetic, no device.</summary>
internal static class IsoTpCanFdCaptureAuditSelfTests
{
    internal static int Run()
    {
        var checks = 0;
        void Check(bool ok, string label)
        {
            checks++;
            if (!ok) throw new InvalidOperationException("CAN-FD trace audit: " + label);
        }
        void Reject<T>(Action action, string label) where T : Exception
        {
            var rejected = false;
            try { action(); } catch (T) { rejected = true; }
            Check(rejected, label);
        }
        static IsoTpClassicCaptureAudit.Frame F(long us, bool tx, uint id,
            params byte[] pdu) => new(us, tx, id, pdu);
        static IsoTpClassicCaptureAudit.Result Audit(
            IReadOnlyList<IsoTpClassicCaptureAudit.Frame> capture,
            bool can29 = false, byte? txAddress = null, byte? rxAddress = null) =>
            IsoTpClassicCaptureAudit.Analyze(capture,
                can29 ? 0x18DA10F1u : 0x7E0u,
                can29 ? 0x18DAF110u : 0x7E8u,
                can29, true, txAddress: txAddress,
                rxAddress: rxAddress, canFd: true);

        var pass = IsoTpClassicCaptureAudit.Status.Pass;
        var fail = IsoTpClassicCaptureAudit.Status.Fail;
        var incomplete = IsoTpClassicCaptureAudit.Status.Inconclusive;
        var sf12 = F(1000, true, 0x7E0,
            0x00, 0x03, 0x22, 0xF1, 0x90, 0, 0, 0, 0, 0, 0, 0);
        Check(Audit(new[] {sf12}).Status == pass,
            "Escaped FD SingleFrame on legal DLC12");
        var invalidSf = sf12 with {
            Data = new byte[]{3, 0x22, 0xF1, 0x90, 0, 0, 0, 0, 0, 0, 0, 0} };
        Check(Audit(new[] {invalidSf}).Status == fail,
            "FD SingleFrame DLC12 must use escape, never 1-byte SF_DL");

        var fd12 = new[] {
            F(1000,true,0x7E0,0x10,33,1,2,3,4,5,6,7,8,9,10),
            F(2000,false,0x7E8,0x30,0,5,0,0,0,0,0),
            F(3000,true,0x7E0,0x21,11,12,13,14,15,16,17,18,19,20,21),
            F(9000,true,0x7E0,0x22,22,23,24,25,26,27,28,29,30,31,32),
            F(15000,true,0x7E0,0x23,33)
        };
        Check(Audit(fd12).Status == pass,
            "CAN-FD DLC12 FF/FC/CF with final short CF");
        var early = fd12.ToArray();
        early[3] = early[3] with {TimeUs = 4000};
        Check(Audit(early).Status == fail,
            "CF-to-CF FD bus timing honors STmin");
        var shortIntermediate = fd12.ToArray();
        shortIntermediate[2] = shortIntermediate[2] with {
            Data = new byte[]{0x21,11,12,13,14,15,16,17} };
        Check(Audit(shortIntermediate).Status == fail,
            "Nonterminal FD CF must use FF's RX_DL");
        var wrongSn = fd12.ToArray();
        wrongSn[3] = wrongSn[3] with {
            Data = new byte[]{0x23,22,23,24,25,26,27,28,29,30,31,32} };
        Check(Audit(wrongSn).Status == fail, "FD CF sequence mismatch");
        var invalidDlc = new[] {F(1000,true,0x7E0,
            0,3,0x22,0xF1,0x90,0,0,0,0) };
        Reject<InvalidDataException>(() => Audit(invalidDlc),
            "CAN-FD noncanonical 9-byte DLC rejected");
        Reject<InvalidDataException>(() => IsoTpClassicCaptureAudit.ReadCapture(
            Path.Combine(Path.GetTempPath(),
                "nonexistent-camarodiag-fd-capture.csv"), false, true),
            "Nonexistent FD capture is never a successful transfer");

        var ff64 = new byte[64];
        ff64[0] = 0x10;
        ff64[1] = 70;
        var cf64Last = new byte[12];
        cf64Last[0] = 0x21;
        Check(Audit(new[] {
            F(1000,true,0x7E0,ff64),
            F(2000,false,0x7E8,0x30,0,0,0,0,0,0,0),
            F(3000,true,0x7E0,cf64Last)
        }).Status == pass, "DLC64 FF and DLC12 final CF");
        var fakeFf = ff64.ToArray();
        fakeFf[1] = 10;
        Check(Audit(new[] {F(1000,true,0x7E0,fakeFf)}).Status == fail,
            "FF cannot declare payload fitting a single FD frame");

        // With a 12-byte FD frame and an N_AE byte, escaped SF has
        // room for 9 payload bytes; verify normal-vs-extended PCI offsets.
        var sfAddressed = F(1000,true,0x18DA10F1,
            0xF1,0x00,0x03,0x22,0xF1,0x90,0,0,0,0,0,0);
        Check(Audit(new[] {sfAddressed}, can29:true,
            txAddress:0xF1, rxAddress:0x10).Status == pass,
            "29-bit FD with extended addressing and escaped SF");
        Check(Audit(fd12.Take(3).ToArray()).Status == incomplete,
            "FD truncated PDU never becomes success");

        var path = Path.Combine(Path.GetTempPath(),
            "camarodiag-can-fd-"+Guid.NewGuid().ToString("N")+".csv");
        try
        {
            File.WriteAllText(path,
                "timestamp_us,direction,can_id,payload_hex\n" +
                "1000,TX,0x7E0,000322F19000000000000000\n");
            var capture = IsoTpClassicCaptureAudit.ReadCapture(path, false, true);
            Check(capture.Count == 1 && Audit(capture).Status == pass,
                "FD CSV accepts legal DLC12 and escaped SF");
            Reject<InvalidDataException>(() =>
                IsoTpClassicCaptureAudit.ReadCapture(path, false),
                "Classic CAN auditor still refuses FD DLC12");
        }
        finally { if (File.Exists(path)) File.Delete(path); }
        return checks;
    }
}
