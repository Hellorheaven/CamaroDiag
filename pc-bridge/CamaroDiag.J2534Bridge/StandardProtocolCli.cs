using System.Globalization;
using System.Net.Sockets;

namespace CamaroDiag.J2534Bridge;

/// <summary>
/// Explicit bidirectional ISO/SAE protocol CLI. No background ECU access.
/// The operator directly selects the real vendor link/target and consents to
/// each transmission, since an arbitrary frame can contain a write service.
/// OEM ECU seed/key, checksum and protocol session requirements still apply.
/// </summary>
internal static class StandardProtocolCli
{
    internal const string DoIpCommand = "--doip-exchange";
    internal const string PassThruCommand = "--j2534-standard";
    internal const string RawIsoTpCommand = "--isotp-exchange";

    internal sealed record IsoTpOptions(
        int AdapterIndex, uint Bitrate, IsoTpCanFdSession.Settings Settings,
        byte[] Request, int TimeoutMs, bool ExtendedId, bool Brs);

    internal static IsoTpOptions ParseIsoTp(string[] args)
    {
        var index = int.Parse(Required(args, "--adapter-index"),
            CultureInfo.InvariantCulture);
        var bitrate = Number(Required(args, "--baud"));
        static uint ParseCanId(string input) => uint.Parse(
            input.StartsWith("0x", StringComparison.OrdinalIgnoreCase) ?
                input[2..] : input,
            NumberStyles.AllowHexSpecifier, CultureInfo.InvariantCulture);
        var tx = ParseCanId(Required(args, "--tx-id"));
        var rx = ParseCanId(Required(args, "--rx-id"));
        var fd = bool.Parse(Optional(args, "--fd") ?? "true");
        var length = int.Parse(Optional(args, "--tx-dl") ?? (fd ? "64" : "8"),
            CultureInfo.InvariantCulture);
        var bs = byte.Parse(Optional(args, "--block-size") ?? "8",
            CultureInfo.InvariantCulture);
        var stMin = byte.Parse(Optional(args, "--stmin") ?? "0",
            CultureInfo.InvariantCulture);
        var extended = bool.Parse(Optional(args, "--extended") ?? "false");
        var brs = bool.Parse(Optional(args, "--brs") ?? "false");
        var timeout = int.Parse(Optional(args, "--timeout-ms") ?? "5000",
            CultureInfo.InvariantCulture);
        var bytes = Hex(Required(args, "--payload"));
        var setting = new IsoTpCanFdSession.Settings(tx, rx, length, fd, bs, stMin);
        setting.Validate();
        if (index < 0 || bitrate == 0 || timeout is < 100 or > 30000 ||
            bytes.Length > IsoTpCanFdSession.MaximumPayloadBytes ||
            tx > (extended ? 0x1FFF_FFFFu : 0x7FFu) ||
            rx > (extended ? 0x1FFF_FFFFu : 0x7FFu) ||
            (!fd && brs))
            throw new ArgumentException("Invalid ISO-TP physical address or J2534 option.");
        return new(index, bitrate, setting, bytes, timeout, extended, brs);
    }

    internal static async Task<int> RunIsoTpAsync(string[] args)
    {
        try
        {
            var o = ParseIsoTp(args);
            var adapters = J2534Registry.Discover();
            if (o.AdapterIndex >= adapters.Count)
                throw new ArgumentOutOfRangeException(nameof(o.AdapterIndex));
            if (!Confirm($"ISO-TP {(o.Settings.CanFd ? "CAN FD" : "CAN")} " +
                $"TX=0x{o.Settings.TxId:X} RX=0x{o.Settings.RxId:X}, " +
                $"payload={Convert.ToHexString(o.Request)}"))
                return 2;
            // The RAW CAN/FD_CAN channel is required: J2534 ISO15765 already
            // performs its own ISO-TP segmentation and must not be double-framed.
            using var driver = J2534StandardChannel.Connect(adapters[o.AdapterIndex],
                o.Settings.CanFd ? J2534StandardChannel.FdCan :
                J2534StandardChannel.Can, o.Bitrate,
                o.ExtendedId ? 0x100u : 0u);
            var mask = new byte[4];
            var pattern = new byte[4];
            System.Buffers.Binary.BinaryPrimitives.WriteUInt32BigEndian(
                mask, o.ExtendedId ? 0x1FFF_FFFFu : 0x7FFu);
            System.Buffers.Binary.BinaryPrimitives.WriteUInt32BigEndian(
                pattern, o.Settings.RxId);
            driver.ConfigureFilter(mask, pattern);
            var link = new IsoTpJ2534RawCanLink(driver, o.Settings.CanFd,
                o.ExtendedId, o.Brs);
            var limit = TimeSpan.FromMilliseconds(o.TimeoutMs);
            await IsoTpCanFdSession.SendAsync(link, o.Settings, o.Request,
                limit, CancellationToken.None);
            var reply = await IsoTpCanFdSession.ReceiveAsync(link, o.Settings,
                limit, CancellationToken.None);
            Console.WriteLine("ISO-TP ECU RX: " + Convert.ToHexString(reply));
            return 0;
        }
        catch (Exception ex) when (ex is ArgumentException or IOException or
            InvalidOperationException or TimeoutException or
            DllNotFoundException or BadImageFormatException or
            EntryPointNotFoundException)
        {
            Console.Error.WriteLine("ISO-TP exchange failed: " + ex.Message);
            return 6;
        }
    }

    internal sealed record DoIpOptions(string Host, ushort Tester, ushort Ecu,
        byte[] Request, int TimeoutMs);
    internal sealed record PassThruOptions(int AdapterIndex, uint ProtocolId,
        uint Baud, uint Flags, byte[] Data, byte[]? Mask, byte[]? Pattern,
        byte[]? Flow, string? KlineInit, int TimeoutMs, int ReadFrames);

    private static string Required(IReadOnlyList<string> args, string option)
    {
        var i = Array.FindIndex(args.ToArray(), x =>
            x.Equals(option, StringComparison.OrdinalIgnoreCase));
        if (i < 0 || i == args.Count - 1 || args[i + 1].StartsWith("--", StringComparison.Ordinal))
            throw new ArgumentException("Missing required option " + option);
        return args[i + 1];
    }

    private static string? Optional(IReadOnlyList<string> args, string option)
    {
        var i = Array.FindIndex(args.ToArray(), x =>
            x.Equals(option, StringComparison.OrdinalIgnoreCase));
        return i == -1 ? null : Required(args, option);
    }

    private static uint Number(string value)
    {
        if (value.StartsWith("0x", StringComparison.OrdinalIgnoreCase))
            return uint.Parse(value[2..], NumberStyles.AllowHexSpecifier, CultureInfo.InvariantCulture);
        return uint.Parse(value, NumberStyles.None, CultureInfo.InvariantCulture);
    }

    private static ushort Address(string value)
    {
        // DoIP logical tester/ECU addresses are conventionally displayed in hex.
        if (!ushort.TryParse(value.TrimStart('0', 'x', 'X'),
            NumberStyles.AllowHexSpecifier, CultureInfo.InvariantCulture, out var number))
            throw new ArgumentException("DoIP logical address must be a 16-bit hex value.");
        return number;
    }

    private static byte[] Hex(string value)
    {
        if (value.Length == 0 || value.Length % 2 != 0 || value.Length > 8256 ||
            !value.All(Uri.IsHexDigit))
            throw new ArgumentException("Transmission data must be even-length hex.");
        return Convert.FromHexString(value);
    }

    internal static DoIpOptions ParseDoIp(string[] args)
    {
        var host = Required(args, "--host");
        var tester = Address(Required(args, "--tester"));
        var ecu = Address(Required(args, "--ecu"));
        var bytes = Hex(Required(args, "--payload"));
        var timeout = int.Parse(Optional(args, "--timeout-ms") ?? "5000",
            CultureInfo.InvariantCulture);
        if (host.Length is < 1 or > 253 || timeout is < 100 or > 30000 ||
            tester == ecu || bytes.Length > DoIpCodec.MaxPayload - 4)
            throw new ArgumentException("DoIP target or diagnostic PDU is invalid.");
        return new(host, tester, ecu, bytes, timeout);
    }

    internal static PassThruOptions ParseJ2534(string[] args)
    {
        var index = int.Parse(Required(args, "--adapter-index"),
            CultureInfo.InvariantCulture);
        var protocol = Number(Required(args, "--protocol-id"));
        var baud = Number(Required(args, "--baud"));
        var flags = Number(Optional(args, "--flags") ?? "0");
        var data = Hex(Required(args, "--data"));
        var mask = Optional(args, "--mask") is string m ? Hex(m) : null;
        var pattern = Optional(args, "--pattern") is string p ? Hex(p) : null;
        var flow = Optional(args, "--flow-control") is string f ? Hex(f) : null;
        var init = Optional(args, "--kline-init");
        var timeout = int.Parse(Optional(args, "--timeout-ms") ?? "1500",
            CultureInfo.InvariantCulture);
        var count = int.Parse(Optional(args, "--read-frames") ?? "1",
            CultureInfo.InvariantCulture);
        if (index < 0 || !J2534StandardChannel.IsKnownProtocol(protocol) ||
            baud == 0 || timeout is < 1 or > 30000 || count is < 0 or > 256 ||
            data.Length > J2534StandardChannel.MaxData ||
            (mask == null) != (pattern == null) ||
            (flow != null && mask == null) ||
            (init != null && protocol is not (J2534StandardChannel.Iso9141
                                             or J2534StandardChannel.Iso14230)) ||
            (init != null && !init.StartsWith("five:", StringComparison.OrdinalIgnoreCase) &&
                !init.StartsWith("fast:", StringComparison.OrdinalIgnoreCase)))
            throw new ArgumentException("Invalid standard J2534 channel configuration.");
        return new(index, protocol, baud, flags, data,
            mask, pattern, flow, init, timeout, count);
    }

    internal static async Task<int> RunDoIpAsync(string[] args)
    {
        try
        {
            var o = ParseDoIp(args);
            if (!Confirm($"DoIP {o.Host}:13400 ECU=0x{o.Ecu:X4} UDS={Convert.ToHexString(o.Request)}"))
                return 2;
            await using var link = await DoIpDiagnosticTransport.ConnectAsync(
                o.Host, o.Tester, o.Ecu,
                TimeSpan.FromMilliseconds(o.TimeoutMs), CancellationToken.None);
            var reply = await link.ExchangeUdsAsync(o.Request,
                TimeSpan.FromMilliseconds(o.TimeoutMs), CancellationToken.None);
            Console.WriteLine("DoIP ECU RX: " + Convert.ToHexString(reply));
            return 0;
        }
        catch (Exception ex) when (ex is ArgumentException or
            IOException or SocketException or OperationCanceledException or
            InvalidDataException or TimeoutException)
        {
            Console.Error.WriteLine("DoIP exchange failed: " + ex.Message);
            return 6;
        }
    }

    internal static int RunJ2534(string[] args)
    {
        try
        {
            var o = ParseJ2534(args);
            var adapters = J2534Registry.Discover();
            if (o.AdapterIndex >= adapters.Count)
                throw new ArgumentOutOfRangeException(nameof(o.AdapterIndex));
            if (!Confirm($"SAE J2534 {adapters[o.AdapterIndex].Name}, " +
                $"protocol=0x{o.ProtocolId:X} TX={Convert.ToHexString(o.Data)}"))
                return 2;
            using var driver = J2534StandardChannel.Connect(adapters[o.AdapterIndex],
                o.ProtocolId, o.Baud, o.Flags);
            if (o.KlineInit is string init)
            {
                var tokens = init.Split(':', 2);
                var value = Hex(tokens[1]);
                if (tokens[0].Equals("five", StringComparison.OrdinalIgnoreCase))
                {
                    if (value.Length != 1) throw new ArgumentException("Five-baud address must be one byte.");
                    Console.WriteLine("K-Line init RX: " +
                        Convert.ToHexString(driver.FiveBaudInitialize(value[0])));
                }
                else Console.WriteLine("K-Line fast init RX: " +
                    Convert.ToHexString(driver.FastInitialize(value)));
            }
            if (o.Mask != null && o.Pattern != null)
                driver.ConfigureFilter(o.Mask, o.Pattern, o.Flow);
            driver.Transmit(o.Data, timeoutMs: o.TimeoutMs);
            Console.WriteLine("J2534 TX queued; this does not prove ECU execution.");
            for (var i = 0; i < o.ReadFrames; i++)
            {
                var received = driver.Receive(o.TimeoutMs);
                if (received == null) break;
                Console.WriteLine($"J2534 RX protocol=0x{received.ProtocolId:X} " +
                    $"status=0x{received.RxStatus:X} {Convert.ToHexString(received.Data)}");
            }
            return 0;
        }
        catch (Exception ex) when (ex is ArgumentException or
            IOException or InvalidOperationException or InvalidDataException or
            DllNotFoundException or BadImageFormatException or EntryPointNotFoundException)
        {
            Console.Error.WriteLine("J2534 exchange failed: " + ex.Message);
            return 6;
        }
    }

    private static bool Confirm(string operation) =>
        EcuWriteOperatorConsent.AskBeforeProgramming(Console.ReadLine,
            Console.WriteLine, operation);
}
