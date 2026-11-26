using System.Buffers.Binary;
using System.Runtime.ExceptionServices;
using System.Threading.Channels;
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
    internal sealed record SessionOptions(int P2Ms, int P2StarMs, byte RoutingType, string OemHex)
    {
        internal static SessionOptions Parse(int totalMs, string p2, string p2Star, string routing, string oem)
        {
            if (routing.Length != 2 || !byte.TryParse(routing,
                System.Globalization.NumberStyles.AllowHexSpecifier,
                System.Globalization.CultureInfo.InvariantCulture, out var routingType))
                throw new ArgumentException("Routing type must contain two hexadecimal digits.");
            static int Stage(string value, int fallback) => string.IsNullOrWhiteSpace(value) ? fallback :
                int.TryParse(value, System.Globalization.NumberStyles.None,
                    System.Globalization.CultureInfo.InvariantCulture, out var parsed) ? parsed :
                    throw new ArgumentException("Invalid UDS response timing.");
            var options = new SessionOptions(Stage(p2, totalMs), Stage(p2Star, totalMs),
                routingType, oem.ToUpperInvariant());
            options.Validate(TimeSpan.FromMilliseconds(totalMs));
            return options;
        }

        internal void Validate(TimeSpan total)
        {
            if (P2Ms < 1 || P2StarMs < P2Ms || P2StarMs > total.TotalMilliseconds ||
                OemHex is null || (OemHex.Length != 0 && (OemHex.Length != 8 || !OemHex.All(Uri.IsHexDigit))))
                throw new ArgumentException("Invalid bounded UDS timing or four-byte OEM routing data.");
        }

        internal byte[]? OemBytes => OemHex.Length == 0 ? null : Convert.FromHexString(OemHex);
    }

    private readonly SessionOptions? sessionOptions;
    private readonly TcpClient socket;
    private readonly Stream stream;
    private readonly ushort testerAddress;
    private readonly ushort ecuAddress;
    private ushort? routedGatewayAddress;
    internal bool MatchesLogicalTarget(ushort gateway, ushort tester, ushort ecu) =>
        routedGatewayAddress == gateway && testerAddress == tester && ecuAddress == ecu;
    private readonly byte protocolVersion;
    internal sealed record PduTrace(string Direction, DateTimeOffset ObservedAtUtc, byte[] Bytes);
    internal sealed class TraceObserverException(Exception inner)
        : InvalidOperationException("DoIP trace observer failed: " + inner.Message, inner) { }
    private readonly Action<PduTrace>? trace;
    private void Trace(string direction, byte[] frame)
    {
        // Give the observer a private copy; it cannot alter a transmitted
        // request or received response. Includes diagnostic and control PDUs.
        if (trace is null) return;
        try { trace(new PduTrace(direction, DateTimeOffset.UtcNow, frame.ToArray())); }
        catch (Exception error)
        {
            // Evidence sink errors must never be mistaken for ECU timeouts
            // or communication failures eligible for inventory continuation.
            throw new TraceObserverException(error);
        }
    }
    // 0 = ready, 1 = one UDS exchange in progress, 2 = closed/uncertain.
    // DoIP carries no per-request transaction ID. A late reply after a
    // failed exchange could otherwise be mistaken for a fresh request.
    private int exchangeState;
    private readonly object exchangeBoundary = new();
    private int disposed;
    // One owner reads from the TCP stream for the entire activated session.
    // Gateway AliveChecks must be answered even while no UDS exchange runs.
    // Channel is bounded: uncorrelated or flooding peers cannot accumulate
    // unlimited responses. DoIP has no universal request transaction ID.
    private readonly Channel<DoIpCodec.Message> inbound =
        Channel.CreateBounded<DoIpCodec.Message>(new BoundedChannelOptions(32)
        {
            SingleReader = true,
            SingleWriter = true,
            FullMode = BoundedChannelFullMode.Wait
        });
    private readonly SemaphoreSlim writeGate = new(1, 1);
    private readonly CancellationTokenSource lifetime = new();
    private Task? receiveTask;
    private Exception? receiveFailure;
    // A clean EOF between PDUs is not equivalent to a truncated PDU.
    private sealed class PeerClosedAtBoundaryException : EndOfStreamException
    {
        internal PeerClosedAtBoundaryException() : base("DoIP peer closed at a PDU boundary.") { }
    }

    private DoIpDiagnosticTransport(TcpClient socket, Stream dataStream,
        ushort tester, ushort ecu, byte version, Action<PduTrace>? trace, SessionOptions? sessionOptions)
    {
        this.socket = socket;
        stream = dataStream;
        testerAddress = tester;
        ecuAddress = ecu;
        protocolVersion = version;
        this.trace = trace;
        this.sessionOptions = sessionOptions;
    }

    internal static async Task<DoIpDiagnosticTransport> ConnectAsync(
        string host, ushort tester, ushort ecu, TimeSpan timeout,
        CancellationToken cancellationToken, bool useTls = false,
        byte protocolVersion = DoIpCodec.Version,
        string? clientCertificateThumbprint = null,
        int? portOverride = null, byte activationType = 0x00,
        byte[]? activationOemData = null, ushort? expectedGatewayAddress = null,
        Action<PduTrace>? trace = null, SessionOptions? sessionOptions = null)
    {
        if (sessionOptions is not null)
        {
            sessionOptions.Validate(timeout);
            if (activationType != 0 || activationOemData is not null)
                throw new ArgumentException("Use one source of routing activation parameters.");
            activationType = sessionOptions.RoutingType;
            activationOemData = sessionOptions.OemBytes;
        }
        if (string.IsNullOrWhiteSpace(host) || host.Length > 253 ||
            tester == ecu || timeout <= TimeSpan.Zero ||
            timeout > TimeSpan.FromSeconds(30) ||
            protocolVersion is not (0x02 or 0x03 or 0x04) ||
            (portOverride.HasValue && portOverride.Value is < 1 or > 65535) ||
            (activationOemData is not null && activationOemData.Length != 4) ||
            (clientCertificateThumbprint is not null && !useTls))
            throw new ArgumentException("Explicit gateway, distinct logical addresses and timeout required.");
        if (System.Net.IPAddress.TryParse(host, out var numericAddress) &&
            !DoIpVehicleDiscovery.IsExplicitUnicast(numericAddress))
            throw new ArgumentException("Explicit unicast address and IPv6 scope required.");
        cancellationToken.ThrowIfCancellationRequested();
        // Freeze the caller-owned OEM routing bytes before any asynchronous I/O.
        var fixedActivationOemData = activationOemData?.ToArray();
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
                    TargetHost = DoIpVehicleDiscovery.TlsServerName(host),
                    EnabledSslProtocols = SslProtocols.Tls12 | SslProtocols.Tls13,
                    CertificateRevocationCheckMode = X509RevocationMode.Online
                };
                if (clientCert is not null)
                    authentication.ClientCertificates =
                        new X509CertificateCollection { clientCert };
                await tls.AuthenticateAsClientAsync(authentication, deadline.Token);
                if (clientCert is not null &&
                    (!tls.IsMutuallyAuthenticated || tls.LocalCertificate is null ||
                     !string.Equals(tls.LocalCertificate.GetCertHashString(), clientCert.Thumbprint,
                         StringComparison.OrdinalIgnoreCase)))
                    throw new AuthenticationException(
                        "The selected TLS client certificate was not used for mutual authentication.");
                dataStream = tls;
            }
            var session = new DoIpDiagnosticTransport(socket, dataStream,
                tester, ecu, protocolVersion, trace, sessionOptions);
            await session.SendAsync(DoIpCodec.RoutingActivation(
                tester, activationType: activationType,
                version: protocolVersion,
                oemSpecificData: fixedActivationOemData), deadline.Token);
            // Code 0x11 is a pending vehicle-side confirmation, not a
            // registered diagnostic routing state. Keep waiting within
            // the original connection deadline for code 0x10.
            var pendingConfirmation = false;
            ushort? routingEntityAddress = null;
            while (true)
            {
                var routing = await session.ReceiveAsync(deadline.Token);
                // Receiving a routing message concurrently with a timer's
                // expiry cannot retroactively activate an expired session.
                deadline.Token.ThrowIfCancellationRequested();
                if (routing.Version != protocolVersion)
                    throw new InvalidDataException(
                        "DoIP gateway routing response changed protocol version.");
                DoIpCodec.RequireNoGenericNack(routing);
                if (routing.Type == DoIpCodec.AliveCheckRequest)
                {
                    await session.SendAsync(DoIpCodec.ReplyToAliveCheck(
                        routing, tester), deadline.Token);
                    continue;
                }
                var status = DoIpCodec.ReadRoutingActivationCode(routing, tester);
                var entityAddress = BinaryPrimitives.ReadUInt16BigEndian(
                    routing.Payload.AsSpan(2, 2));
                // When discovery supplied a gateway identity, the routed
                // endpoint must report that same logical address before UDS.
                if (expectedGatewayAddress.HasValue &&
                    expectedGatewayAddress.Value != entityAddress)
                    throw new InvalidDataException(
                        "DoIP routing gateway does not match the observed logical address.");
                if (routingEntityAddress.HasValue &&
                    routingEntityAddress.Value != entityAddress)
                    throw new InvalidDataException(
                        "DoIP routing activation response changed gateway logical address.");
                routingEntityAddress = entityAddress;
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
            deadline.Token.ThrowIfCancellationRequested();
            session.routedGatewayAddress = routingEntityAddress;
            session.StartReceivePump();
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
        if (sessionOptions is not null)
        {
            p2Timeout ??= TimeSpan.FromMilliseconds(sessionOptions.P2Ms);
            p2StarTimeout ??= TimeSpan.FromMilliseconds(sessionOptions.P2StarMs);
        }
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
        // Capture the user-approved bytes BEFORE the first asynchronous I/O.
        // A caller must not be able to mutate a pending diagnostic request
        // or its ACK/UDS correlation on a shared array.
        var fixedRequest = request.ToArray();
        int previous;
        lock (exchangeBoundary)
        {
            previous = exchangeState;
            if (previous == 0) exchangeState = 1;
        }
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
            testerAddress, ecuAddress, fixedRequest, protocolVersion), deadline.Token);
        var remaining = timeout - overall.Elapsed;
        if (remaining <= TimeSpan.Zero)
            throw new TimeoutException("DoIP diagnostic TX exhausted exchange budget.");

        // ACKs and gateway AliveCheck messages are not ECU UDS responses.
        // They cannot reset the P2/P2* timer or the overall deadline.
        var response = await UdsResponseSequence.AwaitFinalAsync(fixedRequest,
            async (budget, token) =>
            {
                using var stage = CancellationTokenSource.CreateLinkedTokenSource(token);
                stage.CancelAfter(budget);
                while (true)
                {
                    DoIpCodec.Message message;
                    try
                    {
                        message = await ReceiveQueuedAsync(stage.Token);
                        // Even a completed TCP read must not cross the
                        // absolute P2/P2* stage deadline.
                        stage.Token.ThrowIfCancellationRequested();
                    }
                    catch (OperationCanceledException) when (
                        !token.IsCancellationRequested && stage.IsCancellationRequested)
                    {
                        throw new TimeoutException("DoIP diagnostic UDS stage deadline exceeded.");
                    }
                    if (message.Version != protocolVersion)
                        throw new InvalidDataException(
                            "DoIP response changed the negotiated protocol version.");
                    DoIpCodec.RequireNoGenericNack(message);
                    if (message.Type == DoIpCodec.AliveCheckRequest)
                    {
                        await SendAsync(DoIpCodec.ReplyToAliveCheck(
                            message, testerAddress), stage.Token);
                        continue;
                    }
                    if (DoIpCodec.IsCorrelatedDiagnosticAck(
                        message, testerAddress, ecuAddress, fixedRequest))
                        continue;
                    return DoIpCodec.RequireDiagnosticResponse(
                        message, testerAddress, ecuAddress);
                }
            },
            remaining, deadline.Token, p2Timeout, p2StarTimeout);
        lock (exchangeBoundary)
        {
            // Cancellation or expiry during final decoding/queue validation
            // must not turn an uncertain transaction into a reusable socket.
            cancellationToken.ThrowIfCancellationRequested();
            if (overall.Elapsed >= timeout)
                throw new TimeoutException("DoIP exchange expired before final publication.");
            deadline.Token.ThrowIfCancellationRequested();
            if (exchangeState != 1)
                throw new ObjectDisposedException(nameof(DoIpDiagnosticTransport));
            // Check for queued duplicates and transition to idle atomically
            // with the pump's publish. Consuming a legitimate final reply
            // may resume this continuation before TryWrite itself returns.
            if (inbound.Reader.TryPeek(out _))
                throw new InvalidDataException(
                    "DoIP stale queued diagnostic response after final UDS reply; reconnect.");
            // Only a clean EOF between frames may follow a valid final
            // reply. A malformed, truncated or overflowing receive stream
            // already detected by the pump cannot establish success.
            if (receiveFailure is not null && receiveFailure is not PeerClosedAtBoundaryException)
                ExceptionDispatchInfo.Capture(receiveFailure).Throw();
            cancellationToken.ThrowIfCancellationRequested();
            if (overall.Elapsed >= timeout)
                throw new TimeoutException("DoIP exchange expired before final publication.");
            deadline.Token.ThrowIfCancellationRequested();
            exchangeState = receiveFailure is null ? 0 : 2;
        }
        return response;
        }
        catch
        {
            // Even a cancelled/timed-out write may already be on the TCP
            // wire. Never silently retry it or allow a late reply to be
            // interpreted as belonging to another operation.
            lock (exchangeBoundary) exchangeState = 2;
            try { lifetime.Cancel(); }
            catch (ObjectDisposedException) { /* Concurrent DisposeAsync retired session. */ }
            stream.Dispose();
            socket.Dispose();
            throw;
        }
    }

    private async Task SendAsync(byte[] frame, CancellationToken token)
    {
        // AliveCheck can arrive while a user diagnostic frame is being
        // written. Never interleave their bytes on the same TCP/TLS stream.
        await writeGate.WaitAsync(token);
        try
        {
            token.ThrowIfCancellationRequested();
            await stream.WriteAsync(frame, token);
            await stream.FlushAsync(token);
            Trace("TX", frame);
        }
        finally { writeGate.Release(); }
    }

    private void StartReceivePump()
    {
        receiveTask = Task.Run(ReceivePumpAsync);
    }

    private async Task ReceivePumpAsync()
    {
        try
        {
            while (!lifetime.IsCancellationRequested)
            {
                var message = await ReceiveAsync(lifetime.Token);
                lifetime.Token.ThrowIfCancellationRequested();
                if (message.Version != protocolVersion)
                    throw new InvalidDataException(
                        "DoIP TCP response changed protocol version.");
                if (message.Type == DoIpCodec.AliveCheckRequest)
                {
                    await SendAsync(DoIpCodec.ReplyToAliveCheck(
                        message, testerAddress), lifetime.Token);
                    continue;
                }
                // There is no universal UDS transaction identifier. A
                // duplicate or unsolicited response between exchanges
                // cannot be safely assigned to the NEXT request.
                lock (exchangeBoundary)
                {
                    if (exchangeState != 1)
                    {
                        // Retire before releasing the boundary lock so a new
                        // request cannot start after this unsolicited frame.
                        exchangeState = 2;
                        throw new InvalidDataException(
                            "Unsolicited DoIP diagnostic data outside an exchange; reconnect.");
                    }
                    if (!inbound.Writer.TryWrite(message))
                        throw new InvalidDataException(
                            "DoIP diagnostic receive buffer overflow; reconnect.");
                }
            }
        }
        catch (OperationCanceledException) when (lifetime.IsCancellationRequested)
        {
            inbound.Writer.TryComplete();
        }
        catch (Exception error)
        {
            bool close;
            lock (exchangeBoundary)
            {
                receiveFailure = error;
                inbound.Writer.TryComplete(error);
                // If an exchange is active, the waiter owns final failure
                // handling. Only an orderly close may preserve a queued
                // valid final reply; all other failures are checked before
                // the exchange can return success.
                close = exchangeState != 1;
                if (close) exchangeState = 2;
            }
            if (close)
            {
                stream.Dispose();
                socket.Dispose();
            }
        }
    }

    private async Task<DoIpCodec.Message> ReceiveQueuedAsync(CancellationToken token)
    {
        try { return await inbound.Reader.ReadAsync(token); }
        catch (ChannelClosedException error) when (error.InnerException is not null)
        {
            ExceptionDispatchInfo.Capture(error.InnerException).Throw();
            throw;
        }
    }

    private async Task<DoIpCodec.Message> ReceiveAsync(CancellationToken token)
    {
        var header = new byte[8];
        // Distinguish no next PDU from a peer closing in the middle of
        // its header or payload. ReadExactly on the whole header cannot
        // distinguish these conditions.
        if (await stream.ReadAsync(header.AsMemory(0, 1), token) == 0)
            throw new PeerClosedAtBoundaryException();
        await stream.ReadExactlyAsync(header.AsMemory(1), token);
        var length = DoIpCodec.ReadPayloadLength(header);
        var message = new byte[8 + length];
        header.CopyTo(message, 0);
        if (length > 0) await stream.ReadExactlyAsync(message.AsMemory(8), token);
        Trace("RX", message);
        return DoIpCodec.Decode(message);
    }

    public async ValueTask DisposeAsync()
    {
        // Explicit callers and await-using may both dispose the same
        // transport; closing a TCP session must be idempotent.
        if (Interlocked.Exchange(ref disposed, 1) != 0)
            return;
        lock (exchangeBoundary) exchangeState = 2;
        lifetime.Cancel();
        stream.Dispose();
        socket.Dispose();
        if (receiveTask is not null)
            await receiveTask;
        lifetime.Dispose();
    }
}
