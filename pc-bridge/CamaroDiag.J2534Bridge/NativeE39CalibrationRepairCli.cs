namespace CamaroDiag.J2534Bridge;

/// <summary>
/// CLI for offline E39/E39A calibration checksum repair. No adapter,
/// bridge service, third-party executable, or network connection is used.
/// </summary>
internal static class NativeE39CalibrationRepairCli
{
    internal const string Command = "--repair-e39-cal-checksums";

    internal static int Run(string[] args)
    {
        if (args.Length != 4 ||
            !args[0].Equals(Command, StringComparison.OrdinalIgnoreCase))
        {
            Console.Error.WriteLine(
                "Usage: CamaroDiag.J2534Bridge.exe --repair-e39-cal-checksums " +
                "<original-3MiB.bin> <modified-3MiB.bin> <NEW-output.bin>");
            return 10;
        }

        var outputCreated = false;
        string? outputPath = null;
        try
        {
            var original = Path.GetFullPath(args[1]);
            var modified = Path.GetFullPath(args[2]);
            outputPath = Path.GetFullPath(args[3]);

            if (string.Equals(original, modified, StringComparison.OrdinalIgnoreCase) ||
                string.Equals(original, outputPath, StringComparison.OrdinalIgnoreCase) ||
                string.Equals(modified, outputPath, StringComparison.OrdinalIgnoreCase))
                throw new ArgumentException(
                    "Original, edited and output must be three different files.");

            if (new FileInfo(original).Length != NativeE39ImageInspector.ImageBytes ||
                new FileInfo(modified).Length != NativeE39ImageInspector.ImageBytes)
                throw new InvalidDataException("Both input files must be exactly 0x300000 bytes.");

            var result = NativeE39CalibrationRepair.Repair(
                File.ReadAllBytes(original), File.ReadAllBytes(modified));

            // CreateNew prevents silently overwriting an earlier ECU backup,
            // previous output or another project's file.
            using (var stream = new FileStream(outputPath,
                FileMode.CreateNew, FileAccess.Write, FileShare.None,
                64 * 1024, FileOptions.WriteThrough))
            {
                outputCreated = true;
                stream.Write(result.RepairedImage);
                stream.Flush(flushToDisk: true);
            }

            // Independently reopen and verify the exact bytes written to disk.
            var diskBytes = File.ReadAllBytes(outputPath);
            if (!diskBytes.SequenceEqual(result.RepairedImage) ||
                NativeE39ImageInspector.Inspect(diskBytes).Status !=
                    NativeE39ImageInspector.Verdict.Valid)
                throw new InvalidDataException("Output checksum or disk readback mismatch.");

            Console.WriteLine("E39/E39A calibration checksum repair: SUCCESS (OFFLINE ONLY)");
            Console.WriteLine($"OS ID: {result.OperatingSystemId}");
            Console.WriteLine($"Changed calibration bytes in input: {result.EditedBytesChanged}");
            Console.WriteLine($"SUM/CVN fields updated: {result.ChecksumFieldsUpdated}");
            foreach (var segment in result.ChangedSegments)
                Console.WriteLine($"  {segment.Name}: {segment.BytesChanged} edited bytes");
            Console.WriteLine($"Original SHA-256: {result.OriginalSha256}");
            Console.WriteLine($"Edited SHA-256:   {result.EditedSha256}");
            Console.WriteLine($"Repaired SHA-256: {result.RepairedSha256}");
            Console.WriteLine($"Saved: {outputPath}");
            Console.WriteLine(
                "No ECU was accessed. This file is not approved for flash until " +
                "VIN/OSID, memory map, power supply and recovery are validated on bench.");
            return 0;
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException or
            InvalidDataException or ArgumentException or NotSupportedException)
        {
            // Remove only a file this attempt actually created. Never delete
            // an existing target, even when CreateNew reports an I/O error.
            if (outputCreated && outputPath is not null)
            {
                try { File.Delete(outputPath); }
                catch (IOException) { /* Preserve original exception. */ }
                catch (UnauthorizedAccessException) { /* Preserve original exception. */ }
            }
            Console.Error.WriteLine("E39 calibration repair blocked: " + error.Message);
            return 10;
        }
    }
}
