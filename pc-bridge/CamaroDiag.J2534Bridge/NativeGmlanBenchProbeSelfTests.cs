namespace CamaroDiag.J2534Bridge;

internal static class NativeGmlanBenchProbeSelfTests
{
    internal static int Run()
    {
        var checks = 0;
        void Assert(bool condition, string reason)
        {
            checks++;
            if (!condition)
                throw new InvalidOperationException("GMLAN local bench probe self-test failed: " + reason);
        }
        static void Expect<T>(Action call) where T : Exception
        {
            try { call(); }
            catch (T) { return; }
            throw new InvalidOperationException("Expected " + typeof(T).Name);
        }

        var args = new[]
        {
            NativeGmlanBenchProbe.Command, "--adapter-index", "0",
            "--request-can-id", "7E0", "--response-can-id", "7E8",
            "--protocol", "33"
        };
        var opts = NativeGmlanBenchProbe.Parse(args);
        Assert(opts.AdapterIndex == 0 && opts.RequestCanId == 0x7E0 &&
               opts.ResponseCanId == 0x7E8 && opts.ProtocolNumber == 33 &&
               !opts.Can29Bit,
               "Explicit high-speed 11-bit GMLAN physical channel parsed");

        var extended = (string[])args.Clone();
        extended[4] = "0x18DA10F1";
        extended[6] = "0x18DAF110";
        extended[8] = "34";
        var ext = NativeGmlanBenchProbe.Parse(extended);
        Assert(ext.RequestCanId == 0x18DA10F1 && ext.Can29Bit,
               "29-bit physical CAN IDs supported only on matching protocol profile");

        var sw = (string[])args.Clone();
        sw[8] = "63";
        Assert(NativeGmlanBenchProbe.Parse(sw).ProtocolNumber == 63,
               "SW-CAN profile preserved for adapter compatibility");

        var tooBig = (string[])args.Clone();
        tooBig[4] = "800";
        Expect<ArgumentException>(() => NativeGmlanBenchProbe.Parse(tooBig));
        checks++;
        var same = (string[])args.Clone();
        same[6] = same[4];
        Expect<ArgumentException>(() => NativeGmlanBenchProbe.Parse(same));
        checks++;
        var invalidHex = (string[])args.Clone();
        invalidHex[4] = "7EZ";
        Expect<ArgumentException>(() => NativeGmlanBenchProbe.Parse(invalidHex));
        checks++;
        var duplicateFlag = (string[])args.Clone();
        duplicateFlag[7] = "--request-can-id";
        Expect<ArgumentException>(() => NativeGmlanBenchProbe.Parse(duplicateFlag));
        checks++;
        var badAdapter = (string[])args.Clone();
        badAdapter[2] = "-1";
        Expect<ArgumentException>(() => NativeGmlanBenchProbe.Parse(badAdapter));
        checks++;
        var unknownProtocol = (string[])args.Clone();
        unknownProtocol[8] = "6";
        Expect<ArgumentException>(() => NativeGmlanBenchProbe.Parse(unknownProtocol));
        checks++;
        Expect<ArgumentException>(() =>
            NativeGmlanBenchProbe.Parse([NativeGmlanBenchProbe.Command]));
        checks++;

        var identity = new NativeGmlanIdentification.Identity(
            "1G1FA1RXXR0123456", 12664221u, 0xC1, true, "ECU live identity");
        Assert(ReferenceEquals(
                   identity, NativeGmlanBenchProbe.RequireStableIdentity(identity,
                       identity with { Status = "read twice" })),
            "Independent matching VIN, OSID and software DID identify the same ECU");

        Expect<InvalidDataException>(() =>
            NativeGmlanBenchProbe.RequireStableIdentity(
                identity, identity with { OperatingSystemId = 12664222u }));
        checks++;
        Expect<InvalidDataException>(() =>
            NativeGmlanBenchProbe.RequireStableIdentity(
                identity, identity with { Vin = "1G1FA1RXXR0123457" }));
        checks++;
        Expect<InvalidDataException>(() =>
            NativeGmlanBenchProbe.RequireStableIdentity(
                identity, identity with { OsDid = 0xC9 }));
        checks++;
        Expect<InvalidDataException>(() =>
            NativeGmlanBenchProbe.RequireStableIdentity(
                identity, identity with { PhysicalRead = false }));
        checks++;
        Expect<InvalidDataException>(() =>
            NativeGmlanBenchProbe.RequireStableIdentity(
                identity with { PhysicalRead = false }, identity));
        checks++;

        Console.WriteLine($"PASS: {checks} read-only local GMLAN J2534 probe self-tests.");
        return checks;
    }
}
