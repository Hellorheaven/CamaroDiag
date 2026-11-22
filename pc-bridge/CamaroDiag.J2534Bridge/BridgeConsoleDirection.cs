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

    
/// <summary>Truthful, testable TX evidence for the Windows J2534 console.
/// A write accepted by a driver cannot be reported as a bus-level ECU ACK.</summary>
internal enum BridgeConsoleTxState { Requested, DriverAccepted, EcuReplied, Unconfirmed }

internal static class BridgeConsoleTxEvidence
{
    internal static string Label(BridgeConsoleTxState state) => state switch
    {
        BridgeConsoleTxState.Requested => "demande TX, non confirmée",
        BridgeConsoleTxState.DriverAccepted => "acceptée par le pilote J2534 ; émission sur bus non vérifiée",
        BridgeConsoleTxState.EcuReplied => "réponse ECU corrélée reçue",
        BridgeConsoleTxState.Unconfirmed => "TX non confirmée : erreur ou délai d'attente",
        _ => "TX non confirmée"
    };

    internal static bool HasVerifiedEcuReply(BridgeConsoleTxState state) =>
        state == BridgeConsoleTxState.EcuReplied;
}
