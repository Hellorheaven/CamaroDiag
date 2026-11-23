using System.IO.Ports;
using System.Text.RegularExpressions;

namespace CamaroDiag.J2534Bridge;

/// <summary>Local isolated-prototype discovery, never a J2534 or ECU transport.</summary>
internal static class ConnectorDiscoveryCli
{
    internal const string Command = "--discover-connector";
    internal static bool IsValidPort(string port) =>
        Regex.IsMatch(port, @"\ACOM[1-9][0-9]{0,3}\z", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);

    /// <summary>
    /// A cached UI selection cannot authorize probing a port that disappeared
    /// after USB hot-unplug. The OS port name is only a routing hint, never a
    /// cryptographic device identity or permission for vehicle communication.
    /// </summary>
    internal static bool IsPortStillEnumerated(string port, IEnumerable<string> available) =>
        IsValidPort(port) &&
        available.Count(name => string.Equals(name, port, StringComparison.OrdinalIgnoreCase)) == 1;

    internal static string[] VerifyPort(string port)
    {
        if (!IsValidPort(port)) throw new ArgumentException("Invalid COM port.", nameof(port));
        if (!IsPortStillEnumerated(port, SerialPort.GetPortNames()))
            throw new IOException("Selected connector COM port is no longer enumerated; refresh the list.");
        using var serial = new SerialPort(port, 115200, Parity.None, 8, StopBits.One)
        {
            Handshake = Handshake.None, DtrEnable = true, RtsEnable = false,
            ReadTimeout = 1500, WriteTimeout = 1500
        };
        serial.Open();
        return new ConnectorBinaryDiscovery(serial.BaseStream).Verify();
    }

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
            foreach (var response in VerifyPort(args[1]))
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
