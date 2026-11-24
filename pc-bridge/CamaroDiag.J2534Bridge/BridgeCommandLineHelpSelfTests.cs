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
        foreach (var option in new[] {
            "--doip-discover", "--doip-status", "--doip-exchange", "--doip-backup", "--doip-verify-backup", "--confirm-read true",
            "--listen-ipv6-ifindex", "--multicast-ipv6-ifindex",
            "--doip-version", "--uds-p2-ms", "--uds-p2-star-ms",
            "--power-mode true", "--broadcast-ip", "--vin", "--eid",
            "--tls true", "--tester", "--ecu", "--payload"
        })
        {
            Require(english.Contains(option, StringComparison.Ordinal) &&
                    french.Contains(option, StringComparison.Ordinal),
                "DoIP CLI help options present in both languages: " + option);
        }
        Require(english.Contains("ACTIVE", StringComparison.Ordinal) &&
                french.Contains("ACTIVE", StringComparison.Ordinal),
            "DoIP multicast active discovery is distinguished from passive listening.");
        Require(english.Contains("version=0x02/0x03/0x04", StringComparison.Ordinal) &&
                french.Contains("version=0x02/0x03/0x04", StringComparison.Ordinal),
            "DoIP discovered protocol versions displayed in French and English.");
        Require(english.Contains("consent", StringComparison.OrdinalIgnoreCase) &&
                french.Contains("consentement", StringComparison.OrdinalIgnoreCase),
            "Both CLI help resources warn that DoIP exchanges require consent.");
        Require(BridgeCommandLineHelp.Run(["--doip-exchange", "--help"]) == 2,
            "Help with DoIP UDS exchange cannot reach any socket or ECU.");

        Require(english.Contains("issue #115", StringComparison.Ordinal) &&
                french.Contains("issue #115", StringComparison.Ordinal),
            "Windows/Linux CLI parity roadmap stays explicit");

        Console.WriteLine($"PASS: {checks} Windows offline help checks.");
        return checks;
    }
}
