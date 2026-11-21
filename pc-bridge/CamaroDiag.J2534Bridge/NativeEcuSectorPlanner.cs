using System.Security.Cryptography;

namespace CamaroDiag.J2534Bridge;

/// <summary>
/// Immutable, ECU-family-agnostic image delta planning for the native PC
/// programmer. No third-party programming software or proprietary memory map.
/// A physically verified family/firmware-specific map is mandatory.
/// </summary>
internal static class NativeEcuSectorPlanner
{
    internal readonly record struct Range(uint Start, uint EndExclusive);

    internal sealed record Layout(
        uint RegionStartAddress,
        int RegionSize,
        int EraseSectorBytes,
        int ProgramUnitBytes,
        IReadOnlyList<Range> ProtectedRanges,
        bool VerifiedForExactFirmware);

    internal sealed record Sector(
        uint Address,
        byte[] Original,
        byte[] Updated,
        string OriginalSha256,
        string UpdatedSha256)
    {
        public int Length => Original.Length;
    }

    internal sealed record Plan(
        IReadOnlyList<Sector> Sectors,
        int ChangedBytes,
        bool VerifiedMemoryMap)
    {
        public bool CanAttemptPhysicalWrite => VerifiedMemoryMap && ChangedBytes > 0;
    }

    internal static Plan Preview(Layout layout, byte[] original, byte[] updated)
    {
        ArgumentNullException.ThrowIfNull(layout);
        ArgumentNullException.ThrowIfNull(original);
        ArgumentNullException.ThrowIfNull(updated);
        if (original.Length == 0 || original.Length != updated.Length ||
            original.Length != layout.RegionSize)
            throw new ArgumentException("ECU original, modified and memory region sizes must match.");
        if (layout.EraseSectorBytes <= 0 || layout.ProgramUnitBytes <= 0 ||
            layout.EraseSectorBytes % layout.ProgramUnitBytes != 0 ||
            layout.RegionSize % layout.EraseSectorBytes != 0 ||
            layout.RegionStartAddress % layout.EraseSectorBytes != 0)
            throw new ArgumentException("ECU erase and write units must match the verified layout.");
        if ((ulong)layout.RegionStartAddress + (uint)layout.RegionSize > 0x1_0000_0000UL)
            throw new ArgumentException("ECU flash address range overflows.");
        var lastEnd = 0UL;
        foreach (var protectedRange in layout.ProtectedRanges.OrderBy(range => range.Start))
        {
            if (protectedRange.Start >= protectedRange.EndExclusive ||
                protectedRange.Start < layout.RegionStartAddress ||
                protectedRange.EndExclusive > (ulong)layout.RegionStartAddress + (uint)layout.RegionSize ||
                protectedRange.Start < lastEnd)
                throw new ArgumentException("Invalid, overlapping or out-of-range protected ECU region.");
            lastEnd = protectedRange.EndExclusive;
        }

        var sectors = new List<Sector>();
        var changed = 0;
        for (var offset = 0; offset < original.Length; offset += layout.EraseSectorBytes)
        {
            var delta = 0;
            for (var i = offset; i < offset + layout.EraseSectorBytes; i++)
            {
                if (original[i] == updated[i]) continue;
                var address = (ulong)layout.RegionStartAddress + (uint)i;
                if (layout.ProtectedRanges.Any(range =>
                    address >= range.Start && address < range.EndExclusive))
                    throw new UnauthorizedAccessException(
                        "Modification targets protected ECU bootloader or NVM memory.");
                delta++;
            }
            if (delta == 0) continue;
            var sectorStart = (uint)((ulong)layout.RegionStartAddress + (uint)offset);
            var sectorEnd = (ulong)sectorStart + (uint)layout.EraseSectorBytes;
            if (layout.ProtectedRanges.Any(range =>
                sectorStart < range.EndExclusive && sectorEnd > range.Start))
                throw new UnauthorizedAccessException(
                    "Erasing this sector would also erase protected ECU memory.");
            var baseline = original.AsSpan(offset, layout.EraseSectorBytes).ToArray();
            var patched = updated.AsSpan(offset, layout.EraseSectorBytes).ToArray();
            sectors.Add(new Sector(
                sectorStart, baseline, patched,
                Convert.ToHexString(SHA256.HashData(baseline)),
                Convert.ToHexString(SHA256.HashData(patched))));
            changed += delta;
        }
        return new Plan(sectors, changed, layout.VerifiedForExactFirmware);
    }
}
