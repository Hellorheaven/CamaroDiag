using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;

namespace CamaroDiag.J2534Bridge;

internal static class Program
{
    // Launching from the Windows Start menu is graphical. Console commands
    // are kept as an explicitly invoked compatibility and CI interface.
    [STAThread]
    private static int Main(string[] args)
    {
        if (OperatingSystem.IsWindows() &&
            (args.Length == 0 ||
             (args.Length == 1 && args[0].Equals("--ui", StringComparison.OrdinalIgnoreCase))))
        {
            BridgeConsoleWindow.HideOnlyStandaloneConsole();
            ApplicationConfiguration.Initialize();
            Application.Run(new BridgeDesktopForm());
            return 0;
        }
        return RunCommandLineAsync(args).GetAwaiter().GetResult();
    }

    private static async Task<int> RunCommandLineAsync(string[] args)
    {
        if (!OperatingSystem.IsWindows())
        {
            Console.Error.WriteLine("CamaroDiag J2534 Bridge requires Windows 10/11.");
            return 2;
        }

        // Offline, no-driver validation for Windows CI. Never starts a bridge listener.
        if (args.Contains("--self-test", StringComparer.OrdinalIgnoreCase))
            return BridgeSelfTests.Run();

        // Driver verification is explicitly requested by the operator. Do not
        // contact an ECU, start the network bridge or run the updater here.
        if (args.Contains("--probe-drivers", StringComparer.OrdinalIgnoreCase))
            return J2534DriverProbe.RunAll();

        // Read-only local hardware test. Requires an explicit J2534 adapter,
        // CAN addressing and transport profile. No TCP bridge or updater.
        if (args.Contains(NativeGmlanBenchProbe.Command, StringComparer.OrdinalIgnoreCase))
            return NativeGmlanBenchProbe.Run(args);

        // Local, read-only physical comparison before any programming qualification.
        if (args.Contains(NativeE39BackupQualification.Command, StringComparer.OrdinalIgnoreCase))
            return await NativeE39BackupQualification.RunAsync(args);

        if (args.Contains(NativeE39WritePreviewCli.Command, StringComparer.OrdinalIgnoreCase))
            return NativeE39WritePreviewCli.Run(args);

        // Offline image check: native, no ECU, no vendor driver or network.
        if (args.Contains("--inspect-e39-image", StringComparer.OrdinalIgnoreCase))
            return InspectE39Image(args);

        // Explicit offline calibration checksum repair. No J2534 adapter,
        // network listener or external programming software is involved.
        if (args.Contains(NativeE39CalibrationRepairCli.Command,
                StringComparer.OrdinalIgnoreCase))
            return NativeE39CalibrationRepairCli.Run(args);


        if (await BridgeUpdateManager.CheckAndOfferUpdateAsync()) return 0;
        var port = ReadPort(args);
        var allowObdClear = args.Contains("--allow-obd-clear", StringComparer.OrdinalIgnoreCase);
        J2534MemoryReadScope? memoryReadScope = null;
        var mapIndex = Array.FindIndex(args,
            value => value.Equals("--memory-read-profile", StringComparison.OrdinalIgnoreCase));
        if (mapIndex >= 0)
        {
            if (mapIndex + 1 >= args.Length)
            {
                Console.Error.WriteLine("Missing JSON path after --memory-read-profile.");
                return 6;
            }
            try { memoryReadScope = J2534MemoryReadScope.Load(args[mapIndex + 1]); }
            catch (Exception ex)
            {
                Console.Error.WriteLine("Invalid ECU memory read profile: " + ex.Message);
                return 6;
            }
        }
        var adapters = J2534Registry.Discover();
        if (adapters.Count == 0)
        {
            Console.Error.WriteLine("No installed J2534 driver was found.");
            Console.Error.WriteLine(@"Install the VXDIAG Windows driver/VX Manager, then check HKLM\SOFTWARE\PassThruSupport.04.04 or .05.00.");
            return 3;
        }

        Console.WriteLine("Installed J2534 adapters:");
        for (var i = 0; i < adapters.Count; i++)
            Console.WriteLine($"  [{i}] {adapters[i].DisplayName}");

        var selectedIndex = 0;
        if (adapters.Count > 1)
        {
            Console.Write($"Select adapter [0-{adapters.Count - 1}] (default 0): ");
            var input = Console.ReadLine();
            if (!string.IsNullOrWhiteSpace(input) &&
                (!int.TryParse(input, out selectedIndex) || selectedIndex < 0 || selectedIndex >= adapters.Count))
            {
                Console.Error.WriteLine("Invalid adapter selection.");
                return 4;
            }
        }

        // New TLS identity and token on each launch: no unencrypted fallback,
        // no private key persistence, and no reuse of a previously leaked code.
        using var tlsIdentity = BridgeTlsIdentity.Create();
        var tokenBytes = RandomNumberGenerator.GetBytes(32);
        var token = Convert.ToBase64String(tokenBytes).TrimEnd('=').Replace('+', '-').Replace('/', '_');
        Console.WriteLine();
        Console.WriteLine($"Selected: {adapters[selectedIndex].Name}");
        Console.WriteLine($"Secure pairing code (paste in Android token field): {token}.{tlsIdentity.Fingerprint}");
        Console.WriteLine("TLS: required (minimum version 1.2); certificate SHA-256 pinned in pairing code.");
        Console.WriteLine("A new pairing code is required every time the bridge restarts.");
        Console.WriteLine($"Port: {port}");
        Console.WriteLine("Available: physical standard OBD data/diagnostic reads, Mode 09 identity, UDS reads and TesterPresent.");
        if (allowObdClear) Console.WriteLine("Explicit emissions DTC reset enabled; engine off and stationary required. Readiness data will be reset.");
        Console.WriteLine("ECU writes, security access, actuator controls and flash programming are blocked.");
        if (memoryReadScope != null)
            Console.WriteLine("Memory read profile enabled: every 0x23 read is restricted to the configured " +
                "range and requires an exact live ECU VIN match. No write privileges are granted.");
        Console.WriteLine();

        try
        {
            await new BridgeServer(
                adapters[selectedIndex], token, port, tlsIdentity, memoryReadScope,
                allowStandardObdClear: allowObdClear).RunAsync();
            return 0;
        }
        catch (SocketException ex)
        {
            Console.Error.WriteLine($"Could not listen on TCP port {port}: {ex.Message}");
            return 5;
        }
    }

    private static int InspectE39Image(string[] args)
    {
        if (args.Length != 2 || !args[0].Equals("--inspect-e39-image", StringComparison.OrdinalIgnoreCase))
        {
            Console.Error.WriteLine("Usage: CamaroDiag.J2534Bridge.exe --inspect-e39-image <original-3MiB.bin>");
            return 10;
        }

        try
        {
            var path = Path.GetFullPath(args[1]);
            var bytes = new FileInfo(path);
            if (bytes.Length != NativeE39ImageInspector.ImageBytes)
            {
                Console.Error.WriteLine("Expected exact E39/E39A raw image length: 0x300000 bytes.");
                return 10;
            }
            var report = NativeE39ImageInspector.Inspect(File.ReadAllBytes(path));
            Console.WriteLine($"E39/E39A image status: {report.Status}");
            if (report.OsIdentifier.HasValue)
                Console.WriteLine($"OS ID: {report.OsIdentifier.Value}");
            foreach (var segment in report.Segments)
                Console.WriteLine($"  {segment.Name,-12} 0x{segment.Start:X6}..0x{segment.EndInclusive:X6}");
            foreach (var check in report.Checks)
                Console.WriteLine($"  {check.Name,-12} {check.Kind}: stored=0x{check.Stored:X4}, computed=0x{check.Computed:X4} " +
                    (check.Matches ? "OK" : "MISMATCH"));
            if (report.Reason is not null)
                Console.Error.WriteLine("Invalid image: " + report.Reason);
            if (report.Status == NativeE39ImageInspector.Verdict.CvnMismatch)
                Console.Error.WriteLine("CVN mismatch: inspect the file's provenance before any programming.");
            return report.Status switch
            {
                NativeE39ImageInspector.Verdict.Valid => 0,
                NativeE39ImageInspector.Verdict.CvnMismatch => 9,
                _ => 10
            };
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException or ArgumentException)
        {
            Console.Error.WriteLine("Could not inspect image: " + error.Message);
            return 10;
        }
    }

    private static int ReadPort(string[] args)
    {
        var index = Array.IndexOf(args, "--port");
        if (index >= 0 && index + 1 < args.Length &&
            int.TryParse(args[index + 1], out var port) && port is >= 1024 and <= 65535)
            return port;
        return 35001;
    }
}
