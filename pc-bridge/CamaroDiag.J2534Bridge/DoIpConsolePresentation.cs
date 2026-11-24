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

    internal static IReadOnlyList<string> FormatDtcs(
        ObdOnUdsServices.DtcStatusReport report, byte requestedMask,
        CultureInfo? culture = null)
    {
        ArgumentNullException.ThrowIfNull(report);
        var lines = new List<string>(report.Codes.Count + 2)
        {
            string.Format(CultureInfo.InvariantCulture, Text("DtcResult", culture),
                report.Codes.Count, requestedMask, report.AvailabilityMask)
        };
        foreach (var code in report.Codes)
            lines.Add(string.Format(CultureInfo.InvariantCulture,
                Text("DtcEntry", culture), code.Code, code.Status));
        lines.Add(Text("DtcNotice", culture));
        return lines;
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
