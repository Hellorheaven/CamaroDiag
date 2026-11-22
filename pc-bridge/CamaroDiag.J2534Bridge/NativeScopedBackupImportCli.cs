namespace CamaroDiag.J2534Bridge;

/// <summary>Offline ZIP verification only: no J2534 driver or network connection.</summary>
internal static class NativeScopedBackupImportCli
{
    internal const string Command = "--verify-ecu-archive";

    internal static int Run(string[] args)
    {
        if (args.Length != 3 || !args[0].Equals(Command, StringComparison.OrdinalIgnoreCase) ||
            string.IsNullOrWhiteSpace(args[1]) || string.IsNullOrWhiteSpace(args[2]))
        {
            Console.Error.WriteLine("Usage: --verify-ecu-archive <archive.zip> <memory-read-profile.json>");
            return 6;
        }
        try
        {
            var scope = J2534MemoryReadScope.Load(args[2]);
            var checkedCapture = NativeScopedBackupImport.VerifyZip(args[1], scope);
            Console.WriteLine("ECU archive: integrity verified (two independent stored acquisitions).");
            Console.WriteLine("VIN: " + checkedCapture.Vin);
            Console.WriteLine("Start: 0x" + checkedCapture.StartAddress.ToString("X8"));
            Console.WriteLine("Bytes: " + checkedCapture.ByteCount);
            Console.WriteLine("SHA-256: " + checkedCapture.Sha256);
            Console.WriteLine("Declared region: " + checkedCapture.DeclaredRegion);
            Console.WriteLine("Declared origin (not attested): " + checkedCapture.DeclaredOrigin);
            Console.WriteLine("Offline integrity only. No physical restoration or full ECU coverage is asserted.");
            return 0;
        }
        catch (Exception ex) when (ex is IOException or ArgumentException or UnauthorizedAccessException
            or InvalidDataException or System.Text.Json.JsonException or OverflowException
            or NotSupportedException or InvalidOperationException)
        {
            Console.Error.WriteLine("ECU archive rejected: " + ex.Message);
            return 8;
        }
    }
}
