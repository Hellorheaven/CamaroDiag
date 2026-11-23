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
    internal const string DoIpDiscoverCommand = "--doip-discover";
    internal const string PassThruCommand = "--j2534-standard";
    internal const string RawIsoTpCommand = "--isotp-exchange";
    internal const string J1939Command = "--j1939-transport";

    internal static async Task<int> RunDoIpDiscoveryAsync(string[] args)
    {
        try
        {
            var host = Optional(args, "--host");
            var broadcastIp = Optional(args, "--broadcast-ip");
            if ((host is null) == (broadcastIp is null))
                throw new ArgumentException(
                    "Supply exactly one of --host or --broadcast-ip.");
            var timeoutMs = int.Parse(Optional(args, "--timeout-ms") ?? "2000",
                CultureInfo.InvariantCulture);
            var requestedVin = Optional(args, "--vin");
            var eidHex = Optional(args, "--eid");
            if (requestedVin is not null && eidHex is not null)
                throw new ArgumentException("Choose VIN or EID, not both.");
            var eid = eidHex is null ? null : Convert.FromHexString(eidHex);
            var timeout = TimeSpan.FromMilliseconds(timeoutMs);
            IReadOnlyList<DoIpVehicleDiscovery.Identity> identities;
            if (broadcastIp is not null)
                identities = await DoIpVehicleDiscovery.DiscoverBroadcastAsync(
                    broadcastIp, timeout, CancellationToken.None,
                    vin: requestedVin, eid: eid);
            else
                identities = [await DoIpVehicleDiscovery.DiscoverAsync(
                    host!, timeout, CancellationToken.None,
                    vin: requestedVin, eid: eid)];
            if (identities.Count == 0)
                throw new TimeoutException("No DoIP gateway identity received.");
            foreach (var identity in identities)
                Console.WriteLine($"DoIP gateway={identity.Source} " +
                    $"VIN={identity.Vin ?? "unavailable"} " +
                    $"logical=0x{identity.LogicalAddress:X4} " +
                    $"EID={Convert.ToHexString(identity.Eid)} " +
                    $"GID={Convert.ToHexString(identity.Gid)} " +
                    $"further=0x{identity.FurtherAction:X2} " +
                    $"sync={(identity.VinGidStatus.HasValue ?
                        $"0x{identity.VinGidStatus.Value:X2}" : "unspecified")}");
            Console.WriteLine("UDP identities are not ECU authentication or programming qualification.");
            return 0;
        }
        catch (Exception ex) when (ex is ArgumentException or IOException or
            InvalidDataException or TimeoutException or SocketException or
            OperationCanceledException or OverflowException or FormatException)
        {
            Console.Error.WriteLine("DoIP vehicle discovery failed: " + ex.Message);
            return 6;
        }
    }

    internal sealed record J1939Options(
        int AdapterIndex, uint Bitrate, J1939ClassicTpSession.Options Settings,
        string Mode, bool Broadcast, byte[] Request, int TimeoutMs);

    internal static J1939Options ParseJ1939(string[] args)
    {
        var index = int.Parse(Required(args, "--adapter-index"),
            CultureInfo.InvariantCulture);
        var baud = Number(Required(args, "--baud"));
        var source = checked((byte)Number(Required(args, "--source")));
        var target = checked((byte)Number(Required(args, "--target")));
        var pgn = checked((int)Number(Required(args, "--pgn")));
        var priority = checked((byte)Number(Optional(args, "--priority") ?? "6"));
        var window = checked((byte)Number(Optional(args, "--receive-window") ?? "16"));
        var mode = Required(args, "--mode").ToLowerInvariant();
        var broadcast = bool.Parse(Optional(args, "--broadcast") ?? "false");
        var timeout = int.Parse(Optional(args, "--timeout-ms") ?? "5000",
            CultureInfo.InvariantCulture);
        var payloadHex = Optional(args, "--payload");
        if ((mode == "send" && payloadHex is null) ||
            ((mode is "receive" or "request") && payloadHex is not null))
            throw new ArgumentException("J1939 send requires payload, receive/request forbid it.");
        byte[] data = mode == "send" ? Hex(payloadHex!) :
            mode == "request" ? J1939PgnRequest.Encode(pgn) : [];
        var options = new J1939ClassicTpSession.Options(source, target, pgn,
            priority, window);
        options.Validate();
        if (index < 0 || baud == 0 || timeout is < 100 or > 30000 ||
            mode is not ("send" or "receive" or "request") ||
            ((mode is "receive" or "request") && broadcast) ||
            data.Length > J1939ClassicTpSession.MaxPayload)
            throw new ArgumentException("Invalid J1939 transport or operating mode.");
        return new(index, baud, options, mode, broadcast, data, timeout);
    }

    internal static async Task<int> RunJ1939Async(string[] args)
    {
        try
        {
            var o = ParseJ1939(args);
            var adapters = J2534Registry.Discover();
            if (o.AdapterIndex >= adapters.Count)
                throw new ArgumentOutOfRangeException(nameof(o.AdapterIndex));
            if (o.Mode != "receive" &&
                !Confirm($"SAE J1939-21 {(o.Mode == "request" ? "request" : "transmit")} " +
                    $"PGN=0x{o.Settings.Pgn:X} " +
                    $"source=0x{o.Settings.LocalAddress:X2} " +
                    $"destination=0x{(o.Broadcast ? 0xFF : o.Settings.RemoteAddress):X2} " +
                    $"payload={Convert.ToHexString(o.Request)}"))
                return 2;
            // J1939-21 TP uses RAW 29-bit CAN; do not use ISO15765 framing.
            using var driver = J2534StandardChannel.Connect(
                adapters[o.AdapterIndex], J2534StandardChannel.Can,
                o.Bitrate, flags: 0x100);
            // CM (0xEC00), DT (0xEB00), and direct PGNs need more than one
            // arbitration ID. Software session code correlates PGN + source
            // and destination; listen to the 29-bit CAN bus.
            driver.ConfigureFilter(new byte[4], new byte[4]);
            var link = new IsoTpJ2534RawCanLink(driver, canFd: false,
                extendedId: true, bitRateSwitch: false);
            var window = TimeSpan.FromMilliseconds(o.TimeoutMs);
            if (o.Mode == "send")
            {
                await J1939ClassicTpSession.SendAsync(link, o.Settings,
                    o.Request, o.Broadcast, window, CancellationToken.None);
                Console.WriteLine("J1939 TP transmit completed; ECU functionality remains unverified.");
            }
            else
            {
                if (o.Mode == "request")
                {
                    var requestSettings = o.Settings with { Pgn = J1939PgnRequest.RequestPgn };
                    await J1939ClassicTpSession.SendAsync(link,
                        requestSettings, o.Request, broadcast: false,
                        timeout: window, token: CancellationToken.None);
                }
                var rxSettings = o.Mode == "request" ?
                    o.Settings with { AcceptAcknowledgement = true } : o.Settings;
                var response = await J1939ClassicTpSession.ReceiveAsync(
                    link, rxSettings, window, CancellationToken.None);
                if (response.Pgn == J1939PgnAcknowledgement.AcknowledgementPgn)
                {
                    var ack = J1939PgnAcknowledgement.Decode(
                        response.Payload, o.Settings.Pgn);
                    Console.WriteLine($"J1939 request PGN 0x{ack.RequestedPgn:X} " +
                        $"ACKM control={ack.Control} " +
                        $"groupFunction=0x{ack.GroupFunction:X2} " +
                        $"address=0x{ack.AcknowledgedAddress:X2}; " +
                        "no requested PGN payload was received.");
                    return ack.Positive ? 0 : 6;
                }
                Console.WriteLine($"J1939 RX PGN=0x{response.Pgn:X} " +
                    $"broadcast={response.Broadcast}: {Convert.ToHexString(response.Payload)}");
                if (response.Pgn == J1939AddressClaim.AddressClaimPgn)
                {
                    var claimId = J1939ClassicTpSession.ArbitrationId(
                        response.Pgn, o.Settings.Priority,
                        o.Settings.RemoteAddress, 0xFF);
                    var claim = J1939AddressClaim.Decode(claimId, response.Payload);
                    Console.WriteLine($"J1939 Address Claimed source=0x{claim.SourceAddress:X2} " +
                        $"NAME=0x{claim.Name:X16} manufacturer={claim.ManufacturerCode} " +
                        $"identity={claim.IdentityNumber} function={claim.Function} " +
                        $"arbitraryAddress={claim.ArbitraryAddressCapable}");
                }
                if (response.Pgn is J1939DmDiagnostics.Dm1Pgn or
                    J1939DmDiagnostics.Dm2Pgn)
                {
                    var report = J1939DmDiagnostics.Decode(
                        response.Pgn, response.Payload);
                    Console.WriteLine($"J1939 DM lamps=0x{report.LampStatus:X2} " +
                        $"flash=0x{report.LampFlashStatus:X2}, " +
                        $"DTC count={report.Codes.Count}");
                    foreach (var code in report.Codes)
                        Console.WriteLine($"SPN={(code.Spn?.ToString() ?? "undecoded")} " +
                            $"FMI={code.Fmi} occurrences={code.OccurrenceCount} " +
                            $"conversion={(code.AlternativeSpnConversion ? 1 : 0)} " +
                            $"raw={Convert.ToHexString(code.RawBytes)}");
                }
            }
            return 0;
        }
        catch (Exception ex) when (ex is ArgumentException or
            IOException or InvalidOperationException or TimeoutException or
            DllNotFoundException or BadImageFormatException or
            EntryPointNotFoundException or OverflowException)
        {
            Console.Error.WriteLine("J1939 TP exchange failed: " + ex.Message);
            return 6;
        }
    }

    internal sealed record IsoTpOptions(
        int AdapterIndex, uint Bitrate, IsoTpCanFdSession.Settings Settings,
        byte[] Request, int TimeoutMs, bool ExtendedId, bool Brs, uint? DataBaud,
        string Mode);

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
        var maxWaitFrames = int.Parse(Optional(args, "--wftmax") ?? "4",
            CultureInfo.InvariantCulture);
        var extended = bool.Parse(Optional(args, "--extended") ?? "false");
        // --extended selects 29-bit CAN IDs. These separate options select
        // ISO 15765-2 extended addressing with a one-byte address prefix.
        var txAddress = Optional(args, "--tx-address") is string txAddressText ?
            checked((byte)Number(txAddressText)) : (byte?)null;
        var rxAddress = Optional(args, "--rx-address") is string rxAddressText ?
            checked((byte)Number(rxAddressText)) : (byte?)null;
        var mixedText = Optional(args, "--mixed-address");
        var mixedAddressing = mixedText is not null;
        if (mixedAddressing)
        {
            if (txAddress.HasValue || rxAddress.HasValue || !extended)
                throw new ArgumentException(
                    "Mixed addressing requires --extended true (29-bit IDs) and no TX/RX address selectors.");
            txAddress = checked((byte)Number(mixedText!));
            rxAddress = txAddress;
        }
        var brs = bool.Parse(Optional(args, "--brs") ?? "false");
        var dataBaud = Optional(args, "--data-baud") is string rate ?
            Number(rate) : (uint?)null;
        var timeout = int.Parse(Optional(args, "--timeout-ms") ?? "5000",
            CultureInfo.InvariantCulture);
        var mode = (Optional(args, "--mode") ?? "exchange").ToLowerInvariant();
        var hexPayload = Optional(args, "--payload");
        var payloadFile = Optional(args, "--payload-file");
        if (mode is not ("exchange" or "receive") ||
            (mode == "exchange" && ((hexPayload is null) == (payloadFile is null))) ||
            (mode == "receive" && (hexPayload is not null || payloadFile is not null)))
            throw new ArgumentException(
                "Exchange needs exactly one payload source; receive mode accepts no payload.");
        byte[] bytes = mode == "receive" ? Array.Empty<byte>() : payloadFile is null ?
            Convert.FromHexString(hexPayload!) : File.ReadAllBytes(payloadFile);
        var setting = new IsoTpCanFdSession.Settings(tx, rx, length, fd,
            bs, stMin, TxAddress: txAddress, RxAddress: rxAddress,
            MaxWaitFrames: maxWaitFrames, MixedAddressing: mixedAddressing);
        setting.Validate();
        if (index < 0 || bitrate == 0 || timeout is < 100 or > 30000 ||
            (mode == "exchange" && bytes.Length is < 1 or > IsoTpCanFdSession.MaximumPayloadBytes) ||
            (brs && (!dataBaud.HasValue || dataBaud.Value == 0)) ||
            (dataBaud.HasValue && (!fd || dataBaud.Value == 0)) ||
            tx > (extended ? 0x1FFF_FFFFu : 0x7FFu) ||
            rx > (extended ? 0x1FFF_FFFFu : 0x7FFu) ||
            (!fd && brs))
            throw new ArgumentException("Invalid ISO-TP physical address or J2534 option.");
        return new(index, bitrate, setting, bytes, timeout, extended, brs, dataBaud,
            mode);
    }

    internal static async Task<int> RunIsoTpAsync(string[] args)
    {
        try
        {
            var o = ParseIsoTp(args);
            var adapters = J2534Registry.Discover();
            if (o.AdapterIndex >= adapters.Count)
                throw new ArgumentOutOfRangeException(nameof(o.AdapterIndex));
            if (o.Mode == "exchange" && !Confirm($"ISO-TP {(o.Settings.CanFd ? "CAN FD" : "CAN")} " +
                $"TX=0x{o.Settings.TxId:X} RX=0x{o.Settings.RxId:X}, " +
                $"TX address={o.Settings.TxAddress?.ToString("X2") ?? "normal"} " +
                $"RX address={o.Settings.RxAddress?.ToString("X2") ?? "normal"}, " +
                $"payload={Convert.ToHexString(o.Request)}"))
                return 2;
            // The RAW CAN/FD_CAN channel is required: J2534 ISO15765 already
            // performs its own ISO-TP segmentation and must not be double-framed.
            using var driver = J2534StandardChannel.Connect(adapters[o.AdapterIndex],
                o.Settings.CanFd ? J2534StandardChannel.FdCan :
                J2534StandardChannel.Can, o.Bitrate,
                o.ExtendedId ? 0x100u : 0u);
            if (o.DataBaud.HasValue)
                driver.SetCanFdDataPhaseRate(o.DataBaud.Value);
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
            if (o.Mode == "exchange")
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
            EntryPointNotFoundException or OverflowException or InvalidDataException)
        {
            Console.Error.WriteLine("ISO-TP exchange failed: " + ex.Message);
            return 6;
        }
    }

    internal sealed record DoIpOptions(string Host, ushort Tester, ushort Ecu,
        byte[] Request, int TimeoutMs, bool UseTls);
    internal sealed record PassThruOptions(int AdapterIndex, uint ProtocolId,
        uint Baud, uint Flags, byte[] Data, byte[]? Mask, byte[]? Pattern,
        byte[]? Flow, string? KlineInit, int TimeoutMs, int ReadFrames,
        byte? KwpRxTarget, byte? KwpRxSource, string Mode);

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
        var useTls = bool.Parse(Optional(args, "--tls") ?? "false");
        if (host.Length is < 1 or > 253 || timeout is < 100 or > 30000 ||
            tester == ecu || bytes.Length > DoIpCodec.MaxPayload - 4)
            throw new ArgumentException("DoIP target or diagnostic PDU is invalid.");
        return new(host, tester, ecu, bytes, timeout, useTls);
    }

    internal static PassThruOptions ParseJ2534(string[] args)
    {
        var index = int.Parse(Required(args, "--adapter-index"),
            CultureInfo.InvariantCulture);
        var protocol = Number(Required(args, "--protocol-id"));
        var baud = Number(Required(args, "--baud"));
        var flags = Number(Optional(args, "--flags") ?? "0");
        var mode = (Optional(args, "--mode") ?? "exchange").ToLowerInvariant();
        var txText = Optional(args, "--data");
        if (mode is not ("exchange" or "receive") ||
            (mode == "exchange" && txText is null) ||
            (mode == "receive" && txText is not null))
            throw new ArgumentException("J2534 exchange needs TX data; receive mode must not specify TX data.");
        var data = txText is null ? Array.Empty<byte>() : Hex(txText);
        var mask = Optional(args, "--mask") is string m ? Hex(m) : null;
        var pattern = Optional(args, "--pattern") is string p ? Hex(p) : null;
        var flow = Optional(args, "--flow-control") is string f ? Hex(f) : null;
        var init = Optional(args, "--kline-init");
        var kwpRxTarget = Optional(args, "--kwp-rx-target") is string targetText ?
            checked((byte)Number(targetText)) : (byte?)null;
        var kwpRxSource = Optional(args, "--kwp-rx-source") is string sourceText ?
            checked((byte)Number(sourceText)) : (byte?)null;
        var timeout = int.Parse(Optional(args, "--timeout-ms") ?? "1500",
            CultureInfo.InvariantCulture);
        var count = int.Parse(Optional(args, "--read-frames") ?? "1",
            CultureInfo.InvariantCulture);
        if (index < 0 || !J2534StandardChannel.IsKnownProtocol(protocol) ||
            baud == 0 || timeout is < 1 or > 30000 || count is < 0 or > 256 ||
            (mode == "receive" && count == 0) ||
            data.Length > J2534StandardChannel.MaxData ||
            (mask == null) != (pattern == null) ||
            (flow != null && mask == null) ||
            (kwpRxTarget.HasValue != kwpRxSource.HasValue) ||
            (kwpRxTarget.HasValue && protocol != J2534StandardChannel.Iso14230) ||
            (init != null && protocol is not (J2534StandardChannel.Iso9141
                                             or J2534StandardChannel.Iso14230)) ||
            (init != null && !init.StartsWith("five:", StringComparison.OrdinalIgnoreCase) &&
                !init.StartsWith("fast:", StringComparison.OrdinalIgnoreCase)))
            throw new ArgumentException("Invalid standard J2534 channel configuration.");
        return new(index, protocol, baud, flags, data,
            mask, pattern, flow, init, timeout, count,
            kwpRxTarget, kwpRxSource, mode);
    }

    internal static async Task<int> RunDoIpAsync(string[] args)
    {
        try
        {
            var o = ParseDoIp(args);
            if (!Confirm($"DoIP {(o.UseTls ? "TLS" : "TCP")} " +
                $"{o.Host}:{(o.UseTls ? 3496 : 13400)} ECU=0x{o.Ecu:X4} " +
                $"UDS={Convert.ToHexString(o.Request)}"))
                return 2;
            await using var link = await DoIpDiagnosticTransport.ConnectAsync(
                o.Host, o.Tester, o.Ecu,
                TimeSpan.FromMilliseconds(o.TimeoutMs), CancellationToken.None,
                useTls: o.UseTls);
            var reply = await link.ExchangeUdsAsync(o.Request,
                TimeSpan.FromMilliseconds(o.TimeoutMs), CancellationToken.None);
            Console.WriteLine("DoIP ECU RX: " + Convert.ToHexString(reply));
            return 0;
        }
        catch (Exception ex) when (ex is ArgumentException or
            IOException or SocketException or OperationCanceledException or
            InvalidDataException or TimeoutException or
            System.Security.Authentication.AuthenticationException)
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
            if (o.Mode == "exchange" && !Confirm($"SAE J2534 {adapters[o.AdapterIndex].Name}, " +
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
            if (o.Mode == "exchange")
            {
                driver.Transmit(o.Data, timeoutMs: o.TimeoutMs);
                Console.WriteLine("J2534 TX queued; this does not prove ECU execution.");
            }
            for (var i = 0; i < o.ReadFrames; i++)
            {
                var received = driver.Receive(o.TimeoutMs);
                if (received == null) break;
                Console.WriteLine($"J2534 RX protocol=0x{received.ProtocolId:X} " +
                    $"status=0x{received.RxStatus:X} {Convert.ToHexString(received.Data)}");
                if (o.KwpRxTarget is byte target && o.KwpRxSource is byte source)
                {
                    var payload = StandardVehicleProtocols.ParseKwpPhysicalFrame(
                        received.Data, target, source);
                    Console.WriteLine("KWP physical RX payload: " +
                        Convert.ToHexString(payload));
                }
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
