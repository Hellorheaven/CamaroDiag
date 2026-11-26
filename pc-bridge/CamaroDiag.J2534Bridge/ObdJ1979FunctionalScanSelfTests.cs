namespace CamaroDiag.J2534Bridge;

internal static class ObdJ1979FunctionalScanSelfTests
{
    internal static int Run()
    {
        var checks = 0;
        void Assert(bool condition, string name)
        {
            checks++;
            if (!condition) throw new InvalidOperationException("J1979 recipe self-test: " + name);
        }
        var sent = new List<string>();
        byte[] Exchange(byte[] request, int timeout)
        {
            var hex = Convert.ToHexString(request);
            sent.Add(hex);
            Assert(timeout is >= 100 and <= 2500, "bounded timeout");
            return hex switch
            {
                "0100" => [0x41, 0, 0x80, 0x10, 0, 0],
                "0101" => [0x41, 1, 0, 0, 0, 0],
                "010C" => [0x41, 0x0C, 0, 0],
                "03" => [0x43, 0],
                "07" => [0x47, 0],
                "0A" => [0x7F, 0x0A, 0x11],
                "020200" => [0x42, 2, 0, 0, 0],
                "0600" => [0x46, 0, 0, 0, 0, 0],
                "0800" => [0x48, 0, 0x80, 0, 0, 0],
                "0900" => [0x49, 0, 0x40, 0, 0, 0],
                "0902" => [0x49, 2, 1, ..System.Text.Encoding.ASCII.GetBytes("1G1FA1RXXR0123456")],
                _ => throw new InvalidOperationException("Unexpected request: " + hex)
            };
        }
        var report = ObdJ1979FunctionalScan.Run(Exchange, () => {}, TimeSpan.FromMinutes(1));
        Assert(report.Completed, "completed bounded campaign");
        Assert(sent.Take(4).SequenceEqual(new[] { "0902", "03", "07", "0A" }), "identity and DTC priority");
        Assert(report.ObservedVin == "1G1FA1RXXR0123456", "actual VIN only");
        Assert(sent.Contains("010C") && !sent.Contains("010D"), "only advertised PID read");
        Assert(sent.Contains("0800") && !sent.Contains("0801") && !sent.Contains("04"),
            "no control execution or clear");
        Assert(report.Steps.Single(x => x.RequestHex == "0A").Status == "NOT_TESTED",
            "negative is not positive evidence");
        Assert(report.Steps.Single(x => x.RequestHex == "03").Status == "PASSED",
            "positive zero count is evidence");
        Assert(!sent.Contains("020000"), "no freeze-frame inventory without trigger");

        var malformed = ObdJ1979FunctionalScan.Run((request, _) =>
            request[0] == 1 ? [0x41, 0x20, 0, 0, 0, 0] : [0x7F, request[0], 0x11],
            () => {}, TimeSpan.FromMinutes(1));
        Assert(malformed.Steps.Single(x => x.RequestHex == "0100").Status == "FAILED", "wrong identifier rejected");
        Assert(malformed.ObservedVin is null, "missing VIN not inferred");
        var faultCalls = 0;
        var partial = ObdJ1979FunctionalScan.Run((request, _) =>
        {
            faultCalls++;
            if (faultCalls == 1)
                return [0x49, 2, 1, ..System.Text.Encoding.ASCII.GetBytes("1G1FA1RXXR0123456")];
            throw new IOException("Disconnected");
        }, () => {}, TimeSpan.FromMinutes(1));
        Assert(faultCalls == 2 && !partial.Completed && partial.StopReason == "TRANSPORT_ERROR",
            "terminal transport failure stops all following requests");
        Assert(partial.ObservedVin == "1G1FA1RXXR0123456" && partial.Steps.Count == 2 &&
            partial.Steps[1].Status == "FAILED", "partial evidence retained");
        var tx = 0;
        try
        {
            ObdJ1979FunctionalScan.Run((_, _) => { tx++; return [0x41, 0, 0, 0, 0, 0]; },
                () => { if (tx != 0) throw new OperationCanceledException(); }, TimeSpan.FromMinutes(1));
            throw new InvalidOperationException("Cancellation not propagated.");
        }
        catch (OperationCanceledException) { Assert(tx == 1, "no late request after cancellation"); }

        return checks;
    }
}
