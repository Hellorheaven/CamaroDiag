using System.Runtime.InteropServices;
using System.Text;

namespace CamaroDiag.J2534Bridge;

/// <summary>
/// Checks real Windows J2534 provider DLLs, their ABI entry points and their
/// ability to open a connected interface. This is a transport-driver probe,
/// NOT a declaration that ECU programming or a recovery sequence is validated.
/// </summary>
internal static class J2534DriverProbe
{
    private static readonly string[] RequiredExports =
    [
        "PassThruOpen", "PassThruClose", "PassThruConnect",
        "PassThruDisconnect", "PassThruReadMsgs", "PassThruWriteMsgs",
        "PassThruStartMsgFilter", "PassThruStopMsgFilter",
        "PassThruIoctl", "PassThruReadVersion", "PassThruGetLastError"
    ];

    [UnmanagedFunctionPointer(CallingConvention.StdCall)]
    private delegate uint PassThruOpenDelegate(IntPtr name, out uint deviceId);
    [UnmanagedFunctionPointer(CallingConvention.StdCall)]
    private delegate uint PassThruCloseDelegate(uint deviceId);
    [UnmanagedFunctionPointer(CallingConvention.StdCall, CharSet = CharSet.Ansi)]
    private delegate uint PassThruReadVersionDelegate(
        uint deviceId, StringBuilder firmware, StringBuilder dll, StringBuilder api);

    internal sealed record ProbeResult(
        string Adapter, bool AbiComplete, bool DeviceOpened, bool VersionRead,
        string? Firmware, string? Dll, string? Api, string Detail)
    {
        public bool DriverOperational => AbiComplete && DeviceOpened && VersionRead;
        // A live connection, ECU protocol, firmware image and recovery still need
        // independent physical validation before any destructive flash.
        public bool EcuWriteValidated => false;
    }

    internal static IReadOnlyList<string> MissingRequiredExports(Func<string, bool> hasExport) =>
        RequiredExports.Where(name => !hasExport(name)).ToArray();

    internal static ProbeResult Probe(J2534Adapter adapter)
    {
        IntPtr dll = IntPtr.Zero;
        uint deviceId = 0;
        var opened = false;
        try
        {
            dll = NativeLibrary.Load(adapter.FunctionLibrary);
            var missing = MissingRequiredExports(name => NativeLibrary.TryGetExport(dll, name, out _));
            if (missing.Count > 0)
                return new ProbeResult(adapter.DisplayName, false, false, false,
                    null, null, null, "Missing J2534 API exports: " + string.Join(", ", missing));

            var open = Marshal.GetDelegateForFunctionPointer<PassThruOpenDelegate>(
                NativeLibrary.GetExport(dll, "PassThruOpen"));
            var close = Marshal.GetDelegateForFunctionPointer<PassThruCloseDelegate>(
                NativeLibrary.GetExport(dll, "PassThruClose"));
            var version = Marshal.GetDelegateForFunctionPointer<PassThruReadVersionDelegate>(
                NativeLibrary.GetExport(dll, "PassThruReadVersion"));

            try
            {
                var status = open(IntPtr.Zero, out deviceId);
                if (status != 0)
                    return new ProbeResult(adapter.DisplayName, true, false, false,
                        null, null, null, $"PassThruOpen returned 0x{status:X8}. Check USB connection, driver and architecture.");

                opened = true;
                // SAE J2534 returns firmware, DLL, then API version in that order.
                var firmware = new StringBuilder(80);
                var dllVersion = new StringBuilder(80);
                var api = new StringBuilder(80);
                status = version(deviceId, firmware, dllVersion, api);
                if (status != 0)
                    return new ProbeResult(adapter.DisplayName, true, true, false,
                        null, null, null, $"PassThruReadVersion returned 0x{status:X8}.");

                if (string.IsNullOrWhiteSpace(api.ToString()) ||
                    string.IsNullOrWhiteSpace(dllVersion.ToString()))
                    return new ProbeResult(adapter.DisplayName, true, true, false,
                        firmware.ToString(), dllVersion.ToString(), api.ToString(),
                        "J2534 provider returned empty required version information.");

                return new ProbeResult(adapter.DisplayName, true, true, true,
                    firmware.ToString(), dllVersion.ToString(), api.ToString(),
                    "J2534 ABI and PassThruOpen/ReadVersion succeeded; ECU programming is NOT certified.");
            }
            finally
            {
                if (opened)
                {
                    try
                    {
                        var closeStatus = close(deviceId);
                        if (closeStatus != 0)
                            Console.Error.WriteLine($"Warning: PassThruClose returned 0x{closeStatus:X8} for {adapter.Name}.");
                    }
                    catch (Exception error)
                    {
                        Console.Error.WriteLine($"Warning: PassThruClose failed for {adapter.Name}: {error.Message}");
                    }
                }
            }
        }
        catch (Exception error) when (error is DllNotFoundException or BadImageFormatException or
            EntryPointNotFoundException or SEHException or InvalidOperationException or FileLoadException)
        {
            return new ProbeResult(adapter.DisplayName, false, false, false,
                null, null, null, $"Cannot use J2534 DLL: {error.GetType().Name}: {error.Message}");
        }
        finally
        {
            if (dll != IntPtr.Zero) NativeLibrary.Free(dll);
        }
    }

    public static int RunAll()
    {
        var adapters = J2534Registry.Discover();
        if (adapters.Count == 0)
        {
            Console.Error.WriteLine("No J2534 provider registered in Windows. Install the vendor driver first.");
            return 3;
        }

        var allOperational = true;
        foreach (var adapter in adapters)
        {
            var result = Probe(adapter);
            Console.WriteLine($"Adapter: {result.Adapter}");
            Console.WriteLine($"  J2534 exports: {(result.AbiComplete ? "OK" : "FAIL")}");
            Console.WriteLine($"  Physical adapter open: {(result.DeviceOpened ? "OK" : "FAIL")}");
            Console.WriteLine($"  Firmware: {result.Firmware ?? "(not available)"}");
            Console.WriteLine($"  DLL: {result.Dll ?? "(not available)"}");
            Console.WriteLine($"  API: {result.Api ?? "(not available)"}");
            Console.WriteLine($"  Result: {result.Detail}");
            Console.WriteLine("  ECU programming and recovery: NOT VALIDATED by this driver probe.");
            allOperational &= result.DriverOperational;
        }
        return allOperational ? 0 : 7;
    }
}
