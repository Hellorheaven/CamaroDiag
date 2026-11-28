using System.Globalization;
using System.Resources;

namespace CamaroDiag.J2534Bridge;

/// <summary>Localized strings for the offline ECU recovery inspection UI.</summary>
internal static class BridgeRecoveryPresentation
{
    private static readonly ResourceManager Resources =
        new("CamaroDiag.J2534Bridge.BridgeRecoveryPresentation",
            typeof(BridgeRecoveryPresentation).Assembly);

    internal static string Text(string key, CultureInfo? culture = null) =>
        Resources.GetString(key, culture ?? CultureInfo.CurrentUICulture)
        ?? throw new InvalidOperationException("Missing recovery UI resource: " + key);
}
