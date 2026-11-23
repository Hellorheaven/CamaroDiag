using System.Net.Security;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Security.Cryptography.X509Certificates;

namespace CamaroDiag.J2534Bridge;

/// <summary>
/// ISO 13400 TCP client for both diagnostic reading AND writing over UDS.
/// The transport never invents a service blacklist or OEM seed/key.
/// Mutating transactions require the caller's existing OK then Oui/Non consent.
/// OEM programming/session requirements remain the responsibility of the caller.
/// TLS uses the secured DoIP port and the operating-system trust store.
/// OEM client certificates or private OEM authorities may require additional
/// configuration not represented by this minimal transport.
/// </summary>
internal sealed class DoIpDiagnosticTransport : IAsyncDisposable
{
    private readonly TcpClient socket;
    private readonly Stream stream;
    private readonly ushort testerAddress;
    private readonly ushort ecuAddress;
    private readonly byte protocolVersion;

    private DoIpDiagnosticTransport(TcpClient socket, Stream dataStream,
        ushort tester, ushort ecu, byte version)
    {
        this.socket = socket;
        stream = dataStream;
        testerAddress = tester;
        ecuAddress = ecu;
        protocolVersion = version;
    }

    internal static async Task<DoIpDiagnosticTransport> ConnectAsync(
        string host, ushort tester, ushort ecu, TimeSpan timeout,
        CancellationToken cancellationToken, bool useTls = false,
        byte protocolVersion = DoIpCodec.Version,
        string? clientCertificateThumbprint = null)
    {
        if (string.IsNullOrWhiteSpace(host) || host.Length > 253 ||
            tester == ecu || timeout <= TimeSpan.Zero ||
            timeout > TimeSpan.FromSeconds(30) ||
            protocolVersion is not (0x02 or 0x03) ||
            (clientCertificateThumbprint is not null && !useTls))
            throw new ArgumentException("Explicit gateway, distinct logical addresses and timeout required.");
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        deadline.CancelAfter(timeout);
        var socket = new TcpClient();
        try
        {
            await socket.ConnectAsync(host, useTls ? 3496 : 13400, deadline.Token);
            Stream dataStream = socket.GetStream();
            if (useTls)
            {
                // Never downgrade to TCP or bypass certificate/hostname
                // verification when an OEM gateway requires secure DoIP.
                var tls = new SslStream(dataStream, leaveInnerStreamOpen: false);
                var clientCert = clientCertificateThumbprint is null ? null :
                    FindClientCertificate(clientCertificateThumbprint);
                var authentication = new SslClientAuthenticationOptions
                {
                    TargetHost = host,
                    EnabledSslProtocols = SslProtocols.Tls12 | SslProtocols.Tls13,
                    CertificateRevocationCheckMode = X509RevocationMode.Online
                };
                if (clientCert is not null)
                    authentication.ClientCertificates =
                        new X509CertificateCollection { clientCert };
                await tls.AuthenticateAsClientAsync(authentication, deadline.Token);
                dataStream = tls;
            }
            var session = new DoIpDiagnosticTransport(socket, dataStream,
                tester, ecu, protocolVersion);
            await session.SendAsync(DoIpCodec.RoutingActivation(
                tester, version: protocolVersion), deadline.Token);
            DoIpCodec.Message routing;
            do
            {
                routing = await session.ReceiveAsync(deadline.Token);
                if (routing.Type == DoIpCodec.AliveCheckRequest)
                    await session.SendAsync(DoIpCodec.ReplyToAliveCheck(
                        routing, tester), deadline.Token);
            }
            while (routing.Type == DoIpCodec.AliveCheckRequest);
            DoIpCodec.RequireNoGenericNack(routing);
            if (routing.Version != protocolVersion)
                throw new InvalidDataException(
                    "DoIP gateway routing response used an unexpected protocol version.");
            DoIpCodec.RequireRoutingActive(routing, tester);
            return session;
        }
        catch
        {
            socket.Dispose();
            throw;
        }
    }

    /// <summary>
    /// Use an already provisioned Windows client certificate, with private
    /// key held in the OS. Never accept PFX passwords or bypass server trust
    /// checks through the command-line transport.
    /// </summary>
    internal static X509Certificate2 FindClientCertificate(string thumbprint)
    {
        if (string.IsNullOrWhiteSpace(thumbprint))
            throw new ArgumentException("TLS client certificate thumbprint required.");
        var normalized = new string(thumbprint
            .Where(c => !char.IsWhiteSpace(c)).ToArray());
        if (normalized.Length != 40 ||
            !normalized.All(Uri.IsHexDigit))
            throw new ArgumentException(
                "Windows TLS certificate thumbprint must be 40 hexadecimal digits.");
        foreach (var location in new[]
            { StoreLocation.CurrentUser, StoreLocation.LocalMachine })
        {
            using var store = new X509Store(StoreName.My, location);
            store.Open(OpenFlags.ReadOnly);
            var matching = store.Certificates.Find(
                X509FindType.FindByThumbprint, normalized,
                validOnly: false);
            foreach (var certificate in matching)
            {
                if (certificate.HasPrivateKey &&
                    DateTime.UtcNow >= certificate.NotBefore.ToUniversalTime() &&
                    DateTime.UtcNow <= certificate.NotAfter.ToUniversalTime())
                    return certificate;
            }
        }
        throw new AuthenticationException(
            "Requested DoIP client certificate is missing, expired or has no private key.");
    }

    internal async Task<byte[]> ExchangeUdsAsync(byte[] request,
        TimeSpan timeout, CancellationToken cancellationToken)
    {
        if (request is null || request.Length == 0 || request.Length > DoIpCodec.MaxPayload - 4)
            throw new ArgumentException("UDS request must fit the DoIP diagnostic PDU.", nameof(request));
        if (timeout <= TimeSpan.Zero || timeout > TimeSpan.FromSeconds(30))
            throw new ArgumentOutOfRangeException(nameof(timeout));
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        deadline.CancelAfter(timeout);
        await SendAsync(DoIpCodec.Diagnostic(
            testerAddress, ecuAddress, request, protocolVersion), deadline.Token);
        // UDS NRC 0x78 (ResponsePending), DoIP diagnostic ACKs and Alive
        // Check requests are legitimate interim messages. An arbitrary
        // count limit can terminate a valid long-running routine. The
        // caller's bounded deadline, not the number of interim frames,
        // controls the full exchange.
        while (true)
        {
            deadline.Token.ThrowIfCancellationRequested();
            var message = await ReceiveAsync(deadline.Token);
            DoIpCodec.RequireNoGenericNack(message);
            if (message.Version != protocolVersion)
                throw new InvalidDataException(
                    "DoIP diagnostic response changed the negotiated protocol version.");
            if (message.Type == DoIpCodec.AliveCheckRequest)
            {
                await SendAsync(DoIpCodec.ReplyToAliveCheck(
                    message, testerAddress), deadline.Token);
                continue;
            }
            if (DoIpCodec.IsCorrelatedDiagnosticAck(
                message, testerAddress, ecuAddress, request))
                continue;
            var uds = DoIpCodec.RequireDiagnosticResponse(message, testerAddress, ecuAddress);
            // The UDS server may report a negative response. Do not mistake
            // it for a passing post-programming self-test.
            if (uds.Length >= 3 && uds[0] == 0x7F && uds[1] == request[0])
            {
                if (uds[2] == 0x78) continue; // UDS ResponsePending; stay within deadline
                throw new IOException($"ECU negative UDS response: 0x{uds[2]:X2}.");
            }
            // One ISO 14229 correlation implementation for CAN and DoIP:
            // SID, DID, routine ID, security/session subfunction and
            // TransferData sequence are checked in the same place.
            return UdsStandardServices.RequireResponse(request, uds);
        }
    }

    private async Task SendAsync(byte[] frame, CancellationToken token)
    {
        await stream.WriteAsync(frame, token);
        await stream.FlushAsync(token);
    }

    private async Task<DoIpCodec.Message> ReceiveAsync(CancellationToken token)
    {
        var header = new byte[8];
        await stream.ReadExactlyAsync(header, token);
        var length = DoIpCodec.ReadPayloadLength(header);
        var message = new byte[8 + length];
        header.CopyTo(message, 0);
        if (length > 0) await stream.ReadExactlyAsync(message.AsMemory(8), token);
        return DoIpCodec.Decode(message);
    }

    public ValueTask DisposeAsync()
    {
        stream.Dispose();
        socket.Dispose();
        return ValueTask.CompletedTask;
    }
}
