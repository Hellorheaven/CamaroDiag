using System.Text;

namespace CamaroDiag.J2534Bridge;

/// <summary>J1979 application response tests, no DLL, VCI or real ECU.</summary>
internal static class ObdJ1979InterpretationSelfTests
{
    internal static int Run()
    {
        var checks = 0;
        void Check(bool ok, string name)
        {
            checks++;
            if (!ok) throw new InvalidOperationException("J1979 live interpretation: " + name);
        }
        void Reject<T>(Action action, string name) where T : Exception
        {
            bool caught = false;
            try { action(); }
            catch (T) { caught = true; }
            Check(caught, name);
        }
        var support = ObdJ1979Interpretation.Interpret(
            [0x01, 0x00], [0x41, 0x00, 0x80, 0, 0, 1]);
        Check(support.Positive && support.Description.Contains("supported=01,20") &&
            support.Description.Contains("next=True"), "PID bitmap interpreted.");

        var freeze = ObdJ1979Interpretation.Interpret(
            [0x02, 0x00, 0x01], [0x42, 0x00, 0x01, 0x80, 0, 0, 0]);
        Check(freeze.Positive && freeze.Description.Contains("freeze-frame=1"),
            "Mode 02 freeze-frame correlation.");

        var vin = new byte[] { 0x49, 0x02, 0x01 }
            .Concat(Encoding.ASCII.GetBytes("1G1FF1R70C0100001")).ToArray();
        Check(ObdJ1979Interpretation.Interpret([0x09, 0x02], vin)
            .Description.Contains("1G1FF1R70C0100001"), "VIN decoded after ISO-TP.");

        var dtcs = ObdJ1979Interpretation.Interpret(
            [0x03], [0x43, 0x02, 0x01, 0x33, 0xC1, 0]);
        Check(dtcs.Positive && dtcs.Description.Contains("P0133,U0100") &&
            dtcs.Description.Contains("count=2"), "DTC pairs interpreted.");

        var pending = ObdJ1979Interpretation.Interpret(
            [0x07], [0x47, 0x01, 0x01, 0x33]);
        Check(pending.Positive && pending.Description.Contains("P0133") &&
            pending.Description.Contains("count=1"),
            "Pending DTC count decoded for Mode 07.");
        var permanent = ObdJ1979Interpretation.Interpret(
            [0x0A], [0x4A, 0x00]);
        Check(permanent.Positive && permanent.Description.Contains("count=0"),
            "Mode 0A empty permanent DTC list accepted.");
        var negative = ObdJ1979Interpretation.Interpret(
            [0x04], [0x7F, 0x04, 0x22]);
        Check(!negative.Positive && negative.Description.Contains("NRC=0x22"),
            "Negative reply cannot be reported as completed clear.");

        var clear = ObdJ1979Interpretation.Interpret([0x04], [0x44]);
        Check(clear.Positive && clear.Description.Contains("no scaling or ECU effect verified"),
            "Positive clear ACK never claims actual ECU operation succeeded.");

        var raw = ObdJ1979Interpretation.Interpret(
            [0x01, 0x0C], [0x41, 0x0C, 0x2E, 0xE0]);
        Check(raw.Positive && raw.Description.Contains("engine-rpm=3000 rpm"),
            "Known SAE J1979 RPM signal is scaled from A/B bytes.");
        var unknown = ObdJ1979Interpretation.Interpret(
            [0x01, 0x90], [0x41, 0x90, 0x22]);
        Check(unknown.Positive && unknown.Description.Contains("data=9022") &&
            !unknown.Description.Contains("rpm="),
            "Unknown PID remains raw without invented scale.");

        Reject<InvalidDataException>(() =>
            ObdJ1979Interpretation.Interpret([0x01, 0x0C],
                [0x41, 0x0D, 0x00]),
            "Wrong PID on same service cannot be reported as success.");
        Reject<InvalidDataException>(() =>
            ObdJ1979Interpretation.Interpret([0x06, 0x01],
                [0x46, 0x02, 0x00]),
            "Wrong monitor identifier rejected.");
        Reject<InvalidDataException>(() =>
            ObdJ1979Interpretation.Interpret([0x09, 0x02], [0x49, 0x02, 0x01]),
            "Incomplete VIN rejected.");
        Reject<InvalidDataException>(() =>
            ObdJ1979Interpretation.Interpret([0x01, 0x00],
                [0x41, 0x20, 0, 0, 0, 0]),
            "Mismatched PID page rejected.");
        Reject<InvalidDataException>(() =>
            ObdJ1979Interpretation.Interpret([0x03], [0x43, 0x01]),
            "Partial DTC rejected.");

        var args = new string[]
        {
            StandardProtocolCli.RawIsoTpCommand, "--adapter-index", "0",
            "--tx-id", "7E0", "--rx-id", "7E8", "--baud", "500000",
            "--fd", "false", "--payload", "0100", "--j1979-decode", "true"
        };
        var parsed = StandardProtocolCli.ParseIsoTp(args);
        Check(parsed.InterpretJ1979 && parsed.Request.SequenceEqual([0x01, 0]),
            "Actual ISO-TP CLI can request J1979 interpretation.");

        Reject<ArgumentException>(() =>
            StandardProtocolCli.ParseIsoTp(args.Concat(
                new[] { "--uds-validate", "true" }).ToArray()),
            "UDS and classic J1979 validators cannot be combined.");

        Reject<ArgumentException>(() =>
            StandardProtocolCli.ParseIsoTp(args.Concat(
                new[] { "--mode", "receive" }).ToArray()),
            "J1979 correlation needs outgoing request.");
        return checks;
    }
}
