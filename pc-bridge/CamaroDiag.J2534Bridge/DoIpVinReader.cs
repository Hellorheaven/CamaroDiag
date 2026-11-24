using System.Globalization;
using System.Net;
using System.Text;

namespace CamaroDiag.J2534Bridge;

/// <summary>Read-only UDS application services over a discovered DoIP gateway.</summary>
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
        string? clientCertificateThumbprint)
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
            expectedGatewayAddress: gateway.LogicalAddress);
    }

    internal static async Task<byte[]> ReadObservedGatewayDidAsync(
        DoIpVehicleDiscovery.Identity gateway, IPAddress address, ushort tester,
        ushort ecu, bool useTls, ushort did, TimeSpan timeout,
        CancellationToken token, Action<byte[]>? requested = null,
        Action<byte[]>? received = null, string? clientCertificateThumbprint = null)
    {
        await using var transport = await ConnectObservedAsync(gateway, address, tester,
            ecu, useTls, timeout, token, clientCertificateThumbprint);
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
        string? clientCertificateThumbprint = null)
    {
        await using var transport = await ConnectObservedAsync(gateway, address, tester,
            ecu, useTls, timeout, token, clientCertificateThumbprint);
        await MonitorDidAsync(transport, did, sampleCount, interval, timeout, token,
            sample, requested, received);
    }

    internal static async Task<ObdOnUdsServices.DtcStatusReport> ReadObservedGatewayDtcsAsync(
        DoIpVehicleDiscovery.Identity gateway, IPAddress address, ushort tester,
        ushort ecu, bool useTls, byte statusMask, TimeSpan timeout,
        CancellationToken token, Action<byte[]>? requested = null,
        Action<byte[]>? received = null, string? clientCertificateThumbprint = null)
    {
        await using var transport = await ConnectObservedAsync(gateway, address, tester,
            ecu, useTls, timeout, token, clientCertificateThumbprint);
        return await ReadDtcsAsync(transport, statusMask, timeout, token, requested, received);
    }

    internal static async Task<string> ReadObservedGatewayAsync(
        DoIpVehicleDiscovery.Identity gateway, IPAddress address, ushort tester,
        ushort ecu, bool useTls, TimeSpan timeout, CancellationToken token,
        Action<byte[]>? requested = null, Action<byte[]>? received = null,
        string? clientCertificateThumbprint = null)
    {
        await using var transport = await ConnectObservedAsync(gateway, address, tester,
            ecu, useTls, timeout, token, clientCertificateThumbprint);
        var vin = await ReadAsync(transport, timeout, token, requested, received);
        if (gateway.Vin is not null && !string.Equals(gateway.Vin, vin, StringComparison.Ordinal))
            throw new InvalidDataException("UDS VIN differs from the observed DoIP vehicle identity.");
        token.ThrowIfCancellationRequested();
        return vin;
    }
}
