using System.Globalization;

namespace CamaroDiag.J2534Bridge;

internal static class BridgeRecoveryPresentationSelfTests
{
    internal static int Run()
    {
        var checks = 0;
        var keys = new[]
        {
            "VerifyOriginalButton", "ChooseOriginalTitle", "OriginalBinFilter",
            "OriginalBinName", "OriginalBinMatched", "OriginalBinRejected",
            "FailedSector", "OfflineNotice"
        };
        foreach (var culture in new[] { CultureInfo.GetCultureInfo("fr"),
                    CultureInfo.GetCultureInfo("en") })
        {
            foreach (var key in keys)
            {
                if (string.IsNullOrWhiteSpace(BridgeRecoveryPresentation.Text(key, culture)))
                    throw new InvalidOperationException(
                        "Missing localized ECU recovery action: " + culture.Name + "/" + key);
                checks++;
            }
        }
        if (BridgeRecoveryPresentation.Text("VerifyOriginalButton",
                CultureInfo.GetCultureInfo("fr")) ==
            BridgeRecoveryPresentation.Text("VerifyOriginalButton",
                CultureInfo.GetCultureInfo("en")))
            throw new InvalidOperationException("Recovery GUI must have distinct FR/EN labels.");
        checks++;
        return checks;
    }
}
