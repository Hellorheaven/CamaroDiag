namespace CamaroDiag.J2534Bridge;

/// <summary>Synthetic capture vectors. No real ECU or vendor driver.</summary>
internal static class IsoTpClassicCaptureAuditSelfTests
{
    internal static int Run()
    {
        int checks = 0;
        void Check(bool passed, string name)
        {
            checks++;
            if (!passed) throw new InvalidOperationException("ISO-TP capture: " + name);
        }
        static IsoTpClassicCaptureAudit.Frame F(long us, bool tx, uint id,
            params byte[] d) => new(us, tx, id, d);
        static IsoTpClassicCaptureAudit.Result Audit(
            IReadOnlyList<IsoTpClassicCaptureAudit.Frame> frames, int nBs = 1000) =>
            IsoTpClassicCaptureAudit.Analyze(frames, 0x7E0, 0x7E8, false, true, nBs);
        var pass = IsoTpClassicCaptureAudit.Status.Pass;
        var fail = IsoTpClassicCaptureAudit.Status.Fail;
        var uncertain = IsoTpClassicCaptureAudit.Status.Inconclusive;
        var valid = new List<IsoTpClassicCaptureAudit.Frame> {
            F(1000, true, 0x7E0, 0x10,20,1,2,3,4,5,6),
            F(2000, false, 0x7E8, 0x30,0,5,0,0,0,0,0),
            F(3000, true, 0x7E0, 0x21,7,8,9,10,11,12,13),
            F(8500, true, 0x7E0, 0x22,14,15,16,17,18,19,20)
        };
        Check(Audit(valid).Status == pass, "11-bit FF/CTS/CF with 5 ms STmin");
        Check(IsoTpClassicCaptureAudit.Analyze(valid.Select(f => f with {
            Id = f.Id == 0x7E0 ? 0x18DA10F1u : 0x18DAF110u }).ToArray(),
            0x18DA10F1, 0x18DAF110, true, true).Status == pass,
            "29-bit arbitration IDs");
        var early = valid.ToArray();
        early[3] = early[3] with {TimeUs = 7000};
        Check(Audit(early).Status == fail, "Measured CF below STmin");
        var sn = valid.ToArray();
        sn[3] = sn[3] with {Data = new byte[]{0x23,14,15,16,17,18,19,20}};
        Check(Audit(sn).Status == fail, "CF sequence");
        Check(Audit(new[]{valid[0], valid[2]}).Status == fail, "No CF before CTS");
        Check(Audit(valid.Take(3).ToArray()).Status == uncertain, "Truncated trace");
        var bs1 = valid.ToArray();
        bs1[1] = bs1[1] with {Data = new byte[]{0x30,1,5,0,0,0,0,0}};
        Check(Audit(bs1).Status == fail, "BS=1 requires second CTS");
        var bs1Ok = bs1.ToList();
        bs1Ok.Insert(3, F(5000,false,0x7E8,0x30,1,5,0,0,0,0,0));
        Check(Audit(bs1Ok).Status == pass, "Second CTS after BS=1");
        var over = new[]{valid[0], F(2000,false,0x7E8,0x32,0,0,0,0,0,0,0)};
        Check(Audit(over).Status == uncertain, "Overflow is abort, not full PDU");
        Check(Audit(over.Append(valid[2]).ToArray()).Status == fail,
            "No CF sent after FC/OVFLW");
        var reserved = valid.ToArray();
        reserved[1] = reserved[1] with {Data = new byte[]{0x30,0,0x80,0,0,0,0,0}};
        Check(Audit(reserved).Status == fail, "Reserved STmin is 127 ms");
        reserved[3] = reserved[3] with {TimeUs = 140000};
        Check(Audit(reserved).Status == pass, "Reserved STmin respected");
        var wait = valid.ToList();
        wait.Insert(1, F(1600,false,0x7E8,0x31,0,0,0,0,0,0,0));
        Check(Audit(wait).Status == pass, "WAIT then CTS");
        var lateFc = valid.ToArray();
        lateFc[1] = lateFc[1] with {TimeUs = 2000000};
        lateFc[2] = lateFc[2] with {TimeUs = 2001000};
        lateFc[3] = lateFc[3] with {TimeUs = 2006500};
        Check(Audit(lateFc).Status == fail, "N_Bs expiry");
        Check(Audit(new[]{F(1000,true,0x7E0,3,0x22,0xF1,0x90)}).Status == pass,
            "SingleFrame syntax");
        Check(Audit(Array.Empty<IsoTpClassicCaptureAudit.Frame>()).Status ==
            uncertain, "Empty capture cannot PASS");
        var temp = Path.Combine(Path.GetTempPath(),
            "camarodiag-isotp-"+Guid.NewGuid().ToString("N")+".csv");
        try
        {
            File.WriteAllText(temp,
                "timestamp_us,direction,can_id,payload_hex\n" +
                "1000,TX,0x7E0,1014010203040506\n" +
                "2000,RX,0x7E8,3000050000000000\n" +
                "3000,TX,0x7E0,210708090A0B0C0D\n" +
                "8500,TX,0x7E0,220E0F1011121314\n");
            Check(Audit(IsoTpClassicCaptureAudit.ReadCapture(temp,false)).Status ==
                pass, "External bus CSV decoder");
            File.WriteAllText(temp,
                "elapsed_us,event,can_id,frame_type,dlc_bytes,payload_hex\n");
            bool rejected=false;
            try { IsoTpClassicCaptureAudit.ReadCapture(temp,false); }
            catch (InvalidDataException) { rejected=true; }
            Check(rejected, "Reject Windows host TX_QUEUED CSV");
        }
        finally { if(File.Exists(temp)) File.Delete(temp); }

        // Classical CAN can carry the extended 32-bit FF length as well.
        // 4096 bytes require sequence wrap, without any large allocation.
        var large = new List<IsoTpClassicCaptureAudit.Frame> {
            F(1000, true, 0x7E0, 0x10, 0x00, 0x00, 0x00, 0x10, 0x00, 1, 2),
            F(2000, false, 0x7E8, 0x30, 0, 0, 0, 0, 0, 0, 0)
        };
        var left = 4094;
        for (var i = 0; left > 0; i++)
        {
            var take = Math.Min(left, 7);
            var bytes = new byte[take + 1];
            bytes[0] = (byte)(0x20 | ((i + 1) & 0x0F));
            large.Add(F(3000 + i * 1000L, true, 0x7E0, bytes));
            left -= take;
        }
        Check(Audit(large).Status == pass, "32-bit 4096-byte FF and CF SN wrap");
        var fakeExtended = large.Take(2).ToArray();
        fakeExtended[0] = fakeExtended[0] with {
            Data = new byte[] {0x10, 0, 0, 0, 0, 100, 1, 2} };
        Check(Audit(fakeExtended).Status == fail,
            "32-bit FF length must not encode values <=4095");

        // Normal, extended and mixed ISO-TP addressing share the same
        // classic CAN physical frame format; the prefix is not a PCI byte.
        var addressed = new[] {
            F(1000, true, 0x7E0, 0xF1, 0x10, 0x11, 1, 2, 3, 4, 5),
            F(2000, false, 0x7E8, 0x10, 0x30, 0, 5, 0, 0, 0, 0),
            F(3000, true, 0x7E0, 0xF1, 0x21, 6, 7, 8, 9, 10, 11),
            F(8500, true, 0x7E0, 0xF1, 0x22, 12, 13, 14, 15, 16, 17)
        };
        Check(IsoTpClassicCaptureAudit.Analyze(addressed,
            0x7E0, 0x7E8, false, true, txAddress: 0xF1,
            rxAddress: 0x10).Status == pass, "Extended addressing both ways");
        var mismatchedFc = addressed.ToArray();
        mismatchedFc[1] = mismatchedFc[1] with {
            Data = new byte[] {0x11, 0x30, 0, 5, 0, 0, 0, 0} };
        Check(IsoTpClassicCaptureAudit.Analyze(mismatchedFc,
            0x7E0, 0x7E8, false, true, txAddress: 0xF1,
            rxAddress: 0x10).Status == fail,
            "FC sent to another ISO-TP logical address cannot release CF");
        var mixed = addressed.ToArray();
        mixed[1] = mixed[1] with {
            Data = new byte[] {0xF1, 0x30, 0, 5, 0, 0, 0, 0} };
        Check(IsoTpClassicCaptureAudit.Analyze(mixed,
            0x7E0, 0x7E8, false, true, txAddress: 0xF1,
            rxAddress: 0xF1).Status == pass, "Shared mixed addressing extension");
        var missingAddress = false;
        try {
            IsoTpClassicCaptureAudit.Analyze(addressed, 0x7E0, 0x7E8,
                false, true, txAddress: 0xF1);
        } catch (ArgumentException) { missingAddress = true; }
        Check(missingAddress, "Address pair required, no silent mixed profile");

        var delayedCf = valid.ToArray();
        delayedCf[2] = delayedCf[2] with { TimeUs = 1500000 };
        delayedCf[3] = delayedCf[3] with { TimeUs = 1505500 };
        Check(Audit(delayedCf).Status == fail,
            "N_Cr counts from CTS even for first CF");
        Check(IsoTpClassicCaptureAudit.Analyze(wait,
            0x7E0, 0x7E8, false, true, maxWaitFrames: 0).Status == fail,
            "WFTmax zero must reject the first FC/WAIT");
        var tooManyWaits = new List<IsoTpClassicCaptureAudit.Frame> {valid[0]};
        for (int i = 0; i < 5; i++)
            tooManyWaits.Add(F(1100 + 100L * i, false, 0x7E8,
                0x31, 0, 0, 0, 0, 0, 0, 0));
        tooManyWaits.Add(valid[1]);
        Check(Audit(tooManyWaits).Status == fail,
            "WFTmax four rejects fifth FC/WAIT before CTS");

        return checks;
    }
}
