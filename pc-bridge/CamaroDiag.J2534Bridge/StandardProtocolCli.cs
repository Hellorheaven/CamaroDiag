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
    internal const string ObdCanDiscoverCommand = "--obd-discover-can";
    internal const string J1979PidDiscoveryCommand = "--j1979-pids";
    internal const string J1979FunctionalDiscoveryCommand = "--j1979-discover-can";
    internal const string ObdProtocolAutoDetectCommand = "--obd-auto-detect-can";
    internal const string PassThruCommand = "--j2534-standard";
    internal const string RawIsoTpCommand = "--isotp-exchange";
    internal const string J1939Command = "--j1939-transport";
    internal const string J1939FdCommand = "--j1939-fd-multipg";
    internal const string J1939FdBamCommand = "--j1939-fd-bam";
    internal const string J1939FdCmdtCommand = "--j1939-fd-cmdt";

    internal sealed record J1979PidDiscoveryOptions(
        int AdapterIndex, uint Baud, IsoTpCanFdSession.Settings Settings,
        byte Mode, byte FreezeFrame, bool Extended, int TimeoutMs);

    internal static J1979PidDiscoveryOptions ParseJ1979PidDiscovery(string[] args)
    {
        var index = int.Parse(Required(args, "--adapter-index"),
            CultureInfo.InvariantCulture);
        static uint CanId(string input)
        {
            var hex = input.StartsWith("0x", StringComparison.OrdinalIgnoreCase) ?
                input[2..] : input;
            if (hex.Length == 0 || !hex.All(Uri.IsHexDigit))
                throw new ArgumentException("CAN arbitration IDs require hexadecimal digits.");
            return uint.Parse(hex, NumberStyles.AllowHexSpecifier,
                CultureInfo.InvariantCulture);
        }
        var tx = CanId(Required(args, "--tx-id"));
        var rx = CanId(Required(args, "--rx-id"));
        var baud = Number(Required(args, "--baud"));
        var mode = checked((byte)Number(Optional(args, "--j1979-mode") ?? "1"));
        var frame = checked((byte)Number(Optional(args, "--freeze-frame") ?? "0"));
        var extended = bool.Parse(Optional(args, "--extended") ?? "false");
        var timeout = int.Parse(Optional(args, "--timeout-ms") ?? "5000",
            CultureInfo.InvariantCulture);
        if (index < 0 || baud == 0 || timeout is < 100 or > 30000 ||
            mode is not (0x01 or 0x02) || (mode == 1 && frame != 0) ||
            tx > (extended ? 0x1FFF_FFFFu : 0x7FFu) ||
            rx > (extended ? 0x1FFF_FFFFu : 0x7FFu) ||
            tx == rx ||
            Optional(args, "--payload") is not null ||
            Optional(args, "--payload-file") is not null)
            throw new ArgumentException("Invalid J1979 physical CAN discovery options.");
        var settings = new IsoTpCanFdSession.Settings(tx, rx, 8, false);
        settings.Validate();
        return new(index, baud, settings, mode, frame, extended, timeout);
    }

    /// <summary>
    /// Explicit physical J1979 discovery for one CAN ECU. No functional
    /// broadcast, mutation, or guessed defaults for the physical addresses.
    /// </summary>
    internal static async Task<int> RunJ1979PidDiscoveryAsync(string[] args)
    {
        try
        {
            var o = ParseJ1979PidDiscovery(args);
            var adapterIndex = o.AdapterIndex;
            var txId = o.Settings.TxId;
            var rxId = o.Settings.RxId;
            var baud = o.Baud;
            var mode = o.Mode;
            var frame = o.FreezeFrame;
            var extended = o.Extended;
            var timeoutMs = o.TimeoutMs;
            var adapters = J2534Registry.Discover();
            if (adapterIndex >= adapters.Count)
                throw new ArgumentOutOfRangeException(nameof(adapterIndex));
            using var driver = J2534StandardChannel.Connect(
                adapters[adapterIndex], J2534StandardChannel.Can,
                baud, extended ? 0x100u : 0u);
            var mask = new byte[4];
            var pattern = new byte[4];
            System.Buffers.Binary.BinaryPrimitives.WriteUInt32BigEndian(
                mask, extended ? 0x1FFF_FFFFu : 0x7FFu);
            System.Buffers.Binary.BinaryPrimitives.WriteUInt32BigEndian(pattern, rxId);
            driver.ConfigureFilter(mask, pattern);
            var link = new IsoTpJ2534RawCanLink(driver,
                canFd: false, extendedId: extended, bitRateSwitch: false);
            var discovery = await ObdJ1979PidDiscovery.DiscoverAsync(
                link, o.Settings, mode, frame,
                TimeSpan.FromMilliseconds(timeoutMs), CancellationToken.None);
            foreach (var page in discovery.Pages)
                Console.WriteLine($"J1979 mode=0x{mode:X2} page=0x{page.RequestedBase:X2} " +
                    $"supported={string.Join(",", page.SupportedPids.Select(x => x.ToString("X2")))} " +
                    $"next={page.HasNextPage}");
            Console.WriteLine($"J1979 ECU RX=0x{rxId:X} pages={discovery.Pages.Count} " +
                $"supported={discovery.SupportedPids.Count}; PID support does not establish scaling, " +
                "write permission, or ECU physical programming capability.");
            return 0;
        }
        catch (Exception ex) when (ex is ArgumentException or InvalidOperationException or
            IOException or InvalidDataException or TimeoutException or DllNotFoundException or
            BadImageFormatException or EntryPointNotFoundException or OverflowException)
        {
            Console.Error.WriteLine("SAE J1979 PID discovery failed: " + ex.Message);
            return 6;
        }
    }

    /// <summary>Read-only functional Mode 01 PID 00 query on 11-bit classic CAN.</summary>
    internal static async Task<int> RunJ1979FunctionalDiscoveryAsync(string[] args)
    {
        try
        {
            var o = ParseObdCanDiscovery(args);
            var adapters = J2534Registry.Discover();
            if (o.AdapterIndex >= adapters.Count)
                throw new ArgumentOutOfRangeException(nameof(o.AdapterIndex));
            using var driver = J2534StandardChannel.Connect(
                adapters[o.AdapterIndex], J2534StandardChannel.Can, o.Baud);
            driver.ConfigureFilter(new byte[4], new byte[4]);
            var link = new IsoTpJ2534RawCanLink(driver,
                canFd: false, extendedId: false, bitRateSwitch: false);
            var found = await ObdJ1979FunctionalCanDiscovery.DiscoverAsync(
                link, TimeSpan.FromMilliseconds(o.TimeoutMs), CancellationToken.None);
            foreach (var ecu in found)
                Console.WriteLine($"J1979 response CAN=0x{ecu.ResponseId:X3} " +
                    $"PID 00 supports={string.Join(",", ecu.SupportedPids.Select(x => x.ToString("X2")))} " +
                    $"next={ecu.NextPage}");
            Console.WriteLine($"J1979 functional Mode 01 PID 00 responders={found.Count}; " +
                "physical ECU identity and sensor support require further verification.");
            return 0;
        }
        catch (Exception ex) when (ex is ArgumentException or
            InvalidOperationException or IOException or InvalidDataException or TimeoutException or
            DllNotFoundException or BadImageFormatException or
            EntryPointNotFoundException or OverflowException)
        {
            Console.Error.WriteLine("J1979 functional CAN discovery failed: " + ex.Message);
            return 6;
        }
    }

    /// <summary>
    /// ISO 15765-4 CAN 11-bit two-stage emissions protocol detection:
    /// functional UDS F810 first, classic J1979 PID 00 only if not confirmed.
    /// This does not authorize or perform any ECU write.
    /// </summary>
    internal static async Task<int> RunObdProtocolAutoDetectAsync(string[] args)
    {
        try
        {
            var o = ParseObdCanDiscovery(args);
            var adapters = J2534Registry.Discover();
            if (o.AdapterIndex >= adapters.Count)
                throw new ArgumentOutOfRangeException(nameof(o.AdapterIndex));
            using var driver = J2534StandardChannel.Connect(
                adapters[o.AdapterIndex], J2534StandardChannel.Can, o.Baud);
            driver.ConfigureFilter(new byte[4], new byte[4]);
            var link = new IsoTpJ2534RawCanLink(driver,
                canFd: false, extendedId: false, bitRateSwitch: false);
            var result = await ObdCanProtocolDetection.DetectAsync(link,
                TimeSpan.FromMilliseconds(o.TimeoutMs), CancellationToken.None);
            Console.WriteLine($"OBD protocol={result.Kind} " +
                $"ECU responders={string.Join(",", result.ResponseIds.Select(x => $"0x{x:X3}"))} " +
                $"classic fallback={result.ClassicFallbackAttempted}; " +
                "no physical ECU programming capability inferred.");
            return result.Kind == ObdCanProtocolDetection.ProtocolKind.NoConfirmedProtocol
                ? 7 : 0;
        }
        catch (Exception ex) when (ex is ArgumentException or
            InvalidOperationException or IOException or InvalidDataException or TimeoutException or
            DllNotFoundException or BadImageFormatException or
            EntryPointNotFoundException or OverflowException)
        {
            Console.Error.WriteLine("OBD CAN protocol detection failed: " + ex.Message);
            return 6;
        }
    }

    internal sealed record ObdCanDiscoveryOptions(
        int AdapterIndex, uint Baud, int TimeoutMs);

    internal static ObdCanDiscoveryOptions ParseObdCanDiscovery(string[] args)
    {
        var index = int.Parse(Required(args, "--adapter-index"),
            CultureInfo.InvariantCulture);
        var baud = Number(Required(args, "--baud"));
        var timeout = int.Parse(Optional(args, "--timeout-ms") ?? "2500",
            CultureInfo.InvariantCulture);
        if (index < 0 || baud == 0 || timeout is < 100 or > 30000 ||
            Optional(args, "--payload") is not null ||
            Optional(args, "--payload-file") is not null)
            throw new ArgumentException(
                "OBDonUDS functional CAN discovery accepts only adapter, rate and timeout.");
        return new(index, baud, timeout);
    }

    internal static async Task<int> RunObdCanDiscoveryAsync(string[] args)
    {
        try
        {
            var o = ParseObdCanDiscovery(args);
            var adapters = J2534Registry.Discover();
            if (o.AdapterIndex >= adapters.Count)
                throw new ArgumentOutOfRangeException(nameof(o.AdapterIndex));
            using var driver = J2534StandardChannel.Connect(
                adapters[o.AdapterIndex], J2534StandardChannel.Can, o.Baud);
            // Multiple physical responders (7E8..7EF) require a broad
            // passive J2534 pass filter. The only TX PDU is fixed 22F810.
            driver.ConfigureFilter(new byte[4], new byte[4]);
            var link = new IsoTpJ2534RawCanLink(driver,
                canFd: false, extendedId: false,
                bitRateSwitch: false);
            var found = await ObdOnUdsCanDiscovery.DiscoverAsync(link,
                TimeSpan.FromMilliseconds(o.TimeoutMs),
                CancellationToken.None);
            foreach (var ecu in found)
                Console.WriteLine(
                    $"OBDonUDS physical RX=0x{ecu.ResponseId:X3} F810=" +
                    $"0x{ecu.ProtocolValue:X2} " +
                    $"status={(ecu.IsObdOnUds ? "identified" : "not confirmed")}");
            Console.WriteLine($"OBDonUDS found={found.Count}; only F810 is probed. " +
                "No writing, ECU programming or manufacturer permissions inferred.");
            return 0;
        }
        catch (Exception ex) when (ex is ArgumentException or
            InvalidOperationException or IOException or InvalidDataException or TimeoutException or
            DllNotFoundException or BadImageFormatException or
            EntryPointNotFoundException or OverflowException)
        {
            Console.Error.WriteLine("OBDonUDS CAN discovery failed: " + ex.Message);
            return 6;
        }
    }

    internal static async Task<int> RunDoIpStatusAsync(string[] args)
    {
        try
        {
            ValidateDoIpArguments(args, "--doip-status",
                ["--host", "--power-mode", "--timeout-ms", "--doip-version", "--trace-file"], []);
            var host = Required(args, "--host");
            var power = bool.Parse(Optional(args, "--power-mode") ?? "false");
            var timeoutMs = int.Parse(Optional(args, "--timeout-ms") ?? "2000",
                CultureInfo.InvariantCulture);
            var version = checked((byte)Number(
                Optional(args, "--doip-version") ?? "2"));
            using var journal = OpenDoIpUdpTrace(args);
            var status = await DoIpVehicleDiscovery.QueryStatusAsync(
                host, power, TimeSpan.FromMilliseconds(timeoutMs),
                CancellationToken.None, protocolVersion: version,
                trace: journal is null ? null : journal.RecordDatagram);
            Console.WriteLine(DoIpConsolePresentation.FormatStatus(status, power));
            Console.WriteLine(DoIpConsolePresentation.Text("StatusNotice"));
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
            ValidateDoIpArguments(args, "--doip-discover", ["--host", "--broadcast-ip",
                "--listen", "--listen-ipv6-ifindex", "--multicast-ipv6-ifindex", "--timeout-ms",
                "--vin", "--eid", "--trace-file"], []);
            var host = Optional(args, "--host");
            var broadcastIp = Optional(args, "--broadcast-ip");
            var passiveListen = bool.Parse(Optional(args, "--listen") ?? "false");
            var ipv6InterfaceText = Optional(args, "--listen-ipv6-ifindex");
            var ipv6Interface = ipv6InterfaceText is null ? (int?)null :
                int.Parse(ipv6InterfaceText, CultureInfo.InvariantCulture);
            var activeIpv6Text = Optional(args, "--multicast-ipv6-ifindex");
            var activeIpv6Interface = activeIpv6Text is null ? (int?)null :
                int.Parse(activeIpv6Text, CultureInfo.InvariantCulture);
            if (ipv6Interface.HasValue && !passiveListen)
                throw new ArgumentException(
                    "IPv6 DoIP passive interface requires --listen true.");
            var destinations = (host is null ? 0 : 1) +
                (broadcastIp is null ? 0 : 1) +
                (activeIpv6Interface.HasValue ? 1 : 0);
            if (passiveListen ?
                destinations != 0 :
                destinations != 1 || ipv6Interface.HasValue)
                throw new ArgumentException(
                    "Listen mode needs no destination; active DoIP discovery requires exactly one host, broadcast or IPv6 multicast interface.");
            var timeoutMs = int.Parse(Optional(args, "--timeout-ms") ?? "2000",
                CultureInfo.InvariantCulture);
            var requestedVin = Optional(args, "--vin");
            var eidHex = Optional(args, "--eid");
            if (requestedVin is not null && eidHex is not null)
                throw new ArgumentException("Choose VIN or EID, not both.");
            var eid = eidHex is null ? null : Convert.FromHexString(eidHex);
            var timeout = TimeSpan.FromMilliseconds(timeoutMs);
            using var journal = OpenDoIpUdpTrace(args);
            DoIpVehicleDiscovery.DatagramTrace? trace = journal is null ? null : journal.RecordDatagram;
            IReadOnlyList<DoIpVehicleDiscovery.Identity> identities;
            if (passiveListen)
            {
                if (requestedVin is not null || eid is not null)
                    throw new ArgumentException(
                        "Passive DoIP listening cannot apply an outgoing VIN/EID request.");
                identities = ipv6Interface.HasValue ?
                    await DoIpVehicleDiscovery.ListenIpv6MulticastAsync(
                        ipv6Interface.Value, timeout, CancellationToken.None, trace: trace) :
                    await DoIpVehicleDiscovery.ListenAsync(
                        timeout, CancellationToken.None, trace: trace);
            }
            else if (activeIpv6Interface.HasValue)
                identities = await DoIpVehicleDiscovery.DiscoverIpv6MulticastAsync(
                    activeIpv6Interface.Value, timeout, CancellationToken.None,
                    vin: requestedVin, eid: eid, trace: trace);
            else if (broadcastIp is not null)
                identities = await DoIpVehicleDiscovery.DiscoverBroadcastAsync(
                    broadcastIp, timeout, CancellationToken.None,
                    vin: requestedVin, eid: eid, trace: trace);
            else
                identities = [await DoIpVehicleDiscovery.DiscoverAsync(
                    host!, timeout, CancellationToken.None,
                    vin: requestedVin, eid: eid, trace: trace)];
            if (identities.Count == 0)
                throw new TimeoutException("No DoIP gateway identity received.");
            foreach (var identity in identities)
                Console.WriteLine(DoIpConsolePresentation.FormatIdentity(identity));
            Console.WriteLine(DoIpConsolePresentation.Text("Notice"));
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

    private static void PrintMultiDidResponse(
        string[] args, byte[] request, byte[] response)
    {
        var specification = Optional(args, "--uds-dids");
        if (specification is null) return;
        var entries = UdsMultiDidCli.Parse(specification);
        var results = UdsMultiDidCli.Decode(entries, request, response);
        foreach (var item in results)
            Console.WriteLine($"UDS multi-DID=0x{item.Did:X4} " +
                $"raw={Convert.ToHexString(item.Data)} " +
                "(scaling and meaning require vehicle/ODX metadata).");
    }

    private static void PrintObdOnUdsResponse(byte[] request, byte[] response, int? extendedRecordSize = null)
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
            else if (did == ObdOnUdsVehicleIdentification.VinDid &&
                response.Length > 0 && response[0] == 0x62)
            {
                var vin = ObdOnUdsVehicleIdentification.DecodeVin(response);
                Console.WriteLine($"OBDonUDS F802 VIN={vin}");
            }
            else if (ObdOnUdsServices.ClassifyDid(did) !=
                    ObdOnUdsServices.DataGroup.NotLegislated &&
                response.Length > 0 && response[0] == 0x62)
            {
                var raw = ObdOnUdsServices.DecodeReadDid(did, response);
                Console.WriteLine($"OBDonUDS DID=0x{did:X4} " +
                    $"category={ObdOnUdsServices.ClassifyDid(did)} " +
                    $"raw={Convert.ToHexString(raw)} " +
                    "(physical units and availability require SAE J1979DA).");
            }
        }
        else if (request.Length >= 2 && request[0] == 0x19 && request[1] is >= 7 and <= 9)
        {
            var report = ObdOnUdsServices.DecodeSeverity(request, response);
            Console.WriteLine(System.Text.Json.JsonSerializer.Serialize(new
            {
                severity_subfunction = report.Subfunction, availability_mask = report.AvailabilityMask,
                count = report.Count, format_identifier = report.FormatIdentifier,
                records = report.Records.Select(r => new { dtc = $"{r.Dtc:X6}", severity = r.Severity,
                    functional_unit = r.FunctionalUnit, status = r.Status })
            }));
        }
        else if (request.SequenceEqual(ObdOnUdsServices.ReadFaultCounters()))
        {
            var rows = ObdOnUdsServices.DecodeFaultCounters(response);
            Console.WriteLine(System.Text.Json.JsonSerializer.Serialize(new
            {
                fault_counters = rows.Select(r => new { dtc = $"{r.Dtc:X6}", counter = r.Counter })
            }));
        }
        else if (request.Length == 2 && request[0] == 0x19 && request[1] is >= 0x0B and <= 0x0E)
        {
            var report = ObdOnUdsServices.DecodeHistoricalDtc(request[1], response);
            Console.WriteLine(System.Text.Json.JsonSerializer.Serialize(new
            {
                historical_subfunction = request[1], availability_mask = report.AvailabilityMask,
                records = report.Codes.Select(c => new { dtc = $"{c.Code:X6}", status = c.Status })
            }));
        }
        else if (request.SequenceEqual(ObdOnUdsServices.ReadSupportedDtcs()))
        {
            var report = ObdOnUdsServices.DecodeSupportedDtcs(response);
            Console.WriteLine($"Supported DTCs={report.Codes.Count} available-mask=0x{report.AvailabilityMask:X2}");
            foreach (var code in report.Codes)
                Console.WriteLine($"DTC=0x{code.Code:X6} status=0x{code.Status:X2}");
        }
        else if (request.Length >= 2 && request[0] == 0x19 && request[1] is >= 0x0F and <= 0x13)
        {
            var report = ObdOnUdsServices.DecodeLegacyDtc(request, response);
            Console.WriteLine(System.Text.Json.JsonSerializer.Serialize(new
            {
                subfunction = report.Subfunction, legacy = true, scope = report.Subfunction <= 0x11 ? "mirror" : "emissions",
                availability_mask = report.List?.AvailabilityMask ?? report.Count?.StatusAvailabilityMask,
                format = report.Count?.DtcFormatIdentifier, count = report.Count?.Count,
                records = report.List?.Codes.Select(r => new { dtc = $"{r.Code:X6}", status = r.Status }),
                dtc = report.Detail?.Dtc.ToString("X6"), status = report.Detail?.Status,
                raw_records = report.Detail is null ? null : Convert.ToHexString(report.Detail.RawExtendedDataRecords)
            }));
        }
        else if (request.Length == 3 && request[0] == 0x19 && request[1] == 5 && request[2] != 255)
        {
            var report = ObdOnUdsServices.DecodeStoredRecord(request[2], response);
            Console.WriteLine(System.Text.Json.JsonSerializer.Serialize(new
            {
                subfunction = 5, record = report.Record, present = report.Dtc.HasValue,
                dtc = report.Dtc?.ToString("X6"), status = report.Status,
                did_count = report.Dtc.HasValue ? (int?)report.RawData[0] : null,
                raw_stored_data = Convert.ToHexString(report.RawData),
                raw_response = Convert.ToHexString(report.RawResponse)
            }));
        }
        else if (request.Length == 3 && request[0] == 0x19 &&
            (request[1] == 0x1A || (request[1] == 0x16 && extendedRecordSize.HasValue)))
        {
            var report = ObdOnUdsServices.DecodeExtendedRecord(request[2], extendedRecordSize, response);
            Console.WriteLine(System.Text.Json.JsonSerializer.Serialize(new
            {
                subfunction = request[1], record = report.Record, availability_mask = report.Availability,
                record_size = extendedRecordSize, records = report.Entries.Select(r => new
                { dtc = $"{r.Dtc:X6}", status = r.Status, data = extendedRecordSize.HasValue ? Convert.ToHexString(r.Data) : null })
            }));
        }
        else if (request.Length >= 2 && request[0] == 0x19 && request[1] is >= 0x17 and <= 0x19)
        {
            var report = ObdOnUdsServices.DecodeUserMemory(request, response);
            Console.WriteLine(System.Text.Json.JsonSerializer.Serialize(new
            {
                subfunction = request[1], memory = report.Memory, availability_mask = report.List?.AvailabilityMask,
                records = report.List?.Codes.Select(c => new { dtc = $"{c.Code:X6}", status = c.Status }),
                dtc = report.Dtc?.ToString("X6"), status = report.Status,
                raw_records = report.RawRecords is null ? null : Convert.ToHexString(report.RawRecords)
            }));
        }
        else if (request.SequenceEqual(ObdOnUdsServices.ReadPermanentDtcs()))
        {
            var report = ObdOnUdsServices.DecodePermanentDtcs(response);
            Console.WriteLine(System.Text.Json.JsonSerializer.Serialize(new
            {
                kind = "permanent-dtcs", availability_mask = report.AvailabilityMask,
                records = report.Codes.Select(c => new { dtc = $"{c.Code:X6}", status = c.Status })
            }));
        }
        else if (request.SequenceEqual(ObdOnUdsServices.ReadSnapshotIndex()))
        {
            var rows = ObdOnUdsServices.DecodeSnapshotIndex(response);
            Console.WriteLine($"Snapshot references={rows.Count}");
            foreach (var row in rows)
                Console.WriteLine($"DTC=0x{row.Dtc:X6} record=0x{row.Record:X2}");
        }
        else if (request.Length == 5 && request[0] == 0x19 &&
            request[1] == 0x42 && response.Length > 0 && response[0] == 0x59)
        {
            var report = ObdOnUdsServices.DecodeWwhEmissionsDtcs(response);
            Console.WriteLine($"WWH-OBD emissions DTC 0x42 " +
                $"statusMask=0x{report.StatusAvailabilityMask:X2} " +
                $"severityMask=0x{report.SeverityAvailabilityMask:X2} " +
                $"format=0x{report.DtcFormatIdentifier:X2} count={report.Codes.Count}");
            foreach (var dtc in report.Codes)
                Console.WriteLine($"DTC=0x{dtc.Code:X6} " +
                    $"severity=0x{dtc.Severity:X2} status=0x{dtc.Status:X2}");
        }
        else if (request.Length == 6 && request[0] == 0x19 &&
            request[1] == 0x06 && response.Length > 0 && response[0] == 0x59)
        {
            var requested = (uint)request[2] << 16 |
                (uint)request[3] << 8 | request[4];
            var extended = ObdOnUdsServices.DecodeDtcExtendedData(
                requested, response);
            Console.WriteLine($"UDS extended DTC=0x{extended.Dtc:X6} " +
                $"status=0x{extended.Status:X2} " +
                $"recordsRaw={Convert.ToHexString(extended.RawExtendedDataRecords)} " +
                "(record layouts require vehicle data definitions).");
        }
        else if (request.Length == 6 && request[0] == 0x19 &&
            request[1] == 0x04 && response.Length > 0 && response[0] == 0x59)
        {
            var expectedDtc = (uint)request[2] << 16 |
                (uint)request[3] << 8 | request[4];
            var snapshot = ObdOnUdsServices.DecodeDtcSnapshot(expectedDtc, response);
            Console.WriteLine($"UDS snapshot DTC=0x{snapshot.Dtc:X6} " +
                $"status=0x{snapshot.Status:X2} " +
                $"recordsRaw={Convert.ToHexString(snapshot.RawSnapshotRecords)} " +
                "(DID values require ECU/ODX metadata).");
        }
        else if (request.Length == 4 && request[0] == 0x19 &&
            request[1] == 0x56 && response.Length > 0 && response[0] == 0x59)
        {
            var report = ObdOnUdsServices.DecodeWwhReadinessGroup(response);
            if (report.ReadinessGroup != request[3])
                throw new InvalidDataException(
                    "WWH-OBD readiness response does not match the requested group.");
            Console.WriteLine($"WWH-OBD readiness group=0x{report.ReadinessGroup:X2} " +
                $"statusMask=0x{report.DtcReport.StatusAvailabilityMask:X2} " +
                $"format=0x{report.DtcReport.DtcFormatIdentifier:X2} " +
                $"count={report.DtcReport.Codes.Count}");
            foreach (var dtc in report.DtcReport.Codes)
                Console.WriteLine($"DTC=0x{dtc.Code:X6} status=0x{dtc.Status:X2}");
        }
        else if (request.Length == 3 && request[0] == 0x19 &&
            request[1] == 0x55 && response.Length > 0 && response[0] == 0x59)
        {
            var report = ObdOnUdsServices.DecodeWwhPermanentEmissionsDtcs(response);
            Console.WriteLine($"WWH-OBD permanent emissions DTC 0x55 " +
                $"format=0x{report.DtcFormatIdentifier:X2} count={report.Codes.Count}");
            foreach (var dtc in report.Codes)
                Console.WriteLine($"DTC=0x{dtc.Code:X6} status=0x{dtc.Status:X2}");
        }
        else if (request.Length == 3 && request[0] == 0x19 &&
            request[1] == 0x01 && response.Length > 0 && response[0] == 0x59)
        {
            var count = ObdOnUdsServices.DecodeDtcCountByStatusMask(response);
            Console.WriteLine($"UDS DTC count by status mask=0x{request[2]:X2} " +
                $"available-mask=0x{count.StatusAvailabilityMask:X2} " +
                $"format=0x{count.DtcFormatIdentifier:X2} count={count.Count} " +
                "(DTC count does not prove vehicle readiness).");
        }
        else if (request.Length == 3 && request[0] == 0x19 &&
            request[1] == 0x02 && response.Length > 0 && response[0] == 0x59)
        {
            var report = ObdOnUdsServices.DecodeDtcByStatusMask(request[2], response);
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
        ulong? Name = null, bool FastBroadcast = false,
        IReadOnlyList<J1939FdMultiPg.ContainedGroup>? Groups = null)
    {
        internal uint J2534ConnectFlags => FastBroadcast ? 0u : 0x100u;
    }

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
        var fbff = bool.Parse(Optional(args, "--fbff") ?? "false");
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
            (fbff ? "0" : bam || cmdt ? "7" : "6")));
        var pgnText = Optional(args, "--pgn");
        var groupText = Optional(args, "--groups");
        var groups = groupText is null ? null :
            J1939FdMultiPgCliParser.Parse(groupText);
        int? pgn = pgnText is null ? null : checked((int)Number(pgnText));
        var mode = Required(args, "--mode").ToLowerInvariant();
        var payloadText = Optional(args, "--payload");
        var payloadFile = Optional(args, "--payload-file");
        if ((payloadText is null ? 0 : 1) +
            (payloadFile is null ? 0 : 1) +
            (groups is null ? 0 : 1) > 1)
            throw new ArgumentException(
                "Choose exactly one J1939-22 payload source, file or grouped C-PGs.");
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
            (mode == "send" && (groups is null &&
                (pgn is null ||
                    (bam || cmdt ?
                        payload.Length is <= 60 or > J1939FdBamSession.MaximumPayload :
                        payload.Length is < 1 or > 60)))) ||
            (groups is not null && (mode != "send" || !multiPg ||
                pgn is not null || payload.Length != 0)) ||
            (mode == "receive" && (payloadText is not null || payloadFile is not null)) ||
            (bam && (pgn is null || session > 3 ||
                (mode == "send" && target != 0xFF))) ||
            (cmdt && (pgn is null || session > 7 ||
                target >= 0xFE || window == 0)) ||
            (!bam && !cmdt && Optional(args, "--session") is not null) ||
            (!cmdt && Optional(args, "--window") is not null) ||
            (fbff && (!multiPg || name.HasValue ||
                priority != 0 || (mode == "send" && target != 0xFF))) ||
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
        // Build the final CAN-FD payload before connecting to a vendor
        // adapter, so an oversized group collection cannot send anything.
        if (groups is not null)
        {
            if (fbff)
                _ = J1939FdMultiPg.EncodeFastBroadcast(source, groups);
            else
                _ = J1939FdMultiPg.Encode(source, target, priority, groups);
        }
        return new(index, baud, dataBaud, brs, source, target,
            priority, pgn, mode, payload, timeout, bam, session, cmdt, window,
            name, fbff, groups);
    }

    internal static async Task<int> RunJ1939FdAsync(string[] args)
    {
        try
        {
            var o = ParseJ1939Fd(args);
            var adapters = J2534Registry.Discover();
            if (o.AdapterIndex >= adapters.Count)
                throw new ArgumentOutOfRangeException(nameof(o.AdapterIndex));
            var txPreview = o.Groups is null ? o.Payload :
                (o.FastBroadcast ?
                    J1939FdMultiPg.EncodeFastBroadcast(o.Source, o.Groups).Data :
                    J1939FdMultiPg.Encode(
                        o.Source, o.Target, o.Priority, o.Groups).Data);
            var groupNames = o.Groups is null ?
                $"0x{o.Pgn:X}" :
                string.Join(",", o.Groups.Select(g => $"0x{g.Pgn:X}"));
            if ((o.Mode == "send" || o.Name.HasValue) &&
                !Confirm($"SAE J1939-22 {(o.Bam ? "FD.TP BAM" : o.Cmdt ? "FD.TP CMDT" :
                        o.FastBroadcast ? "FBFF Multi-PG" : "FEFF Multi-PG")} " +
                    $"TX source=0x{o.Source:X2} destination=0x{o.Target:X2} " +
                    $"NAME={(o.Name.HasValue ? $"0x{o.Name.Value:X16} CLAIM" : "not claimed here")} " +
                    $"PGNs={groupNames} frame-or-payload-bytes={txPreview.Length} " +
                    $"SHA256={Convert.ToHexString(
                        System.Security.Cryptography.SHA256.HashData(txPreview))}"))
                return 2;

            // Raw FD_CAN: FEFF and FD.TP require 29-bit IDs; FBFF
            // requires a genuinely standard 11-bit channel and flags.
            // Do not open the channel in 29-bit mode then try to transmit
            // an 11-bit FBFF data frame through it.
            using var driver = J2534StandardChannel.Connect(
                adapters[o.AdapterIndex], J2534StandardChannel.FdCan,
                o.Bitrate, flags: o.J2534ConnectFlags);
            if (o.DataBaud.HasValue)
                driver.SetCanFdDataPhaseRate(o.DataBaud.Value);
            driver.ConfigureFilter(new byte[4], new byte[4]);
            var link = new IsoTpJ2534RawCanLink(driver, canFd: true,
                extendedId: !o.FastBroadcast, bitRateSwitch: o.Brs);
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
                    var groups = o.Groups ?? new[]
                    {
                        new J1939FdMultiPg.ContainedGroup(
                            o.Pgn!.Value, o.Payload)
                    };
                    var packet = o.FastBroadcast ?
                        J1939FdMultiPg.EncodeFastBroadcast(o.Source, groups) :
                        J1939FdMultiPg.Encode(
                            o.Source, o.Target, o.Priority, groups);
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
                if (!frame.IsFd || frame.Id > 0x1FFFFFFF)
                    continue;
                if (o.FastBroadcast)
                {
                    if (frame.Id != o.Target)
                        continue;
                }
                else
                {
                    var id = StandardVehicleProtocols.ParseJ1939Id(frame.Id);
                    if (id.Pgn != J1939FdMultiPg.MultiPgPgn ||
                        id.Source != o.Target ||
                        (id.Destination != o.Source && id.Destination != 0xFF))
                        continue;
                }
                var received = J1939FdMultiPg.Decode(frame);
                if (received.FastBroadcast != o.FastBroadcast)
                    continue;
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
        string Mode, bool ValidateUds = false, bool InterpretJ1979 = false,
        string? TraceFile = null, int UdsP2Ms = 30000,
        int UdsP2StarMs = 30000)
    {
        // SAE J2534-1 CAN pass-filter mask/pattern TxFlags must indicate
        // whether the RX arbitration identifier uses 29-bit formatting.
        internal uint FilterTxFlags => ExtendedId ? 0x100u : 0u;
    }

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
        var flowControlTimeout = int.Parse(
            Optional(args, "--n-bs-ms") ?? "1000",
            CultureInfo.InvariantCulture);
        var consecutiveFrameTimeout = int.Parse(
            Optional(args, "--n-cr-ms") ?? "1000",
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
        var udsValidate = bool.Parse(Optional(args, "--uds-validate") ?? "false");
        var p2Text = Optional(args, "--uds-p2-ms");
        var p2StarText = Optional(args, "--uds-p2-star-ms");
        var p2Ms = int.Parse(p2Text ?? "30000", CultureInfo.InvariantCulture);
        var p2StarMs = int.Parse(p2StarText ?? "30000", CultureInfo.InvariantCulture);
        if (p2Ms is < 1 or > 30000 || p2StarMs is < 1 or > 30000)
            throw new ArgumentOutOfRangeException("UDS P2/P2* must be 1..30000 ms on this CLI.");
        var interpretJ1979 = bool.Parse(Optional(args, "--j1979-decode") ?? "false");
        var traceFile = Optional(args, "--trace-file");
        if (traceFile is not null && string.IsNullOrWhiteSpace(traceFile))
            throw new ArgumentException("ISO-TP trace file path cannot be empty.");
        var hexPayload = Optional(args, "--payload");
        var payloadFile = Optional(args, "--payload-file");
        var udsDids = Optional(args, "--uds-dids");
        var obdDid = Optional(args, "--obd-did");
        var obdDtcMask = Optional(args, "--obd-dtc-mask");
        var obdDtcCountMask = Optional(args, "--obd-dtc-count-mask");
        var wwhStatus = Optional(args, "--obd-wwh-status");
        var wwhReadiness = Optional(args, "--obd-readiness-group");
        var snapshotDtc = Optional(args, "--obd-snapshot");
        var snapshotRecord = Optional(args, "--obd-snapshot-record");
        var extendedDtc = Optional(args, "--obd-extended-dtc");
        var extendedRecord = Optional(args, "--obd-extended-record");
        if (snapshotRecord is not null && snapshotDtc is null)
            throw new ArgumentException("DTC snapshot record option requires --obd-snapshot.");
        if (extendedRecord is not null && extendedDtc is null)
            throw new ArgumentException("DTC extended-data record option requires --obd-extended-dtc.");
        var wwhSeverity = Optional(args, "--obd-wwh-severity");
        var wwhPermanent = args.Contains("--obd-wwh-permanent",
            StringComparer.OrdinalIgnoreCase);
        if (wwhSeverity is not null && wwhStatus is null)
            throw new ArgumentException(
                "WWH severity mask requires --obd-wwh-status.");
        var obdIdentify = args.Contains("--obd-identify",
            StringComparer.OrdinalIgnoreCase);
        var payloadSources = (hexPayload is null ? 0 : 1) +
            (payloadFile is null ? 0 : 1) +
            (udsDids is null ? 0 : 1) +
            (obdDid is null ? 0 : 1) +
            (obdDtcMask is null ? 0 : 1) +
            (obdDtcCountMask is null ? 0 : 1) +
            (wwhStatus is null ? 0 : 1) +
            (wwhReadiness is null ? 0 : 1) +
            (snapshotDtc is null ? 0 : 1) +
            (extendedDtc is null ? 0 : 1) +
            (wwhPermanent ? 1 : 0) +
            (obdIdentify ? 1 : 0);
        if (mode is not ("exchange" or "receive") ||
            (mode == "exchange" && payloadSources != 1) ||
            (mode == "receive" && (payloadSources != 0 || udsValidate || interpretJ1979)) ||
            (interpretJ1979 && (udsValidate || fd ||
                (hexPayload is null && payloadFile is null))))
            throw new ArgumentException(
                "Exchange needs exactly one payload, file, OBD DID or DTC mask; receive mode accepts none.");
        byte[] bytes = mode == "receive" ? Array.Empty<byte>() :
            obdIdentify ? ObdOnUdsServices.ReadProtocolIdentification() :
            udsDids is not null ? UdsMultiDidCli.BuildRequest(
                UdsMultiDidCli.Parse(udsDids)) :
            obdDid is not null ? ObdOnUdsServices.ReadObdDid(
                checked((ushort)ParseCanId(obdDid))) :
            obdDtcMask is not null ? ObdOnUdsServices.ReadDtcByStatusMask(
                checked((byte)Number(obdDtcMask))) :
            obdDtcCountMask is not null ? ObdOnUdsServices.ReadDtcCountByStatusMask(
                checked((byte)Number(obdDtcCountMask))) :
            wwhStatus is not null ? ObdOnUdsServices.ReadWwhEmissionsDtcs(
                checked((byte)Number(wwhStatus)),
                wwhSeverity is null ? (byte)0xFF :
                    checked((byte)Number(wwhSeverity))) :
            wwhPermanent ? ObdOnUdsServices.ReadWwhPermanentEmissionsDtcs() :
            wwhReadiness is not null ? ObdOnUdsServices.ReadWwhReadinessGroup(
                checked((byte)Number(wwhReadiness))) :
            snapshotDtc is not null ? ObdOnUdsServices.ReadDtcSnapshot(
                Number(snapshotDtc), snapshotRecord is null ?
                    (byte)1 : checked((byte)Number(snapshotRecord))) :
            extendedDtc is not null ? ObdOnUdsServices.ReadDtcExtendedData(
                Number(extendedDtc), extendedRecord is null ?
                    (byte)0xFE : checked((byte)Number(extendedRecord))) :
            payloadFile is not null ? File.ReadAllBytes(payloadFile) :
            Convert.FromHexString(hexPayload!);
        // SAE J1979 classic and SAE J1979-2 OBDonUDS are different
        // application protocols. A decoder flag must not reinterpret a UDS
        // selector as a legacy emissions mode or silently invent support.
        if (interpretJ1979 && (bytes.Length == 0 ||
            bytes[0] is < 0x01 or > 0x0A))
            throw new ArgumentException(
                "Classic SAE J1979 decoding requires a service 01..0A payload.");

        var setting = new IsoTpCanFdSession.Settings(tx, rx, length, fd,
            bs, stMin, TxAddress: txAddress, RxAddress: rxAddress,
            MaxWaitFrames: maxWaitFrames, MixedAddressing: mixedAddressing,
            FlowControlTimeoutMs: flowControlTimeout,
            ConsecutiveFrameTimeoutMs: consecutiveFrameTimeout);
        setting.Validate();
        if (index < 0 || bitrate == 0 || timeout is < 100 or > 30000 ||
            (mode == "exchange" && bytes.Length is < 1 or > IsoTpCanFdSession.MaximumPayloadBytes) ||
            (brs && (!dataBaud.HasValue || dataBaud.Value == 0)) ||
            (dataBaud.HasValue && (!fd || dataBaud.Value == 0)) ||
            tx > (extended ? 0x1FFF_FFFFu : 0x7FFu) ||
            rx > (extended ? 0x1FFF_FFFFu : 0x7FFu) ||
            (!fd && brs))
            throw new ArgumentException("Invalid ISO-TP physical address or J2534 option.");
        var validateUds = udsValidate || obdDid is not null ||
            udsDids is not null || obdDtcMask is not null ||
            obdDtcCountMask is not null || wwhStatus is not null ||
            wwhPermanent || wwhReadiness is not null ||
            snapshotDtc is not null || extendedDtc is not null || obdIdentify;
        if ((p2Text is not null || p2StarText is not null) &&
            (mode != "exchange" || !validateUds))
            throw new ArgumentException(
                "UDS P2/P2* options require a validated UDS exchange.");
        return new(index, bitrate, setting, bytes, timeout, extended, brs, dataBaud,
            mode, validateUds, interpretJ1979, traceFile, p2Ms, p2StarMs);
    }

    internal static TimeSpan RemainingIsoTpExchangeBudget(
        TimeSpan limit, TimeSpan elapsed)
    {
        var remaining = limit - elapsed;
        if (remaining <= TimeSpan.Zero)
            throw new TimeoutException("ISO-TP exchange deadline exceeded.");
        return remaining;
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
            // CAN_29BIT_ID applies to both J2534 filter mask and pattern.
            // Omitting it on a 29-bit channel can silently drop ECU replies.
            driver.ConfigureFilter(mask, pattern, txFlags: o.FilterTxFlags);
            var rawLink = new IsoTpJ2534RawCanLink(driver, o.Settings.CanFd,
                o.ExtendedId, o.Brs);
            // Opt-in local trace; CreateNew preserves older bench evidence.
            // Host-clock timestamps do not represent physical CAN timing.
            using var traceStream = o.TraceFile is null ? null : new FileStream(
                o.TraceFile, FileMode.CreateNew, FileAccess.Write, FileShare.Read);
            using var traceWriter = traceStream is null ? null : new StreamWriter(
                traceStream, new System.Text.UTF8Encoding(false))
                { AutoFlush = true };
            IsoTpCanFdSession.ICanLink link = traceWriter is null ?
                rawLink : new IsoTpHostTraceLink(rawLink, traceWriter);
            var limit = TimeSpan.FromMilliseconds(o.TimeoutMs);
            // The CLI request/reply budget includes TX and all RX frames.
            // Neither the send phase nor a series of 0x78 pending replies
            // restarts the selected operator timeout.
            var elapsed = System.Diagnostics.Stopwatch.StartNew();
            if (o.Mode == "exchange")
                await IsoTpCanFdSession.SendAsync(link, o.Settings, o.Request,
                    limit, CancellationToken.None);
            var remainingBudget = RemainingIsoTpExchangeBudget(
                limit, elapsed.Elapsed);
            var reply = o.Mode == "exchange" && o.ValidateUds ?
                await UdsResponseSequence.AwaitFinalAsync(
                    o.Request,
                    (remaining, token) => IsoTpCanFdSession.ReceiveAsync(
                        link, o.Settings, remaining, token),
                    remainingBudget, CancellationToken.None,
                    TimeSpan.FromMilliseconds(o.UdsP2Ms),
                    TimeSpan.FromMilliseconds(o.UdsP2StarMs)) :
                await IsoTpCanFdSession.ReceiveAsync(link, o.Settings,
                    remainingBudget, CancellationToken.None);
            _ = RemainingIsoTpExchangeBudget(limit, elapsed.Elapsed);
            Console.WriteLine("ISO-TP ECU RX: " + Convert.ToHexString(reply));
            if (o.InterpretJ1979)
            {
                var decoded = ObdJ1979Interpretation.Interpret(o.Request, reply);
                Console.WriteLine(decoded.Description);
                if (!decoded.Positive) return 7;
            }
            if (o.Mode == "exchange")
            {
                PrintMultiDidResponse(args, o.Request, reply);
                PrintObdOnUdsResponse(o.Request, reply);
            }
            return 0;
        }
        catch (Exception ex) when (ex is ArgumentException or IOException or
            InvalidOperationException or TimeoutException or
            DllNotFoundException or BadImageFormatException or
            EntryPointNotFoundException or OverflowException or InvalidDataException or
            UnauthorizedAccessException)
        {
            Console.Error.WriteLine("ISO-TP exchange failed: " + ex.Message);
            return 6;
        }
    }

    internal sealed record DoIpOptions(string Host, ushort Tester, ushort Ecu,
        byte[] Request, int TimeoutMs, bool UseTls, byte Version = 0x02,
        string? ReceiveFile = null,
        string? ClientCertificateThumbprint = null,
        int DoIpP2Ms = 30000, int DoIpP2StarMs = 30000,
        byte RoutingActivationType = 0x00, byte[]? RoutingOemData = null, string? TraceFile = null);
    internal sealed record PassThruOptions(int AdapterIndex, uint ProtocolId,
        uint Baud, uint Flags, byte[] Data, byte[]? Mask, byte[]? Pattern,
        byte[]? Flow, string? KlineInit, int TimeoutMs, int ReadFrames,
        byte? KwpRxTarget, byte? KwpRxSource, string Mode,
        bool KwpFunctionalRx = false, uint? SwCanHighSpeedBaud = null,
        bool J1850WireCrc = false, byte[]? KwpRequest = null,
        int KwpEchoBytes = 0);

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
        var hex = value.StartsWith("0x", StringComparison.OrdinalIgnoreCase) ? value[2..] : value;
        if (hex.Length is < 1 or > 4 || !ushort.TryParse(hex,
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

    private static ushort ParseTypedDid(string text)
    {
        var hex = text.StartsWith("0x", StringComparison.OrdinalIgnoreCase) ? text[2..] : text;
        return DoIpVinReader.ParseLogicalAddress(hex);
    }

    internal static void ValidateDoIpArguments(string[] args, string command,
        IReadOnlyCollection<string> valueOptions, IReadOnlyCollection<string> flagOptions)
    {
        var values = new HashSet<string>(valueOptions, StringComparer.OrdinalIgnoreCase);
        var flags = new HashSet<string>(flagOptions, StringComparer.OrdinalIgnoreCase);
        var seen = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        for (var i = args.Length > 0 && args[0].Equals(command, StringComparison.OrdinalIgnoreCase) ? 1 : 0;
            i < args.Length; i++)
        {
            var option = args[i];
            if (!seen.Add(option))
                throw new ArgumentException("Repeated DoIP option: " + option);
            if (flags.Contains(option)) continue;
            if (!values.Contains(option))
                throw new ArgumentException("Unknown DoIP option: " + option);
            if (i + 1 == args.Length || string.IsNullOrWhiteSpace(args[i + 1]) ||
                args[i + 1].StartsWith("--", StringComparison.Ordinal))
                throw new ArgumentException("Missing DoIP option value: " + option);
            i++;
        }
    }

    internal static DoIpOptions ParseDoIp(string[] args)
    {
        ValidateDoIpArguments(args, DoIpCommand, [
            "--host", "--tester", "--ecu", "--payload", "--payload-file", "--expected-vin",
            "--uds-dids", "--uds-did", "--uds-scaling-did", "--obd-verified", "--obd-did",
            "--obd-dtc-mask", "--obd-dtc-count-mask", "--uds-historical-dtc",
            "--uds-severity-count", "--uds-severity-list", "--uds-severity-info", "--uds-severity-status",
            "--uds-memory", "--uds-memory-mask", "--uds-memory-snapshot", "--uds-memory-extended", "--uds-memory-record",
            "--uds-stored-record", "--uds-all-stored-records", "--uds-extended-record-support", "--uds-extended-record-data", "--uds-extended-record-size",
            "--uds-mirror-mask", "--uds-mirror-count", "--uds-mirror-extended", "--uds-mirror-record",
            "--uds-legacy-emissions-mask", "--uds-legacy-emissions-count",
            "--uds-dtc-mask", "--uds-dtc-count-mask", "--uds-snapshot", "--uds-snapshot-record", "--snapshot-did-lengths", "--extended-record-lengths",
            "--uds-extended-dtc", "--uds-extended-record", "--obd-wwh-status", "--obd-readiness-group",
            "--obd-snapshot", "--obd-snapshot-record", "--obd-extended-dtc", "--obd-extended-record",
            "--obd-wwh-severity", "--timeout-ms", "--uds-p2-ms", "--uds-p2-star-ms", "--tls",
            "--doip-version", "--routing-activation-type", "--routing-oem-data", "--rx-file",
            "--client-cert-thumbprint", "--trace-file"
        ], ["--uds-supported-dtcs", "--uds-permanent-dtcs", "--uds-snapshot-index", "--uds-fault-counters",
            "--obd-wwh-permanent", "--obd-identify"]);
        var host = Required(args, "--host");
        var tester = Address(Required(args, "--tester"));
        var ecu = Address(Required(args, "--ecu"));
        var hexPayload = Optional(args, "--payload");
        var payloadFile = Optional(args, "--payload-file");
        var udsDids = Optional(args, "--uds-dids");
        var udsDid = Optional(args, "--uds-did");
        var scalingDid = Optional(args, "--uds-scaling-did");
        if ((udsDid is not null || scalingDid is not null) && Optional(args, "--expected-vin") is null)
            throw new ArgumentException("Typed UDS DID reads require --expected-vin.");
        var obdDid = Optional(args, "--obd-did");
        var obdDtcMask = Optional(args, "--obd-dtc-mask");
        var obdDtcCountMask = Optional(args, "--obd-dtc-count-mask");
        var supportedDtcs = args.Contains("--uds-supported-dtcs", StringComparer.OrdinalIgnoreCase);
        var permanentDtcs = args.Contains("--uds-permanent-dtcs", StringComparer.OrdinalIgnoreCase);
        var snapshotIndex = args.Contains("--uds-snapshot-index", StringComparer.OrdinalIgnoreCase);
        var historicalDtc = Optional(args, "--uds-historical-dtc");
        var faultCounters = args.Contains("--uds-fault-counters", StringComparer.OrdinalIgnoreCase);
        var severityCount = Optional(args, "--uds-severity-count");
        var severityList = Optional(args, "--uds-severity-list");
        var severityInfo = Optional(args, "--uds-severity-info");
        var severityStatus = Optional(args, "--uds-severity-status");
        if ((severityStatus is not null) != (severityCount is not null || severityList is not null))
            throw new ArgumentException("Severity filters require explicit status mask.");
        if ((supportedDtcs || permanentDtcs || snapshotIndex || faultCounters || historicalDtc is not null ||
            severityCount is not null || severityList is not null || severityInfo is not null) && Optional(args, "--expected-vin") is null)
            throw new ArgumentException("DTC inventory requires --expected-vin.");
        var mirrorMask = Optional(args, "--uds-mirror-mask");
        var mirrorCount = Optional(args, "--uds-mirror-count");
        var mirrorExtended = Optional(args, "--uds-mirror-extended");
        var mirrorRecord = Optional(args, "--uds-mirror-record");
        var legacyEmissionsMask = Optional(args, "--uds-legacy-emissions-mask");
        var legacyEmissionsCount = Optional(args, "--uds-legacy-emissions-count");
        var legacySelectors = new[] { mirrorMask, mirrorCount, mirrorExtended, legacyEmissionsMask, legacyEmissionsCount }.Count(v => v is not null);
        if (legacySelectors > 1 || (mirrorRecord is not null) != (mirrorExtended is not null) ||
            (legacySelectors == 1 && Optional(args, "--expected-vin") is null))
            throw new ArgumentException("Legacy DTC reads require VIN, one selector and explicit mirror record for detail.");
        var allStoredRecords = Optional(args, "--uds-all-stored-records");
        if (allStoredRecords is not null)
        {
            if (Optional(args, "--expected-vin") is null) throw new ArgumentException("Stored records require explicit VIN.");
            _ = ObdOnUdsServices.ParseStoredDidLengths(allStoredRecords);
        }
        var storedRecord = Optional(args, "--uds-stored-record");
        if (storedRecord is not null && Optional(args, "--expected-vin") is null)
            throw new ArgumentException("Stored DTC record requires explicit VIN.");
        var extendedRecordSupport = Optional(args, "--uds-extended-record-support");
        var extendedRecordData = Optional(args, "--uds-extended-record-data");
        var extendedRecordSize = Optional(args, "--uds-extended-record-size");
        var extendedRecordSelectors = (extendedRecordSupport is null ? 0 : 1) + (extendedRecordData is null ? 0 : 1);
        if (extendedRecordSelectors > 1 || (extendedRecordSize is not null) != (extendedRecordData is not null) ||
            (extendedRecordSelectors == 1 && Optional(args, "--expected-vin") is null))
            throw new ArgumentException("Extended record reads require VIN, one selector and size for data.");
        var memory = Optional(args, "--uds-memory");
        var memoryMask = Optional(args, "--uds-memory-mask");
        var memorySnapshot = Optional(args, "--uds-memory-snapshot");
        var memoryExtended = Optional(args, "--uds-memory-extended");
        var memoryRecord = Optional(args, "--uds-memory-record");
        var memorySelectors = (memoryMask is null ? 0 : 1) + (memorySnapshot is null ? 0 : 1) + (memoryExtended is null ? 0 : 1);
        if (memorySelectors > 1 || (memory is not null) != (memorySelectors == 1) ||
            (memoryRecord is not null) != (memorySnapshot is not null || memoryExtended is not null) ||
            (memorySelectors == 1 && Optional(args, "--expected-vin") is null))
            throw new ArgumentException("User-memory reads require VIN, memory and one explicit selector.");
        var udsDtcMask = Optional(args, "--uds-dtc-mask");
        var udsDtcCountMask = Optional(args, "--uds-dtc-count-mask");
        var udsSnapshot = Optional(args, "--uds-snapshot");
        var udsSnapshotRecord = Optional(args, "--uds-snapshot-record");
        var udsExtended = Optional(args, "--uds-extended-dtc");
        var udsExtendedRecord = Optional(args, "--uds-extended-record");
        if ((udsDtcMask is not null || udsDtcCountMask is not null ||
             udsSnapshot is not null || udsExtended is not null) &&
            Optional(args, "--expected-vin") is null)
            throw new ArgumentException("Typed UDS DTC reads require --expected-vin.");
        if ((udsSnapshotRecord is not null) != (udsSnapshot is not null) ||
            (udsExtendedRecord is not null) != (udsExtended is not null))
            throw new ArgumentException("Generic DTC details require an explicit DTC and record.");
        var wwhStatus = Optional(args, "--obd-wwh-status");
        var wwhReadiness = Optional(args, "--obd-readiness-group");
        var snapshotDtc = Optional(args, "--obd-snapshot");
        var snapshotRecord = Optional(args, "--obd-snapshot-record");
        var extendedDtc = Optional(args, "--obd-extended-dtc");
        var extendedRecord = Optional(args, "--obd-extended-record");
        if (snapshotRecord is not null && snapshotDtc is null)
            throw new ArgumentException(
                "DTC snapshot record option requires --obd-snapshot.");
        if (extendedRecord is not null && extendedDtc is null)
            throw new ArgumentException(
                "DTC extended-data record option requires --obd-extended-dtc.");
        var extendedCatalog = Optional(args, "--extended-record-lengths");
        if (extendedCatalog is not null)
        {
            if ((udsExtended is null) == (extendedDtc is null) ||
                Optional(args, "--expected-vin") is null ||
                (extendedDtc is not null && extendedRecord is null))
                throw new ArgumentException("Decoded extended data requires VIN, one DTC selector and explicit record.");
            _ = ObdOnUdsServices.ReadDecodedExtended(Number(udsExtended ?? extendedDtc!),
                checked((byte)Number(udsExtendedRecord ?? extendedRecord!)),
                ObdOnUdsServices.ParseExtendedRecordLengths(extendedCatalog));
        }
        var snapshotCatalog = Optional(args, "--snapshot-did-lengths");
        if (snapshotCatalog is not null)
        {
            if ((udsSnapshot is null) == (snapshotDtc is null) ||
                Optional(args, "--expected-vin") is null ||
                (snapshotDtc is not null && snapshotRecord is null))
                throw new ArgumentException("Decoded snapshots require VIN, one DTC selector and explicit record.");
            _ = ObdOnUdsServices.ParseStoredDidLengths(snapshotCatalog);
        }
        var wwhSeverity = Optional(args, "--obd-wwh-severity");
        var wwhPermanent = args.Contains("--obd-wwh-permanent",
            StringComparer.OrdinalIgnoreCase);
        if (wwhSeverity is not null && wwhStatus is null)
            throw new ArgumentException(
                "WWH severity mask requires --obd-wwh-status.");
        var obdIdentify = args.Contains("--obd-identify",
            StringComparer.OrdinalIgnoreCase);
        if ((hexPayload is null ? 0 : 1) +
            (payloadFile is null ? 0 : 1) +
            (udsDids is null ? 0 : 1) +
            (udsDid is null ? 0 : 1) +
            (scalingDid is null ? 0 : 1) +
            (obdDid is null ? 0 : 1) +
            (obdDtcMask is null ? 0 : 1) +
            (obdDtcCountMask is null ? 0 : 1) +
            (supportedDtcs ? 1 : 0) +
            (permanentDtcs ? 1 : 0) + memorySelectors + extendedRecordSelectors + legacySelectors + (storedRecord is null ? 0 : 1) + (allStoredRecords is null ? 0 : 1) +
            (snapshotIndex ? 1 : 0) +
            (historicalDtc is null ? 0 : 1) +
            (faultCounters ? 1 : 0) +
            (severityCount is null ? 0 : 1) + (severityList is null ? 0 : 1) + (severityInfo is null ? 0 : 1) +
            (udsDtcMask is null ? 0 : 1) +
            (udsDtcCountMask is null ? 0 : 1) +
            (udsSnapshot is null ? 0 : 1) +
            (udsExtended is null ? 0 : 1) +
            (wwhStatus is null ? 0 : 1) +
            (wwhReadiness is null ? 0 : 1) +
            (snapshotDtc is null ? 0 : 1) +
            (extendedDtc is null ? 0 : 1) +
            (wwhPermanent ? 1 : 0) +
            (obdIdentify ? 1 : 0) != 1)
            throw new ArgumentException(
                "Supply exactly one diagnostic payload or typed read selector.");
        if (payloadFile is not null &&
            new FileInfo(payloadFile).Length > DoIpCodec.MaxPayload - 4)
            throw new ArgumentException(
                "DoIP file payload exceeds the bounded in-memory PDU capacity.");
        var bytes = obdIdentify ? ObdOnUdsServices.ReadProtocolIdentification() :
            udsDids is not null ? UdsMultiDidCli.BuildRequest(
                UdsMultiDidCli.Parse(udsDids)) :
            udsDid is not null ? UdsStandardServices.ReadDid(ParseTypedDid(udsDid)) :
            scalingDid is not null ? UdsStandardServices.ReadScalingDid(ParseTypedDid(scalingDid)) :
            obdDid is not null ? ObdOnUdsServices.ReadObdDid(
                checked((ushort)uint.Parse(
                    obdDid.StartsWith("0x", StringComparison.OrdinalIgnoreCase) ?
                        obdDid[2..] : obdDid,
                    NumberStyles.AllowHexSpecifier, CultureInfo.InvariantCulture))) :
            supportedDtcs ? ObdOnUdsServices.ReadSupportedDtcs() :
            permanentDtcs ? ObdOnUdsServices.ReadPermanentDtcs() :
            mirrorMask is not null ? ObdOnUdsServices.ReadLegacyDtc(0x0F, checked((byte)Number(mirrorMask))) :
            mirrorCount is not null ? ObdOnUdsServices.ReadLegacyDtc(0x11, checked((byte)Number(mirrorCount))) :
            legacyEmissionsMask is not null ? ObdOnUdsServices.ReadLegacyDtc(0x13, checked((byte)Number(legacyEmissionsMask))) :
            legacyEmissionsCount is not null ? ObdOnUdsServices.ReadLegacyDtc(0x12, checked((byte)Number(legacyEmissionsCount))) :
            mirrorExtended is not null ? ObdOnUdsServices.ReadLegacyDtc(0x10, 0, Number(mirrorExtended), checked((byte)Number(mirrorRecord!))) :
            allStoredRecords is not null ? ObdOnUdsServices.ReadAllStoredRecords() :
            storedRecord is not null ? ObdOnUdsServices.ReadStoredRecord(checked((byte)Number(storedRecord))) :
            extendedRecordSupport is not null ? ObdOnUdsServices.ReadExtendedRecord(checked((byte)Number(extendedRecordSupport)), null) :
            extendedRecordData is not null ? ObdOnUdsServices.ReadExtendedRecord(checked((byte)Number(extendedRecordData)),
                int.Parse(extendedRecordSize!, NumberStyles.None, CultureInfo.InvariantCulture)) :
            memoryMask is not null ? ObdOnUdsServices.ReadUserMemory(0x17, checked((byte)Number(memory!)), checked((byte)Number(memoryMask))) :
            memorySnapshot is not null ? ObdOnUdsServices.ReadUserMemory(0x18, checked((byte)Number(memory!)), 0,
                Number(memorySnapshot), checked((byte)Number(memoryRecord!))) :
            memoryExtended is not null ? ObdOnUdsServices.ReadUserMemory(0x19, checked((byte)Number(memory!)), 0,
                Number(memoryExtended), checked((byte)Number(memoryRecord!))) :
            snapshotIndex ? ObdOnUdsServices.ReadSnapshotIndex() :
            faultCounters ? ObdOnUdsServices.ReadFaultCounters() :
            severityCount is not null ? ObdOnUdsServices.ReadSeverity(7, checked((byte)Number(severityCount)), checked((byte)Number(severityStatus!))) :
            severityList is not null ? ObdOnUdsServices.ReadSeverity(8, checked((byte)Number(severityList)), checked((byte)Number(severityStatus!))) :
            severityInfo is not null ? ObdOnUdsServices.ReadSeverity(9, 0, 0, Number(severityInfo)) :
            historicalDtc is not null ? ObdOnUdsServices.ReadHistoricalDtc(checked((byte)Number(historicalDtc))) :
            udsDtcMask is not null ? ObdOnUdsServices.ReadDtcByStatusMask(
                checked((byte)Number(udsDtcMask))) :
            udsDtcCountMask is not null ? ObdOnUdsServices.ReadDtcCountByStatusMask(
                checked((byte)Number(udsDtcCountMask))) :
            extendedCatalog is not null ? ObdOnUdsServices.ReadDecodedExtended(
                Number(udsExtended ?? extendedDtc!), checked((byte)Number(udsExtendedRecord ?? extendedRecord!)),
                ObdOnUdsServices.ParseExtendedRecordLengths(extendedCatalog)) :
            snapshotCatalog is not null ? ObdOnUdsServices.ReadDecodedSnapshot(
                Number(udsSnapshot ?? snapshotDtc!), checked((byte)Number(udsSnapshotRecord ?? snapshotRecord!))) :
            udsSnapshot is not null ? ObdOnUdsServices.ReadDtcSnapshot(
                Number(udsSnapshot), checked((byte)Number(udsSnapshotRecord!))) :
            udsExtended is not null ? ObdOnUdsServices.ReadDtcExtendedData(
                Number(udsExtended), checked((byte)Number(udsExtendedRecord!))) :
            obdDtcMask is not null ? ObdOnUdsServices.ReadDtcByStatusMask(
                checked((byte)Number(obdDtcMask))) :
            obdDtcCountMask is not null ? ObdOnUdsServices.ReadDtcCountByStatusMask(
                checked((byte)Number(obdDtcCountMask))) :
            wwhStatus is not null ? ObdOnUdsServices.ReadWwhEmissionsDtcs(
                checked((byte)Number(wwhStatus)),
                wwhSeverity is null ? (byte)0xFF :
                    checked((byte)Number(wwhSeverity))) :
            wwhPermanent ? ObdOnUdsServices.ReadWwhPermanentEmissionsDtcs() :
            wwhReadiness is not null ? ObdOnUdsServices.ReadWwhReadinessGroup(
                checked((byte)Number(wwhReadiness))) :
            snapshotDtc is not null ? ObdOnUdsServices.ReadDtcSnapshot(
                Number(snapshotDtc), snapshotRecord is null ?
                    (byte)1 : checked((byte)Number(snapshotRecord))) :
            extendedDtc is not null ? ObdOnUdsServices.ReadDtcExtendedData(
                Number(extendedDtc), extendedRecord is null ?
                    (byte)0xFE : checked((byte)Number(extendedRecord))) :
            payloadFile is not null ? File.ReadAllBytes(payloadFile) :
            Hex(hexPayload!);
        var timeout = int.Parse(Optional(args, "--timeout-ms") ?? "5000",
            CultureInfo.InvariantCulture);
        var p2Ms = int.Parse(Optional(args, "--uds-p2-ms") ?? "30000",
            CultureInfo.InvariantCulture);
        var p2StarMs = int.Parse(Optional(args, "--uds-p2-star-ms") ?? "30000",
            CultureInfo.InvariantCulture);
        if (p2Ms is < 1 or > 30000 || p2StarMs is < 1 or > 30000)
            throw new ArgumentOutOfRangeException(
                "DoIP UDS P2/P2* values must be within 1..30000 ms.");
        var useTls = bool.Parse(Optional(args, "--tls") ?? "false");
        var version = checked((byte)Number(
            Optional(args, "--doip-version") ?? "2"));
        var routingType = checked((byte)Number(
            Optional(args, "--routing-activation-type") ?? "0"));
        var routingOemText = Optional(args, "--routing-oem-data");
        var routingOem = routingOemText is null ? null : Hex(routingOemText);
        if (routingOem is not null && routingOem.Length != 4)
            throw new ArgumentException(
                "DoIP OEM routing-activation data must be exactly four bytes.");
        var receiveFile = Optional(args, "--rx-file");
        if (receiveFile is not null)
        {
            receiveFile = Path.GetFullPath(receiveFile);
            if (File.Exists(receiveFile) || Directory.Exists(receiveFile) ||
                !Directory.Exists(Path.GetDirectoryName(receiveFile)))
                throw new ArgumentException("Choose a new DoIP reply file in an existing directory.");
        }
        var traceFile = ParseDoIpTracePath(args, receiveFile);
        var clientCert = Optional(args, "--client-cert-thumbprint");
        if (clientCert is not null &&
            (!useTls || clientCert.Length != 40 ||
             !clientCert.All(Uri.IsHexDigit)))
            throw new ArgumentException(
                "Client certificate requires TLS and a 40-digit Windows store thumbprint.");
        if (host.Length is < 1 or > 253 || timeout is < 100 or > 30000 ||
            tester == ecu || bytes.Length is < 1 or > DoIpCodec.MaxPayload - 4 ||
            version is not (0x02 or 0x03 or 0x04))
            throw new ArgumentException("DoIP target or diagnostic PDU is invalid.");
        return new(host, tester, ecu, bytes, timeout, useTls,
            version, receiveFile, clientCert, p2Ms, p2StarMs,
            routingType, routingOem, traceFile);
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
        var j1850WireCrc = bool.Parse(
            Optional(args, "--j1850-wire-crc") ?? "false");
        var kwpService = Optional(args, "--kwp-service");
        var kwpValidate = bool.Parse(Optional(args, "--kwp-validate") ?? "false");
        var kwpEchoBytes = int.Parse(Optional(args, "--kwp-echo-bytes") ?? "0",
            CultureInfo.InvariantCulture);
        var kwpTxTargetText = Optional(args, "--kwp-tx-target");
        var kwpTxSourceText = Optional(args, "--kwp-tx-source");
        var kwpFunctional = bool.Parse(Optional(args, "--kwp-functional") ?? "false");
        var kwpFunctionalRx = bool.Parse(
            Optional(args, "--kwp-rx-functional") ?? "false");
        if (mode is not ("exchange" or "receive") ||
            (mode == "exchange" &&
                ((txText is null) == (kwpService is null))) ||
            (mode == "receive" &&
                (txText is not null || kwpService is not null)))
            throw new ArgumentException(
                "J2534 exchange needs one raw TX or KWP service; receive mode accepts no TX.");
        if (kwpService is not null &&
            (protocol != J2534StandardChannel.Iso14230 ||
             kwpTxTargetText is null || kwpTxSourceText is null))
            throw new ArgumentException(
                "KWP service needs ISO14230 with explicit TX source and target.");
        if (kwpService is null &&
            (kwpTxTargetText is not null || kwpTxSourceText is not null ||
             kwpFunctional))
            throw new ArgumentException(
                "KWP TX addressing requires a KWP service payload.");
        var data = kwpService is not null ?
            StandardVehicleProtocols.KwpForJ2534(
                kwpFunctional ?
                    StandardVehicleProtocols.BuildKwpFunctionalFrame(
                        checked((byte)Number(kwpTxTargetText!)),
                        checked((byte)Number(kwpTxSourceText!)), Hex(kwpService)) :
                    StandardVehicleProtocols.BuildKwpPhysicalFrame(
                        checked((byte)Number(kwpTxTargetText!)),
                        checked((byte)Number(kwpTxSourceText!)), Hex(kwpService)),
                checksumDisabled: (flags &
                    J2534StandardChannel.KlineChecksumDisabled) != 0) :
            txText is null ? Array.Empty<byte>() : Hex(txText);
        var mask = Optional(args, "--mask") is string m ? Hex(m) : null;
        var pattern = Optional(args, "--pattern") is string p ? Hex(p) : null;
        var flow = Optional(args, "--flow-control") is string f ? Hex(f) : null;
        var init = Optional(args, "--kline-init");
        var hsBaudText = Optional(args, "--sw-can-hs-baud");
        uint? hsBaud = hsBaudText is null ? null : Number(hsBaudText);
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
            (mode == "receive" &&
                (init != null || flow != null || hsBaud.HasValue)) ||
            (j1850WireCrc &&
                protocol is not (J2534StandardChannel.J1850Vpw or
                                 J2534StandardChannel.J1850Pwm)) ||
            (hsBaud.HasValue &&
                (protocol != J2534StandardChannel.SwCan ||
                 hsBaud.Value == 0)) ||
            (kwpRxTarget.HasValue != kwpRxSource.HasValue) ||
            (kwpFunctionalRx && !kwpRxTarget.HasValue) ||
            (kwpRxTarget.HasValue && protocol != J2534StandardChannel.Iso14230) ||
            (kwpValidate && (kwpService is null || mode != "exchange" ||
                kwpRxTarget is null || kwpRxSource is null || count == 0)) ||
            (kwpEchoBytes < 0 || kwpEchoBytes > 4095 ||
                (kwpEchoBytes > 0 &&
                 (!kwpValidate || kwpService is null ||
                  kwpEchoBytes >= Hex(kwpService).Length))) ||
            (init != null && protocol is not (J2534StandardChannel.Iso9141
                                             or J2534StandardChannel.Iso14230)) ||
            (init != null && !init.StartsWith("five:", StringComparison.OrdinalIgnoreCase) &&
                !init.StartsWith("fast:", StringComparison.OrdinalIgnoreCase)))
            throw new ArgumentException("Invalid standard J2534 channel configuration.");
        return new(index, protocol, baud, flags, data,
            mask, pattern, flow, init, timeout, count,
            kwpRxTarget, kwpRxSource, mode, kwpFunctionalRx,
            hsBaud, j1850WireCrc,
            kwpValidate ? Hex(kwpService!) : null, kwpEchoBytes);
    }

    internal static async Task SaveDoIpReplyAsync(
        string path, ReadOnlyMemory<byte> reply)
    {
        if (string.IsNullOrWhiteSpace(path) ||
            reply.IsEmpty || reply.Length > DoIpCodec.MaxPayload)
            throw new ArgumentException("Invalid DoIP reply file or payload.");
        // CreateNew is essential: an existing ECU snapshot or diagnostic
        // capture must never be silently replaced.
        await using var output = new FileStream(path,
            FileMode.CreateNew, FileAccess.Write, FileShare.None);
        await output.WriteAsync(reply);
        await output.FlushAsync();
        output.Flush(flushToDisk: true);
    }

    internal static string? ParseDoIpTracePath(string[] args, string? otherOutput = null)
    {
        var path = Optional(args, "--trace-file");
        if (args.Count(x => x.Equals("--trace-file", StringComparison.OrdinalIgnoreCase)) > 1)
            throw new ArgumentException("Repeated DoIP trace file option.");
        if (path is null) return null;
        if (string.IsNullOrWhiteSpace(path)) throw new ArgumentException("DoIP trace path cannot be empty.");
        path = Path.GetFullPath(path);
        if (File.Exists(path) || Directory.Exists(path) || !Directory.Exists(Path.GetDirectoryName(path)))
            throw new ArgumentException("Choose a new DoIP trace file in an existing directory.");
        if (otherOutput is not null && string.Equals(path, Path.GetFullPath(otherOutput), StringComparison.OrdinalIgnoreCase))
            throw new ArgumentException("DoIP trace and response files must be distinct.");
        return path;
    }

    internal static string FormatDoIpDatagramTrace(string direction, System.Net.IPEndPoint endpoint,
        byte[] data, DateTimeOffset utc) => System.Text.Json.JsonSerializer.Serialize(new
        {
            utc, direction, transport = "UDP", host = endpoint.Address.ToString(),
            port = endpoint.Port, bytes = data.Length, pdu = Convert.ToHexString(data)
        });

    internal static string FormatDoIpTrace(DoIpOptions target, DoIpDiagnosticTransport.PduTrace frame) =>
        System.Text.Json.JsonSerializer.Serialize(new
        {
            utc = frame.ObservedAtUtc, direction = frame.Direction,
            transport = target.UseTls ? "TLS" : "TCP", host = target.Host,
            tester = $"{target.Tester:X4}", ecu = $"{target.Ecu:X4}",
            bytes = frame.Bytes.Length, pdu = Convert.ToHexString(frame.Bytes)
        });

    // The transport can invoke the observer on either its reader or writer.
    // Keep complete JSON lines in chronological callback order. A failed file
    // write is not silently ignored; the exchange must not claim full logging.
    internal sealed class DoIpTraceJournal : IDisposable
    {
        private readonly object gate = new();
        private readonly StreamWriter writer;
        private readonly DoIpOptions? target;
        private bool disposed;
        internal DoIpTraceJournal(string path, DoIpOptions? target = null)
        {
            this.target = target;
            writer = new StreamWriter(new FileStream(path, FileMode.CreateNew,
                FileAccess.Write, FileShare.Read), new System.Text.UTF8Encoding(false)) { AutoFlush = true };
        }
        internal void Record(DoIpDiagnosticTransport.PduTrace frame) =>
            Write(FormatDoIpTrace(target ?? throw new InvalidOperationException("TCP trace target missing."), frame));
        internal void RecordForEcu(ushort ecu, DoIpDiagnosticTransport.PduTrace frame) =>
            Write(FormatDoIpTrace((target ?? throw new InvalidOperationException("TCP trace target missing.")) with { Ecu = ecu }, frame));
        internal void RecordDatagram(string direction, System.Net.IPEndPoint endpoint, byte[] data) =>
            Write(FormatDoIpDatagramTrace(direction, endpoint, data, DateTimeOffset.UtcNow));
        private void Write(string line)
        {
            lock (gate)
            {
                if (disposed) throw new ObjectDisposedException(nameof(DoIpTraceJournal));
                try { writer.WriteLine(line); }
                catch (IOException error) { throw new InvalidOperationException("DoIP trace journal write failed.", error); }
            }
        }
        public void Dispose()
        {
            lock (gate)
            {
                if (disposed) return;
                disposed = true;
                writer.Dispose();
            }
        }
    }

    private static DoIpTraceJournal? OpenDoIpTrace(DoIpOptions target) =>
        target.TraceFile is null ? null : new DoIpTraceJournal(target.TraceFile, target);

    private static DoIpTraceJournal? OpenDoIpUdpTrace(string[] args) =>
        ParseDoIpTracePath(args) is string path ? new DoIpTraceJournal(path) : null;

    internal sealed record DoIpSequenceOptions(DoIpOptions Target, string Vin,
        byte[][] Commands, bool ExplicitVersion);

    internal static DoIpSequenceOptions ParseDoIpSequence(string[] args)
    {
        // Parse every option as an explicit pair, reject duplicated or
        // unrelated options instead of silently changing the approved target.
        var allowed = new HashSet<string>(StringComparer.OrdinalIgnoreCase)
        {
            "--host", "--tester", "--ecu", "--expected-vin", "--sequence", "--timeout-ms",
            "--tls", "--doip-version", "--client-cert-thumbprint", "--uds-p2-ms",
            "--uds-p2-star-ms", "--routing-activation-type", "--routing-oem-data", "--trace-file"
        };
        var values = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        for (var i = args.Length > 0 && args[0].Equals(DoIpCommand, StringComparison.OrdinalIgnoreCase) ? 1 : 0;
             i < args.Length; i += 2)
        {
            if (i + 1 >= args.Length || !allowed.Contains(args[i]) ||
                args[i + 1].StartsWith("--", StringComparison.Ordinal) ||
                !values.TryAdd(args[i], args[i + 1]))
                throw new ArgumentException("Unexpected or repeated DoIP sequence option.");
        }
        if (!values.TryGetValue("--expected-vin", out var vin) ||
            !DoIpVehicleDiscovery.IsVehicleVin(vin) ||
            !values.TryGetValue("--sequence", out var sequence))
            throw new ArgumentException("Explicit expected VIN and sequence required.");
        var commands = DoIpConsolePresentation.ParseCommands(sequence);
        var explicitVersion = values.ContainsKey("--doip-version");
        values.Remove("--sequence");
        values.Remove("--expected-vin");
        values["--payload"] = Convert.ToHexString(commands[0]);
        if (!values.ContainsKey("--tls")) values["--tls"] = "true";
        var target = ParseDoIp(values.SelectMany(x => new[] { x.Key, x.Value }).ToArray());
        if (!System.Net.IPAddress.TryParse(target.Host, out _))
            throw new ArgumentException("DoIP sequence requires a numeric unicast IP.");
        return new DoIpSequenceOptions(target, vin, commands, explicitVersion);
    }

    private static async Task<int> RunDoIpSequenceAsync(string[] args)
    {
        var sequence = ParseDoIpSequence(args);
        var o = sequence.Target;
        if (Console.IsInputRedirected)
            throw new InvalidOperationException("Interactive terminal required for ECU consent.");
        using var cancellation = new CancellationTokenSource();
        ConsoleCancelEventHandler cancel = (_, e) => { e.Cancel = true; cancellation.Cancel(); };
        Console.CancelKeyPress += cancel;
        try
        {
            var timeout = TimeSpan.FromMilliseconds(o.TimeoutMs);
            using var journal = OpenDoIpTrace(o);
            var gateway = await DoIpVehicleDiscovery.DiscoverAsync(o.Host, timeout,
                cancellation.Token, vin: sequence.Vin, trace: journal is null ? null : journal.RecordDatagram);
            if (gateway.Vin != sequence.Vin ||
                (sequence.ExplicitVersion && gateway.ProtocolVersion != o.Version) ||
                gateway.LogicalAddress == o.Tester)
                throw new InvalidDataException("Observed DoIP gateway differs from the sequence target.");
            if (!Confirm($"DoIP {(o.UseTls ? "TLS" : "TCP")} VIN={sequence.Vin} " +
                $"IP={o.Host} gateway=0x{gateway.LogicalAddress:X4} ECU=0x{o.Ecu:X4} tester=0x{o.Tester:X4} " +
                $"v=0x{gateway.ProtocolVersion:X2} routing=0x{o.RoutingActivationType:X2} " +
                $"OEM={(o.RoutingOemData is null ? "none" : Convert.ToHexString(o.RoutingOemData))} " +
                $"commands={string.Join("; ", sequence.Commands.Select(c => Convert.ToHexString(c)))}"))
                return 2;
            cancellation.Token.ThrowIfCancellationRequested();
            await DoIpVehicleDiscovery.RequireCurrentIdentityAsync(gateway, timeout, cancellation.Token,
                trace: journal is null ? null : journal.RecordDatagram);
            await using var transport = await DoIpDiagnosticTransport.ConnectAsync(
                o.Host, o.Tester, o.Ecu, timeout, cancellation.Token,
                useTls: o.UseTls, protocolVersion: gateway.ProtocolVersion,
                clientCertificateThumbprint: o.ClientCertificateThumbprint,
                activationType: o.RoutingActivationType, activationOemData: o.RoutingOemData,
                expectedGatewayAddress: gateway.LogicalAddress,
                trace: journal is null ? null : journal.Record);
            var count = await DoIpVinReader.ExchangeSequenceVerifiedAsync(transport, gateway,
                sequence.Commands, timeout, cancellation.Token, (index, reply) =>
                    Console.WriteLine(string.Format(DoIpConsolePresentation.Text("SequenceReply"),
                        index, Convert.ToHexString(reply))),
                TimeSpan.FromMilliseconds(o.DoIpP2Ms), TimeSpan.FromMilliseconds(o.DoIpP2StarMs));
            Console.WriteLine(string.Format(DoIpConsolePresentation.Text("SequenceComplete"), count));
            return 0;
        }
        finally { Console.CancelKeyPress -= cancel; }
    }

    internal sealed record DoIpMonitorOptions(DoIpOptions Target, string Vin, ushort Did,
        int Count, int IntervalMs, bool Obd, bool ExplicitVersion, IReadOnlyList<UdsMultiDidCli.Entry>? Layout = null);

    internal static DoIpMonitorOptions ParseDoIpMonitor(string[] args)
    {
        var allowed = new HashSet<string>(StringComparer.OrdinalIgnoreCase)
        {
            "--host", "--tester", "--ecu", "--expected-vin", "--monitor-did", "--obd-monitor-did",
            "--monitor-dids", "--obd-monitor-dids", "--samples", "--interval-ms", "--timeout-ms", "--tls", "--doip-version",
            "--client-cert-thumbprint", "--routing-activation-type", "--routing-oem-data",
            "--uds-p2-ms", "--uds-p2-star-ms", "--trace-file"
        };
        var values = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        for (var i = args.Length > 0 && args[0].Equals(DoIpCommand, StringComparison.OrdinalIgnoreCase) ? 1 : 0;
             i < args.Length; i += 2)
        {
            if (i + 1 >= args.Length || !allowed.Contains(args[i]) ||
                args[i + 1].StartsWith("--", StringComparison.Ordinal) ||
                !values.TryAdd(args[i], args[i + 1]))
                throw new ArgumentException("Unexpected or repeated DoIP monitoring option.");
        }
        var obdSingle = values.TryGetValue("--obd-monitor-did", out var obdDid);
        var genericSingle = values.TryGetValue("--monitor-did", out var genericDid);
        var obdMulti = values.TryGetValue("--obd-monitor-dids", out var obdLayout);
        var genericMulti = values.TryGetValue("--monitor-dids", out var genericLayout);
        if (new[] { obdSingle, genericSingle, obdMulti, genericMulti }.Count(x => x) != 1 ||
            !values.TryGetValue("--expected-vin", out var vin) || !DoIpVehicleDiscovery.IsVehicleVin(vin))
            throw new ArgumentException("One monitoring selection and an explicit expected VIN required.");
        var obd = obdSingle || obdMulti;
        var layout = obdMulti || genericMulti ? UdsMultiDidCli.ValidateMonitor(
            UdsMultiDidCli.Parse(obdMulti ? obdLayout! : genericLayout!)) : null;
        var did = layout is null ? Address(obdSingle ? obdDid! : genericDid!) : layout[0].Did;
        if (obd && (layout is null ? ObdOnUdsServices.ClassifyDid(did) == ObdOnUdsServices.DataGroup.NotLegislated :
            layout.Any(e => ObdOnUdsServices.ClassifyDid(e.Did) == ObdOnUdsServices.DataGroup.NotLegislated)))
            throw new ArgumentException("Legislated OBDonUDS DID required.");
        var count = int.Parse(values.GetValueOrDefault("--samples", "10"), CultureInfo.InvariantCulture);
        var interval = int.Parse(values.GetValueOrDefault("--interval-ms", "1000"), CultureInfo.InvariantCulture);
        if (count is < 1 or > 3600 || interval is < 250 or > 60000)
            throw new ArgumentOutOfRangeException(nameof(args), "Invalid monitoring count or interval.");
        var explicitVersion = values.ContainsKey("--doip-version");
        var totalMs = int.Parse(values.GetValueOrDefault("--timeout-ms", "5000"), CultureInfo.InvariantCulture);
        var p2 = values.GetValueOrDefault("--uds-p2-ms", totalMs.ToString(CultureInfo.InvariantCulture));
        var p2Star = values.GetValueOrDefault("--uds-p2-star-ms", totalMs.ToString(CultureInfo.InvariantCulture));
        var timing = DoIpDiagnosticTransport.SessionOptions.Parse(totalMs, p2, p2Star, "00", "");
        values["--uds-p2-ms"] = timing.P2Ms.ToString(CultureInfo.InvariantCulture);
        values["--uds-p2-star-ms"] = timing.P2StarMs.ToString(CultureInfo.InvariantCulture);
        foreach (var key in new[] { "--monitor-did", "--obd-monitor-did", "--monitor-dids",
            "--obd-monitor-dids", "--samples", "--interval-ms", "--expected-vin" }) values.Remove(key);
        values["--payload"] = layout is null ? $"22{did:X4}" : Convert.ToHexString(UdsMultiDidCli.BuildRequest(layout));
        if (!values.ContainsKey("--tls")) values["--tls"] = "true";
        var target = ParseDoIp(values.SelectMany(x => new[] { x.Key, x.Value }).ToArray());
        if (!System.Net.IPAddress.TryParse(target.Host, out var address) ||
            !DoIpVehicleDiscovery.IsExplicitUnicast(address))
            throw new ArgumentException("DoIP monitoring requires a numeric unicast IP.");
        return new DoIpMonitorOptions(target, vin, did, count, interval, obd, explicitVersion, layout);
    }

    private static async Task<int> RunDoIpMonitorAsync(string[] args)
    {
        var monitor = ParseDoIpMonitor(args);
        var o = monitor.Target;
        using var cancellation = new CancellationTokenSource();
        ConsoleCancelEventHandler cancel = (_, e) => { e.Cancel = true; cancellation.Cancel(); };
        Console.CancelKeyPress += cancel;
        try
        {
            var timeout = TimeSpan.FromMilliseconds(o.TimeoutMs);
            using var journal = OpenDoIpTrace(o);
            var gateway = await DoIpVehicleDiscovery.DiscoverAsync(o.Host, timeout,
                cancellation.Token, vin: monitor.Vin, trace: journal is null ? null : journal.RecordDatagram);
            if (gateway.Vin != monitor.Vin ||
                (monitor.ExplicitVersion && gateway.ProtocolVersion != o.Version) ||
                gateway.LogicalAddress == o.Tester)
                throw new InvalidDataException("Observed DoIP gateway differs from the monitoring target.");
            await DoIpVehicleDiscovery.RequireCurrentIdentityAsync(gateway, timeout, cancellation.Token,
                trace: journal is null ? null : journal.RecordDatagram);
            await using var transport = await DoIpDiagnosticTransport.ConnectAsync(
                o.Host, o.Tester, o.Ecu, timeout, cancellation.Token,
                useTls: o.UseTls, protocolVersion: gateway.ProtocolVersion,
                clientCertificateThumbprint: o.ClientCertificateThumbprint,
                expectedGatewayAddress: gateway.LogicalAddress,
                trace: journal is null ? null : journal.Record,
                sessionOptions: new DoIpDiagnosticTransport.SessionOptions(o.DoIpP2Ms,
                    o.DoIpP2StarMs, o.RoutingActivationType,
                    o.RoutingOemData is null ? "" : Convert.ToHexString(o.RoutingOemData)));
            var interval = TimeSpan.FromMilliseconds(monitor.IntervalMs);
            void Sample(int index, byte[] data) => Console.WriteLine(System.Text.Json.JsonSerializer.Serialize(
                new { sample = index, utc = DateTimeOffset.UtcNow, vin = monitor.Vin,
                    ecu = $"{o.Ecu:X4}", did = $"{monitor.Did:X4}", protocol = monitor.Obd ? "OBDonUDS" : "UDS",
                    payload = Convert.ToHexString(data), partial = true }));
            if (monitor.Layout is not null)
                await DoIpVinReader.MonitorVerifiedMultiDidAsync(transport, gateway, monitor.Vin,
                    monitor.Layout, monitor.Obd, monitor.Count, interval, timeout, cancellation.Token,
                    (index, values) => Console.WriteLine(System.Text.Json.JsonSerializer.Serialize(new
                    {
                        sample = index, utc = DateTimeOffset.UtcNow, vin = monitor.Vin,
                        ecu = $"{o.Ecu:X4}", protocol = monitor.Obd ? "OBDonUDS" : "UDS", partial = true,
                        dids = values.Select(v => new { did = $"{v.Did:X4}", payload = Convert.ToHexString(v.Data) })
                    })));
            else if (monitor.Obd)
                await DoIpVinReader.MonitorVerifiedObdAsync(transport, monitor.Vin, monitor.Did,
                    monitor.Count, interval, timeout, cancellation.Token, Sample);
            else
            {
                var vin = await DoIpVinReader.VerifyObservedVinAsync(transport, gateway,
                    timeout, cancellation.Token, null, null);
                await DoIpVinReader.MonitorDidAsync(transport, monitor.Did, monitor.Count,
                    interval, timeout, cancellation.Token, Sample, expectedVin: vin);
                var after = await DoIpVinReader.VerifyObservedVinAsync(transport, gateway,
                    timeout, cancellation.Token, null, null);
                if (vin != after) throw new InvalidDataException("ECU VIN changed during monitoring.");
            }
            cancellation.Token.ThrowIfCancellationRequested();
            Console.WriteLine(System.Text.Json.JsonSerializer.Serialize(new
            {
                complete = true, samples = monitor.Count,
                protocol = monitor.Obd ? "OBDonUDS" : "UDS", vin = monitor.Vin
            }));
            return 0;
        }
        finally { Console.CancelKeyPress -= cancel; }
    }

    internal sealed record VerifiedDoIpRead(DoIpOptions Target, string Vin, bool Obd, bool ExplicitVersion,
        bool IdentityOnly = false);

    internal static VerifiedDoIpRead ParseVerifiedDoIpRead(string[] args)
    {
        var vin = Required(args, "--expected-vin");
        if (!DoIpVehicleDiscovery.IsVehicleVin(vin))
            throw new ArgumentException("Explicit valid VIN required.");
        // Optional verification mode is for typed reads, not a restriction on the raw exchange engine.
        if (Optional(args, "--payload") is not null || Optional(args, "--payload-file") is not null)
            throw new ArgumentException("For arbitrary UDS commands use the verified --sequence path.");
        var multi = Optional(args, "--uds-dids");
        var genericRead = new[] { "--uds-did", "--uds-scaling-did", "--uds-dtc-mask",
            "--uds-dtc-count-mask", "--uds-snapshot", "--uds-extended-dtc",
            "--uds-memory-mask", "--uds-memory-snapshot", "--uds-memory-extended",
            "--uds-stored-record", "--uds-all-stored-records", "--uds-extended-record-support", "--uds-extended-record-data", "--uds-mirror-mask", "--uds-mirror-count",
            "--uds-mirror-extended", "--uds-legacy-emissions-mask", "--uds-legacy-emissions-count" }
            .Any(selector => Optional(args, selector) is not null);
        genericRead |= args.Contains("--uds-supported-dtcs", StringComparer.OrdinalIgnoreCase) ||
            args.Contains("--uds-permanent-dtcs", StringComparer.OrdinalIgnoreCase) ||
            args.Contains("--uds-snapshot-index", StringComparer.OrdinalIgnoreCase) ||
            Optional(args, "--uds-historical-dtc") is not null ||
            Optional(args, "--uds-severity-count") is not null || Optional(args, "--uds-severity-list") is not null ||
            Optional(args, "--uds-severity-info") is not null ||
            args.Contains("--uds-fault-counters", StringComparer.OrdinalIgnoreCase);
        var obd = !genericRead && (multi is null || bool.Parse(Optional(args, "--obd-verified") ?? "false"));
        if (Optional(args, "--obd-verified") is not null && multi is null)
            throw new ArgumentException("--obd-verified is only needed with --uds-dids.");
        if (obd && multi is not null && UdsMultiDidCli.Parse(multi).Any(e =>
            ObdOnUdsServices.ClassifyDid(e.Did) == ObdOnUdsServices.DataGroup.NotLegislated))
            throw new ArgumentException("Expected legislated DIDs.");
        // An unknown identification is valid evidence for discovery, not permission
        // to use an OBDonUDS data route.
        var identityOnly = args.Contains("--obd-identify", StringComparer.OrdinalIgnoreCase);
        var tlsDefault = Optional(args, "--tls") is null;
        var target = ParseDoIp(tlsDefault ? [..args, "--tls", "true"] : args);
        if (!System.Net.IPAddress.TryParse(target.Host, out var address) ||
            !DoIpVehicleDiscovery.IsExplicitUnicast(address) ||
            target.Request[0] is not (0x22 or 0x19 or 0x24))
            throw new ArgumentException("Verified reads require numeric unicast IP and a typed read.");
        var p2 = Math.Min(target.TimeoutMs, target.DoIpP2Ms);
        var p2Star = Math.Min(target.TimeoutMs, target.DoIpP2StarMs);
        if (p2 > p2Star || (Optional(args, "--uds-p2-ms") is not null && target.DoIpP2Ms > target.TimeoutMs) ||
            (Optional(args, "--uds-p2-star-ms") is not null && target.DoIpP2StarMs > target.TimeoutMs))
            throw new ArgumentException("Expected P2 <= P2* <= total timeout.");
        return new(target with { DoIpP2Ms = p2, DoIpP2StarMs = p2Star }, vin, obd,
            Optional(args, "--doip-version") is not null, identityOnly);
    }

    internal static byte[] DecodeVerifiedSingleDid(byte[] request, byte[] reply)
    {
        if (request.Length != 3 || request[0] is not (0x22 or 0x24) ||
            reply.Length < 4 || reply[0] != request[0] + 0x40 ||
            reply[1] != request[1] || reply[2] != request[2])
            throw new InvalidDataException("Missing or uncorrelated UDS DID data.");
        return reply[3..];
    }

    private static async Task<int> RunVerifiedDoIpReadAsync(string[] args)
    {
        var verified = ParseVerifiedDoIpRead(args);
        var o = verified.Target;
        using var stop = new CancellationTokenSource();
        ConsoleCancelEventHandler cancel = (_, e) => { e.Cancel = true; stop.Cancel(); };
        Console.CancelKeyPress += cancel;
        try
        {
            var timeout = TimeSpan.FromMilliseconds(o.TimeoutMs);
            using var journal = OpenDoIpTrace(o);
            var gateway = await DoIpVehicleDiscovery.DiscoverAsync(o.Host, timeout, stop.Token, vin: verified.Vin,
                trace: journal is null ? null : journal.RecordDatagram);
            if (gateway.Vin != verified.Vin || gateway.LogicalAddress == o.Tester ||
                (verified.ExplicitVersion && gateway.ProtocolVersion != o.Version))
                throw new InvalidDataException("Observed gateway differs from selected vehicle.");
            if (!Confirm($"Verified {(verified.Obd ? "OBDonUDS" : "UDS")} read VIN={verified.Vin} " +
                $"host={o.Host} ECU={o.Ecu:X4} TLS={o.UseTls} TX={Convert.ToHexString(o.Request)}"))
                return 2;
            await DoIpVehicleDiscovery.RequireCurrentIdentityAsync(gateway, timeout, stop.Token,
                trace: journal is null ? null : journal.RecordDatagram);
            await using var link = await DoIpDiagnosticTransport.ConnectAsync(o.Host, o.Tester, o.Ecu,
                timeout, stop.Token, useTls: o.UseTls, protocolVersion: gateway.ProtocolVersion,
                clientCertificateThumbprint: o.ClientCertificateThumbprint,
                expectedGatewayAddress: gateway.LogicalAddress,
                trace: journal is null ? null : journal.Record,
                sessionOptions: new DoIpDiagnosticTransport.SessionOptions(o.DoIpP2Ms, o.DoIpP2StarMs,
                    o.RoutingActivationType, o.RoutingOemData is null ? "" : Convert.ToHexString(o.RoutingOemData)));
            if (verified.IdentityOnly)
            {
                var identity = await DoIpVinReader.ReadStableObdIdentityAsync(link, verified.Vin, timeout, stop.Token);
                // F810 is decoded only from an exact four-byte positive response.
                byte[] protocolReply = [0x62, 0xF8, 0x10, identity.ProtocolValue];
                if (o.ReceiveFile is not null) await SaveDoIpReplyAsync(o.ReceiveFile, protocolReply);
                Console.WriteLine(System.Text.Json.JsonSerializer.Serialize(new
                {
                    complete = true, vin = identity.Vin, ecu = $"{o.Ecu:X4}",
                    protocol_value = identity.ProtocolValue, obd_on_uds = identity.ProtocolValue == 1,
                    raw = o.ReceiveFile is null ? Convert.ToHexString(protocolReply) : null
                }));
                return 0;
            }
            async Task VerifyAsync()
            {
                if (verified.Obd)
                {
                    var identity = await DoIpVinReader.ReadVerifiedObdAsync(link, verified.Vin, null, timeout, stop.Token);
                    if (identity.ProtocolValue != 1)
                        throw new InvalidDataException("Selected ECU did not identify as OBDonUDS.");
                }
                else if (await DoIpVinReader.VerifyObservedVinAsync(link, gateway, timeout, stop.Token, null, null) != verified.Vin)
                    throw new InvalidDataException("ECU VIN mismatch.");
            }
            await VerifyAsync();
            var reply = await link.ExchangeUdsAsync(o.Request, timeout, stop.Token);
            var singleGeneric = !verified.Obd && o.Request.Length == 3 && o.Request[0] is (0x22 or 0x24);
            var singleObd = verified.Obd && o.Request.Length == 3 && o.Request[0] == 0x22;
            var singleData = singleGeneric || singleObd ? DecodeVerifiedSingleDid(o.Request, reply) : null;
            if (singleObd && o.Request[1] == 0xF8)
            {
                if (o.Request[2] == 0x02 && ObdOnUdsVehicleIdentification.DecodeVin(reply) != verified.Vin)
                    throw new InvalidDataException("Returned legislated VIN differs from selected vehicle.");
                if (o.Request[2] == 0x10 && ObdOnUdsServices.DecodeProtocolIdentification(reply).Value != 1)
                    throw new InvalidDataException("Returned protocol differs from verified OBDonUDS identity.");
            }
            var layoutText = Optional(args, "--uds-dids");
            if (layoutText is not null)
                UdsMultiDidCli.RequireIdentity(UdsMultiDidCli.Decode(UdsMultiDidCli.Parse(layoutText),
                    o.Request, reply), verified.Vin, verified.Obd);
            if (singleGeneric && o.Request.SequenceEqual(UdsStandardServices.ReadDid(0xF190)) &&
                DoIpVinReader.ParseVin(reply) != verified.Vin)
                throw new InvalidDataException("Returned VIN differs from selected vehicle.");
            await VerifyAsync();
            stop.Token.ThrowIfCancellationRequested();
            PrintMultiDidResponse(args, o.Request, reply);
            var sizeText = Optional(args, "--uds-extended-record-size");
            var storedCatalog = Optional(args, "--uds-all-stored-records");
            var snapshotCatalog = Optional(args, "--snapshot-did-lengths");
            var extendedCatalog = Optional(args, "--extended-record-lengths");
            if (extendedCatalog is not null)
            {
                uint dtc = ((uint)o.Request[2] << 16) | ((uint)o.Request[3] << 8) | o.Request[4];
                var report = ObdOnUdsServices.DecodeExtendedRecords(dtc, o.Request[5], reply,
                    ObdOnUdsServices.ParseExtendedRecordLengths(extendedCatalog));
                Console.WriteLine(System.Text.Json.JsonSerializer.Serialize(new
                {
                    dtc = report.Dtc.ToString("X6"), status = report.Status,
                    requested_record = report.RequestedRecord, raw_response = Convert.ToHexString(report.RawResponse),
                    records = report.Records.Select(row => new
                    {
                        record = row.Number, length = row.Data.Length, data = Convert.ToHexString(row.Data)
                    })
                }));
            }
            else if (snapshotCatalog is not null)
            {
                uint dtc = ((uint)o.Request[2] << 16) | ((uint)o.Request[3] << 8) | o.Request[4];
                var report = ObdOnUdsServices.DecodeSnapshotRecords(dtc, o.Request[5], reply,
                    ObdOnUdsServices.ParseStoredDidLengths(snapshotCatalog));
                Console.WriteLine(System.Text.Json.JsonSerializer.Serialize(new
                {
                    dtc = report.Dtc.ToString("X6"), status = report.Status,
                    requested_record = report.RequestedRecord, raw_response = Convert.ToHexString(report.RawResponse),
                    records = report.Records.Select(row => new
                    {
                        record = row.Record, did_count = row.Dids.Count, raw_record = Convert.ToHexString(row.RawRecord),
                        dids = row.Dids.Select(item => new { did = item.Did.ToString("X4"),
                            length = item.Data.Length, data = Convert.ToHexString(item.Data) })
                    })
                }));
            }
            else if (storedCatalog is not null)
            {
                var report = ObdOnUdsServices.DecodeAllStoredRecords(reply, ObdOnUdsServices.ParseStoredDidLengths(storedCatalog));
                Console.WriteLine(System.Text.Json.JsonSerializer.Serialize(new
                {
                    subfunction = 5, record = 255, raw_response = Convert.ToHexString(report.RawResponse),
                    records = report.Records.Select(row => new
                    {
                        record = row.Record, dtc = row.Dtc.ToString("X6"), status = row.Status,
                        did_count = row.Dids.Count, raw_record = Convert.ToHexString(row.RawRecord),
                        dids = row.Dids.Select(item => new { did = item.Did.ToString("X4"),
                            length = item.Data.Length, data = Convert.ToHexString(item.Data) })
                    })
                }));
            }
            else if (verified.Obd || o.Request[0] == 0x19) PrintObdOnUdsResponse(o.Request, reply,
                sizeText is null ? null : int.Parse(sizeText, NumberStyles.None, CultureInfo.InvariantCulture));
            if (o.ReceiveFile is not null) await SaveDoIpReplyAsync(o.ReceiveFile, reply);
            Console.WriteLine(System.Text.Json.JsonSerializer.Serialize(new
            {
                complete = true, vin = verified.Vin, ecu = $"{o.Ecu:X4}",
                protocol = verified.Obd ? "OBDonUDS" : "UDS",
                bytes = reply.Length, sha256 = Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(reply)),
                did = singleGeneric || singleObd ? Convert.ToHexString(o.Request.AsSpan(1, 2)) : null,
                service = singleGeneric || singleObd ? o.Request[0].ToString("X2") : null,
                data = singleData is not null && o.ReceiveFile is null && singleData.Length <= 4093
                    ? Convert.ToHexString(singleData) : null,
                raw = o.ReceiveFile is null && reply.Length <= 4096 ? Convert.ToHexString(reply) : null
            }));
            return 0;
        }
        finally { Console.CancelKeyPress -= cancel; }
    }

    internal sealed record DoIpInventoryOptions(DoIpOptions Target, string Vin, ushort[] Ecus,
        bool ExplicitVersion, bool Obd);

    internal static DoIpInventoryOptions ParseDoIpInventory(string[] args)
    {
        ValidateDoIpArguments(args, DoIpCommand, ["--host", "--tester", "--expected-vin",
            "--ecu-inventory", "--obd-ecu-inventory", "--timeout-ms", "--tls", "--doip-version", "--client-cert-thumbprint",
            "--uds-p2-ms", "--uds-p2-star-ms", "--routing-activation-type", "--routing-oem-data", "--trace-file"], []);
        var vin = Required(args, "--expected-vin");
        if (!DoIpVehicleDiscovery.IsVehicleVin(vin))
            throw new ArgumentException("Explicit valid VIN required.");
        var generic = Optional(args, "--ecu-inventory");
        var legislated = Optional(args, "--obd-ecu-inventory");
        if ((generic is null) == (legislated is null))
            throw new ArgumentException("Choose exactly one ECU inventory operation.");
        var obd = legislated is not null;
        var ecus = DoIpVinReader.ParseInventoryAddresses(legislated ?? generic!);
        var tester = Address(Required(args, "--tester"));
        if (ecus.Contains(tester)) throw new ArgumentException("Tester must differ from every ECU.");
        var forwarded = new List<string>();
        for (var i = args.Length > 0 && args[0].Equals(DoIpCommand, StringComparison.OrdinalIgnoreCase) ? 1 : 0;
            i < args.Length; i += 2)
            if (!args[i].Equals("--ecu-inventory", StringComparison.OrdinalIgnoreCase) &&
                !args[i].Equals("--obd-ecu-inventory", StringComparison.OrdinalIgnoreCase) &&
                !args[i].Equals("--expected-vin", StringComparison.OrdinalIgnoreCase))
            {
                forwarded.Add(args[i]);
                forwarded.Add(args[i+1]);
            }
        forwarded.AddRange(["--ecu", $"{ecus[0]:X4}", "--payload", obd ? "22F802" : "22F190"]);
        if (Optional(args, "--tls") is null) forwarded.AddRange(["--tls", "true"]);
        var target = ParseDoIp(forwarded.ToArray());
        if (!System.Net.IPAddress.TryParse(target.Host, out var address) ||
            !DoIpVehicleDiscovery.IsExplicitUnicast(address))
            throw new ArgumentException("ECU inventory requires a numeric unicast IP.");
        var timing = DoIpDiagnosticTransport.SessionOptions.Parse(target.TimeoutMs,
            Optional(args, "--uds-p2-ms") ?? "", Optional(args, "--uds-p2-star-ms") ?? "",
            target.RoutingActivationType.ToString("X2"),
            target.RoutingOemData is null ? "" : Convert.ToHexString(target.RoutingOemData));
        return new(target with { DoIpP2Ms = timing.P2Ms, DoIpP2StarMs = timing.P2StarMs },
            vin, ecus, Optional(args, "--doip-version") is not null, obd);
    }

    private static async Task<int> RunDoIpInventoryAsync(string[] args)
    {
        var inventory = ParseDoIpInventory(args);
        var o = inventory.Target;
        using var stop = new CancellationTokenSource();
        ConsoleCancelEventHandler cancel = (_, e) => { e.Cancel = true; stop.Cancel(); };
        Console.CancelKeyPress += cancel;
        try
        {
            var timeout = TimeSpan.FromMilliseconds(o.TimeoutMs);
            using var journal = OpenDoIpTrace(o);
            var gateway = await DoIpVehicleDiscovery.DiscoverAsync(o.Host, timeout, stop.Token, vin: inventory.Vin,
                trace: journal is null ? null : journal.RecordDatagram);
            if (gateway.Vin != inventory.Vin || gateway.LogicalAddress == o.Tester ||
                (inventory.ExplicitVersion && gateway.ProtocolVersion != o.Version))
                throw new InvalidDataException("Observed gateway differs from the selected inventory target.");
            var rows = await DoIpVinReader.InventoryObservedAsync(gateway, inventory.Vin, o.Tester,
                inventory.Ecus, o.UseTls, timeout, stop.Token, o.ClientCertificateThumbprint,
                new DoIpDiagnosticTransport.SessionOptions(o.DoIpP2Ms, o.DoIpP2StarMs, o.RoutingActivationType,
                    o.RoutingOemData is null ? "" : Convert.ToHexString(o.RoutingOemData)),
                journal is null ? null : journal.RecordDatagram,
                journal is null ? null : journal.RecordForEcu, inventory.Obd);
            Console.WriteLine(System.Text.Json.JsonSerializer.Serialize(new
            {
                complete = true, vin = inventory.Vin, verified_count = rows.Count(row => row.Vin is not null),
                identification = inventory.Obd ? "F802/F810" : "F190",
                obd_on_uds_count = inventory.Obd ? rows.Count(row => row.ProtocolValue == 1) : (int?)null,
                targets = rows.Select(row => new { ecu = $"{row.Ecu:X4}", vin = row.Vin,
                    status = row.Vin is null ? "unverified" : "vin_verified", reason = row.Reason,
                    protocol_value = row.ProtocolValue, obd_on_uds = row.ProtocolValue.HasValue ? row.ProtocolValue == 1 : (bool?)null }),
                physical_qualification = false
            }));
            return 0;
        }
        finally { Console.CancelKeyPress -= cancel; }
    }

    internal static async Task<int> RunDoIpAsync(string[] args)
    {
        try
        {
            if (args.Contains("--ecu-inventory", StringComparer.OrdinalIgnoreCase) ||
                args.Contains("--obd-ecu-inventory", StringComparer.OrdinalIgnoreCase))
                return await RunDoIpInventoryAsync(args);
            if (args.Contains("--monitor-did", StringComparer.OrdinalIgnoreCase) ||
                args.Contains("--obd-monitor-did", StringComparer.OrdinalIgnoreCase) ||
                args.Contains("--monitor-dids", StringComparer.OrdinalIgnoreCase) ||
                args.Contains("--obd-monitor-dids", StringComparer.OrdinalIgnoreCase))
                return await RunDoIpMonitorAsync(args);
            if (args.Contains("--sequence", StringComparer.OrdinalIgnoreCase))
                return await RunDoIpSequenceAsync(args);
            if (args.Contains("--expected-vin", StringComparer.OrdinalIgnoreCase))
                return await RunVerifiedDoIpReadAsync(args);
            if (args.Contains("--obd-verified", StringComparer.OrdinalIgnoreCase))
                throw new ArgumentException("--obd-verified requires --expected-vin.");
            var o = ParseDoIp(args);
            if (!Confirm($"DoIP {(o.UseTls ? "TLS" : "TCP")} " +
                $"{o.Host}:{(o.UseTls ? 3496 : 13400)} ECU=0x{o.Ecu:X4} " +
                $"v=0x{o.Version:X2} routing=0x{o.RoutingActivationType:X2} " +
                $"OEM={(o.RoutingOemData is null ? "none" : "4 bytes")} " +
                $"UDS length={o.Request.Length} " +
                $"SHA256={Convert.ToHexString(
                    System.Security.Cryptography.SHA256.HashData(o.Request))}"))
                return 2;
            using var journal = OpenDoIpTrace(o);
            await using var link = await DoIpDiagnosticTransport.ConnectAsync(
                o.Host, o.Tester, o.Ecu,
                TimeSpan.FromMilliseconds(o.TimeoutMs), CancellationToken.None,
                useTls: o.UseTls, protocolVersion: o.Version,
                clientCertificateThumbprint: o.ClientCertificateThumbprint,
                activationType: o.RoutingActivationType,
                activationOemData: o.RoutingOemData,
                trace: journal is null ? null : journal.Record);
            var reply = await link.ExchangeUdsAsync(o.Request,
                TimeSpan.FromMilliseconds(o.TimeoutMs), CancellationToken.None,
                TimeSpan.FromMilliseconds(o.DoIpP2Ms),
                TimeSpan.FromMilliseconds(o.DoIpP2StarMs));
            var replyHash = Convert.ToHexString(
                System.Security.Cryptography.SHA256.HashData(reply));
            if (o.ReceiveFile is not null)
            {
                // Never replace an existing diagnostic capture or original
                // ECU backup. The path must not exist before this exchange.
                await SaveDoIpReplyAsync(o.ReceiveFile, reply);
                Console.WriteLine($"DoIP RX saved to {o.ReceiveFile} " +
                    $"bytes={reply.Length} SHA256={replyHash}");
            }
            else if (reply.Length <= 64)
                Console.WriteLine("DoIP ECU RX: " + Convert.ToHexString(reply));
            else
                Console.WriteLine($"DoIP ECU RX: bytes={reply.Length} " +
                    $"SHA256={replyHash} prefix={Convert.ToHexString(reply.AsSpan(0, 64))} " +
                    "(use --rx-file to preserve all bytes).");
            PrintMultiDidResponse(args, o.Request, reply);
            PrintObdOnUdsResponse(o.Request, reply);
            return 0;
        }
        catch (Exception ex) when (ex is ArgumentException or
            IOException or SocketException or OperationCanceledException or
            InvalidDataException or TimeoutException or
            System.Security.Authentication.AuthenticationException or FormatException or OverflowException or InvalidOperationException)
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
                $"protocol=0x{o.ProtocolId:X} " +
                $"SW-CAN-HS={(o.SwCanHighSpeedBaud?.ToString() ?? "unchanged")} " +
                $"TX={Convert.ToHexString(o.Data)}"))
                return 2;
            using var driver = J2534StandardChannel.Connect(adapters[o.AdapterIndex],
                o.ProtocolId, o.Baud, o.Flags);
            if (o.SwCanHighSpeedBaud.HasValue)
                driver.SetSwCanHighSpeedRate(o.SwCanHighSpeedBaud.Value);
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
            var correlatedKwp = false;
            for (var i = 0; i < o.ReadFrames; i++)
            {
                var received = driver.Receive(o.TimeoutMs);
                if (received == null) break;
                // J2534 TX acknowledgements and status frames are not an
                // ISO 14230 physical application reply.
                if (o.KwpRequest is not null &&
                    J2534StandardChannel.IsAuxiliaryReceiveStatus(received.RxStatus))
                    continue;
                Console.WriteLine($"J2534 RX protocol=0x{received.ProtocolId:X} " +
                    $"status=0x{received.RxStatus:X} {Convert.ToHexString(received.Data)}");
                // Only inspect the wire CRC when the operator explicitly
                // confirms that this vendor J2534 DLL preserves it. Many
                // compliant drivers have already stripped it on receipt.
                if (ShouldValidateJ1850WireCrc(
                        o.J1850WireCrc, received.RxStatus) &&
                    !J1850Crc.VerifyRawFrame(received.Data))
                    throw new InvalidDataException(
                        "Captured J1850 frame has an invalid on-wire CRC.");
                if (o.KwpRxTarget is byte target && o.KwpRxSource is byte source)
                {
                    var payload = StandardVehicleProtocols.ParseKwpJ2534(
                        received.Data, target, source,
                        functional: o.KwpFunctionalRx,
                        checksumDisabled: (o.Flags &
                            J2534StandardChannel.KlineChecksumDisabled) != 0);
                    Console.WriteLine("KWP physical RX payload: " +
                        Convert.ToHexString(payload));
                    if (o.KwpRequest is not null)
                    {
                        var reply = Kwp2000Transaction.Decode(
                            o.KwpRequest, payload, o.KwpEchoBytes);
                        Console.WriteLine($"KWP service=0x{reply.Service:X2} " +
                            $"status={reply.Status} " +
                            $"NRC={(reply.NegativeResponseCode is byte code ? $"0x{code:X2}" : "none")}");
                        if (reply.Status == Kwp2000Transaction.Kind.Negative)
                            return 7;
                        if (reply.Status == Kwp2000Transaction.Kind.ResponsePending)
                            continue;
                        correlatedKwp = true;
                        break;
                    }
                }
            }
            if (o.KwpRequest is not null && !correlatedKwp)
            {
                Console.Error.WriteLine(
                    "No final correlated KWP ECU response received; transmission is unconfirmed.");
                return 7;
            }
            return 0;
        }
        catch (Exception ex) when (ex is ArgumentException or
            IOException or InvalidOperationException or InvalidDataException or
            DllNotFoundException or BadImageFormatException or EntryPointNotFoundException or OverflowException)
        {
            Console.Error.WriteLine("J2534 exchange failed: " + ex.Message);
            return 6;
        }
    }

    /// <summary>
    /// A J2534 status/echo event is not a physical J1850 data packet.
    /// Inspect CRC only when the driver contract says the raw wire CRC is
    /// retained, never for auxiliary RxStatus indications.
    /// </summary>
    internal static bool ShouldValidateJ1850WireCrc(
        bool enabled, uint rxStatus) =>
        enabled && !J2534StandardChannel.IsAuxiliaryReceiveStatus(rxStatus);

    private static bool Confirm(string operation) =>
        EcuWriteOperatorConsent.AskBeforeProgramming(Console.ReadLine,
            Console.WriteLine, operation);
}
