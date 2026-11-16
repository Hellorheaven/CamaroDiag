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

        var port = ReadPort(args);
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

        var tokenBytes = RandomNumberGenerator.GetBytes(32);
        var token = Convert.ToBase64String(tokenBytes).TrimEnd('=').Replace('+', '-').Replace('/', '_');
        Console.WriteLine();
        Console.WriteLine($"Selected: {adapters[selectedIndex].Name}");
        Console.WriteLine($"Pairing token: {token}");
        Console.WriteLine($"Port: {port}");
        Console.WriteLine("The bridge only accepts UDS read services 0x22 / 0x19 and TesterPresent 0x3E.");
        Console.WriteLine("ECU writes, security access, actuator controls and flash programming are blocked.");
        Console.WriteLine();

        try
        {
            await new BridgeServer(adapters[selectedIndex], token, port).RunAsync();
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
