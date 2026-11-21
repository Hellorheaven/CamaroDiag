namespace CamaroDiag.J2534Bridge;

internal static class NativeEcuSectorSelfTests
{
    internal static int Run()
    {
        var checks = 0;
        void Assert(bool condition, string name)
        {
            checks++;
            if (!condition) throw new InvalidOperationException("Native ECU sector test failed: " + name);
        }
        static void Expect<T>(Action action) where T : Exception
        {
            try { action(); }
            catch (T) { return; }
            throw new InvalidOperationException("Expected " + typeof(T).Name);
        }

        var original = Enumerable.Range(0, 32).Select(i => (byte)i).ToArray();
        var modified = original.ToArray();
        modified[19] = 0xF0;
        modified[28] = 0xE0;
        var layout = new NativeEcuSectorPlanner.Layout(
            0x80001000, 32, 8, 2,
            [new(0x80001000, 0x80001010)], false);
        var preview = NativeEcuSectorPlanner.Preview(layout, original, modified);
        Assert(!preview.CanAttemptPhysicalWrite, "Unknown physical flash map never authorizes write");
        Assert(preview.Sectors.Count == 2 && preview.ChangedBytes == 2,
            "Only affected erase sectors are planned");
        Assert(preview.Sectors[0].Address == 0x80001010 &&
               preview.Sectors[1].Address == 0x80001018,
            "Absolute ECU flash offsets preserved");
        Assert(preview.Sectors[0].Original.SequenceEqual(original.Skip(16).Take(8)) &&
               preview.Sectors[0].Updated.SequenceEqual(modified.Skip(16).Take(8)),
            "Neighbors in an erase sector are preserved");
        Assert(preview.Sectors.All(sector =>
            sector.OriginalSha256.Length == 64 && sector.UpdatedSha256.Length == 64),
            "Per-sector original and updated SHA-256 evidence");

        original[16] = 0xFF;
        modified[19] = 0x11;
        Assert(preview.Sectors[0].Original[0] == 16 &&
               preview.Sectors[0].Updated[3] == 0xF0,
            "Caller mutation cannot alter frozen sector images");

        Expect<UnauthorizedAccessException>(() =>
        {
            var forbidden = original.ToArray();
            forbidden[1] ^= 1;
            NativeEcuSectorPlanner.Preview(layout, original, forbidden);
        });
        checks++;
        Expect<UnauthorizedAccessException>(() =>
        {
            var partialSectorProtection = layout with {
                ProtectedRanges = [new(0x80001011, 0x80001013)]
            };
            NativeEcuSectorPlanner.Preview(partialSectorProtection, original, modified);
        });
        checks++;
        Expect<ArgumentException>(() =>
            NativeEcuSectorPlanner.Preview(layout with { EraseSectorBytes = 6 }, original, modified));
        checks++;
        Expect<ArgumentException>(() =>
            NativeEcuSectorPlanner.Preview(layout with {
                ProtectedRanges = [new(0x80001000, 0x80001010), new(0x80001008, 0x80001020)]
            }, original, modified));
        checks++;
        Assert(!NativeEcuSectorPlanner.Preview(layout, original, original.ToArray())
            .CanAttemptPhysicalWrite, "No-change image does not authorize erase");

        var approved = NativeEcuSectorPlanner.Preview(
            layout with { VerifiedForExactFirmware = true }, original, modified);
        Assert(approved.CanAttemptPhysicalWrite,
            "Exact-firmware map approval is preserved for later independent safety checks");
        Console.WriteLine($"PASS: {checks} native flash sector planning self-tests.");
        return checks;
    }
}
