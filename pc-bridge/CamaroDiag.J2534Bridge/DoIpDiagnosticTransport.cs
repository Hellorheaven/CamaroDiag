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
    // 0 = ready, 1 = one UDS exchange in progress, 2 = closed/uncertain.
    // DoIP carries no per-request transaction ID. A late reply after a
    // failed exchange could otherwise be mistaken for a fresh request.
    private int exchangeState;

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
        string? clientCertificateThumbprint = null,
        int? portOverride = null, byte activationType = 0x00,
        byte[]? activationOemData = null)
    {
        if (string.IsNullOrWhiteSpace(host) || host.Length > 253 ||
            tester == ecu || timeout <= TimeSpan.Zero ||
            timeout > TimeSpan.FromSeconds(30) ||
            protocolVersion is not (0x02 or 0x03 or 0x04) ||
            (portOverride.HasValue && portOverride.Value is < 1 or > 65535) ||
            (activationOemData is not null && activationOemData.Length != 4) ||
            (clientCertificateThumbprint is not null && !useTls))
            throw new ArgumentException("Explicit gateway, distinct logical addresses and timeout required.");
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        deadline.CancelAfter(timeout);
        var socket = new TcpClient();
        try
        {
            await socket.ConnectAsync(host,
                portOverride ?? (useTls ? 3496 : 13400), deadline.Token);
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
                tester, activationType: activationType,
                version: protocolVersion,
                oemSpecificData: activationOemData), deadline.Token);
            // Code 0x11 is a pending vehicle-side confirmation, not a
            // registered diagnostic routing state. Keep waiting within
            // the original connection deadline for code 0x10.
            var pendingConfirmation = false;
            while (true)
            {
                var routing = await session.ReceiveAsync(deadline.Token);
                DoIpCodec.RequireNoGenericNack(routing);
                if (routing.Version != protocolVersion)
                    throw new InvalidDataException(
                        "DoIP gateway routing response changed protocol version.");
                if (routing.Type == DoIpCodec.AliveCheckRequest)
                {
                    await session.SendAsync(DoIpCodec.ReplyToAliveCheck(
                        routing, tester), deadline.Token);
                    continue;
                }
                var status = DoIpCodec.ReadRoutingActivationCode(routing, tester);
                if (status == 0x11)
                {
                    pendingConfirmation = true;
                    continue;
                }
                DoIpCodec.RequireRoutingActive(routing, tester);
                if (pendingConfirmation)
                    Console.WriteLine("DoIP vehicle confirmation received; routing is now active.");
                break;
            }
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
        TimeSpan timeout, CancellationToken cancellationToken,
        TimeSpan? p2Timeout = null, TimeSpan? p2StarTimeout = null)
    {
        if (request is null || request.Length == 0 ||
            request.Length > DoIpCodec.MaxPayload - 4)
            throw new ArgumentException(
                "UDS request must fit the DoIP diagnostic PDU.", nameof(request));
        if (timeout <= TimeSpan.Zero || timeout > TimeSpan.FromSeconds(30))
            throw new ArgumentOutOfRangeException(nameof(timeout));
        // Reject malformed stage settings *before* sending any potentially
        // modifying UDS request to the ECU, even through non-CLI callers.
        if ((p2Timeout.HasValue &&
                (p2Timeout.Value <= TimeSpan.Zero ||
                 p2Timeout.Value > TimeSpan.FromMinutes(10))) ||
            (p2StarTimeout.HasValue &&
                (p2StarTimeout.Value <= TimeSpan.Zero ||
                 p2StarTimeout.Value > TimeSpan.FromMinutes(10))))
            throw new ArgumentOutOfRangeException(nameof(p2Timeout),
                "UDS P2/P2* must be positive and bounded.");
        cancellationToken.ThrowIfCancellationRequested();
        var previous = Interlocked.CompareExchange(ref exchangeState, 1, 0);
        if (previous != 0)
            throw new InvalidOperationException(previous == 1 ?
                "DoIP socket already has an active UDS exchange." :
                "DoIP session was closed or its previous exchange is uncertain; reconnect.");
        try
        {
        // Keep the complete request/reply transaction budget, including TX.
        var overall = System.Diagnostics.Stopwatch.StartNew();
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        deadline.CancelAfter(timeout);
        await SendAsync(DoIpCodec.Diagnostic(
            testerAddress, ecuAddress, request, protocolVersion), deadline.Token);
        var remaining = timeout - overall.Elapsed;
        if (remaining <= TimeSpan.Zero)
            throw new TimeoutException("DoIP diagnostic TX exhausted exchange budget.");

        // ACKs and gateway AliveCheck messages are not ECU UDS responses.
        // They cannot reset the P2/P2* timer or the overall deadline.
        var response = await UdsResponseSequence.AwaitFinalAsync(request,
            async (budget, token) =>
            {
                using var stage = CancellationTokenSource.CreateLinkedTokenSource(token);
                stage.CancelAfter(budget);
                while (true)
                {
                    DoIpCodec.Message message;
                    try { message = await ReceiveAsync(stage.Token); }
                    catch (OperationCanceledException) when (
                        !token.IsCancellationRequested && stage.IsCancellationRequested)
                    {
                        throw new TimeoutException("DoIP diagnostic UDS stage deadline exceeded.");
                    }
                    DoIpCodec.RequireNoGenericNack(message);
                    if (message.Version != protocolVersion)
                        throw new InvalidDataException(
                            "DoIP response changed the negotiated protocol version.");
                    if (message.Type == DoIpCodec.AliveCheckRequest)
                    {
                        await SendAsync(DoIpCodec.ReplyToAliveCheck(
                            message, testerAddress), stage.Token);
                        continue;
                    }
                    if (DoIpCodec.IsCorrelatedDiagnosticAck(
                        message, testerAddress, ecuAddress, request))
                        continue;
                    return DoIpCodec.RequireDiagnosticResponse(
                        message, testerAddress, ecuAddress);
                }
            },
            remaining, deadline.Token, p2Timeout, p2StarTimeout);
        if (Interlocked.CompareExchange(ref exchangeState, 0, 1) != 1)
            throw new ObjectDisposedException(nameof(DoIpDiagnosticTransport));
        return response;
        }
        catch
        {
            // Even a cancelled/timed-out write may already be on the TCP
            // wire. Never silently retry it or allow a late reply to be
            // interpreted as belonging to another operation.
            Interlocked.Exchange(ref exchangeState, 2);
            stream.Dispose();
            socket.Dispose();
            throw;
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
        Interlocked.Exchange(ref exchangeState, 2);
        stream.Dispose();
        socket.Dispose();
        return ValueTask.CompletedTask;
    }
}
