namespace CamaroDiag.J2534Bridge;

internal static class BridgeLaunchArgumentsSelfTests
{
    internal static int Run()
    {
        int checks = 0;
        void Require(bool value, string explanation)
        {
            checks++;
            if (!value) throw new InvalidOperationException(
                "J2534 bridge command line check failed: " + explanation);
        }

        Require(BridgeLaunchArguments.TryParse([], out var defaults, out _) &&
                defaults is { Port: 35001, AllowObdClear: false, MemoryReadProfile: null },
            "empty console settings retain documented defaults");
        Require(BridgeLaunchArguments.TryParse(
                ["--port", "45001", "--memory-read-profile", "scope.json", "--allow-obd-clear"],
                out var configured, out _) &&
                configured is {
                    Port: 45001, AllowObdClear: true, MemoryReadProfile: "scope.json"
                },
            "explicit, unambiguous settings");

        var malformed = new[] {
            new[] { "--port" },
            new[] { "--port", "0" },
            new[] { "--port", "-1" },
            new[] { "--port", "not-a-port" },
            new[] { "--port", "65536" },
            new[] { "--port", "35001", "--port", "35002" },
            new[] { "--memory-read-profile" },
            new[] { "--memory-read-profile", "--port", "35001" },
            new[] { "--memory-read-profile", " " },
            new[] { "--memory-read-profile", "a.json", "--memory-read-profile", "b.json" },
            new[] { "--memory-read-profile", "name\n.json" },
            new[] { "--allow-obd-clear", "--allow-obd-clear" },
            new[] { "--unknown-option" },
            new[] { "35001" },
            new[] { "--help" },
            new[] { "--allow-obd-clear", "--help" },
            new[] { "--port", "35001", "extra" }
        };
        foreach (var args in malformed)
        {
            Require(!BridgeLaunchArguments.TryParse(args, out var rejected, out var explanation) &&
                    rejected is null && !string.IsNullOrWhiteSpace(explanation),
                "invalid command must fail before driver, updater or listener: " +
                string.Join(" ", args));
        }

        Console.WriteLine($"PASS: {checks} Windows bridge startup argument checks.");
        return checks;
    }
}
