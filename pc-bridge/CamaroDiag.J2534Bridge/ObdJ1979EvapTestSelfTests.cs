namespace CamaroDiag.J2534Bridge;

internal static class ObdJ1979EvapTestSelfTests
{
    internal static int Run()
    {
        int checks = 0;
        void Assert(bool condition, string name)
        {
            checks++;
            if (!condition) throw new InvalidOperationException("EVAP recipe test: " + name);
        }
        const string vin = "1G1FA1RXXR0123456";
        var sent = new List<string>();
        var replies = new Dictionary<string, byte[]>
        {
            ["0902"] = [0x49, 2, 1, ..System.Text.Encoding.ASCII.GetBytes(vin)],
            ["0800"] = [0x48, 0, 0x80, 0, 0, 0],
            ["010D"] = [0x41, 0x0D, 0],
            ["010C"] = [0x41, 0x0C, 0, 0]
        };
        byte[] Read(byte[] request, int _) {
            var hex = Convert.ToHexString(request);
            sent.Add(hex);
            return replies[hex];
        }
        int commands = 0;
        byte[] Send() { commands++; return [0x48, 0x01]; }
        ObdJ1979EvapTest.Result Run(string expected = vin, Func<byte[]>? transmit = null) =>
            ObdJ1979EvapTest.Run(expected, true, true, true, () => {}, Read, transmit ?? Send);

        foreach (var (primary, ok, yes) in new[] { (false,true,true), (true,false,true), (true,true,false) })
        {
            try
            {
                ObdJ1979EvapTest.Run(vin, primary, ok, yes, () => {}, Read, Send);
                throw new InvalidOperationException("No consent gate.");
            }
            catch (UnauthorizedAccessException) { Assert(sent.Count == 0 && commands == 0, "no TX without consent"); }
        }
        var mismatch = Run("1G1FA1RXXR0123457");
        Assert(!mismatch.CommandAttempted && mismatch.Reason == "VIN_MISMATCH" && commands == 0,
            "different vehicle denied");
        replies["0800"] = [0x48, 0, 0, 0, 0, 0];
        Assert(Run().Reason == "TID_01_NOT_ADVERTISED" && commands == 0, "unadvertised test denied");
        replies["0800"] = [0x48, 0, 0x80, 0, 0, 0];
        replies["010C"] = [0x41, 0x0C, 0, 1];
        Assert(Run().Reason == "STATIONARY_EVIDENCE_REQUIRED" && commands == 0, "running engine denied");
        replies["010C"] = [0x41, 0x0C, 0, 0];
        sent.Clear();
        var pass = Run();
        Assert(pass.Outcome == "ACKNOWLEDGED" && commands == 1 && pass.CommandAttempted &&
            sent.SequenceEqual(new[] { "0902", "0800", "010D", "010C" }), "one consented request");
        Assert(pass.Exchanges.Last() == new ObdJ1979EvapTest.ExchangeEvidence("0801", "4801"),
            "actual ACK retained");
        var refusal = Run(transmit: () => { commands++; return [0x7F, 8, 0x22]; });
        Assert(refusal.Outcome == "REFUSED" && commands == 2, "ECU refuses conditions");
        var lost = Run(transmit: () => { commands++; throw new TimeoutException(); });
        Assert(lost.Outcome == "UNCERTAIN" && lost.CommandAttempted && commands == 3 &&
            lost.Exchanges.Last().ResponseHex is null, "no retry on missing acknowledgement");
        var invalid = Run(transmit: () => { commands++; return [0x48, 0x02]; });
        Assert(invalid.Outcome == "UNCERTAIN" && commands == 4, "wrong TID not accepted");
        return checks;
    }
}
