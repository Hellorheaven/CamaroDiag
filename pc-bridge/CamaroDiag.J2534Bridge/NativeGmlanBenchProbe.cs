using System.Globalization;
using System.Runtime.InteropServices;

namespace CamaroDiag.J2534Bridge;

/// <summary>
/// Local, read-only GMLAN identity probe for a Windows J2534 adapter.
/// No flash, security access, session transition or external ECU tool.
///
/// The operator MUST supply the selected physical adapter and both CAN IDs;
/// no CAN bus broadcast or uncertain default ECU target is attempted.
/// </summary>
internal static class NativeGmlanBenchProbe
{
    internal const string Command = "--probe-gmlan-ecu";

    internal sealed record Options(int AdapterIndex, int RequestCanId,
        int ResponseCanId, int ProtocolNumber, bool Can29Bit);

    internal static Options Parse(string[] args)
    {
        ArgumentNullException.ThrowIfNull(args);
        if (args.Length != 9 ||
            !args[0].Equals(Command, StringComparison.OrdinalIgnoreCase))
            throw new ArgumentException(
                "Usage: CamaroDiag.J2534Bridge.exe --probe-gmlan-ecu " +
                "--adapter-index 0 --request-can-id 7E0 --response-can-id 7E8 --protocol 33");

        var values = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        for (var i = 1; i < args.Length; i += 2)
        {
            var flag = args[i];
            if (flag is not ("--adapter-index" or "--request-can-id" or
                "--response-can-id" or "--protocol") || !values.TryAdd(flag, args[i + 1]))
                throw new ArgumentException("Unknown or repeated GMLAN probe parameter: " + flag);
        }

        if (values.Count != 4 ||
            !int.TryParse(values["--adapter-index"], NumberStyles.None,
                CultureInfo.InvariantCulture, out var index) ||
            index is < 0 or > 99 ||
            !int.TryParse(values["--protocol"], NumberStyles.None,
                CultureInfo.InvariantCulture, out var protocol) ||
            protocol is not (33 or 34 or 35 or 36 or 63 or 64))
            throw new ArgumentException("Invalid J2534 adapter index or protocol number.");

        var requestId = ParseCanId(values["--request-can-id"]);
        var responseId = ParseCanId(values["--response-can-id"]);
        var extended = protocol is 34 or 36 or 64;
        var maxId = extended ? 0x1FFFFFFF : 0x7FF;
        if (requestId > maxId || responseId > maxId || requestId == responseId)
            throw new ArgumentException(
                "GMLAN request/response CAN IDs must be distinct and match 11/29-bit protocol.");

        return new Options(index, requestId, responseId, protocol, extended);
    }

    private static int ParseCanId(string value)
    {
        if (value.StartsWith("0x", StringComparison.OrdinalIgnoreCase))
            value = value[2..];
        if (value.Length is < 1 or > 8 ||
            value.Any(x => !Uri.IsHexDigit(x)) ||
            !uint.TryParse(value, NumberStyles.AllowHexSpecifier,
                CultureInfo.InvariantCulture, out var id) ||
            id > 0x1FFFFFFF)
            throw new ArgumentException("CAN IDs must be hexadecimal 11/29-bit identifiers.");
        return (int)id;
    }

    /// <summary>
    /// Identity must be independently read twice. A stale or changing
    /// identity is not a reliable basis for subsequent programming work.
    /// </summary>
    internal static NativeGmlanIdentification.Identity RequireStableIdentity(
        NativeGmlanIdentification.Identity first,
        NativeGmlanIdentification.Identity second)
    {
        ArgumentNullException.ThrowIfNull(first);
        ArgumentNullException.ThrowIfNull(second);
        if (!first.PhysicalRead || !second.PhysicalRead ||
            !string.Equals(first.Vin, second.Vin, StringComparison.Ordinal) ||
            first.OperatingSystemId == 0 ||
            first.OperatingSystemId != second.OperatingSystemId ||
            first.OsDid != second.OsDid)
            throw new InvalidDataException(
                "Independent physical GMLAN reads disagree on VIN, OSID or identifier DID.");
        return first;
    }

    internal static int Run(string[] args)
    {
        try
        {
            var options = Parse(args);
            var adapters = J2534Registry.Discover();
            if (options.AdapterIndex >= adapters.Count)
                throw new ArgumentException(
                    $"Adapter index {options.AdapterIndex} not installed; " +
                    $"found {adapters.Count} J2534 drivers.");

            var adapter = adapters[options.AdapterIndex];
            Console.WriteLine("GMLAN physical ECU identification (READ ONLY)");
            Console.WriteLine($"Adapter: {adapter.DisplayName}");
            Console.WriteLine($"CAN request: 0x{options.RequestCanId:X}; " +
                $"response: 0x{options.ResponseCanId:X}; profile: {options.ProtocolNumber}");
            using var device = new J2534Device(adapter);
            device.Open(options.RequestCanId, options.ResponseCanId,
                options.Can29Bit, options.ProtocolNumber);

            var voltageBefore = device.ReadBatteryMillivolts();
            var first = device.ReadGmlanIdentification();
            var second = device.ReadGmlanIdentification();
            var confirmed = RequireStableIdentity(first, second);
            var voltageAfter = device.ReadBatteryMillivolts();

            Console.WriteLine($"VIN (read twice): {confirmed.Vin}");
            Console.WriteLine($"ECU OSID (read twice): {confirmed.OperatingSystemId}");
            Console.WriteLine($"GM identifier DID: 0x{confirmed.OsDid:X2}");
            Console.WriteLine("Battery before: " + DescribeVoltage(voltageBefore));
            Console.WriteLine("Battery after: " + DescribeVoltage(voltageAfter));
            Console.WriteLine("Read-only identity succeeded. " +
                "FLASH PROGRAMMING IS NOT CERTIFIED OR ENABLED.");
            return 0;
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException or
            ArgumentException or TimeoutException or InvalidOperationException or
            DllNotFoundException or BadImageFormatException or EntryPointNotFoundException or
            ExternalException)
        {
            Console.Error.WriteLine("GMLAN ECU probe failed: " + error.Message);
            Console.Error.WriteLine("No ECU programming operations were requested.");
            return 12;
        }
    }

    private static string DescribeVoltage(uint? millivolts) =>
        millivolts.HasValue
            ? (millivolts.Value / 1000.0).ToString("F2", CultureInfo.InvariantCulture) + " V (vendor-reported)"
            : "not available through vendor J2534 driver";
}
