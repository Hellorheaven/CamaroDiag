namespace CamaroDiag.J2534Bridge;

internal static class ObdJ1979ClearRecipeSelfTests
{
    internal static int Run()
    {
        int checks = 0;
        const string vin = "1G1FA1RXXR0123456";
        void Check(bool value, string label)
        {
            checks++;
            if (!value) throw new InvalidOperationException("Mode 04 recipe: " + label);
        }
        int sent = 0;
        bool missingBaseline = false, moving = false, missingAfter = false;
        bool stillPresent = false;
        bool reset = false, saved=false, failSave=false, changeVin=false, changeMotion=false;
        byte[] Read(byte[] request, int timeout)
        {
            return Convert.ToHexString(request) switch
            {
                "0902" => [0x49, 2, 1, ..System.Text.Encoding.ASCII.GetBytes(saved&&changeVin?"1G1FA1RXXR0123457":vin)],
                "03" when !reset && missingBaseline => throw new TimeoutException(),
                "03" when reset && missingAfter => throw new TimeoutException(),
                "03" when reset && !stillPresent => [0x43, 0],
                "03" => [0x43, 1, 1, 0x23],
                "07" => [0x47, 0],
                "0A" => [0x4A, 1, 1, 0x23],
                "0101" => [0x41, 1, 1, 0, 0, 0],
                "010D" => [0x41, 0x0D, moving||saved&&changeMotion ? (byte)1 : (byte)0],
                "010C" => [0x41, 0x0C, 0, 0],
                _ => throw new InvalidOperationException("Unexpected request")
            };
        }
        ObdJ1979ClearRecipe.Result Run(string expected = vin, Func<byte[]>? send = null)
        {
            reset = false;saved=false;
            sent = 0;
            return ObdJ1979ClearRecipe.Run(expected, true, true, true, () => {}, Read, () =>
            {
                Check(saved,"baseline before transmit");
                sent++;
                reset = true;
                return send is null ? [0x44] : send();
            }, baseline=>{
                Check(!baseline.CommandAttempted&&baseline.Outcome=="BASELINE_SAVED","baseline is pre-reset evidence");
                if(failSave)throw new IOException("Disk unavailable");
                saved=true;
            });
        }
        var pass = Run();
        Check(pass.Acknowledged && pass.CommandAttempted && sent == 1 &&
            pass.Outcome == "ACKNOWLEDGED_READBACK", "single reset");
        Check(pass.BeforeDtcs["03"]!.SequenceEqual(new[] {"P0123"}) &&
            pass.AfterDtcs["03"]!.Length == 0 &&
            pass.AfterDtcs["0A"]!.SequenceEqual(new[] {"P0123"}),
            "observed cleared and permanent records retained");
        Check(pass.Observations.Count(x => x.RequestHex == "0101") == 2, "readiness before and after");
        Check(Run("1G1FA1RXXR0123457").Reason == "VIN_MISMATCH" && sent == 0, "wrong VIN");
        missingBaseline = true;
        Check(Run().Reason == "BASELINE_UNAVAILABLE" && sent == 0, "missing baseline");
        missingBaseline = false;
        moving = true;
        Check(Run().Reason == "STATIONARY_EVIDENCE_REQUIRED" && sent == 0, "moving vehicle");
        moving = false;
        missingAfter = true;
        var partial = Run();
        Check(partial.Outcome == "ACKNOWLEDGED_PARTIAL" && partial.AfterDtcs["03"] is null &&
            sent == 1, "missing readback not zero");
        missingAfter = false;
        stillPresent = true;
        Check(Run().Reason == "DTCS_STILL_PRESENT" && sent == 1, "residual DTC not erased by assumption");
        Check(Run(send: () => throw new TimeoutException()).Outcome == "UNCERTAIN" && sent == 1,
            "lost ACK no retry");
        Check(Run(send: () => [0x7F, 4, 0x22]).Outcome == "REFUSED" && sent == 1, "ECU refusal");
        failSave=true;
        Check(Run().Outcome=="NOT_TESTED"&&sent==0,"disk failure blocks reset");
        failSave=false;changeVin=true;
        Check(Run().Reason=="IDENTITY_CHANGED_AFTER_BASELINE"&&sent==0,"fresh VIN mismatch blocks reset");
        changeVin=false;changeMotion=true;
        Check(Run().Reason=="STATIONARY_EVIDENCE_CHANGED"&&sent==0,"motion after baseline blocks reset");
        return checks;
    }
}
