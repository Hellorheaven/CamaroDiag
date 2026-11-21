namespace CamaroDiag.J2534Bridge;

internal static class NativeMpc5566FlashMapSelfTests
{
    internal static int Run()
    {
        int count = 0;
        void Assert(bool condition, string why)
        {
            count++;
            if (!condition) throw new InvalidOperationException("MPC5566 map: " + why);
        }
        void Reject<T>(Action work) where T : Exception
        {
            try { work(); }
            catch (T) { count++; return; }
            throw new InvalidOperationException("Expected " + typeof(T).Name);
        }
        var layout = NativeMpc5566FlashMap.E39PreviewLayout();
        var blocks = NativeMpc5566FlashMap.Blocks();
        Assert(blocks.Count == 28 && blocks[0].Start == 0 &&
            blocks[^1].EndExclusive == 0x300000, "28 contiguous blocks in 3 MiB");
        Assert(blocks.Take(6).Select(x => x.EndExclusive - x.Start)
            .SequenceEqual(new uint[] { 0x4000, 0xC000, 0xC000, 0x4000, 0x10000, 0x10000 }),
            "Low-address blocks have variable physical size");
        Assert(blocks.Skip(6).All(x => x.EndExclusive - x.Start == 0x20000),
            "Middle/high blocks are 128 KiB");
        var original = new byte[NativeMpc5566FlashMap.MainFlashBytes];
        var modified = original.ToArray();
        modified[0x2FFFF] = 1;
        modified[0x30000] = 2;
        modified[0x40000] = 3;
        modified[0x2FFFFF] = 4;
        var plan = NativeEcuSectorPlanner.Preview(layout, original, modified);
        Assert(plan.ChangedBytes == 4 && plan.Sectors.Count == 4 &&
            plan.Sectors.Select(x => x.Length).SequenceEqual(new[] { 0x10000, 0x10000, 0x20000, 0x20000 }),
            "Edits crossing block boundaries expand to exact full erase blocks");
        Assert(!plan.CanAttemptPhysicalWrite, "Silicon map is not ECU hardware approval");
        modified[0x1FFFF] = 5;
        Reject<UnauthorizedAccessException>(() => NativeEcuSectorPlanner.Preview(layout, original, modified));
        modified[0x1FFFF] = 0;
        Reject<ArgumentException>(() => NativeEcuSectorPlanner.Preview(
            layout with { PhysicalEraseBlocks = blocks.Skip(1).ToArray() }, original, modified));
        Reject<ArgumentException>(() => NativeEcuSectorPlanner.Preview(
            layout with { PhysicalEraseBlocks = blocks.Reverse().ToArray() }, original, modified));
        Reject<ArgumentException>(() => NativeEcuSectorPlanner.Preview(
            layout with { EraseSectorBytes = 0x10000 }, original, modified));
        Console.WriteLine($"PASS: {count} native MPC5566 block-map tests.");
        return count;
    }
}
