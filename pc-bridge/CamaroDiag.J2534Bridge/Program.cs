using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;

namespace CamaroDiag.J2534Bridge;

internal static class Program
{
    private static async Task<int> Main(string[] args)
    {
        if (!OperatingSystem.IsWindows())
        {
            Console.Error.WriteLine("CamaroDiag J2534 Bridge requires Windows 10/11.");
            return 2;
        }

        // Offline, no-driver validation for Windows CI. Never starts a bridge listener.
        if (args.Contains("--self-test", StringComparer.OrdinalIgnoreCase))
            return BridgeSelfTests.Run();

        if (await BridgeUpdateManager.CheckAndOfferUpdateAsync()) return 0;
        var port = ReadPort(args);
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
        Console.WriteLine("Available: OBD Mode 09 identity, UDS reads and TesterPresent.");
        Console.WriteLine("ECU writes, security access, actuator controls and flash programming are blocked.");
        if (memoryReadScope != null)
            Console.WriteLine("Memory read profile enabled: every 0x23 read is restricted to the configured " +
                "range and requires an exact live ECU VIN match. No write privileges are granted.");
        Console.WriteLine();

        try
        {
            await new BridgeServer(
                adapters[selectedIndex], token, port, tlsIdentity, memoryReadScope).RunAsync();
            return 0;
        }
        catch (SocketException ex)
        {
            Console.Error.WriteLine($"Could not listen on TCP port {port}: {ex.Message}");
            return 5;
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
