namespace CamaroDiag.J2534Bridge;

/// <summary>
/// Last-mile validation of the ON-WIRE E39 RAM-kernel packet against the exact
/// intended flash sector. Never infer a target from just a service ID.
/// A packet's embedded address, length, bytes and 16-bit checksum must all
/// match the verified target image slice before ExchangeAsync is called.
/// </summary>
internal static class NativeE39WriteBlockVerifier
{
    internal static int RequireExactFrame(
        NativeEcuSectorPlanner.Sector sector, byte[] frame, int sectorOffset, bool dryRun)
    {
        ArgumentNullException.ThrowIfNull(sector);
        ArgumentNullException.ThrowIfNull(frame);
        if (sectorOffset < 0 || sectorOffset >= sector.Length ||
            sector.Length != sector.Updated.Length ||
            frame.Length < NativeE39FlashCommands.FlashWriteHeaderBytes +
                NativeE39FlashCommands.FlashWriteChecksumBytes + 1 ||
            frame[0] != 0x36 || frame[1] != (dryRun ? 0x44 : 0x00))
            throw new InvalidDataException("Outgoing ECU write does not match the planned block protocol.");
        var length = frame[2] << 8 | frame[3];
        if (length == 0 || length > NativeE39FlashCommands.MaximumWriteDataBytes ||
            frame.Length != NativeE39FlashCommands.FlashWriteHeaderBytes + length +
                NativeE39FlashCommands.FlashWriteChecksumBytes ||
            length > sector.Length - sectorOffset)
            throw new InvalidDataException("Outgoing ECU block length does not match the target sector.");
        var address = (uint)(frame[4] << 16 | frame[5] << 8 | frame[6]);
        if ((ulong)sector.Address + (uint)sectorOffset != address)
            throw new InvalidDataException("Outgoing ECU block address differs from the intended target.");
        if (!frame.AsSpan(NativeE39FlashCommands.FlashWriteHeaderBytes, length)
                .SequenceEqual(sector.Updated.AsSpan(sectorOffset, length)))
            throw new InvalidDataException("Outgoing ECU block bytes differ from the requested changes.");
        uint sum = 0;
        foreach (var value in frame.AsSpan(NativeE39FlashCommands.FlashWriteHeaderBytes, length))
            sum += value;
        if (frame[^2] != (byte)(sum >> 8) || frame[^1] != (byte)sum)
            throw new InvalidDataException("Outgoing ECU packet checksum differs from the target bytes.");
        return length;
    }

    internal static void RequireCompleteBatch(
        NativeEcuSectorPlanner.Sector sector, IReadOnlyList<byte[]> frames, bool dryRun)
    {
        var offset = 0;
        foreach (var frame in frames)
            offset = checked(offset + RequireExactFrame(sector, frame, offset, dryRun));
        if (offset != sector.Length)
            throw new InvalidDataException("ECU write packets do not cover the intended sector exactly once.");
    }
}
