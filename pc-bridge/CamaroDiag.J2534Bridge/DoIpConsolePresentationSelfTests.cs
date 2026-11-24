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

        var observed = new DoIpVehicleDiscovery.Identity(
            "1G1FA1RXXR0123456", 0x1234, [1, 2, 3, 4, 5, 6],
            [7, 8, 9, 10, 11, 12], 0x10, 0x00, IPAddress.Loopback, 3);
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
            foreach (var key in new[] { "Host", "Discover", "Cancel", "Notice",
                "Started", "Canceled", "Busy", "InvalidHost" })
                Check(!string.IsNullOrWhiteSpace(DoIpConsolePresentation.Text(key, culture)),
                    "Resource " + key + " in " + language);
        }
        return checks;
    }
}
