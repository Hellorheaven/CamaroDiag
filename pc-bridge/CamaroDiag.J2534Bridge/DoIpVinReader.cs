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
        string? clientCertificateThumbprint, Action<DoIpDiagnosticTransport.PduTrace>? trace = null)
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
            expectedGatewayAddress: gateway.LogicalAddress, trace: trace);
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

    internal static async Task<byte[]> ReadObservedGatewayDidAsync(
        DoIpVehicleDiscovery.Identity gateway, IPAddress address, ushort tester,
        ushort ecu, bool useTls, ushort did, TimeSpan timeout,
        CancellationToken token, Action<byte[]>? requested = null,
        Action<byte[]>? received = null, string? clientCertificateThumbprint = null,
        Action<DoIpDiagnosticTransport.PduTrace>? trace = null)
    {
        await using var transport = await ConnectObservedAsync(gateway, address, tester,
            ecu, useTls, timeout, token, clientCertificateThumbprint, trace);
        var vin = await VerifyObservedVinAsync(transport, gateway, timeout, token, requested, received);
        if (did == 0xF190) return Encoding.ASCII.GetBytes(vin);
        return await ReadDidAsync(transport, did, timeout, token, requested, received);
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

    internal static async Task MonitorObservedGatewayDidAsync(
        DoIpVehicleDiscovery.Identity gateway, IPAddress address, ushort tester,
        ushort ecu, bool useTls, ushort did, int sampleCount, TimeSpan interval,
        TimeSpan timeout, CancellationToken token, Action<int, byte[]> sample,
        Action<byte[]>? requested = null, Action<byte[]>? received = null,
        string? clientCertificateThumbprint = null,
        Action<DoIpDiagnosticTransport.PduTrace>? trace = null)
    {
        await using var transport = await ConnectObservedAsync(gateway, address, tester,
            ecu, useTls, timeout, token, clientCertificateThumbprint, trace);
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
        Action<DoIpDiagnosticTransport.PduTrace>? trace = null)
    {
        await using var transport = await ConnectObservedAsync(gateway, address, tester,
            ecu, useTls, timeout, token, clientCertificateThumbprint, trace);
        _ = await VerifyObservedVinAsync(transport, gateway, timeout, token, requested, received);
        return await ReadDtcsAsync(transport, statusMask, timeout, token, requested, received);
    }

    // Caller obtains consent for the exact immutable request and context.
    // No automatic replay on negative response, timeout or interrupted connection.
    internal static async Task<byte[]> ExchangeObservedGatewayAsync(
        DoIpVehicleDiscovery.Identity gateway, IPAddress address, ushort tester,
        ushort ecu, bool useTls, byte[] command, TimeSpan timeout, CancellationToken token,
        string? certificate = null, Action<DoIpDiagnosticTransport.PduTrace>? trace = null)
    {
        ArgumentNullException.ThrowIfNull(command);
        if (command.Length is < 1 or > 4091 || gateway.Vin is null ||
            !DoIpVehicleDiscovery.IsVehicleVin(gateway.Vin))
            throw new ArgumentException("A valid observed VIN and explicit command are required.");
        var fixedCommand = command.ToArray();
        await using var transport = await ConnectObservedAsync(gateway, address, tester,
            ecu, useTls, timeout, token, certificate, trace);
        return await ExchangeVerifiedAsync(transport, gateway, fixedCommand, timeout, token);
    }

    internal static async Task<int> ExchangeSequenceObservedAsync(
        DoIpVehicleDiscovery.Identity gateway, IPAddress address, ushort tester, ushort ecu,
        bool useTls, IReadOnlyList<byte[]> commands, TimeSpan timeout, CancellationToken token,
        Action<int, byte[]> onReply, string? certificate = null,
        Action<DoIpDiagnosticTransport.PduTrace>? trace = null)
    {
        var frozen = FreezeCommands(commands);
        if (gateway.Vin is null || !DoIpVehicleDiscovery.IsVehicleVin(gateway.Vin))
            throw new ArgumentException("A valid observed VIN is required.");
        await using var transport = await ConnectObservedAsync(gateway, address, tester, ecu,
            useTls, timeout, token, certificate, trace);
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
        string? certificate = null, Action<DoIpDiagnosticTransport.PduTrace>? trace = null)
    {
        await using var transport = await ConnectObservedAsync(gateway, address, tester,
            ecu, useTls, timeout, token, certificate, trace);
        _ = await VerifyObservedVinAsync(transport, gateway, timeout, token, null, null);
        var reply = await ExchangeReadAsync(transport,
            ObdOnUdsServices.ReadDtcCountByStatusMask(statusMask), timeout, token, null, null);
        return ObdOnUdsServices.DecodeDtcCountByStatusMask(reply);
    }

    internal sealed record DtcDetail(uint Dtc, byte Status, byte[] Records);

    internal static async Task<DtcDetail> ReadObservedGatewayDtcDetailAsync(
        DoIpVehicleDiscovery.Identity gateway, IPAddress address, ushort tester, ushort ecu,
        bool useTls, uint dtc, byte record, bool extended, TimeSpan timeout, CancellationToken token,
        string? certificate = null, Action<DoIpDiagnosticTransport.PduTrace>? trace = null)
    {
        var request = extended ? ObdOnUdsServices.ReadDtcExtendedData(dtc, record) :
            ObdOnUdsServices.ReadDtcSnapshot(dtc, record);
        await using var transport = await ConnectObservedAsync(gateway, address, tester,
            ecu, useTls, timeout, token, certificate, trace);
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
        Action<DoIpDiagnosticTransport.PduTrace>? trace = null)
    {
        await using var transport = await ConnectObservedAsync(gateway, address, tester,
            ecu, useTls, timeout, token, clientCertificateThumbprint, trace);
        return await VerifyObservedVinAsync(transport, gateway, timeout, token, requested, received);
    }
}
