using System.Security.Cryptography;

namespace CamaroDiag.J2534Bridge;

internal static class NativeE39BackupQualificationSelfTests
{
    internal static int Run()
    {
        const string vin = "2G1TEST00C0000001";
        var original = NativeE39ImageInspectorSelfTests.Fixture();
        var hash = Convert.ToHexString(SHA256.HashData(original));
        var scope = new J2534MemoryReadScope(vin, 0x7E0, 0x7E8, 33, 0,
            (uint)original.Length, 256, 1);
        var identity = new NativeGmlanIdentification.Identity(vin, 12664221u, 0xC1, true, "test");
        var capture = new NativeScopedMemoryReader.VerifiedCapture(original.ToArray(), 0,
            vin, hash, original.Length / 256, original.ToArray(), scope);
        var evidence = NativeE39BackupQualification.Compare(scope, original, capture, identity, identity);
        if (evidence.Sha256 != hash || evidence.Bytes != original.Length)
            throw new InvalidOperationException("Valid full image comparison failed.");
        int count = 1;
        void Reject<T>(Action action) where T : Exception
        {
            try { action(); }
            catch (T) { count++; return; }
            throw new InvalidOperationException("Expected " + typeof(T).Name);
        }
        var partial = new J2534MemoryReadScope(vin, 0x7E0, 0x7E8, 33, 0, 256, 256, 1);
        Reject<ArgumentException>(() => NativeE39BackupQualification.Compare(partial, original, capture, identity, identity));
        Reject<UnauthorizedAccessException>(() => NativeE39BackupQualification.Compare(scope, original, capture with { ExpectedVin = "2G1TEST00C0000002" }, identity, identity));
        Reject<UnauthorizedAccessException>(() => NativeE39BackupQualification.Compare(scope, original, capture, identity with { OperatingSystemId = 1 }, identity with { OperatingSystemId = 1 }));
        Reject<InvalidDataException>(() => NativeE39BackupQualification.Compare(scope, original, capture, identity, identity with { PhysicalRead = false }));
        Reject<InvalidDataException>(() => NativeE39BackupQualification.Compare(scope, original, capture with { BlocksPerPass = 1 }, identity, identity));
        Reject<InvalidDataException>(() => NativeE39BackupQualification.Compare(scope, original, capture with { SecondBytes = null }, identity, identity));
        Reject<InvalidDataException>(() => NativeE39BackupQualification.Compare(scope, original, capture with { SecondBytes = capture.Bytes }, identity, identity));
        var corruptSecond = original.ToArray();
        corruptSecond[0] ^= 1;
        Reject<InvalidDataException>(() => NativeE39BackupQualification.Compare(scope, original, capture with { SecondBytes = corruptSecond }, identity, identity));
        var altered = original.ToArray();
        altered[0x20040] ^= 1;
        Reject<InvalidDataException>(() => NativeE39BackupQualification.Compare(scope, original, capture with { Bytes = altered }, identity, identity));
        Reject<InvalidDataException>(() => NativeE39BackupQualification.Compare(scope, original, capture with { Bytes = altered[..^1] }, identity, identity));
        Console.WriteLine($"PASS: {count} native physical backup qualification self-tests (simulated).");
        return count;
    }
}
