using System.Text;

namespace CamaroDiag.J2534Bridge;

internal static class NativeGmlanIdentificationSelfTests
{
    internal static int Run()
    {
        var checks = 0;
        void Assert(bool condition, string why)
        {
            checks++;
            if (!condition) throw new InvalidOperationException(
                "Native GMLAN identity test failed: " + why);
        }
        static void Reject<T>(Action fn) where T : Exception
        {
            try { fn(); }
            catch (T) { return; }
            throw new InvalidOperationException("Expected " + typeof(T).Name);
        }

        byte[] vinReply = [0x5A, 0x90, .. Encoding.ASCII.GetBytes("1G1FA1RXXR0123456")];
        byte[] osReply = [0x5A, 0xC1, 0x00, 0xC1, 0x3D, 0x3D];
        byte[] unused = [0x7F, 0x1A, 0x31];
        var expectedOsId = NativeGmlanIdentification.DecodeOsId([0x00, 0xC1, 0x3D, 0x3D]);
        var id = NativeGmlanIdentification.Compose(vinReply, osReply, unused, physicalRead: false);
        Assert(id.Vin == "1G1FA1RXXR0123456" && id.OperatingSystemId == expectedOsId &&
               id.OsDid == 0xC1 && !id.PhysicalRead, "Correct GMLAN VIN and OSID");
        Assert(!id.FlashHardwareQualified, "Identity alone never certifies flash hardware");
        Assert(NativeGmlanIdentification.BuildRequest(0x90).SequenceEqual(
            new byte[] { 0x1A, 0x90 }), "Read-only GM VIN DID");
        Assert(NativeGmlanIdentification.BuildRequest(0xC1).SequenceEqual(
            new byte[] { 0x1A, 0xC1 }), "Read-only GM module-1 ID");
        Assert(NativeGmlanIdentification.BuildRequest(0xC9).SequenceEqual(
            new byte[] { 0x1A, 0xC9 }), "Read-only GM fallback OS ID");
        Reject<UnauthorizedAccessException>(() =>
            NativeGmlanIdentification.BuildRequest(0x90 - 1));
        checks++;

        var fallback = NativeGmlanIdentification.Compose(vinReply, unused,
            [0x5A, 0xC9, 0x00, 0xC1, 0x3D, 0x3D], false);
        Assert(fallback.OsDid == 0xC9 &&
               fallback.OperatingSystemId == expectedOsId, "Empty SWMI slot falls back to C9");
        var empty = NativeGmlanIdentification.Compose(vinReply,
            [0x5A, 0xC1, 0, 0, 0, 0],
            [0x5A, 0xC9, 0, 0xC1, 0x3D, 0x3D], false);
        Assert(empty.OsDid == 0xC9, "Zero SWMI slot does not mask valid fallback");

        Reject<NativeGmlanIdentification.IdentityUnavailableException>(() =>
            NativeGmlanIdentification.Compose(vinReply, unused, unused, false));
        checks++;
        Reject<NativeGmlanIdentification.IdentityUnavailableException>(() =>
            NativeGmlanIdentification.Compose(unused, osReply, unused, false));
        checks++;
        Reject<IOException>(() =>
            NativeGmlanIdentification.Compose(vinReply,
                [0x7F, 0x1A, 0x33], unused, false));
        checks++;
        bool accessDeniedIsNotOptional = false;
        try
        {
            NativeGmlanIdentification.Compose(vinReply,
                [0x7F, 0x1A, 0x33], unused, false);
        }
        catch (NativeGmlanIdentification.IdentityUnavailableException) { }
        catch (IOException) { accessDeniedIsNotOptional = true; }
        Assert(accessDeniedIsNotOptional,
            "A denied GMLAN OSID read must not be treated as unsupported identity");

        bool malformedOsIdIsNotOptional = false;
        try
        {
            NativeGmlanIdentification.Compose(vinReply,
                [0x5A, 0xC1, 0x00, 0x01], unused, false);
        }
        catch (NativeGmlanIdentification.IdentityUnavailableException) { }
        catch (InvalidDataException) { malformedOsIdIsNotOptional = true; }
        Assert(malformedOsIdIsNotOptional,
            "A truncated GM OSID reply must fail, not downgrade to optional absence");
        Reject<InvalidDataException>(() =>
            NativeGmlanIdentification.Compose([0x5A, 0x90, 0x00], osReply, unused, false));
        checks++;
        var malformedWasNotUnsupported = false;
        try
        {
            NativeGmlanIdentification.Compose(
                [0x5A, 0x90, 0x00], osReply, unused, false);
        }
        catch (NativeGmlanIdentification.IdentityUnavailableException)
        {
            malformedWasNotUnsupported = false;
        }
        catch (InvalidDataException)
        {
            malformedWasNotUnsupported = true;
        }
        Assert(malformedWasNotUnsupported,
            "Malformed GMLAN VIN is not classified as an unsupported legacy identity");
        Reject<InvalidDataException>(() =>
            NativeGmlanIdentification.Compose(
                [0x5A, 0x90, .. Encoding.ASCII.GetBytes("1G1FA1RXXR012345I")],
                osReply, unused, false));
        checks++;
        Reject<InvalidDataException>(() =>
            NativeGmlanIdentification.DecodeResponse(0x90, [0x5A, 0xC1, 0x01]));
        checks++;

        Console.WriteLine($"PASS: {checks} native read-only GMLAN identity self-tests.");
        return checks;
    }
}
