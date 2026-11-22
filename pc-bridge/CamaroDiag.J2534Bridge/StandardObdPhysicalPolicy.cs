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

    // Clear is available only via a dedicated operation opted in by the local
    // PC operator, not via the arbitrary read-only exchange interface.
    internal static bool CanClear(bool enabled, bool physicalChannel,
        ReadOnlySpan<byte> speed, ReadOnlySpan<byte> rpm) =>
        enabled && physicalChannel &&
        speed.SequenceEqual(new byte[] { 0x41,0x0D,0 }) &&
        rpm.SequenceEqual(new byte[] { 0x41,0x0C,0,0 });

    internal static bool IsClearAcknowledged(ReadOnlySpan<byte> payload) =>
        payload.SequenceEqual(new byte[] { 0x44 });

    internal static bool AllowsRead(ReadOnlySpan<byte> payload) =>
        payload.Length == 2 && payload[0] == 0x01 ||
        payload.Length == 3 && payload[0] == 0x02 && payload[2] == 0x00 ||
        payload.Length == 2 && payload[0] == 0x06 && payload[1] == 0x00 ||
        payload.Length == 1 && payload[0] is 0x03 or 0x07 or 0x0A;
}
