namespace CamaroDiag.J2534Bridge;

/// <summary>
/// Pure Windows TLS bridge startup argument policy. Invalid, repeated or
/// unknown arguments must never fall back to driver discovery, auto-update,
/// J2534 ECU operations or a network listener.
/// </summary>
internal sealed record BridgeLaunchArguments(
    int Port,
    bool AllowObdClear,
    string? MemoryReadProfile)
{
    internal static bool TryParse(
        string[] args,
        out BridgeLaunchArguments? parsed,
        out string error)
    {
        parsed = null;
        error = "";
        var port = 35001;
        bool seenPort = false;
        bool seenMemory = false;
        bool allowObdClear = false;
        string? memoryReadProfile = null;

        for (var index = 0; index < args.Length; index++)
        {
            var arg = args[index];
            if (arg.Equals("--port", StringComparison.OrdinalIgnoreCase))
            {
                if (seenPort || ++index == args.Length ||
                    !int.TryParse(args[index], out var requestedPort) ||
                    requestedPort is < 1024 or > 65535)
                {
                    error = "Specify exactly one valid --port 1024..65535.";
                    return false;
                }
                port = requestedPort;
                seenPort = true;
            }
            else if (arg.Equals("--memory-read-profile", StringComparison.OrdinalIgnoreCase))
            {
                if (seenMemory || ++index == args.Length ||
                    string.IsNullOrWhiteSpace(args[index]) ||
                    args[index].StartsWith("--", StringComparison.Ordinal) ||
                    args[index].Any(char.IsControl))
                {
                    error = "Specify exactly one local path after --memory-read-profile.";
                    return false;
                }
                memoryReadProfile = args[index];
                seenMemory = true;
            }
            else if (arg.Equals("--allow-obd-clear", StringComparison.OrdinalIgnoreCase))
            {
                if (allowObdClear)
                {
                    error = "Duplicate --allow-obd-clear switch.";
                    return false;
                }
                allowObdClear = true;
            }
            else
            {
                error = "Unknown or misplaced Windows bridge option: " + arg;
                return false;
            }
        }
        parsed = new BridgeLaunchArguments(port, allowObdClear, memoryReadProfile);
        return true;
    }
}
