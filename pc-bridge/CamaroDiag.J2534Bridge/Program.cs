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

        // Help is intentionally resolved before updater, DLL enumeration,
        // network listener and *all* protocol/ECU command dispatch.
        // A mixed --help + destructive option is rejected without I/O.
        if (BridgeCommandLineHelp.IsHelpRequest(args))
            return BridgeCommandLineHelp.Run(args);

        // Explicit bidirectional ISO/SAE exchanges. No default startup
        // programming and no network-listener fallback on malformed CLI.
        if (args.Contains(StandardProtocolCli.J1939FdCmdtCommand,
            StringComparer.OrdinalIgnoreCase))
            return await StandardProtocolCli.RunJ1939FdAsync(args);
        if (args.Contains(StandardProtocolCli.J1939FdBamCommand,
            StringComparer.OrdinalIgnoreCase))
            return await StandardProtocolCli.RunJ1939FdAsync(args);
        if (args.Contains(StandardProtocolCli.J1939FdCommand,
            StringComparer.OrdinalIgnoreCase))
            return await StandardProtocolCli.RunJ1939FdAsync(args);
        if (args.Contains(StandardProtocolCli.J1939Command,
            StringComparer.OrdinalIgnoreCase))
            return await StandardProtocolCli.RunJ1939Async(args);
        if (args.Contains(StandardProtocolCli.RawIsoTpCommand,
            StringComparer.OrdinalIgnoreCase))
            return await StandardProtocolCli.RunIsoTpAsync(args);
        // Entirely offline: never discover or open a J2534 adapter.
        if (args.Contains(IsoTpClassicCaptureAudit.Command,
            StringComparer.OrdinalIgnoreCase))
            return IsoTpClassicCaptureAudit.Run(args);
        if (args.Contains(StandardProtocolCli.ObdProtocolAutoDetectCommand,
            StringComparer.OrdinalIgnoreCase))
            return await StandardProtocolCli.RunObdProtocolAutoDetectAsync(args);
        if (args.Contains(StandardProtocolCli.J1979FunctionalDiscoveryCommand,
            StringComparer.OrdinalIgnoreCase))
            return await StandardProtocolCli.RunJ1979FunctionalDiscoveryAsync(args);
        if (args.Contains(StandardProtocolCli.J1979PidDiscoveryCommand,
            StringComparer.OrdinalIgnoreCase))
            return await StandardProtocolCli.RunJ1979PidDiscoveryAsync(args);
        if (args.Contains(StandardProtocolCli.ObdCanDiscoverCommand,
            StringComparer.OrdinalIgnoreCase))
            return await StandardProtocolCli.RunObdCanDiscoveryAsync(args);
        if (args.Contains(StandardProtocolCli.DoIpStatusCommand,
            StringComparer.OrdinalIgnoreCase))
            return await StandardProtocolCli.RunDoIpStatusAsync(args);
        if (args.Contains(StandardProtocolCli.DoIpDiscoverCommand,
            StringComparer.OrdinalIgnoreCase))
            return await StandardProtocolCli.RunDoIpDiscoveryAsync(args);
        if (args.Contains(StandardProtocolCli.DoIpCommand,
            StringComparer.OrdinalIgnoreCase))
            return await StandardProtocolCli.RunDoIpAsync(args);
        if (args.Contains(StandardProtocolCli.PassThruCommand,
            StringComparer.OrdinalIgnoreCase))
            return StandardProtocolCli.RunJ2534(args);

        // Explicit public CD/1 discovery. Never falls through to a vendor
        // driver, network listener, updater or ECU operation.
        if (args.Contains(ConnectorDiscoveryCli.Command, StringComparer.OrdinalIgnoreCase))
            return ConnectorDiscoveryCli.Run(args);

        // Offline verification must never load a vendor DLL, contact an ECU,
        // start the network bridge or launch the updater.
        if (args.Contains(NativeScopedBackupImportCli.Command, StringComparer.OrdinalIgnoreCase))
            return NativeScopedBackupImportCli.Run(args);

        // Explicit local programming path; malformed invocations cannot fall
        // through to a network listener, updater or another CLI operation.
        if (args.Contains(NativeE39QualifiedBenchCli.Command, StringComparer.OrdinalIgnoreCase))
            return await NativeE39QualifiedBenchCli.RunAsync(args);

        // Offline, no-driver validation for Windows CI. Never starts a bridge listener.
        if (args.Contains("--self-test", StringComparer.OrdinalIgnoreCase))
            return BridgeSelfTests.Run();

        if (args.Contains(ConnectorCheckpoint.Command, StringComparer.OrdinalIgnoreCase))
            return ConnectorCheckpoint.Run(args);

        // Offline recovery inspection must never load a J2534 driver or
        // trigger the updater. Invalid or incomplete evidence is not success.
        if (args.Contains("--inspect-write-journal", StringComparer.OrdinalIgnoreCase))
            return InspectWriteJournal(args);

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


        // Never silently reinterpret a mistyped CLI argument as "start the
        // bridge on the default port". Parse before any updater, DLL lookup,
        // vehicle connection or network listener.
        if (!BridgeLaunchArguments.TryParse(args, out var launch, out var argumentError))
        {
            Console.Error.WriteLine(argumentError);
            Console.Error.WriteLine("Run --help alone for valid command examples.");
            return 6;
        }
        var port = launch!.Port;
        var allowObdClear = launch.AllowObdClear;
        J2534MemoryReadScope? memoryReadScope = null;
        if (launch.MemoryReadProfile is { } profilePath)
        {
            try { memoryReadScope = J2534MemoryReadScope.Load(profilePath); }
            catch (Exception ex)
            {
                Console.Error.WriteLine("Invalid ECU memory read profile: " + ex.Message);
                return 6;
            }
        }
        if (await BridgeUpdateManager.CheckAndOfferUpdateAsync()) return 0;
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
        Console.WriteLine("Standard background diagnostics remain read-only. Explicit paired raw UDS exchanges can send ECU services; actual ECU protocol restrictions apply.");
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

    private static int InspectWriteJournal(string[] args)
    {
        if (args.Length != 2 ||
            !args[0].Equals("--inspect-write-journal", StringComparison.OrdinalIgnoreCase) ||
            string.IsNullOrWhiteSpace(args[1]))
        {
            Console.Error.WriteLine("Usage: --inspect-write-journal <journal.jsonl>");
            return 6;
        }

        try
        {
            var report = NativeE39FileWriteJournal.InspectDetailed(args[1]);
            Console.WriteLine("ECU journal: " + report.State);
            if (report.SequenceValid)
            {
                Console.WriteLine($"Prepared sectors: {report.PreparedSectors}; written and verified: {report.WrittenSectors}; final reread: {report.FinalReadbackSectors}");
                Console.WriteLine("Last phase: " + (report.LastPhase ?? "none"));
            }
            else Console.WriteLine("Journal sequence is incomplete, invalid, or contains an explicit recovery marker.");
            if (report.PossiblyIncompleteAddress is uint address)
                Console.WriteLine($"Potentially incomplete sector: 0x{address:X8}");
            Console.WriteLine("Offline evidence only; this command does not contact, reset or program an ECU.");
            if (!report.RequiresAssessment) return 0;
            Console.Error.WriteLine("No completed transaction recorded. Preserve the original backup and journal.");
            return 8;
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException or ArgumentException
            or NotSupportedException)
        {
            Console.Error.WriteLine("Unable to inspect ECU journal: " + error.Message);
            return 6;
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

}
