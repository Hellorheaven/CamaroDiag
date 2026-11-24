using System.Globalization;

namespace CamaroDiag.J2534Bridge;

internal static class BridgeCommandLineHelpSelfTests
{
    internal static int Run()
    {
        int checks = 0;
        void Require(bool valid, string description)
        {
            checks++;
            if (!valid) throw new InvalidOperationException(
                "Bridge CLI help test failed: " + description);
        }

        foreach (var option in new[] { "--help", "--HELP", "-h", "/?" })
            Require(BridgeCommandLineHelp.IsHelpToken(option), "supported help alias");
        Require(!BridgeCommandLineHelp.IsHelpToken("--self-test"), "not help");
        Require(!BridgeCommandLineHelp.IsHelpToken("--help--program"), "no substring matching");

        Require(BridgeCommandLineHelp.IsHelpRequest(["--help"]), "standalone help");
        Require(BridgeCommandLineHelp.IsHelpRequest(["--program-e39-bench", "--help"]),
            "help always wins over hardware command dispatch");
        Require(!BridgeCommandLineHelp.IsHelpRequest(["--self-test"]), "test remains separate");
        Require(BridgeCommandLineHelp.Run(["--help", "--program-e39-bench"]) == 2,
            "mixed help cannot proceed to programming or bridge startup");

        var english = BridgeCommandLineHelp.Render(CultureInfo.GetCultureInfo("en-US"));
        var french = BridgeCommandLineHelp.Render(CultureInfo.GetCultureInfo("fr-FR"));
        Require(english.Contains("Usage:", StringComparison.Ordinal), "English resource");
        Require(french.Contains("Utilisation", StringComparison.Ordinal), "French resource");
        foreach (var option in new[] {
            "--self-test", "--probe-drivers", "--list-ports", "--discover-connector",
            "--inspect-write-journal", "--inspect-e39-image", "--port 35001"
        })
        {
            Require(english.Contains(option, StringComparison.Ordinal), "English " + option);
            Require(french.Contains(option, StringComparison.Ordinal), "French " + option);
        }
        Require(english.Contains("issue #115", StringComparison.Ordinal) &&
                french.Contains("issue #115", StringComparison.Ordinal),
            "Windows/Linux CLI parity roadmap stays explicit");

        Console.WriteLine($"PASS: {checks} Windows offline help checks.");
        return checks;
    }
}
