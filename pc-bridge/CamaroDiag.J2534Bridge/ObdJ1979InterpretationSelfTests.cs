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

        var trigger = ObdJ1979Interpretation.Interpret(
            [0x02, 0x02, 1], [0x42, 0x02, 1, 0x01, 0x30]);
        Check(trigger.Positive && trigger.Description.EndsWith("freeze-frame=1 DTC=P0130"),
            "Freeze-frame number and stored trigger code are both preserved.");
        var emptyFrame = ObdJ1979Interpretation.Interpret(
            [0x02, 0x02, 0], [0x42, 0x02, 0, 0, 0]);
        Check(emptyFrame.Positive && emptyFrame.Description.EndsWith("DTC=none"),
            "A zero DTC means no stored freeze frame.");
        Reject<InvalidDataException>(() => ObdJ1979Interpretation.Interpret(
            [0x02, 0x02, 1], [0x42, 0x02, 0, 0x01, 0x30]), "Unrelated frame number.");
        Reject<InvalidDataException>(() => ObdJ1979Interpretation.Interpret(
            [0x02, 0x02, 0], [0x42, 0x02, 0, 1]), "Truncated trigger code.");
        Reject<InvalidDataException>(() => ObdJ1979Interpretation.Interpret(
            [0x02, 0x02, 0], [0x42, 0x02, 0, 1, 0x30, 0]), "Oversized trigger code.");

        var reordered = ObdJ1979Interpretation.Interpret(
            [0x01, 0x0C, 0x0D, 0x0D], [0x41, 0x0D, 30, 0x0C, 0x10, 0, 0x0D, 31]);
        Check(reordered.Positive && reordered.Description.Contains("30") &&
            reordered.Description.Contains("1024") && reordered.Description.Contains("31") &&
            !reordered.Description.Contains("not-returned"), "Reordered and repeated Mode 01 values.");
        var partial = ObdJ1979Interpretation.Interpret(
            [0x02, 0x02, 0, 0x02, 1], [0x42, 0x02, 1, 0x01, 0x30]);
        Check(partial.Positive && partial.Description.Contains("DTC=P0130") &&
            partial.Description.Contains("not-returned=02/00"), "Unanswered freeze frames stay missing.");
        var multiplePages = ObdJ1979Interpretation.Interpret(
            [0x01, 0x00, 0x20], [0x41, 0x20, 0x80, 0, 0, 0]);
        Check(multiplePages.Description.Contains("supported=21") &&
            multiplePages.Description.Contains("not-returned=00"), "A later requested page may answer first.");
        Reject<InvalidDataException>(() => ObdJ1979Interpretation.Interpret(
            [0x01, 0x0C, 0x0D], [0x41, 0x0D, 0, 0x0D, 1]), "Extra unrequested duplicate.");
        Reject<InvalidDataException>(() => ObdJ1979Interpretation.Interpret(
            [0x02, 0x0C, 0, 0x0C, 1], [0x42, 0x0C, 2, 0, 0]), "Unrequested freeze frame.");
        Reject<InvalidDataException>(() => ObdJ1979Interpretation.Interpret(
            [0x01, 0x0D, 0x0C], [0x41, 0x0D, 31, 0x0C, 0]), "Incomplete final data record.");
        Reject<ArgumentException>(() => ObdJ1979Interpretation.Interpret(
            [0x01, 0x00, 0x0D], [0x41, 0x0D, 0]), "Support pages and data cannot mix.");
        var unparsed = ObdJ1979Interpretation.Interpret(
            [0x01, 0x0D, 0xFE], [0x41, 0xFE, 0x0D, 0x40]);
        Check(unparsed.Description.Contains("unparsed-from-PID=FE raw=FE0D40") &&
            !unparsed.Description.Contains("not-returned"), "Unknown width keeps the tail uninterpreted.");
        var monitors = ObdJ1979Interpretation.Interpret(
            [0x06, 0x00], [0x46, 0, 0x80, 0, 0, 1]);
        Check(monitors.Positive &&
            monitors.Description.Contains("supported=01,20"),
            "Mode 06 monitor availability is interpreted.");
        var monitorTest = ObdJ1979Interpretation.Interpret(
            [0x06, 0x21],
            [0x46, 0x21, 0x80, 0x24, 0, 0x64, 0, 0, 0, 0xC8]);
        Check(monitorTest.Positive &&
            monitorTest.Description.Contains("TID=0x80") &&
            monitorTest.Description.Contains("UASID=0x24") &&
            monitorTest.Description.Contains("raw=100") &&
            monitorTest.Description.Contains("unit=count") &&
            monitorTest.Description.Contains("no monitor pass verdict"),
            "Mode 06 retains raw unit/scaling ID and exposes known engineering values.");
        foreach (var encoded in new[]{"00800100","00001100","00010110","00080400","00080004"})
        {
            var baseline=Convert.FromHexString(encoded);
            var cycle=ObdJ1979OperatingContext.DecodeCycle([0,(byte)(baseline[1]&8),1,0],baseline);
            Check(cycle is {SupportVerified:false} && cycle.Monitors.All(x=>x.Supported is null),
                "Malformed PID01 cannot establish monitor support.");
        }
        var ambiguous=ObdJ1979OperatingContext.Project(new ObdJ1979OperatingContext.Observation[]{
            new("0101","410100010100","PASSED"),new("0101","410100","RAW"),
            new("0141","414100010100","PASSED"),new("011D","411D30","PASSED"),
            new("011D","411D3030","RAW"),new("0106","41068090","PASSED")});
        Check(ambiguous.DriveCycle is {SupportVerified:false} &&
            ambiguous.FuelTrims.Single().Signals[1].Number is null,
            "Ambiguous operating context never establishes monitor or upper-bank support.");

        var readiness = ObdJ1979Interpretation.Interpret(
            [0x01, 0x01], [0x41, 0x01, 0x83, 0x08, 0xA5, 0x5A]);
        Check(readiness.Positive &&
            readiness.Description.Contains("MIL=ON") &&
            readiness.Description.Contains("confirmed-DTCs=3"),
            "Physical CAN PID 01 reply exposes MIL and stored-DTC count.");

        var vin = new byte[] { 0x49, 0x02, 0x01 }
            .Concat(Encoding.ASCII.GetBytes("1G1FF1R70C0100001")).ToArray();
        Check(ObdJ1979Interpretation.Interpret([0x09, 0x02], vin)
            .Description.Contains("1G1FF1R70C0100001"), "VIN decoded after ISO-TP.");

        var types = ObdJ1979Interpretation.Interpret(
            [0x09, 0x00], [0x49, 0x00, 0x54, 0, 0, 0x01]);
        Check(types.Positive && types.Description.Contains("02,04,06,20"),
            "Mode 09 supported InfoTypes interpreted from actual bitmap.");
        var calibrationId = new byte[] { 0x49, 0x04, 0x01 }
            .Concat(Encoding.ASCII.GetBytes("ECU-CAL-ALPHA001")).ToArray();
        var calResult = ObdJ1979Interpretation.Interpret([0x09, 0x04], calibrationId);
        Check(calResult.Positive &&
            calResult.Description.Contains("ECU-CAL-ALPHA001"),
            "Calibration ID decoded without inventing ECU identity.");
        var cvn = ObdJ1979Interpretation.Interpret(
            [0x09, 0x06], [0x49, 0x06, 0x01, 0x12, 0x34, 0xAB, 0xCD]);
        Check(cvn.Positive && cvn.Description.Contains("1234ABCD") &&
            cvn.Description.Contains("not manufacturer authenticity"),
            "CVN hexadecimal display does not imply OEM certificate.");

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
        Check(parsed.InterpretJ1979 && parsed.Request.SequenceEqual(new byte[] {0x01, 0}),
            "Actual ISO-TP CLI can request J1979 interpretation.");

        Reject<ArgumentException>(() =>
            StandardProtocolCli.ParseIsoTp(new string[]
            {
                StandardProtocolCli.RawIsoTpCommand,
                "--adapter-index", "0", "--tx-id", "7E0",
                "--rx-id", "7E8", "--baud", "500000",
                "--fd", "false", "--payload", "22F190",
                "--j1979-decode", "true"
            }),
            "J1979 decode never parses classic 0x22 UDS as an emissions service.");
        Reject<ArgumentException>(() =>
            StandardProtocolCli.ParseIsoTp(new string[]
            {
                StandardProtocolCli.RawIsoTpCommand,
                "--adapter-index", "0", "--tx-id", "7E0",
                "--rx-id", "7E8", "--baud", "500000",
                "--fd", "false", "--obd-did", "F810",
                "--j1979-decode", "true"
            }),
            "Classic J1979 decoder may not be combined with SAE J1979-2 UDS DID selector.");
        Reject<ArgumentException>(() =>
            StandardProtocolCli.ParseIsoTp(new string[]
            {
                StandardProtocolCli.RawIsoTpCommand,
                "--adapter-index", "0", "--tx-id", "7E0",
                "--rx-id", "7E8", "--baud", "500000",
                "--fd", "true", "--payload", "0100",
                "--j1979-decode", "true"
            }),
            "Classic ISO 15765-4 J1979 requires classic CAN; FD uses another profile.");
        Reject<ArgumentException>(() =>
            StandardProtocolCli.ParseIsoTp(args.Concat(
                new[] { "--uds-validate", "true" }).ToArray()),
            "UDS and classic J1979 validators cannot be combined.");

        Reject<ArgumentException>(() =>
            StandardProtocolCli.ParseIsoTp(args.Concat(
                new[] { "--mode", "receive" }).ToArray()),
            "J1979 correlation needs outgoing request.");
        var fuel=ObdJ1979Status.DecodeResponse([1,3],[0x41,3,2,3]);
        Check(fuel is {Count:2}&&fuel[0].State=="CLOSED_FEEDBACK"&&fuel[1].State=="UNKNOWN",
            "fuel status retains unknown code");
        var boost=new byte[12];boost[0]=0x41;boost[1]=0x70;boost[2]=4;boost[11]=14;
        var states=ObdJ1979Status.DecodeResponse([1,0x70],boost);
        Check(states is {Count:1}&&states[0].State=="CLOSED_LOOP","boost mask isolates status A");
        Check(ObdJ1979Interpretation.Interpret([1,0x70],boost).Description.Contains("CLOSED_LOOP"),
            "console includes categorical fields");
        boost[11]=0xFE;
        Check(ObdJ1979Status.DecodeResponse([1,0x70],boost) is null,"reserved boost bits rejected");
        return checks;
    }
}
