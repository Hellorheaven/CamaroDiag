using System.IO.Ports;
using System.Text.RegularExpressions;

namespace CamaroDiag.J2534Bridge;

/// <summary>Local isolated-prototype discovery, never a J2534 or ECU transport.</summary>
internal static class ConnectorDiscoveryCli
{
    internal const string Command = "--discover-connector";
    internal static bool IsValidPort(string port) =>
        Regex.IsMatch(port, @"\ACOM[1-9][0-9]{0,3}\z", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);

    internal static int Run(string[] args)
    {
        if (args.Length != 2 || !args[0].Equals(Command, StringComparison.OrdinalIgnoreCase) ||
            !IsValidPort(args[1]))
        {
            Console.Error.WriteLine("Usage: --discover-connector COM5 (isolated CD/1 prototype only)");
            return 2;
        }
        try
        {
            using var serial = new SerialPort(args[1], 115200, Parity.None, 8, StopBits.One)
            {
                Handshake = Handshake.None, DtrEnable = true, RtsEnable = false,
                ReadTimeout = 1500, WriteTimeout = 1500
            };
            serial.Open();
            foreach (var response in new ConnectorBinaryDiscovery(serial.BaseStream).Verify())
                Console.WriteLine(response);
            Console.WriteLine("Public prototype discovery only. Not authenticated; vehicle access disabled.");
            return 0;
        }
        catch (Exception ex) when (ex is IOException or TimeoutException or UnauthorizedAccessException
            or InvalidOperationException or ArgumentException)
        {
            Console.Error.WriteLine("Connector discovery failed: " + ex.Message);
            return 1;
        }
    }
}
