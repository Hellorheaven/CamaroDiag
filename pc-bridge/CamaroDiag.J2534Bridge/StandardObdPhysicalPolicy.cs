namespace CamaroDiag.J2534Bridge;

/// <summary>
/// Standard SAE J1979 reads on a selected ISO 15765-4 physical OBD channel.
/// This is not a broadcast scan, ELM emulation or ECU programming permission.
/// Start with the conventional primary powertrain address pair, on either
/// standardized CAN identifier width and bit rate. A response still has to
/// prove that the ECU actually implements the requested OBD service.
/// </summary>
internal static class StandardObdPhysicalPolicy
{
    internal static bool SupportsChannel(int request, int response, int protocol) =>
        (protocol is 33 or 35 && request == 0x7E0 && response == 0x7E8) ||
        (protocol is 34 or 36 && request == 0x18DA10F1 && response == 0x18DAF110);

    internal static bool AllowsRead(ReadOnlySpan<byte> payload) =>
        payload.Length == 2 && payload[0] == 0x01 ||
        payload.Length == 1 && payload[0] is 0x03 or 0x07 or 0x0A;
}
