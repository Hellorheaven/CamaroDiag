using System.IO.Ports;
using System.Text.RegularExpressions;
using CamaroDiagConnectors.Windows;

if (args is ["--self-test"])
    return SelfTests.Run();

if ((args.Contains(J1979MaintenanceUsbCli.Command,StringComparer.OrdinalIgnoreCase)||args.Contains(J1979MaintenanceUsbCli.ArchiveCommand,StringComparer.OrdinalIgnoreCase)))
    return J1979MaintenanceUsbCli.Run(args);
if (args.Contains(J1979MaintenanceJournal.Command,StringComparer.OrdinalIgnoreCase) ||
    args.Contains(J1979MaintenanceJournal.PrefixCommand,StringComparer.OrdinalIgnoreCase))

    return J1979MaintenanceJournal.Run(args);

if (args.Contains(J1979LiveNativeCli.CaptureCommand,StringComparer.OrdinalIgnoreCase) ||
    args.Contains(J1979LiveNativeCli.InspectCommand,StringComparer.OrdinalIgnoreCase) ||
    args.Contains(J1979LiveNativeCli.CsvCommand,StringComparer.OrdinalIgnoreCase) ||
    args.Contains(J1979LiveNativeCli.ScalarCommand,StringComparer.OrdinalIgnoreCase) ||
    args.Contains(J1979LiveNativeCli.ScalarCsvCommand,StringComparer.OrdinalIgnoreCase) ||
    args.Contains(J1979LiveNativeCli.PrefixCommand,StringComparer.OrdinalIgnoreCase))
    return J1979LiveNativeCli.Run(args);
if (args.Length>0 && J1979FreezeNativeCli.IsCommand(args[0]))
    return J1979FreezeNativeCli.Run(args);

if (args.Length>0 && args[0]=="--j1979-usb")
    return J1979UsbCli.Run(args[1..]);

if (!OperatingSystem.IsWindows() || args.Length != 2 || args[0] != "--port" ||
    !Regex.IsMatch(args[1], @"\ACOM[1-9][0-9]{0,3}\z", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant))
{
    Console.Error.WriteLine("Usage (isolated prototype only): --port COM5 | --self-test");
    return 2;
}
try
{
    using var serial=new SerialPort(args[1],115200,Parity.None,8,StopBits.One)
    {
        Handshake=Handshake.None, DtrEnable=true, RtsEnable=false,
        ReadTimeout=1500, WriteTimeout=1500
    };
    serial.Open();
    foreach(var response in new BinaryDiscovery(serial.BaseStream).Verify())
        Console.WriteLine(response);
    Console.WriteLine("Prototype discovery only; device not cryptographically authenticated.");
    return 0;
}
catch(Exception ex) when(ex is IOException or TimeoutException or UnauthorizedAccessException or InvalidOperationException)
{
    Console.Error.WriteLine("Discovery failed: "+ex.Message);
    return 1;
}
