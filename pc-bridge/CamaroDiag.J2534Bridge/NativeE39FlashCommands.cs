namespace CamaroDiag.J2534Bridge;

/// <summary>
/// Native on-wire primitives for a RUNNING GM E39/E39A flash RAM routine.
///
/// These commands are not understood by a stock ECU without a compatible
/// native flash kernel. No kernel binary is shipped and these primitives
/// are not remotely exposed. Technical wire semantics were inspected from
/// published GMLAN behavior and independently implemented in CamaroDiag.
/// </summary>
internal static class NativeE39FlashCommands
{
    internal const int ProtectedBootEndExclusive = 0x20000;
    internal const int FlashEndExclusive = NativeE39ImageInspector.ImageBytes;
    internal const int MaximumJ2534ApplicationBytes = 4091;
    internal const int FlashWriteHeaderBytes = 7;
    internal const int FlashWriteChecksumBytes = 2;
    internal const int MaximumWriteDataBytes =
        MaximumJ2534ApplicationBytes - FlashWriteHeaderBytes - FlashWriteChecksumBytes;

    private static void CheckFlashSpan(uint address, uint size)
    {
        if (size == 0 ||
            address < ProtectedBootEndExclusive ||
            (ulong)address + size > FlashEndExclusive)
            throw new ArgumentOutOfRangeException(nameof(address),
                "E39 write/erase cannot target the boot block or exceed 3MiB flash.");
    }

    internal static byte[] BuildSectorErase(uint startAddress, uint eraseSectorSize)
    {
        CheckFlashSpan(startAddress, eraseSectorSize);
        if (eraseSectorSize == 0 || startAddress % eraseSectorSize != 0)
            throw new ArgumentException("Sector address must align to verified erase geometry.");
        return
        [
            0x3D, 0x05, (byte)(startAddress >> 16),
            (byte)(startAddress >> 8), (byte)startAddress
        ];
    }

    internal static void RequireEraseSuccess(byte[] reply)
    {
        ArgumentNullException.ThrowIfNull(reply);
        RequireNoNegativeResponse(reply);
        if (reply.Length != 3 || reply[0] != 0x7D || reply[1] != 0x05 ||
            reply[2] != 0x00)
            throw new InvalidDataException("E39 flash kernel did not confirm successful erase.");
    }

    internal static byte[] BuildWriteBlock(uint address, byte[] bytes, bool dryRun)
    {
        ArgumentNullException.ThrowIfNull(bytes);
        if (bytes.Length is < 1 or > MaximumWriteDataBytes)
            throw new ArgumentOutOfRangeException(nameof(bytes));
        CheckFlashSpan(address, (uint)bytes.Length);
        var message = new byte[FlashWriteHeaderBytes + bytes.Length + FlashWriteChecksumBytes];
        message[0] = 0x36;
        message[1] = dryRun ? (byte)0x44 : (byte)0x00;
        message[2] = (byte)(bytes.Length >> 8);
        message[3] = (byte)bytes.Length;
        message[4] = (byte)(address >> 16);
        message[5] = (byte)(address >> 8);
        message[6] = (byte)address;
        Buffer.BlockCopy(bytes, 0, message, FlashWriteHeaderBytes, bytes.Length);
        uint sum = 0;
        foreach (var value in bytes) sum += value;
        message[^2] = (byte)(sum >> 8);
        message[^1] = (byte)sum;
        return message;
    }

    internal static void RequireWriteSuccess(byte[] reply)
    {
        ArgumentNullException.ThrowIfNull(reply);
        RequireNoNegativeResponse(reply);
        if (reply.Length == 0 || reply[0] != 0x76)
            throw new InvalidDataException("No E39 flash block write acknowledgement.");
    }

    internal static byte[] BuildFlashCrcQuery(uint address, uint length)
    {
        CheckFlashSpan(address, length);
        // Kernel dialect: 3-byte size FIRST, then 3-byte flash address.
        return
        [
            0x3D, 0x02,
            (byte)(length >> 16), (byte)(length >> 8), (byte)length,
            (byte)(address >> 16), (byte)(address >> 8), (byte)address
        ];
    }

    internal static uint ParseFlashCrcReply(byte[] reply)
    {
        ArgumentNullException.ThrowIfNull(reply);
        RequireNoNegativeResponse(reply);
        if (reply.Length != 6 || reply[0] != 0x7D || reply[1] != 0x02)
            throw new InvalidDataException("E39 kernel CRC response malformed.");
        return ((uint)reply[2] << 24) | ((uint)reply[3] << 16) |
               ((uint)reply[4] << 8) | reply[5];
    }

    internal static uint ComputeCrc32(ReadOnlySpan<byte> data)
    {
        // Native flash-kernel CRC: polynomial 04C11DB7, non-reflected,
        // initial 0 and no final XOR (NOT the common reflected ZIP CRC32).
        uint crc = 0;
        foreach (var value in data)
        {
            crc ^= (uint)value << 24;
            for (var bit = 0; bit < 8; bit++)
                crc = (crc & 0x80000000) != 0 ?
                    (crc << 1) ^ 0x04C11DB7u : crc << 1;
        }
        return crc;
    }

    private static void RequireNoNegativeResponse(byte[] reply)
    {
        if (reply.Length == 3 && reply[0] == 0x7F)
            throw new IOException(
                $"E39 flash kernel rejected service 0x{reply[1]:X2}: NRC 0x{reply[2]:X2}.");
    }
}
