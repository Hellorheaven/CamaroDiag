using System.Globalization;
using System.Resources;

namespace CamaroDiag.J2534Bridge;

/// <summary>Localized presentation of observed DoIP gateway identities.</summary>
internal static class DoIpConsolePresentation
{
    private static readonly ResourceManager Resources =
        new("CamaroDiag.J2534Bridge.DoIpConsolePresentation", typeof(DoIpConsolePresentation).Assembly);

    internal static string Text(string key, CultureInfo? culture = null) =>
        Resources.GetString(key, culture ?? CultureInfo.CurrentUICulture)
        ?? throw new InvalidOperationException("Missing DoIP console resource: " + key);

    internal readonly record struct TargetReadiness(bool Gateway, bool SingleEcu,
        bool ExplicitVinEcu, bool Inventory);

    internal static TargetReadiness EvaluateTarget(DoIpVehicleDiscovery.Identity? observed,
        string host, string expectedVin, string testerText, string ecuText, string inventoryText)
    {
        if (observed is null || !System.Net.IPAddress.TryParse(host, out var address) ||
            !observed.Source.Equals(address))
            return default;
        var explicitVin = DoIpVehicleDiscovery.IsVehicleVin(expectedVin) && observed.Vin == expectedVin;
        ushort tester;
        try { tester = DoIpVinReader.ParseLogicalAddress(testerText); }
        catch (ArgumentException) { return new(true, false, false, false); }
        if (tester == observed.LogicalAddress) return new(true, false, false, false);
        bool single = false, inventory = false;
        try { single = DoIpVinReader.ParseLogicalAddress(ecuText) != tester; }
        catch (ArgumentException) { /* no usable single ECU target */ }
        if (explicitVin)
        {
            try { inventory = !DoIpVinReader.ParseInventoryAddresses(inventoryText).Contains(tester); }
            catch (ArgumentException) { /* no usable inventory targets */ }
        }
        return new(true, single, single && explicitVin, inventory);
    }

    internal static string FormatStatus(DoIpCodec.Message response,
        bool powerMode, CultureInfo? culture = null)
    {
        ArgumentNullException.ThrowIfNull(response);
        if (powerMode)
        {
            var mode = DoIpCodec.ParseDiagnosticPowerMode(response);
            var label = Text(mode switch
            {
                0 => "PowerNotReady",
                1 => "PowerReady",
                _ => "PowerUnsupported"
            }, culture);
            return string.Format(CultureInfo.InvariantCulture,
                Text("PowerResult", culture), mode, label);
        }
        var status = DoIpCodec.ParseEntityStatus(response);
        return string.Format(CultureInfo.InvariantCulture,
            Text("StatusResult", culture), status.NodeType,
            status.CurrentSockets, status.MaxSockets,
            status.MaxDataSize?.ToString(CultureInfo.InvariantCulture) ??
                Text("Unavailable", culture));
    }

    // ISO 14229 status bits, independent of the OEM's DTC code dictionary.
    // Bits outside DTCStatusAvailabilityMask are never interpreted.
    internal static string FormatDtcStatus(byte status, byte availabilityMask,
        CultureInfo? culture = null)
    {
        string[] keys = ["DtcBitFailed", "DtcBitFailedCycle", "DtcBitPending", "DtcBitConfirmed",
            "DtcBitIncompleteSinceClear", "DtcBitFailedSinceClear", "DtcBitIncompleteCycle",
            "DtcBitWarningRequested"];
        var labels = new List<string>();
        int asserted = status & availabilityMask;
        for (int bit = 0; bit < keys.Length; bit++)
            if ((asserted & (1 << bit)) != 0)
                labels.Add(Text(keys[bit], culture));
        if (availabilityMask == 0) return Text("DtcStatusUnavailable", culture);
        if (labels.Count == 0) return Text("DtcNoAssertedAvailableBits", culture);
        return string.Join(", ", labels);
    }

    internal static string FormatDtcEntry(uint code, byte status, byte availabilityMask,
        CultureInfo? culture = null) =>
        string.Format(CultureInfo.InvariantCulture, Text("DtcEntry", culture), code, status) +
            " — " + FormatDtcStatus(status, availabilityMask, culture);

    internal static IReadOnlyList<string> FormatDtcs(
        ObdOnUdsServices.DtcStatusReport report, byte requestedMask,
        CultureInfo? culture = null)
    {
        ArgumentNullException.ThrowIfNull(report);
        var lines = new List<string>(report.Codes.Count + 2)
        {
            string.Format(CultureInfo.InvariantCulture, Text("DtcResult", culture),
                report.Codes.Count, requestedMask, report.AvailabilityMask,
                requestedMask & report.AvailabilityMask)
        };
        foreach (var code in report.Codes)
            lines.Add(FormatDtcEntry(code.Code, code.Status, report.AvailabilityMask, culture));
        lines.Add(Text((requestedMask & report.AvailabilityMask) == 0 ?
            "DtcNoAvailableFilter" : "DtcNotice", culture));
        return lines;
    }

    internal static IReadOnlyList<string> FormatWwh(ObdOnUdsServices.WwhDtcReport report,
        byte subfunction, byte? group, CultureInfo? culture = null)
    {
        string Hex(byte? value) => value?.ToString("X2", CultureInfo.InvariantCulture) ?? Text("Unavailable", culture);
        var lines = new List<string>
        {
            string.Format(CultureInfo.InvariantCulture, Text("WwhResult", culture),
                subfunction, report.Codes.Count, report.StatusAvailabilityMask, report.DtcFormatIdentifier),
            string.Format(CultureInfo.InvariantCulture, Text("WwhMetadata", culture),
                Hex(report.SeverityAvailabilityMask), Hex(group))
        };
        foreach (var code in report.Codes)
            lines.Add(string.Format(CultureInfo.InvariantCulture, Text("WwhRecord", culture),
                code.Code, code.Status, Hex(code.Severity)) + " — " +
                FormatDtcStatus(code.Status, report.StatusAvailabilityMask, culture));
        return lines;
    }

    internal static string FormatDtcCount(ObdOnUdsServices.DtcCountReport report,
        byte requestedMask, CultureInfo? culture = null) =>
        string.Format(CultureInfo.InvariantCulture, Text("DtcCountResult", culture),
            report.Count, requestedMask, report.StatusAvailabilityMask, report.DtcFormatIdentifier);

    internal static (uint Dtc, byte Record) ParseDetail(string dtc, string record)
    {
        if (dtc.Length != 6 || !uint.TryParse(dtc, NumberStyles.AllowHexSpecifier,
                CultureInfo.InvariantCulture, out var code) ||
            record.Length != 2 || !byte.TryParse(record, NumberStyles.AllowHexSpecifier,
                CultureInfo.InvariantCulture, out var number) || number == 0)
            throw new ArgumentException("Expected a 24-bit DTC and nonzero record number.");
        return (code, number);
    }

    internal static string FormatDetail(uint dtc, byte status, byte[] records, CultureInfo? culture = null) =>
        string.Format(CultureInfo.InvariantCulture, Text("DetailResult", culture),
            dtc, status, Convert.ToHexString(records));

    internal static byte[][] ParseCommands(string text)
    {
        var parts = text.Split(';');
        if (parts.Length is < 1 or > 16)
            throw new ArgumentException("Expected 1..16 semicolon-separated commands.");
        var commands = parts.Select(ParsePayload).ToArray();
        if (commands.Sum(x => x.Length) > 4091)
            throw new ArgumentException("Command sequence is too large.");
        return commands;
    }

    internal static byte[] ParsePayload(string text)
    {
        var compact = string.Concat(text.Where(c => !char.IsWhiteSpace(c)));
        if (compact.Length is < 2 or > 8182 || compact.Length % 2 != 0 ||
            compact.Any(c => !Uri.IsHexDigit(c)))
            throw new ArgumentException("Expected a hexadecimal UDS payload.");
        return Convert.FromHexString(compact);
    }

    internal static byte ParseDtcMask(string text)
    {
        if (text.Length != 2 || !byte.TryParse(text, NumberStyles.AllowHexSpecifier,
                CultureInfo.InvariantCulture, out var mask))
            throw new ArgumentException("Expected two hexadecimal digits.", nameof(text));
        return mask;
    }

    internal static string FormatIdentity(DoIpVehicleDiscovery.Identity identity,
        CultureInfo? culture = null)
    {
        ArgumentNullException.ThrowIfNull(identity);
        return string.Format(CultureInfo.InvariantCulture, Text("Identity", culture),
            identity.Source, identity.ProtocolVersion, identity.LogicalAddress,
            identity.Vin ?? Text("Unavailable", culture),
            Convert.ToHexString(identity.Eid), Convert.ToHexString(identity.Gid),
            identity.FurtherAction, identity.VinGidStatus?.ToString("X2") ??
                Text("Unavailable", culture));
    }
}
