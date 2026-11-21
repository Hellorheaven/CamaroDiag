namespace CamaroDiag.J2534Bridge;

internal static class NativeE39SecuritySelfTests
{
    internal static int Run()
    {
        var checks = 0;
        void Assert(bool cond, string label)
        {
            checks++;
            if (!cond) throw new InvalidOperationException("Native E39 security self-test failed: " + label);
        }

        static void Reject<T>(Action a) where T : Exception
        {
            try { a(); }
            catch (T) { return; }
            throw new InvalidOperationException("Expected " + typeof(T).Name);
        }

        Assert(NativeE39Security.E39AlgorithmIdentifier == 0xDC,
            "E39/A key family explicitly selected");
        Assert(NativeE39Security.SeedRequest().SequenceEqual([0x27, 0x01]),
            "Physical GMLAN seed request");
        Assert(NativeE39Security.ParseSeedResponse([0x67, 0x01, 0x12, 0x34]) == 0x1234,
            "ECU seed parsed big endian");
        Assert(NativeE39Security.DeriveKey(0x1234) == 0xBD17 &&
               NativeE39Security.DeriveKey(0xABCD) == 0xF248 &&
               NativeE39Security.DeriveKey(0x9D19) == 0xD2E0,
            "Independent GM 0xDC key vectors");
        Assert(NativeE39Security.BuildKeyRequest(0x1234).SequenceEqual(
            [0x27, 0x02, 0xBD, 0x17]), "Calculated key request uses correct byte order");
        Assert(NativeE39Security.IsAlreadyGranted(0) &&
               !NativeE39Security.IsAlreadyGranted(0x1234),
               "Zero seed is granted access, not a new key request");
        Reject<InvalidDataException>(() => NativeE39Security.DeriveKey(0));
        checks++;
        Reject<InvalidDataException>(() => NativeE39Security.DeriveKey(0xFFFF));
        checks++;
        Reject<IOException>(() =>
            NativeE39Security.ParseSeedResponse([0x7F, 0x27, 0x37]));
        checks++;
        Reject<InvalidDataException>(() =>
            NativeE39Security.ParseSeedResponse([0x67, 0x01, 0, 1, 2, 3, 4]));
        checks++;
        NativeE39Security.RequireUnlockAck([0x67, 0x02]);
        checks++;
        Reject<IOException>(() =>
            NativeE39Security.RequireUnlockAck([0x7F, 0x27, 0x35]));
        checks++;

        Console.WriteLine($"PASS: {checks} native E39 GMLAN security self-tests.");
        return checks;
    }
}
