using System.Globalization;
using System.Net;

namespace CamaroDiag.J2534Bridge;

internal static class DoIpConsolePresentationSelfTests
{
    internal static int Run()
    {
        var checks = 0;
        void Check(bool condition, string name)
        {
            checks++;
            if (!condition) throw new InvalidOperationException("DoIP console: " + name);
        }

        var progress = new DoIpMonitoringProgress(2);
        progress.RecordVerified(1);
        Check(!progress.Complete && progress.VerifiedSamples == 1, "Verified partial capture.");
        foreach (var index in new[] { 1, 3, 0 })
        {
            var rejected = false;
            try { progress.RecordVerified(index); }
            catch (InvalidDataException) { rejected = true; }
            Check(rejected && progress.VerifiedSamples == 1, "Reject duplicate or out-of-order sample.");
        }
        var premature = false;
        try { progress.MarkComplete(); }
        catch (InvalidDataException) { premature = true; }
        Check(premature && !progress.Complete, "Incomplete capture cannot succeed.");
        progress.RecordVerified(2);
        progress.MarkComplete();
        Check(progress.Complete && progress.VerifiedSamples == progress.RequestedSamples, "Complete verified capture.");

        var defaults = DoIpDiagnosticTransport.SessionOptions.Parse(3000, "", "", "00", "");
        Check(defaults.P2Ms == 3000 && defaults.P2StarMs == 3000 &&
            defaults.RoutingType == 0 && defaults.OemBytes is null, "Blank settings retain total timeout and default routing.");
        var custom = DoIpDiagnosticTransport.SessionOptions.Parse(5000, "100", "2000", "E0", "01020304");
        Check(custom.P2Ms == 100 && custom.P2StarMs == 2000 && custom.RoutingType == 0xE0 &&
            custom.OemBytes!.SequenceEqual(new byte[] { 1, 2, 3, 4 }), "Custom session settings preserve exact OEM bytes.");
        foreach (var values in new[] { new[] {"0", "2000", "00", ""}, new[] {"2000", "100", "00", ""},
            new[] {"100", "6000", "00", ""}, new[] {"100", "2000", "000", ""},
            new[] {"100", "2000", "00", "0102"} })
        {
            var rejected = false;
            try { _ = DoIpDiagnosticTransport.SessionOptions.Parse(5000, values[0], values[1], values[2], values[3]); }
            catch (ArgumentException) { rejected = true; }
            Check(rejected, "Invalid settings cannot open a diagnostic session.");
        }

        var observed = new DoIpVehicleDiscovery.Identity(
            "1G1FA1RXXR0123456", 0x1234, [1, 2, 3, 4, 5, 6],
            [7, 8, 9, 10, 11, 12], 0x10, 0x00, IPAddress.Loopback, 3);
        var target = DoIpConsolePresentation.EvaluateTarget(observed, "127.0.0.1",
            observed.Vin!, "0E80", "1234", "1234,1235");
        Check(target.Gateway && target.SingleEcu && target.ExplicitVinEcu && target.Inventory,
            "Gateway and valid explicit target allow applicable operations.");
        target = DoIpConsolePresentation.EvaluateTarget(observed, "127.0.0.1",
            "", "0E80", "1234", "1234");
        Check(target.Gateway && target.SingleEcu && !target.ExplicitVinEcu && !target.Inventory,
            "Discovery without explicit VIN cannot authorize VIN-bound routes.");
        target = DoIpConsolePresentation.EvaluateTarget(observed, "127.0.0.1",
            observed.Vin!, "0E80", "", "1234,1235");
        Check(target.Gateway && !target.SingleEcu && target.Inventory,
            "Inventory does not require a single ECU address.");
        target = DoIpConsolePresentation.EvaluateTarget(observed, "127.0.0.1",
            observed.Vin!, "1234", "1235", "1235");
        Check(target.Gateway && !target.SingleEcu && !target.Inventory,
            "Tester address cannot equal gateway address.");
        target = DoIpConsolePresentation.EvaluateTarget(observed, "127.0.0.1",
            observed.Vin!, "0E80", "0E80", "1234,0E80");
        Check(target.Gateway && !target.SingleEcu && !target.Inventory,
            "Tester address cannot be an ECU target.");
        target = DoIpConsolePresentation.EvaluateTarget(observed, "127.0.0.2",
            observed.Vin!, "0E80", "1234", "1234");
        Check(!target.Gateway && !target.SingleEcu && !target.Inventory,
            "A different IP invalidates the entire selection.");
        target = DoIpConsolePresentation.EvaluateTarget(observed with { Vin = null }, "127.0.0.1",
            observed.Vin!, "0E80", "1234", "1234");
        Check(target.SingleEcu && !target.ExplicitVinEcu && !target.Inventory,
            "An unobserved gateway VIN is not proof of selected identity.");
        foreach (var language in new[] { "fr-FR", "en-US" })
        {
            var culture = CultureInfo.GetCultureInfo(language);
            var text = DoIpConsolePresentation.FormatIdentity(observed, culture);
            Check(text.Contains("127.0.0.1") && text.Contains("0x03") &&
                text.Contains("0x1234") && text.Contains(observed.Vin!) &&
                text.Contains("010203040506") && text.Contains("0708090A0B0C"),
                "Gateway identity preserves observed values in " + language);
            var unavailable = DoIpConsolePresentation.FormatIdentity(
                observed with { Vin = null, VinGidStatus = null }, culture);
            Check(unavailable.Contains("VIN=" +
                DoIpConsolePresentation.Text("Unavailable", culture)) &&
                !unavailable.Contains(observed.Vin!),
                "Missing VIN stays unavailable in " + language);
            var status = DoIpCodec.Decode(DoIpCodec.Encode(
                DoIpCodec.EntityStatusResponse, [0, 4, 2, 0, 0, 1, 0]));
            var statusText = DoIpConsolePresentation.FormatStatus(status, false, culture);
            Check(statusText.Contains("2/4") && statusText.Contains("256"),
                "Entity status preserves observed socket counts and maximum size in " + language);
            var withoutSize = DoIpCodec.Decode(DoIpCodec.Encode(
                DoIpCodec.EntityStatusResponse, [1, 3, 1]));
            Check(DoIpConsolePresentation.FormatStatus(withoutSize, false, culture)
                .Contains(DoIpConsolePresentation.Text("Unavailable", culture)),
                "Absent maximum data size stays unavailable in " + language);
            foreach (var mode in new byte[] { 0, 1, 2 })
            {
                var power = DoIpCodec.Decode(DoIpCodec.Encode(
                    DoIpCodec.PowerModeResponse, [mode]));
                var key = mode == 0 ? "PowerNotReady" :
                    mode == 1 ? "PowerReady" : "PowerUnsupported";
                Check(DoIpConsolePresentation.FormatStatus(power, true, culture)
                    .Contains(DoIpConsolePresentation.Text(key, culture)),
                    "Power mode decoded in " + language);
            }
            var dtcs = ObdOnUdsServices.DecodeDtcByStatusMask(
                0x05, [0x59, 0x02, 0x0F, 0x12, 0x34, 0x56, 0x01]);
            var dtcLines = DoIpConsolePresentation.FormatDtcs(dtcs, 0x05, culture);
            Check(dtcLines.Count == 3 && dtcLines[0].Contains("0x05") &&
                dtcLines[0].Contains("0x0F") && dtcLines[1].Contains("0x123456") &&
                dtcLines[1].Contains("0x01"),
                "DTC report preserves code and requested/available masks in " + language);
            var detail = DoIpConsolePresentation.ParseDetail("123456", "01");
            Check(detail.Dtc == 0x123456 && detail.Record == 1 &&
                DoIpConsolePresentation.FormatDetail(detail.Dtc, 8, [1, 0xAB], culture).Contains("01AB"),
                "DTC detail raw bytes in " + language);
            foreach (var key in new[] { "Snapshot", "Extended", "DetailDtc", "DetailRecord", "InvalidDetail", "DetailResult" })
                Check(!string.IsNullOrWhiteSpace(DoIpConsolePresentation.Text(key, culture)), "Detail resource " + key);
            var count = ObdOnUdsServices.DecodeDtcCountByStatusMask([0x59, 1, 0xF0, 4, 0x12, 0x34]);
            var countText = DoIpConsolePresentation.FormatDtcCount(count, 0xF0, culture);
            Check(countText.Contains("4660") && countText.Contains("0xF0") && countText.Contains("0x04"),
                "DTC count preserves unsigned value, availability and format in " + language);
            Check(!string.IsNullOrWhiteSpace(DoIpConsolePresentation.Text("CountDtcs", culture)),
                "Localized DTC count button in " + language);
            var emptyDtcs = ObdOnUdsServices.DecodeDtcByStatusMask(
                0x80, [0x59, 0x02, 0x01]);
            var emptyLines = DoIpConsolePresentation.FormatDtcs(emptyDtcs, 0x80, culture);
            Check(emptyLines.Count == 2 && emptyLines[0].Contains("0x80") &&
                emptyLines[0].Contains("0x01") &&
                emptyLines[1] == DoIpConsolePresentation.Text("DtcNotice", culture),
                "Empty DTC report retains availability limits in " + language);
            foreach (var dtcKey in new[] { "ReadDtcs", "DtcMask", "InvalidDtcMask",
                "DtcStarted", "DtcResult", "DtcEntry", "DtcNotice", "ReadDid", "DidLabel",
                "InvalidDid", "DidStarted", "DidResult", "MonitorDid", "MonitorInterval", "MonitorCount",
                "MonitorSample", "MonitorComplete", "MonitorPartial", "Backup", "BackupProfile", "BackupMismatch",
                "BackupExists", "BackupConfirm", "BackupStarted", "BackupProgress", "BackupSaved",
                "BackupVerify", "BackupVerified" })
                Check(!string.IsNullOrWhiteSpace(DoIpConsolePresentation.Text(dtcKey, culture)),
                    "DTC resource " + dtcKey + " in " + language);
            var rejected = false;
            try { _ = DoIpConsolePresentation.FormatStatus(status, true, culture); }
            catch (InvalidDataException) { rejected = true; }
            Check(rejected, "Wrong response type rejected in " + language);
            foreach (var key in new[] { "Host", "Discover", "Cancel", "Notice",
                "Started", "Canceled", "Busy", "InvalidHost", "Status", "Power",
                "StatusNotice", "StatusStarted", "IdentifyFirst", "ExpectedVin",
                "InvalidExpectedVin", "VinDiscoveryStarted", "TraceIncomplete",
                "Exchange", "Payload", "InvalidPayload", "ExpectedVinRequired", "ExchangeTarget", "ExchangeWarning", "ExchangeWarningTitle", "ExchangeConfirm", "ExchangeStarted", "ExchangeResult" })
                Check(!string.IsNullOrWhiteSpace(DoIpConsolePresentation.Text(key, culture)),
                    "Resource " + key + " in " + language);
        }
        Check(DoIpConsolePresentation.ParseDtcMask("fF") == 0xFF &&
            DoIpConsolePresentation.ParseDtcMask("00") == 0,
            "DTC mask accepts exactly two hexadecimal digits.");
        foreach (var invalid in new[] { "", "F", "0xFF", "GG", " F" })
        {
            var invalidMask = false;
            try { _ = DoIpConsolePresentation.ParseDtcMask(invalid); }
            catch (ArgumentException) { invalidMask = true; }
            Check(invalidMask, "Malformed DTC mask rejected: " + invalid);
        }
        Check(DoIpConsolePresentation.ParsePayload("22 f1 90").SequenceEqual(new byte[] { 0x22, 0xf1, 0x90 }),
            "Console accepts exact command bytes.");
        foreach (var invalid in new[] { "", "0x22", "ABC", "GG", new string('A', 8184) })
        {
            var rejected = false;
            try { _ = DoIpConsolePresentation.ParsePayload(invalid); }
            catch (ArgumentException) { rejected = true; }
            Check(rejected, "Malformed or oversized UDS command rejected.");
        }
        var commands = DoIpConsolePresentation.ParseCommands("22 F1 90;19 01 FF");
        Check(commands.Length == 2 && commands[0].SequenceEqual(new byte[] {0x22, 0xf1, 0x90}) &&
            commands[1].SequenceEqual(new byte[] {0x19, 0x01, 0xff}), "Ordered sequence parsing.");
        foreach (var invalid in new[] {"22;", ";22", "22;;19", string.Join(";", Enumerable.Repeat("22", 17))})
        {
            var rejected = false;
            try { _ = DoIpConsolePresentation.ParseCommands(invalid); }
            catch (ArgumentException) { rejected = true; }
            Check(rejected, "Invalid sequence rejected.");
        }
        return checks;
    }
}
