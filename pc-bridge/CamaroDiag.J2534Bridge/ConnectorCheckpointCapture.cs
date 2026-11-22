using System.Diagnostics;
using System.IO.Ports;
using System.Text;
using System.Text.RegularExpressions;

namespace CamaroDiag.J2534Bridge;

/// <summary>Explicit isolated CDJ/USB/1 service firmware only, never a vehicle transport.</summary>
internal static class ConnectorCheckpointCapture
{
    internal const string Command = "--capture-connector-checkpoint";
    private const int DeadlineMs = 3000;
    internal static byte[]? Decode(string line)
    {
        if (line == "CDJ/1 NONE\n") return null;
        if (line == "CDJ/1 CORRUPT\n") throw new InvalidDataException("Device journal corrupt or locked.");
        if (!Regex.IsMatch(line, @"\ACDJ/1 [0-9a-f]{128}\n\z", RegexOptions.CultureInvariant))
            throw new InvalidDataException("Unexpected checkpoint response.");
        var bytes = Convert.FromHexString(line.AsSpan(6, 128));
        if (ConnectorCheckpoint.Inspect(bytes).Record is null)
            throw new InvalidDataException("Invalid checkpoint record or CRC.");
        return bytes;
    }

    internal static byte[]? CapturePort(string port)
    {
        if (!ConnectorDiscoveryCli.IsValidPort(port)) throw new ArgumentException("Invalid COM port.");
        using var serial = new SerialPort(port, 115200, Parity.None, 8, StopBits.One)
        {
            Handshake = Handshake.None, DtrEnable = true, RtsEnable = false,
            ReadTimeout = 1500, WriteTimeout = 1500
        };
        serial.Open();
        var timer = Stopwatch.StartNew();
        var request = "CDJ/1 READ\n"u8.ToArray();
        serial.Write(request, 0, request.Length);
        var line = new StringBuilder(135);
        while (line.Length < 135)
        {
            var remaining = DeadlineMs - timer.ElapsedMilliseconds;
            if (remaining <= 0) throw new TimeoutException("Checkpoint deadline exceeded.");
            serial.ReadTimeout = (int)Math.Min(1500L, remaining);
            int value = serial.ReadByte();
            if (value < 0 || timer.ElapsedMilliseconds >= DeadlineMs)
                throw new IOException("Incomplete checkpoint response.");
            if (value != '\n' && (value < 32 || value > 126))
                throw new InvalidDataException("Non-ASCII checkpoint response.");
            line.Append((char)value);
            if (value == '\n') break;
        }
        if (serial.BytesToRead != 0) throw new InvalidDataException("Unexpected trailing response.");
        return Decode(line.ToString());
    }

    internal static ConnectorCheckpoint.Inspection Save(string path, byte[] bytes)
    {
        var frozen = bytes.ToArray();
        var inspection = ConnectorCheckpoint.Inspect(frozen);
        if (inspection.Record is null) throw new InvalidDataException("Invalid checkpoint.");
        using var file = new FileStream(path, FileMode.CreateNew, FileAccess.Write, FileShare.None);
        file.Write(frozen);
        file.Flush(flushToDisk: true);
        return inspection;
    }

    internal static int Run(string[] args)
    {
        if (args.Length != 4 || !args[0].Equals(Command, StringComparison.OrdinalIgnoreCase)
            || !ConnectorDiscoveryCli.IsValidPort(args[1]) || args[3] != "--isolated")
        {
            Console.Error.WriteLine("Usage: --capture-connector-checkpoint COM5 <new-file.cdj> --isolated");
            return 6;
        }
        try
        {
            var bytes = CapturePort(args[1]);
            if (bytes is null)
            {
                Console.WriteLine("No checkpoint. This does not authorize programming.");
                return 8;
            }
            var result = Save(args[2], bytes);
            Console.WriteLine("CDJ/1 saved: " + result.Status + "; unauthenticated; no automatic recovery.");
            return result.Status == ConnectorCheckpoint.Disposition.RecoveryRequired ? 8 : 0;
        }
        catch (Exception error) when (error is IOException or TimeoutException or UnauthorizedAccessException
            or ArgumentException or InvalidOperationException or NotSupportedException)
        {
            Console.Error.WriteLine("Checkpoint capture failed: " + error.Message);
            return 6;
        }
    }
}
