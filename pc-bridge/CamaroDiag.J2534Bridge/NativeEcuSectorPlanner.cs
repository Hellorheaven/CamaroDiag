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
        bool VerifiedForExactFirmware,
        IReadOnlyList<Range>? PhysicalEraseBlocks = null);

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
        if (layout.ProgramUnitBytes <= 0)
            throw new ArgumentException("Invalid flash programming unit.");
        if (layout.PhysicalEraseBlocks is null &&
            (layout.EraseSectorBytes <= 0 ||
             layout.EraseSectorBytes % layout.ProgramUnitBytes != 0 ||
             layout.RegionSize % layout.EraseSectorBytes != 0 ||
             layout.RegionStartAddress % layout.EraseSectorBytes != 0))
            throw new ArgumentException("ECU erase and write units must match the verified layout.");
        if (layout.PhysicalEraseBlocks is not null && layout.EraseSectorBytes != 0)
            throw new ArgumentException("Choose an explicit block map or uniform geometry, not both.");
        if ((ulong)layout.RegionStartAddress + (uint)layout.RegionSize > uint.MaxValue)
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

        var physicalBlocks = layout.PhysicalEraseBlocks?.ToArray() ??
            Enumerable.Range(0, layout.RegionSize / layout.EraseSectorBytes)
                .Select(i => new Range(
                    checked(layout.RegionStartAddress + (uint)(i * layout.EraseSectorBytes)),
                    checked(layout.RegionStartAddress + (uint)((i + 1) * layout.EraseSectorBytes))))
                .ToArray();
        // Explicit physical blocks must cover the entire input exactly once.
        ulong next = layout.RegionStartAddress;
        foreach (var block in physicalBlocks)
        {
            if (block.Start != next || block.EndExclusive <= block.Start ||
                (block.EndExclusive - block.Start) % layout.ProgramUnitBytes != 0 ||
                block.Start % layout.ProgramUnitBytes != 0 ||
                block.EndExclusive > (ulong)layout.RegionStartAddress + (uint)layout.RegionSize)
                throw new ArgumentException("Invalid or noncontiguous physical flash block map.");
            next = block.EndExclusive;
        }
        if (next != (ulong)layout.RegionStartAddress + (uint)layout.RegionSize)
            throw new ArgumentException("Physical block map does not cover the entire flash region.");

        var sectors = new List<Sector>();
        var changed = 0;
        foreach (var block in physicalBlocks)
        {
            var offset = checked((int)(block.Start - layout.RegionStartAddress));
            var blockLength = checked((int)(block.EndExclusive - block.Start));
            var delta = 0;
            for (var i = offset; i < offset + blockLength; i++)
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
            var sectorStart = block.Start;
            var sectorEnd = (ulong)block.EndExclusive;
            if (layout.ProtectedRanges.Any(range =>
                sectorStart < range.EndExclusive && sectorEnd > range.Start))
                throw new UnauthorizedAccessException(
                    "Erasing this sector would also erase protected ECU memory.");
            var baseline = original.AsSpan(offset, blockLength).ToArray();
            var patched = updated.AsSpan(offset, blockLength).ToArray();
            sectors.Add(new Sector(
                sectorStart, baseline, patched,
                Convert.ToHexString(SHA256.HashData(baseline)),
                Convert.ToHexString(SHA256.HashData(patched))));
            changed += delta;
        }
        return new Plan(sectors, changed, layout.VerifiedForExactFirmware);
    }
}
