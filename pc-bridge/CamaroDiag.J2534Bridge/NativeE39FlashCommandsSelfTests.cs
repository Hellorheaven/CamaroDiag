namespace CamaroDiag.J2534Bridge;

internal static class NativeE39FlashCommandsSelfTests
{
    internal static int Run()
    {
        var assertions = 0;
        void Assert(bool condition, string description)
        {
            assertions++;
            if (!condition)
                throw new InvalidOperationException("Native E39 flash framing failed: " + description);
        }
        static void ExpectFailure<T>(Action operation) where T : Exception
        {
            try { operation(); }
            catch (T) { return; }
            throw new InvalidOperationException("Expected " + typeof(T).Name);
        }

        Assert(Convert.ToHexString(
            NativeE39FlashCommands.BuildSectorErase(0x20000, 0x10000)) ==
            "3D05020000", "Native GMLAN sector erase address");
        ExpectFailure<ArgumentOutOfRangeException>(() =>
            NativeE39FlashCommands.BuildSectorErase(0x10000, 0x10000));
        assertions++;
        ExpectFailure<ArgumentException>(() =>
            NativeE39FlashCommands.BuildSectorErase(0x21000, 0x10000));
        assertions++;

        NativeE39FlashCommands.RequireEraseSuccess([0x7D, 0x05, 0x00]);
        assertions++;
        ExpectFailure<InvalidDataException>(() =>
            NativeE39FlashCommands.RequireEraseSuccess([0x7D, 0x05, 0x01]));
        assertions++;

        var block = NativeE39FlashCommands.BuildWriteBlock(
            0x123456, [1, 2, 3, 4], dryRun: false);
        Assert(Convert.ToHexString(block) == "3600000412345601020304000A",
            "Native write block framing with 16-bit additive byte checksum");
        var dryRun = NativeE39FlashCommands.BuildWriteBlock(
            0x123456, [1, 2, 3, 4], dryRun: true);
        Assert(dryRun[1] == 0x44 && block[1] == 0x00,
            "Preflight write-test command is distinct from physical programming");
        NativeE39FlashCommands.RequireWriteSuccess([0x76]);
        assertions++;
        ExpectFailure<IOException>(() =>
            NativeE39FlashCommands.RequireWriteSuccess([0x7F, 0x36, 0x72]));
        assertions++;

        Assert(Convert.ToHexString(NativeE39FlashCommands.BuildFlashCrcQuery(
            0x20000, 0x1000)) == "3D02001000020000",
            "Native 24-bit length followed by 24-bit flash address");
        Assert(NativeE39FlashCommands.ParseFlashCrcReply(
            [0x7D, 0x02, 0x12, 0x34, 0xAB, 0xCD]) == 0x1234ABCD,
            "Correctly parse physical ECU CRC32 reply");

        var vector = System.Text.Encoding.ASCII.GetBytes("123456789");
        Assert(NativeE39FlashCommands.ComputeCrc32(vector) == 0x89A1897F,
            "Non-reflected GM CRC32 matches independent known vector");

        var maxLength = NativeE39FlashCommands.MaximumWriteDataBytes;
        var maxBlock = NativeE39FlashCommands.BuildWriteBlock(
            0x20000, new byte[maxLength], dryRun: true);
        Assert(maxBlock.Length == NativeE39FlashCommands.MaximumJ2534ApplicationBytes,
            "Maximum write block fits J2534 application payload");
        ExpectFailure<ArgumentOutOfRangeException>(() =>
            NativeE39FlashCommands.BuildWriteBlock(
                0x20000, new byte[maxLength + 1], dryRun: false));
        assertions++;
        ExpectFailure<ArgumentOutOfRangeException>(() =>
            NativeE39FlashCommands.BuildWriteBlock(
                0x1FFFF, [0xAA], dryRun: false));
        assertions++;
        ExpectFailure<ArgumentOutOfRangeException>(() =>
            NativeE39FlashCommands.BuildWriteBlock(
                NativeE39ImageInspector.ImageBytes - 1, [0x01, 0x02], dryRun: false));
        assertions++;

        Console.WriteLine($"PASS: {assertions} native E39 erase/write/CRC wire self-tests.");
        return assertions;
    }
}
