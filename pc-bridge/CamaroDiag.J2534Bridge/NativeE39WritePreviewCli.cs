using System.Globalization;

namespace CamaroDiag.J2534Bridge;

/// <summary>Offline sector impact preview; no adapter, security access or flash.</summary>
internal static class NativeE39WritePreviewCli
{
    internal const string Command = "--preview-e39-write";

    internal static int Run(string[] args)
    {
        if (args.Length is not (3 or 5) ||
            !args[0].Equals(Command, StringComparison.OrdinalIgnoreCase))
        {
            Console.Error.WriteLine("Usage: CamaroDiag.J2534Bridge.exe " + Command +
                " <original.bin> <modified.bin> [<proposed-erase-sector-bytes> <proposed-program-unit-bytes>]");
            return 10;
        }

        try
        {
            var original = ReadImage(args[1]);
            var modified = ReadImage(args[2]);
            var osId = NativeE39ImageInspector.Inspect(original).OsIdentifier ??
                throw new InvalidDataException("Original firmware identity unavailable.");
            var layout = NativeMpc5566FlashMap.E39PreviewLayout();
            if (args.Length == 5)
            {
                if (!int.TryParse(args[3], NumberStyles.None, CultureInfo.InvariantCulture, out var eraseBytes) ||
                    !int.TryParse(args[4], NumberStyles.None, CultureInfo.InvariantCulture, out var unitBytes))
                    throw new ArgumentException("Invalid proposed erase/program unit geometry.");
                layout = new NativeEcuSectorPlanner.Layout(0, original.Length,
                    eraseBytes, unitBytes, [new(0, NativeE39ImageInspector.ProtectedBootBytes)], false);
            }
            else Console.WriteLine("Reference geometry: MPC5566 silicon, not a qualified ECU.");

            var report = NativeE39ImagePreflight.Preview(original, modified, layout,
                osId, calibrationOnly: true);
            Console.WriteLine("E39/E39A offline calibration sector preview (NOT qualified for flash).");
            Console.WriteLine($"OS ID: {report.OperatingSystemId}; changed bytes: {report.ChangedBytes}");
            Console.WriteLine("Original SHA-256: " + report.OriginalSha256);
            Console.WriteLine("Target SHA-256: " + report.ModifiedSha256);
            foreach (var sector in report.SectorPlan.Sectors)
            {
                Console.WriteLine($"0x{sector.Address:X6}: {sector.Length} bytes");
                Console.WriteLine("  Original: " + sector.OriginalSha256);
                Console.WriteLine("  Target:   " + sector.UpdatedSha256);
            }
            Console.WriteLine("Proposed sector geometry is not a verified ECU memory map.");
            return 0;
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException or
            ArgumentException or InvalidOperationException or OverflowException or NotSupportedException)
        {
            Console.Error.WriteLine("E39 preview refused: " + error.Message);
            return 10;
        }
    }

    private static byte[] ReadImage(string path)
    {
        if (new FileInfo(path).Length != NativeE39ImageInspector.ImageBytes)
            throw new InvalidDataException("Each image must be exactly 3 MiB.");
        return File.ReadAllBytes(path);
    }
}
