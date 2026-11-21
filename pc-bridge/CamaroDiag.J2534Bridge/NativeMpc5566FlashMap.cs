namespace CamaroDiag.J2534Bridge;

/// <summary>
/// MPC5566 main flash silicon geometry, from NXP MPC5566RM rev.2,
/// table 13-4 and AN3903 table 2. This is not an ECU bench qualification.
/// E39/E39A boards must still be independently matched to this MCU.
/// </summary>
internal static class NativeMpc5566FlashMap
{
    internal const int MainFlashBytes = 0x300000;
    internal const int ProgramUnitBytes = 8;
    internal const int ProtectedE39Prefix = 0x20000;

    internal static IReadOnlyList<NativeEcuSectorPlanner.Range> Blocks()
    {
        var ranges = new List<NativeEcuSectorPlanner.Range>
        {
            new(0x000000, 0x004000),
            new(0x004000, 0x010000),
            new(0x010000, 0x01C000),
            new(0x01C000, 0x020000),
            new(0x020000, 0x030000),
            new(0x030000, 0x040000)
        };
        for (uint address = 0x040000; address < MainFlashBytes; address += 0x020000)
            ranges.Add(new(address, address + 0x020000));
        return ranges.AsReadOnly();
    }

    // Returns an offline proposal only, never a hardware-authorized writer.
    internal static NativeEcuSectorPlanner.Layout E39PreviewLayout() =>
        new(0, MainFlashBytes, 0, ProgramUnitBytes,
            [new(0, ProtectedE39Prefix)], false, Blocks());
}
