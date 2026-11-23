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
    internal const string DoIpStatusCommand = "--doip-status";
    internal const string PassThruCommand = "--j2534-standard";
    internal const string RawIsoTpCommand = "--isotp-exchange";
    internal const string J1939Command = "--j1939-transport";
    internal const string J1939FdCommand = "--j1939-fd-multipg";
    internal const string J1939FdBamCommand = "--j1939-fd-bam";
    internal const string J1939FdCmdtCommand = "--j1939-fd-cmdt";

    internal static async Task<int> RunDoIpStatusAsync(string[] args)
    {
        try
        {
            var host = Required(args, "--host");
            var power = bool.Parse(Optional(args, "--power-mode") ?? "false");
            var timeoutMs = int.Parse(Optional(args, "--timeout-ms") ?? "2000",
                CultureInfo.InvariantCulture);
            var status = await DoIpVehicleDiscovery.QueryStatusAsync(
                host, power, TimeSpan.FromMilliseconds(timeoutMs),
                CancellationToken.None);
            if (power)
            {
                var mode = DoIpCodec.ParseDiagnosticPowerMode(status);
                Console.WriteLine($"DoIP diagnostic power mode={mode} " +
                    $"({(mode == 1 ? "ready" : mode == 0 ? "not ready" : "not supported")})");
            }
            else
            {
                var entity = DoIpCodec.ParseEntityStatus(status);
                Console.WriteLine($"DoIP node type={entity.NodeType} " +
                    $"sockets={entity.CurrentSockets}/{entity.MaxSockets} " +
                    $"max data size={(entity.MaxDataSize?.ToString() ?? "unspecified")}");
            }
            Console.WriteLine("DoIP UDP status is informational and not ECU authorization.");
            return 0;
        }
        catch (Exception ex) when (ex is ArgumentException or IOException or
            InvalidDataException or TimeoutException or SocketException or
            OperationCanceledException or FormatException or OverflowException)
        {
            Console.Error.WriteLine("DoIP UDP status query failed: " + ex.Message);
            return 6;
        }
    }

    internal static async Task<int> RunDoIpDiscoveryAsync(string[] args)
    {
        try
        {
            var host = Optional(args, "--host");
            var broadcastIp = Optional(args, "--broadcast-ip");
            var passiveListen = bool.Parse(Optional(args, "--listen") ?? "false");
            if (passiveListen ?
                host is not null || broadcastIp is not null :
                (host is null) == (broadcastIp is null))
                throw new ArgumentException(
                    "Listen mode needs no destination; active discovery requires one host or broadcast IP.");
            var timeoutMs = int.Parse(Optional(args, "--timeout-ms") ?? "2000",
                CultureInfo.InvariantCulture);
            var requestedVin = Optional(args, "--vin");
            var eidHex = Optional(args, "--eid");
            if (requestedVin is not null && eidHex is not null)
                throw new ArgumentException("Choose VIN or EID, not both.");
            var eid = eidHex is null ? null : Convert.FromHexString(eidHex);
            var timeout = TimeSpan.FromMilliseconds(timeoutMs);
            IReadOnlyList<DoIpVehicleDiscovery.Identity> identities;
            if (passiveListen)
            {
                if (requestedVin is not null || eid is not null)
                    throw new ArgumentException(
                        "Passive DoIP listening cannot apply an outgoing VIN/EID request.");
                identities = await DoIpVehicleDiscovery.ListenAsync(
                    timeout, CancellationToken.None);
            }
            else if (broadcastIp is not null)
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

    private static void PrintObdOnUdsResponse(byte[] request, byte[] response)
    {
        if (request.Length == 3 && request[0] == 0x22)
        {
            var did = (ushort)(request[1] << 8 | request[2]);
            if (did == ObdOnUdsServices.ProtocolIdentificationDid &&
                response.Length > 0 && response[0] == 0x62)
            {
                var identification =
                    ObdOnUdsServices.DecodeProtocolIdentification(response);
                Console.WriteLine($"OBDonUDS protocol F810=0x{identification.Value:X2} " +
                    $"OBDonUDS={(identification.IsObdOnUds ? "identified" : "not confirmed")}");
            }
            else if (ObdOnUdsServices.IsEmissionsDataDid(did) &&
                response.Length > 0 && response[0] == 0x62)
            {
                var raw = ObdOnUdsServices.DecodeReadDid(did, response);
                Console.WriteLine($"OBDonUDS emissions DID=0x{did:X4} " +
                    $"raw={Convert.ToHexString(raw)} " +
                    "(physical units require the current SAE J1979DA definition).");
            }
        }
        else if (request.Length == 3 && request[0] == 0x19 &&
            request[1] == 0x02 && response.Length > 0 && response[0] == 0x59)
        {
            var report = ObdOnUdsServices.DecodeDtcByStatusMask(response);
            Console.WriteLine($"UDS DTC available-mask=0x{report.AvailabilityMask:X2} " +
                $"count={report.Codes.Count}");
            foreach (var dtc in report.Codes)
                Console.WriteLine($"DTC=0x{dtc.Code:X6} " +
                    $"status=0x{dtc.Status:X2}");
        }
    }

    internal sealed record J1939FdOptions(int AdapterIndex, uint Bitrate,
        uint? DataBaud, bool Brs, byte Source, byte Target, byte Priority,
        int? Pgn, string Mode, byte[] Payload, int TimeoutMs,
        bool Bam, byte Session, bool Cmdt, byte Window,
        ulong? Name = null);

    internal static J1939FdOptions ParseJ1939Fd(string[] args)
    {
        var index = int.Parse(Required(args, "--adapter-index"),
            CultureInfo.InvariantCulture);
        var baud = Number(Required(args, "--baud"));
        var dataBaudText = Optional(args, "--data-baud");
        uint? dataBaud = dataBaudText is null ? null : Number(dataBaudText);
        var brs = bool.Parse(Optional(args, "--brs") ?? "false");
        var source = checked((byte)Number(Required(args, "--source")));
        var target = checked((byte)Number(Required(args, "--target")));
        var name = ParseJ1939Name(args);
        var bam = args.Contains(J1939FdBamCommand,
            StringComparer.OrdinalIgnoreCase);
        var cmdt = args.Contains(J1939FdCmdtCommand,
            StringComparer.OrdinalIgnoreCase);
        var multiPg = args.Contains(J1939FdCommand,
            StringComparer.OrdinalIgnoreCase);
        if ((bam ? 1 : 0) + (cmdt ? 1 : 0) +
            (multiPg ? 1 : 0) != 1)
            throw new ArgumentException(
                "Select exactly one SAE J1939-22 Multi-PG, BAM or CMDT transport.");
        var window = checked((byte)Number(Optional(args, "--window") ?? "8"));
        var session = checked((byte)Number(Optional(args, "--session") ?? "0"));
        var priority = checked((byte)Number(Optional(args, "--priority") ??
            (bam || cmdt ? "7" : "6")));
        var pgnText = Optional(args, "--pgn");
        int? pgn = pgnText is null ? null : checked((int)Number(pgnText));
        var mode = Required(args, "--mode").ToLowerInvariant();
        var payloadText = Optional(args, "--payload");
        var payloadFile = Optional(args, "--payload-file");
        if (payloadText is not null && payloadFile is not null)
            throw new ArgumentException("Provide payload hex or file, never both.");
        if (payloadFile is not null)
        {
            var fileBytes = new FileInfo(payloadFile).Length;
            if (fileBytes > (bam || cmdt ? J1939FdBamSession.MaximumPayload : 60))
                throw new ArgumentException("J1939-22 payload file exceeds transport limit.");
        }
        byte[] payload = payloadText is not null ? Hex(payloadText) :
            payloadFile is not null ? File.ReadAllBytes(payloadFile) : [];
        var timeout = int.Parse(Optional(args, "--timeout-ms") ?? "5000",
            CultureInfo.InvariantCulture);
        // Large FD.TP transfers require more than 30 seconds when BAM is
        // paced. Single-frame FEFF remains limited to short operations.
        var maxTimeout = bam || cmdt ? 600000 : 30000;
        if (index < 0 || baud == 0 || timeout < 100 ||
            timeout > maxTimeout ||
            source >= 0xFE || target == 0xFE || source == target ||
            (mode == "receive" && target >= 0xFE) ||
            priority > 7 || mode is not ("send" or "receive") ||
            (mode == "send" && (pgn is null ||
                (bam || cmdt ?
                    payload.Length is <= 60 or > J1939FdBamSession.MaximumPayload :
                    payload.Length is < 1 or > 60))) ||
            (mode == "receive" && (payloadText is not null || payloadFile is not null)) ||
            (bam && (pgn is null || session > 3 ||
                (mode == "send" && target != 0xFF))) ||
            (cmdt && (pgn is null || session > 7 ||
                target >= 0xFE || window == 0)) ||
            (!bam && !cmdt && Optional(args, "--session") is not null) ||
            (!cmdt && Optional(args, "--window") is not null) ||
            pgn is < 0 or > 0x3FFFF ||
            (brs && (dataBaud is null or 0)) ||
            (dataBaud.HasValue && dataBaud.Value == 0))
            throw new ArgumentException("Invalid SAE J1939-22 FEFF Multi-PG options.");
        if (pgn.HasValue && ((pgn.Value >> 8) & 0xFF) < 240 &&
            (pgn.Value & 0xFF) != 0)
            throw new ArgumentException("Invalid J1939 PDU1 contained PGN.");
        if (mode == "send" && pgn.HasValue &&
            ((pgn.Value >> 8) & 0xFF) >= 240 && target != 0xFF)
            throw new ArgumentException("J1939-22 PDU2 contained PGN must be broadcast.");
        if (bam && pgn.HasValue && ((pgn.Value >> 8) & 0xFF) < 240)
            throw new ArgumentException("J1939-22 broadcast TP requires a PDU2 PGN.");
        if (cmdt && pgn.HasValue && ((pgn.Value >> 8) & 0xFF) >= 240)
            throw new ArgumentException("J1939-22 directed FD.TP requires a PDU1 PGN.");
        return new(index, baud, dataBaud, brs, source, target,
            priority, pgn, mode, payload, timeout, bam, session, cmdt, window,
            name);
    }

    internal static async Task<int> RunJ1939FdAsync(string[] args)
    {
        try
        {
            var o = ParseJ1939Fd(args);
            var adapters = J2534Registry.Discover();
            if (o.AdapterIndex >= adapters.Count)
                throw new ArgumentOutOfRangeException(nameof(o.AdapterIndex));
            if ((o.Mode == "send" || o.Name.HasValue) &&
                !Confirm($"SAE J1939-22 {(o.Bam ? "FD.TP BAM" : o.Cmdt ? "FD.TP CMDT" : "FEFF Multi-PG")} " +
                    $"TX source=0x{o.Source:X2} destination=0x{o.Target:X2} " +
                    $"NAME={(o.Name.HasValue ? $"0x{o.Name.Value:X16} CLAIM" : "not claimed here")} " +
                    $"PGN=0x{o.Pgn:X} length={o.Payload.Length} " +
                    $"SHA256={Convert.ToHexString(
                        System.Security.Cryptography.SHA256.HashData(o.Payload))}"))
                return 2;

            // J1939-22 FEFF Multi-PG uses RAW FD_CAN, not classical
            // J1939-21 TP or ISO15765 pre-segmented channels.
            using var driver = J2534StandardChannel.Connect(
                adapters[o.AdapterIndex], J2534StandardChannel.FdCan,
                o.Bitrate, flags: 0x100u);
            if (o.DataBaud.HasValue)
                driver.SetCanFdDataPhaseRate(o.DataBaud.Value);
            driver.ConfigureFilter(new byte[4], new byte[4]);
            var link = new IsoTpJ2534RawCanLink(driver, canFd: true,
                extendedId: true, bitRateSwitch: o.Brs);
            if (o.Name.HasValue)
            {
                var claim = await J1939AddressClaimSession.ClaimAsync(
                    link, o.Source, o.Name.Value, canFd: true,
                    token: CancellationToken.None);
                if (claim != J1939AddressClaimSession.Outcome.Claimed)
                    throw new IOException(
                        "J1939-81 NAME lost address arbitration; no application PGN sent.");
                Console.WriteLine($"J1939-81 CAN FD claim accepted for 0x{o.Source:X2} " +
                    $"during the initial observation interval.");
            }

            if (o.Mode == "send")
            {
                if (o.Cmdt)
                {
                    var session = new J1939FdCmdtSession.Options(
                        o.Source, o.Target, o.Pgn!.Value,
                        o.Session, o.Priority, o.Window);
                    await J1939FdCmdtSession.SendAsync(link, session,
                        o.Payload, TimeSpan.FromMilliseconds(o.TimeoutMs),
                        CancellationToken.None);
                    Console.WriteLine("J1939-22 directed FD.TP sent and acknowledged; no ECU operation qualified.");
                }
                else if (o.Bam)
                {
                    await J1939FdBamSession.SendAsync(link, o.Source,
                        o.Pgn!.Value, o.Session, o.Payload,
                        CancellationToken.None, o.Priority);
                    Console.WriteLine("J1939-22 broadcast FD.TP sent; ECU processing not verified.");
                }
                else
                {
                    var packet = J1939FdMultiPg.Encode(
                        o.Source, o.Target, o.Priority,
                        [new J1939FdMultiPg.ContainedGroup(o.Pgn!.Value, o.Payload)]);
                    await link.SendAsync(packet, CancellationToken.None);
                    Console.WriteLine("J1939-22 Multi-PG frame queued; ECU processing not verified.");
                }
                return 0;
            }

            var limit = TimeSpan.FromMilliseconds(o.TimeoutMs);
            if (o.Cmdt)
            {
                var options = new J1939FdCmdtSession.Options(
                    o.Source, o.Target, o.Pgn!.Value,
                    o.Session, o.Priority, o.Window);
                var payload = await J1939FdCmdtSession.ReceiveAsync(
                    link, options, limit, CancellationToken.None);
                Console.WriteLine($"J1939-22 CMDT RX PGN=0x{o.Pgn:X} " +
                    $"length={payload.Length} raw={Convert.ToHexString(payload)}");
                return 0;
            }
            if (o.Bam)
            {
                var payload = await J1939FdBamSession.ReceiveAsync(link,
                    o.Target, o.Pgn!.Value, limit, CancellationToken.None);
                Console.WriteLine($"J1939-22 BAM RX PGN=0x{o.Pgn:X} " +
                    $"length={payload.Length} raw={Convert.ToHexString(payload)}");
                return 0;
            }
            var clock = System.Diagnostics.Stopwatch.StartNew();
            for (var frameCount = 0; frameCount < 128; frameCount++)
            {
                var remaining = limit - clock.Elapsed;
                if (remaining <= TimeSpan.Zero)
                    break;
                var frame = await link.ReceiveAsync(remaining, CancellationToken.None);
                if (frame.Id > 0x1FFFFFFF) continue;
                var id = StandardVehicleProtocols.ParseJ1939Id(frame.Id);
                if (id.Pgn != J1939FdMultiPg.MultiPgPgn ||
                    id.Source != o.Target ||
                    (id.Destination != o.Source && id.Destination != 0xFF))
                    continue;
                var received = J1939FdMultiPg.Decode(frame);
                var groups = o.Pgn.HasValue ?
                    received.Groups.Where(g => g.Pgn == o.Pgn.Value).ToArray() :
                    received.Groups.ToArray();
                if (groups.Length == 0) continue;
                foreach (var group in groups)
                    Console.WriteLine($"J1939-22 RX source=0x{received.SourceAddress:X2} " +
                        $"PGN=0x{group.Pgn:X} payload={Convert.ToHexString(group.Payload)}");
                return 0;
            }
            throw new TimeoutException("No correlated SAE J1939-22 Multi-PG frame.");
        }
        catch (Exception ex) when (ex is ArgumentException or IOException or
            InvalidOperationException or TimeoutException or
            DllNotFoundException or BadImageFormatException or
            EntryPointNotFoundException or NotSupportedException or OverflowException)
        {
            Console.Error.WriteLine("J1939-22 CAN FD transport failed: " + ex.Message);
            return 6;
        }
    }

    internal sealed record J1939Options(
        int AdapterIndex, uint Bitrate, J1939ClassicTpSession.Options Settings,
        string Mode, bool Broadcast, byte[] Request, int TimeoutMs,
        ulong? Name = null);

    internal static J1939Options ParseJ1939(string[] args)
    {
        var index = int.Parse(Required(args, "--adapter-index"),
            CultureInfo.InvariantCulture);
        var baud = Number(Required(args, "--baud"));
        var source = checked((byte)Number(Required(args, "--source")));
        var target = checked((byte)Number(Required(args, "--target")));
        var name = ParseJ1939Name(args);
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
        return new(index, baud, options, mode, broadcast, data, timeout,
            name);
    }

    internal static async Task<int> RunJ1939Async(string[] args)
    {
        try
        {
            var o = ParseJ1939(args);
            var adapters = J2534Registry.Discover();
            if (o.AdapterIndex >= adapters.Count)
                throw new ArgumentOutOfRangeException(nameof(o.AdapterIndex));
            if ((o.Mode != "receive" || o.Name.HasValue) &&
                !Confirm($"SAE J1939-21 {(o.Mode == "request" ? "request" : "transmit")} " +
                    $"PGN=0x{o.Settings.Pgn:X} " +
                    $"source=0x{o.Settings.LocalAddress:X2} " +
                    $"NAME={(o.Name.HasValue ? $"0x{o.Name.Value:X16} CLAIM" : "not claimed here")} " +
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
            if (o.Name.HasValue)
            {
                var claim = await J1939AddressClaimSession.ClaimAsync(
                    link, o.Settings.LocalAddress, o.Name.Value, canFd: false,
                    token: CancellationToken.None);
                if (claim != J1939AddressClaimSession.Outcome.Claimed)
                    throw new IOException(
                        "J1939-81 NAME lost address arbitration; no application PGN sent.");
                Console.WriteLine($"J1939-81 classic CAN claim accepted for " +
                    $"0x{o.Settings.LocalAddress:X2} during initial observation.");
            }
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
                    // ACKM only acknowledges the request. Without the
                    // requested PGN data this is INCONCLUSIVE, never a
                    // completed data read (and certainly not an ECU write).
                    return ack.Positive ? 7 : 6;
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
            if (txAddress.HasValue || rxAddress.HasValue)
                throw new ArgumentException(
                    "Mixed addressing uses --mixed-address instead of TX/RX address selectors.");
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
        var obdDid = Optional(args, "--obd-did");
        var obdDtcMask = Optional(args, "--obd-dtc-mask");
        var obdIdentify = args.Contains("--obd-identify",
            StringComparer.OrdinalIgnoreCase);
        var payloadSources = (hexPayload is null ? 0 : 1) +
            (payloadFile is null ? 0 : 1) +
            (obdDid is null ? 0 : 1) +
            (obdDtcMask is null ? 0 : 1) +
            (obdIdentify ? 1 : 0);
        if (mode is not ("exchange" or "receive") ||
            (mode == "exchange" && payloadSources != 1) ||
            (mode == "receive" && payloadSources != 0))
            throw new ArgumentException(
                "Exchange needs exactly one payload, file, OBD DID or DTC mask; receive mode accepts none.");
        byte[] bytes = mode == "receive" ? Array.Empty<byte>() :
            obdIdentify ? ObdOnUdsServices.ReadProtocolIdentification() :
            obdDid is not null ? ObdOnUdsServices.ReadEmissionsData(
                checked((ushort)ParseCanId(obdDid))) :
            obdDtcMask is not null ? ObdOnUdsServices.ReadDtcByStatusMask(
                checked((byte)Number(obdDtcMask))) :
            payloadFile is not null ? File.ReadAllBytes(payloadFile) :
            Convert.FromHexString(hexPayload!);
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
            if (o.Mode == "exchange")
                PrintObdOnUdsResponse(o.Request, reply);
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
        byte[] Request, int TimeoutMs, bool UseTls, byte Version = 0x02,
        string? ReceiveFile = null);
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

    private static ulong? ParseJ1939Name(string[] args)
    {
        var raw = Optional(args, "--name");
        if (raw is null) return null;
        var hex = raw.StartsWith("0x", StringComparison.OrdinalIgnoreCase) ?
            raw[2..] : raw;
        if (hex.Length != 16 || !hex.All(Uri.IsHexDigit))
            throw new ArgumentException(
                "J1939-81 NAME must be an explicit 16-character hex identifier.");
        var value = ulong.Parse(hex, NumberStyles.AllowHexSpecifier,
            CultureInfo.InvariantCulture);
        if (value == ulong.MaxValue)
            throw new ArgumentException("All-FF J1939 NAME is unassigned.");
        return value;
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
        var hexPayload = Optional(args, "--payload");
        var payloadFile = Optional(args, "--payload-file");
        var obdDid = Optional(args, "--obd-did");
        var obdDtcMask = Optional(args, "--obd-dtc-mask");
        var obdIdentify = args.Contains("--obd-identify",
            StringComparer.OrdinalIgnoreCase);
        if ((hexPayload is null ? 0 : 1) +
            (payloadFile is null ? 0 : 1) +
            (obdDid is null ? 0 : 1) +
            (obdDtcMask is null ? 0 : 1) +
            (obdIdentify ? 1 : 0) != 1)
            throw new ArgumentException(
                "Supply exactly one of --payload, --obd-did or --obd-dtc-mask.");
        if (payloadFile is not null &&
            new FileInfo(payloadFile).Length > DoIpCodec.MaxPayload - 4)
            throw new ArgumentException(
                "DoIP file payload exceeds the bounded in-memory PDU capacity.");
        var bytes = obdIdentify ? ObdOnUdsServices.ReadProtocolIdentification() :
            obdDid is not null ? ObdOnUdsServices.ReadEmissionsData(
                checked((ushort)uint.Parse(
                    obdDid.StartsWith("0x", StringComparison.OrdinalIgnoreCase) ?
                        obdDid[2..] : obdDid,
                    NumberStyles.AllowHexSpecifier, CultureInfo.InvariantCulture))) :
            obdDtcMask is not null ? ObdOnUdsServices.ReadDtcByStatusMask(
                checked((byte)Number(obdDtcMask))) :
            payloadFile is not null ? File.ReadAllBytes(payloadFile) :
            Hex(hexPayload!);
        var timeout = int.Parse(Optional(args, "--timeout-ms") ?? "5000",
            CultureInfo.InvariantCulture);
        var useTls = bool.Parse(Optional(args, "--tls") ?? "false");
        var version = checked((byte)Number(
            Optional(args, "--doip-version") ?? "2"));
        var receiveFile = Optional(args, "--rx-file");
        if (host.Length is < 1 or > 253 || timeout is < 100 or > 30000 ||
            tester == ecu || bytes.Length is < 1 or > DoIpCodec.MaxPayload - 4 ||
            version is not (0x02 or 0x03))
            throw new ArgumentException("DoIP target or diagnostic PDU is invalid.");
        return new(host, tester, ecu, bytes, timeout, useTls,
            version, receiveFile);
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
            (mode == "receive" && init != null) ||
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
                $"v=0x{o.Version:X2} UDS length={o.Request.Length} " +
                $"SHA256={Convert.ToHexString(
                    System.Security.Cryptography.SHA256.HashData(o.Request))}"))
                return 2;
            await using var link = await DoIpDiagnosticTransport.ConnectAsync(
                o.Host, o.Tester, o.Ecu,
                TimeSpan.FromMilliseconds(o.TimeoutMs), CancellationToken.None,
                useTls: o.UseTls, protocolVersion: o.Version);
            var reply = await link.ExchangeUdsAsync(o.Request,
                TimeSpan.FromMilliseconds(o.TimeoutMs), CancellationToken.None);
            var replyHash = Convert.ToHexString(
                System.Security.Cryptography.SHA256.HashData(reply));
            if (o.ReceiveFile is not null)
            {
                // Never replace an existing diagnostic capture or original
                // ECU backup. The path must not exist before this exchange.
                await using var output = new FileStream(o.ReceiveFile,
                    FileMode.CreateNew, FileAccess.Write, FileShare.None);
                await output.WriteAsync(reply);
                await output.FlushAsync();
                output.Flush(flushToDisk: true);
                Console.WriteLine($"DoIP RX saved to {o.ReceiveFile} " +
                    $"bytes={reply.Length} SHA256={replyHash}");
            }
            else if (reply.Length <= 64)
                Console.WriteLine("DoIP ECU RX: " + Convert.ToHexString(reply));
            else
                Console.WriteLine($"DoIP ECU RX: bytes={reply.Length} " +
                    $"SHA256={replyHash} prefix={Convert.ToHexString(reply.AsSpan(0, 64))} " +
                    "(use --rx-file to preserve all bytes).");
            PrintObdOnUdsResponse(o.Request, reply);
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
