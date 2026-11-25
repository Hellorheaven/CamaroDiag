using System.Globalization;
using System.Net;
using System.Text;

namespace CamaroDiag.J2534Bridge;

/// <summary>UDS application services over a discovered DoIP gateway.</summary>
internal static class DoIpVinReader
{
    internal static ushort ParseLogicalAddress(string text)
    {
        if (text is null || text.Length != 4 ||
            !ushort.TryParse(text, NumberStyles.AllowHexSpecifier,
                CultureInfo.InvariantCulture, out var address))
            throw new ArgumentException("DoIP logical address must contain exactly four hexadecimal digits.");
        return address;
    }

    internal static string ParseVin(byte[] reply)
    {
        ArgumentNullException.ThrowIfNull(reply);
        if (reply.Length != 20 || reply[0] != 0x62 ||
            reply[1] != 0xF1 || reply[2] != 0x90)
            throw new InvalidDataException("Expected UDS positive response with DID F190 and exactly 17 VIN bytes.");
        var vin = Encoding.ASCII.GetString(reply, 3, 17);
        if (vin.Any(c => !(c is >= '0' and <= '9' ||
            c is >= 'A' and <= 'Z' && c is not ('I' or 'O' or 'Q'))))
            throw new InvalidDataException("UDS F190 contains invalid VIN characters.");
        return vin;
    }

    private static async Task<byte[]> ExchangeReadAsync(
        DoIpDiagnosticTransport transport, byte[] request, TimeSpan timeout,
        CancellationToken token, Action<byte[]>? requested, Action<byte[]>? received)
    {
        ArgumentNullException.ThrowIfNull(transport);
        token.ThrowIfCancellationRequested();
        requested?.Invoke(request.ToArray());
        token.ThrowIfCancellationRequested();
        var reply = await transport.ExchangeUdsAsync(request, timeout, token);
        token.ThrowIfCancellationRequested();
        received?.Invoke(reply.ToArray());
        token.ThrowIfCancellationRequested();
        return reply;
    }

    internal static async Task<string> ReadAsync(
        DoIpDiagnosticTransport transport, TimeSpan timeout, CancellationToken token,
        Action<byte[]>? requested = null, Action<byte[]>? received = null)
    {
        var reply = await ExchangeReadAsync(transport, UdsStandardServices.ReadDid(0xF190),
            timeout, token, requested, received);
        return ParseVin(reply);
    }

    internal static async Task<ObdOnUdsServices.DtcStatusReport> ReadDtcsAsync(
        DoIpDiagnosticTransport transport, byte statusMask, TimeSpan timeout,
        CancellationToken token, Action<byte[]>? requested = null,
        Action<byte[]>? received = null)
    {
        var reply = await ExchangeReadAsync(transport,
            ObdOnUdsServices.ReadDtcByStatusMask(statusMask), timeout, token, requested, received);
        return ObdOnUdsServices.DecodeDtcByStatusMask(statusMask, reply);
    }

    internal static async Task<byte[]> ReadDidAsync(
        DoIpDiagnosticTransport transport, ushort did, TimeSpan timeout,
        CancellationToken token, Action<byte[]>? requested = null,
        Action<byte[]>? received = null)
    {
        var reply = await ExchangeReadAsync(transport, UdsStandardServices.ReadDid(did),
            timeout, token, requested, received);
        return ObdOnUdsServices.DecodeReadDid(did, reply);
    }

    internal static Task<DoIpDiagnosticTransport> ConnectObservedAsync(
        DoIpVehicleDiscovery.Identity gateway, IPAddress address, ushort tester,
        ushort ecu, bool useTls, TimeSpan timeout, CancellationToken token,
        string? clientCertificateThumbprint, Action<DoIpDiagnosticTransport.PduTrace>? trace = null,
        DoIpDiagnosticTransport.SessionOptions? options = null)
    {
        ArgumentNullException.ThrowIfNull(gateway);
        ArgumentNullException.ThrowIfNull(address);
        if (!gateway.Source.Equals(address) || tester == ecu ||
            gateway.ProtocolVersion is not (0x02 or 0x03 or 0x04))
            throw new ArgumentException("The DoIP target must match a discovered gateway and explicit logical addresses.");
        token.ThrowIfCancellationRequested();
        return DoIpDiagnosticTransport.ConnectAsync(
            address.ToString(), tester, ecu, timeout, token,
            useTls: useTls, protocolVersion: gateway.ProtocolVersion,
            clientCertificateThumbprint: clientCertificateThumbprint,
            expectedGatewayAddress: gateway.LogicalAddress, trace: trace, sessionOptions: options);
    }

    internal static async Task<string> VerifyObservedVinAsync(DoIpDiagnosticTransport transport,
        DoIpVehicleDiscovery.Identity gateway, TimeSpan timeout, CancellationToken token,
        Action<byte[]>? requested, Action<byte[]>? received)
    {
        var vin = await ReadAsync(transport, timeout, token, requested, received);
        if (gateway.Vin is not null && !string.Equals(gateway.Vin, vin, StringComparison.Ordinal))
            throw new InvalidDataException("UDS VIN differs from the observed DoIP vehicle identity.");
        token.ThrowIfCancellationRequested();
        return vin;
    }

    internal sealed record ObdRead(string Vin, byte ProtocolValue, byte[]? Data);

    internal static async Task<ObdRead> ReadObservedObdAsync(
        DoIpVehicleDiscovery.Identity gateway, IPAddress address, ushort tester,
        ushort ecu, bool tls, string expectedVin, ushort? did, TimeSpan timeout,
        CancellationToken token, string? certificate = null,
        Action<DoIpDiagnosticTransport.PduTrace>? trace = null,
        DoIpDiagnosticTransport.SessionOptions? options = null)
    {
        if (!DoIpVehicleDiscovery.IsVehicleVin(expectedVin) || gateway.Vin != expectedVin ||
            (did.HasValue && ObdOnUdsServices.ClassifyDid(did.Value) == ObdOnUdsServices.DataGroup.NotLegislated))
            throw new ArgumentException("Explicit matching VIN and legislated DID required.");
        await using var link = await ConnectObservedAsync(gateway, address, tester, ecu,
            tls, timeout, token, certificate, trace, options);
        return await ReadVerifiedObdAsync(link, expectedVin, did, timeout, token);
    }

    internal static async Task<ObdRead> ReadVerifiedObdAsync(DoIpDiagnosticTransport link,
        string expectedVin, ushort? did, TimeSpan timeout, CancellationToken token)
    {
        if (!DoIpVehicleDiscovery.IsVehicleVin(expectedVin) ||
            (did.HasValue && ObdOnUdsServices.ClassifyDid(did.Value) == ObdOnUdsServices.DataGroup.NotLegislated))
            throw new ArgumentException("Invalid OBDonUDS target.");
        var vin = ObdOnUdsVehicleIdentification.DecodeVin(await link.ExchangeUdsAsync(
            ObdOnUdsVehicleIdentification.ReadVin(), timeout, token));
        if (vin != expectedVin) throw new InvalidDataException("Legislated VIN differs from selected vehicle.");
        var identification = ObdOnUdsServices.DecodeProtocolIdentification(await link.ExchangeUdsAsync(
            ObdOnUdsServices.ReadProtocolIdentification(), timeout, token));
        if (!did.HasValue) return new(vin, identification.Value, null);
        if (!identification.IsObdOnUds)
            throw new InvalidDataException("Selected ECU did not identify as OBDonUDS.");
        var data = did.Value switch
        {
            0xF802 => Encoding.ASCII.GetBytes(vin),
            0xF810 => new byte[] { identification.Value },
            _ => ObdOnUdsServices.DecodeReadDid(did.Value, await link.ExchangeUdsAsync(
                ObdOnUdsServices.ReadObdDid(did.Value), timeout, token))
        };
        if (data.Length == 0) throw new InvalidDataException("Missing DID data.");
        var after = await ReadVerifiedObdAsync(link, expectedVin, null, timeout, token);
        if (after.ProtocolValue != identification.Value)
            throw new InvalidDataException("OBDonUDS identification changed during DID read.");
        return new(vin, identification.Value, data);
    }

    internal static async Task<IReadOnlyList<(ushort Did, byte[] Data)>> ReadObservedMultiDidAsync(
        DoIpVehicleDiscovery.Identity gateway, IPAddress address, ushort tester, ushort ecu,
        bool tls, string expectedVin, IReadOnlyList<UdsMultiDidCli.Entry> layout, bool obd,
        TimeSpan timeout, CancellationToken token, string? certificate = null,
        Action<DoIpDiagnosticTransport.PduTrace>? trace = null,
        DoIpDiagnosticTransport.SessionOptions? options = null)
    {
        var frozen = layout.ToArray();
        if (!DoIpVehicleDiscovery.IsVehicleVin(expectedVin) || gateway.Vin != expectedVin ||
            frozen.Length is < 2 or > 16 || frozen.Any(e => e.Length is < 0 or > 4095) ||
            (obd && frozen.Any(e => ObdOnUdsServices.ClassifyDid(e.Did) == ObdOnUdsServices.DataGroup.NotLegislated)))
            throw new ArgumentException("Invalid multi-DID target or layout.");
        var request = UdsMultiDidCli.BuildRequest(frozen);
        await using var link = await ConnectObservedAsync(gateway, address, tester, ecu,
            tls, timeout, token, certificate, trace, options);
        async Task VerifyAsync()
        {
            if (obd)
            {
                var observed = await ReadVerifiedObdAsync(link, expectedVin, null, timeout, token);
                if (observed.ProtocolValue != 1)
                    throw new InvalidDataException("Selected ECU did not identify as OBDonUDS.");
            }
            else if (await VerifyObservedVinAsync(link, gateway, timeout, token, null, null) != expectedVin)
                throw new InvalidDataException("ECU VIN mismatch.");
        }
        await VerifyAsync();
        var reply = await link.ExchangeUdsAsync(request, timeout, token);
        var values = UdsMultiDidCli.Decode(frozen, request, reply);
        await VerifyAsync();
        return values;
    }

    internal static async Task<ObdOnUdsServices.WwhDtcReport> ReadObservedWwhAsync(
        DoIpVehicleDiscovery.Identity gateway, IPAddress address, ushort tester, ushort ecu,
        bool tls, string expectedVin, byte subfunction, byte statusMask, byte severityMask,
        byte readinessGroup, TimeSpan timeout, CancellationToken token, string? certificate = null,
        Action<DoIpDiagnosticTransport.PduTrace>? trace = null,
        DoIpDiagnosticTransport.SessionOptions? options = null)
    {
        if (!DoIpVehicleDiscovery.IsVehicleVin(expectedVin) || gateway.Vin != expectedVin)
            throw new ArgumentException("Explicit matching VIN required.");
        var request = subfunction switch
        {
            0x42 => ObdOnUdsServices.ReadWwhEmissionsDtcs(statusMask, severityMask),
            0x55 => ObdOnUdsServices.ReadWwhPermanentEmissionsDtcs(),
            0x56 => ObdOnUdsServices.ReadWwhReadinessGroup(readinessGroup),
            _ => throw new ArgumentException("Unknown WWH DTC service.")
        };
        await using var link = await ConnectObservedAsync(gateway, address, tester, ecu,
            tls, timeout, token, certificate, trace, options);
        var before = await ReadVerifiedObdAsync(link, expectedVin, null, timeout, token);
        if (before.ProtocolValue != 1) throw new InvalidDataException("Selected ECU did not identify as OBDonUDS.");
        var reply = await link.ExchangeUdsAsync(request, timeout, token);
        ObdOnUdsServices.WwhDtcReport report;
        if (subfunction == 0x56)
        {
            var readiness = ObdOnUdsServices.DecodeWwhReadinessGroup(reply);
            if (readiness.ReadinessGroup != readinessGroup) throw new InvalidDataException("Readiness group mismatch.");
            report = readiness.DtcReport;
        }
        else report = subfunction == 0x42 ? ObdOnUdsServices.DecodeWwhEmissionsDtcs(reply)
            : ObdOnUdsServices.DecodeWwhPermanentEmissionsDtcs(reply);
        var after = await ReadVerifiedObdAsync(link, expectedVin, null, timeout, token);
        if (after.ProtocolValue != before.ProtocolValue) throw new InvalidDataException("OBDonUDS identification changed.");
        return report;
    }

    internal static async Task<byte[]> ReadObservedGatewayDidAsync(
        DoIpVehicleDiscovery.Identity gateway, IPAddress address, ushort tester,
        ushort ecu, bool useTls, ushort did, TimeSpan timeout,
        CancellationToken token, Action<byte[]>? requested = null,
        Action<byte[]>? received = null, string? clientCertificateThumbprint = null,
        Action<DoIpDiagnosticTransport.PduTrace>? trace = null,
        DoIpDiagnosticTransport.SessionOptions? options = null)
    {
        await using var transport = await ConnectObservedAsync(gateway, address, tester,
            ecu, useTls, timeout, token, clientCertificateThumbprint, trace, options);
        var vin = await VerifyObservedVinAsync(transport, gateway, timeout, token, requested, received);
        if (did == 0xF190) return Encoding.ASCII.GetBytes(vin);
        var data = await ReadDidAsync(transport, did, timeout, token, requested, received);
        if (data.Length == 0) throw new InvalidDataException("Missing DID data.");
        if (await VerifyObservedVinAsync(transport, gateway, timeout, token, requested, received) != vin)
            throw new InvalidDataException("ECU VIN changed during DID read.");
        return data;
    }

    internal static async Task<byte[]> ReadObservedScalingDidAsync(
        DoIpVehicleDiscovery.Identity gateway, IPAddress address, ushort tester,
        ushort ecu, bool useTls, ushort did, TimeSpan timeout, CancellationToken token,
        string? certificate = null, Action<DoIpDiagnosticTransport.PduTrace>? trace = null,
        DoIpDiagnosticTransport.SessionOptions? options = null)
    {
        await using var link = await ConnectObservedAsync(gateway, address, tester, ecu,
            useTls, timeout, token, certificate, trace, options);
        var vin = await VerifyObservedVinAsync(link, gateway, timeout, token, null, null);
        var reply = await link.ExchangeUdsAsync(UdsStandardServices.ReadScalingDid(did), timeout, token);
        if (reply.Length < 4 || reply[0] != 0x64 || reply[1] != (byte)(did >> 8) || reply[2] != (byte)did)
            throw new InvalidDataException("Invalid scaling DID response.");
        if (await VerifyObservedVinAsync(link, gateway, timeout, token, null, null) != vin)
            throw new InvalidDataException("ECU VIN changed during scaling data read.");
        return reply[3..];
    }

    internal static async Task MonitorDidAsync(
        DoIpDiagnosticTransport transport, ushort did, int sampleCount, TimeSpan interval,
        TimeSpan timeout, CancellationToken token, Action<int, byte[]> sample,
        Action<byte[]>? requested = null, Action<byte[]>? received = null)
    {
        ArgumentNullException.ThrowIfNull(sample);
        if (sampleCount is < 1 or > 3600 || interval < TimeSpan.FromMilliseconds(250) ||
            interval > TimeSpan.FromMinutes(1))
            throw new ArgumentOutOfRangeException(nameof(sampleCount));
        for (var index = 1; index <= sampleCount; index++)
        {
            token.ThrowIfCancellationRequested();
            var data = await ReadDidAsync(transport, did, timeout, token, requested, received);
            token.ThrowIfCancellationRequested();
            if (data.Length > 4096)
                throw new InvalidDataException("DID payload exceeds the monitoring sample limit; use a single read.");
            sample(index, data);
            token.ThrowIfCancellationRequested();
            if (index < sampleCount) await Task.Delay(interval, token);
        }
    }

    internal static async Task MonitorVerifiedObdAsync(
        DoIpDiagnosticTransport transport, string expectedVin, ushort did, int sampleCount,
        TimeSpan interval, TimeSpan timeout, CancellationToken token, Action<int, byte[]> sample)
    {
        if (!DoIpVehicleDiscovery.IsVehicleVin(expectedVin) ||
            ObdOnUdsServices.ClassifyDid(did) == ObdOnUdsServices.DataGroup.NotLegislated)
            throw new ArgumentException("Explicit VIN and legislated DID required.");
        ArgumentNullException.ThrowIfNull(sample);
        if (sampleCount is < 1 or > 3600 || interval < TimeSpan.FromMilliseconds(250) ||
            interval > TimeSpan.FromMinutes(1))
            throw new ArgumentOutOfRangeException(nameof(sampleCount));
        var before = await ReadVerifiedObdAsync(transport, expectedVin, null, timeout, token);
        if (before.ProtocolValue != 1)
            throw new InvalidDataException("Selected ECU did not identify as OBDonUDS.");
        await MonitorDidAsync(transport, did, sampleCount, interval, timeout, token, sample);
        var after = await ReadVerifiedObdAsync(transport, expectedVin, null, timeout, token);
        if (after.ProtocolValue != before.ProtocolValue)
            throw new InvalidDataException("OBDonUDS identification changed during monitoring.");
    }

    internal static async Task MonitorObservedObdAsync(
        DoIpVehicleDiscovery.Identity gateway, IPAddress address, ushort tester,
        ushort ecu, bool tls, string expectedVin, ushort did, int sampleCount,
        TimeSpan interval, TimeSpan timeout, CancellationToken token, Action<int, byte[]> sample,
        string? certificate = null, Action<DoIpDiagnosticTransport.PduTrace>? trace = null,
        DoIpDiagnosticTransport.SessionOptions? options = null)
    {
        if (!DoIpVehicleDiscovery.IsVehicleVin(expectedVin) || gateway.Vin != expectedVin ||
            ObdOnUdsServices.ClassifyDid(did) == ObdOnUdsServices.DataGroup.NotLegislated)
            throw new ArgumentException("Explicit matching VIN and legislated DID required.");
        await using var transport = await ConnectObservedAsync(gateway, address, tester,
            ecu, tls, timeout, token, certificate, trace, options);
        await MonitorVerifiedObdAsync(transport, expectedVin, did, sampleCount, interval,
            timeout, token, sample);
    }

    internal static async Task MonitorObservedGatewayDidAsync(
        DoIpVehicleDiscovery.Identity gateway, IPAddress address, ushort tester,
        ushort ecu, bool useTls, ushort did, int sampleCount, TimeSpan interval,
        TimeSpan timeout, CancellationToken token, Action<int, byte[]> sample,
        Action<byte[]>? requested = null, Action<byte[]>? received = null,
        string? clientCertificateThumbprint = null,
        Action<DoIpDiagnosticTransport.PduTrace>? trace = null,
        DoIpDiagnosticTransport.SessionOptions? options = null)
    {
        await using var transport = await ConnectObservedAsync(gateway, address, tester,
            ecu, useTls, timeout, token, clientCertificateThumbprint, trace, options);
        var vin = await VerifyObservedVinAsync(transport, gateway, timeout, token, requested, received);
        await MonitorDidAsync(transport, did, sampleCount, interval, timeout, token,
            sample, requested, received);
        var finalVin = await VerifyObservedVinAsync(transport, gateway, timeout, token, requested, received);
        if (!string.Equals(vin, finalVin, StringComparison.Ordinal))
            throw new InvalidDataException("UDS VIN changed during DoIP monitoring.");
    }

    internal static async Task<ObdOnUdsServices.DtcStatusReport> ReadObservedGatewayDtcsAsync(
        DoIpVehicleDiscovery.Identity gateway, IPAddress address, ushort tester,
        ushort ecu, bool useTls, byte statusMask, TimeSpan timeout,
        CancellationToken token, Action<byte[]>? requested = null,
        Action<byte[]>? received = null, string? clientCertificateThumbprint = null,
        Action<DoIpDiagnosticTransport.PduTrace>? trace = null,
        DoIpDiagnosticTransport.SessionOptions? options = null)
    {
        await using var transport = await ConnectObservedAsync(gateway, address, tester,
            ecu, useTls, timeout, token, clientCertificateThumbprint, trace, options);
        _ = await VerifyObservedVinAsync(transport, gateway, timeout, token, requested, received);
        return await ReadDtcsAsync(transport, statusMask, timeout, token, requested, received);
    }

    // Caller obtains consent for the exact immutable request and context.
    // No automatic replay on negative response, timeout or interrupted connection.
    internal static async Task<byte[]> ExchangeObservedGatewayAsync(
        DoIpVehicleDiscovery.Identity gateway, IPAddress address, ushort tester,
        ushort ecu, bool useTls, byte[] command, TimeSpan timeout, CancellationToken token,
        string? certificate = null, Action<DoIpDiagnosticTransport.PduTrace>? trace = null,
        DoIpDiagnosticTransport.SessionOptions? options = null)
    {
        ArgumentNullException.ThrowIfNull(command);
        if (command.Length is < 1 or > 4091 || gateway.Vin is null ||
            !DoIpVehicleDiscovery.IsVehicleVin(gateway.Vin))
            throw new ArgumentException("A valid observed VIN and explicit command are required.");
        var fixedCommand = command.ToArray();
        await using var transport = await ConnectObservedAsync(gateway, address, tester,
            ecu, useTls, timeout, token, certificate, trace, options);
        return await ExchangeVerifiedAsync(transport, gateway, fixedCommand, timeout, token);
    }

    internal static async Task<int> ExchangeSequenceObservedAsync(
        DoIpVehicleDiscovery.Identity gateway, IPAddress address, ushort tester, ushort ecu,
        bool useTls, IReadOnlyList<byte[]> commands, TimeSpan timeout, CancellationToken token,
        Action<int, byte[]> onReply, string? certificate = null,
        Action<DoIpDiagnosticTransport.PduTrace>? trace = null,
        DoIpDiagnosticTransport.SessionOptions? options = null)
    {
        var frozen = FreezeCommands(commands);
        if (gateway.Vin is null || !DoIpVehicleDiscovery.IsVehicleVin(gateway.Vin))
            throw new ArgumentException("A valid observed VIN is required.");
        await using var transport = await ConnectObservedAsync(gateway, address, tester, ecu,
            useTls, timeout, token, certificate, trace, options);
        return await ExchangeSequenceVerifiedAsync(transport, gateway, frozen, timeout, token, onReply);
    }

    private static byte[][] FreezeCommands(IReadOnlyList<byte[]> commands)
    {
        ArgumentNullException.ThrowIfNull(commands);
        if (commands.Count is < 1 or > 16 || commands.Any(c => c is null || c.Length is < 1 or > 4091) ||
            commands.Sum(c => (long)c.Length) > 4091)
            throw new ArgumentException("Expected 1..16 commands, at most 4091 total bytes.");
        return commands.Select(c => c.ToArray()).ToArray();
    }

    internal static async Task<int> ExchangeSequenceVerifiedAsync(DoIpDiagnosticTransport transport,
        DoIpVehicleDiscovery.Identity gateway, IReadOnlyList<byte[]> commands, TimeSpan timeout,
        CancellationToken token, Action<int, byte[]> onReply,
        TimeSpan? p2Timeout = null, TimeSpan? p2StarTimeout = null)
    {
        ArgumentNullException.ThrowIfNull(onReply);
        var frozen = FreezeCommands(commands);
        _ = await VerifyObservedVinAsync(transport, gateway, timeout, token, null, null);
        for (var i = 0; i < frozen.Length; i++)
        {
            token.ThrowIfCancellationRequested();
            var reply = await transport.ExchangeUdsAsync(frozen[i], timeout, token, p2Timeout, p2StarTimeout);
            token.ThrowIfCancellationRequested();
            onReply(i + 1, reply.ToArray());
        }
        token.ThrowIfCancellationRequested();
        return frozen.Length;
    }

    internal static async Task<byte[]> ExchangeVerifiedAsync(DoIpDiagnosticTransport transport,
        DoIpVehicleDiscovery.Identity gateway, byte[] command, TimeSpan timeout, CancellationToken token)
    {
        var fixedCommand = command.ToArray();
        _ = await VerifyObservedVinAsync(transport, gateway, timeout, token, null, null);
        token.ThrowIfCancellationRequested();
        return await transport.ExchangeUdsAsync(fixedCommand, timeout, token);
    }

    internal static async Task<ObdOnUdsServices.DtcCountReport> CountObservedGatewayDtcsAsync(
        DoIpVehicleDiscovery.Identity gateway, IPAddress address, ushort tester, ushort ecu,
        bool useTls, byte statusMask, TimeSpan timeout, CancellationToken token,
        string? certificate = null, Action<DoIpDiagnosticTransport.PduTrace>? trace = null,
        DoIpDiagnosticTransport.SessionOptions? options = null)
    {
        await using var transport = await ConnectObservedAsync(gateway, address, tester,
            ecu, useTls, timeout, token, certificate, trace, options);
        _ = await VerifyObservedVinAsync(transport, gateway, timeout, token, null, null);
        var reply = await ExchangeReadAsync(transport,
            ObdOnUdsServices.ReadDtcCountByStatusMask(statusMask), timeout, token, null, null);
        return ObdOnUdsServices.DecodeDtcCountByStatusMask(reply);
    }

    internal sealed record DtcDetail(uint Dtc, byte Status, byte[] Records);

    internal static async Task<DtcDetail> ReadObservedGatewayDtcDetailAsync(
        DoIpVehicleDiscovery.Identity gateway, IPAddress address, ushort tester, ushort ecu,
        bool useTls, uint dtc, byte record, bool extended, TimeSpan timeout, CancellationToken token,
        string? certificate = null, Action<DoIpDiagnosticTransport.PduTrace>? trace = null,
        DoIpDiagnosticTransport.SessionOptions? options = null)
    {
        var request = extended ? ObdOnUdsServices.ReadDtcExtendedData(dtc, record) :
            ObdOnUdsServices.ReadDtcSnapshot(dtc, record);
        await using var transport = await ConnectObservedAsync(gateway, address, tester,
            ecu, useTls, timeout, token, certificate, trace, options);
        _ = await VerifyObservedVinAsync(transport, gateway, timeout, token, null, null);
        var reply = await ExchangeReadAsync(transport, request, timeout, token, null, null);
        if (extended)
        {
            var detail = ObdOnUdsServices.DecodeDtcExtendedData(dtc, reply);
            return new DtcDetail(detail.Dtc, detail.Status, detail.RawExtendedDataRecords);
        }
        var snapshot = ObdOnUdsServices.DecodeDtcSnapshot(dtc, reply);
        return new DtcDetail(snapshot.Dtc, snapshot.Status, snapshot.RawSnapshotRecords);
    }

    internal static async Task<string> ReadObservedGatewayAsync(
        DoIpVehicleDiscovery.Identity gateway, IPAddress address, ushort tester,
        ushort ecu, bool useTls, TimeSpan timeout, CancellationToken token,
        Action<byte[]>? requested = null, Action<byte[]>? received = null,
        string? clientCertificateThumbprint = null,
        Action<DoIpDiagnosticTransport.PduTrace>? trace = null,
        DoIpDiagnosticTransport.SessionOptions? options = null)
    {
        await using var transport = await ConnectObservedAsync(gateway, address, tester,
            ecu, useTls, timeout, token, clientCertificateThumbprint, trace, options);
        return await VerifyObservedVinAsync(transport, gateway, timeout, token, requested, received);
    }
}
