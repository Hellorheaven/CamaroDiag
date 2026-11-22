namespace CamaroDiag.J2534Bridge;

/// <summary>One direction vocabulary for the Android and Windows CAN consoles.
/// These are protocol/history classifications, not localized UI sentences.</summary>
internal static class BridgeConsoleDirection
{
    internal static string Normalize(string? direction) => direction switch
    {
        "TX" => "TX",
        "RX" => "RX",
        "SYSTEM" or "INFO" => "SYSTEM",
        _ => "SYSTEM"
    };
}
