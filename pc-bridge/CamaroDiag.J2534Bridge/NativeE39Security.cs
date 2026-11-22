namespace CamaroDiag.J2534Bridge;

/// <summary>
/// Native, family-limited 16-bit GM E39/E39A security-key transform.
///
/// Derivation was verified against publicly described E39 GMLAN algorithm
/// 0xDC. No general-purpose seed/key brute force, opcode tables or
/// third-party programming libraries are imported.
///
/// Security access is stateful and depends on the firmware and live ECU.
/// This pure transform alone never authorizes flash programming.
/// </summary>
internal static class NativeE39Security
{
    internal const int E39AlgorithmIdentifier = 0xDC;

    internal static byte[] SeedRequest() => [0x27, 0x01];

    internal static ushort ParseSeedResponse(byte[] bytes)
    {
        ArgumentNullException.ThrowIfNull(bytes);
        if (bytes.Length == 3 && bytes[0] == 0x7F && bytes[1] == 0x27)
            throw new IOException($"GM SecurityAccess refused with NRC 0x{bytes[2]:X2}.");
        if (bytes.Length != 4 || bytes[0] != 0x67 || bytes[1] != 0x01)
            throw new InvalidDataException(
                "Expected two-byte E39 SecurityAccess seed; do not apply 16-bit algorithm to another family.");
        return (ushort)((bytes[2] << 8) | bytes[3]);
    }

    internal static bool IsAlreadyGranted(ushort liveSeed) => liveSeed == 0;

    internal static ushort DeriveKey(ushort liveSeed)
    {
        // The 0xDC profile is a permutation of 16-bit values. Express the
        // individual operations directly rather than importing an opcode
        // interpreter or an entire GM key table.
        if (liveSeed is 0 or ushort.MaxValue)
            throw new InvalidDataException(
                "Zero seed means already unlocked; FFFF may indicate corrupt security state.");

        ushort value = unchecked((ushort)(liveSeed - 0x93B0));
        value = (ushort)((value << 8) | (value >> 8));
        value = unchecked((ushort)(value + 0xDD26));
        value = (ushort)((value >> 7) | (value << 9));
        return unchecked((ushort)(value + 0x7454));
    }

    internal static byte[] BuildKeyRequest(ushort seed)
    {
        var key = DeriveKey(seed);
        return [0x27, 0x02, (byte)(key >> 8), (byte)key];
    }

    internal static void RequireUnlockAck(byte[] reply)
    {
        ArgumentNullException.ThrowIfNull(reply);
        if (reply.Length == 3 && reply[0] == 0x7F && reply[1] == 0x27)
            throw new IOException($"ECU denied seed/key operation: NRC 0x{reply[2]:X2}.");
        if (reply.Length < 2 || reply[0] != 0x67 || reply[1] != 0x02)
            throw new InvalidDataException("E39 ECU did not acknowledge physical security access.");
    }
}
